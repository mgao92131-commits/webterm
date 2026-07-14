package screenprojection

import (
	"fmt"
	"strings"
	"testing"

	"webterm/go-core/internal/terminalengine"
)

func newChangeIndexFixture(rows, cols int) (*terminalengine.Engine, *terminalengine.TrackedScrollback, *Projector) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(rows, cols, sb)
	return engine, sb, NewProjector(engine, sb, "s1", "i1")
}

func mustEngineWrite(t *testing.T, engine *terminalengine.Engine, data string) {
	t.Helper()
	if err := engine.Write([]byte(data)); err != nil {
		t.Fatal(err)
	}
}

// 首次导出（projector 整体重建事件）：barrier 取首次导出 revision，所有组件
// 视为在该 revision 创建。
func TestChangeIndex_FirstExportMarksAllComponents(t *testing.T) {
	engine, _, p := newChangeIndexFixture(5, 10)
	mustEngineWrite(t, engine, "hello")
	p.ExportState(0, 1)

	idx := p.changeIndex
	if idx.SnapshotBarrierRevision != 1 {
		t.Fatalf("barrier=%d, want 1", idx.SnapshotBarrierRevision)
	}
	if len(idx.RowChangedRevision) != 5 {
		t.Fatalf("row index len=%d, want 5", len(idx.RowChangedRevision))
	}
	for r, rev := range idx.RowChangedRevision {
		if rev != 1 {
			t.Fatalf("row %d revision=%d, want 1", r, rev)
		}
	}
	if idx.CursorChangedRevision != 1 || idx.ModesChangedRevision != 1 ||
		idx.PaletteChangedRevision != 1 || idx.TitleChangedRevision != 1 ||
		idx.CWDChangedRevision != 1 {
		t.Fatalf("first export must create all component revisions at 1: %+v", idx)
	}
}

// 规则 1：dirty 行合并进投影时推进对应 row revision；未变化行保留旧值。
func TestChangeIndex_RowMergeBumpsOnlyDirtyRows(t *testing.T) {
	engine, _, p := newChangeIndexFixture(5, 10)
	mustEngineWrite(t, engine, "hello")
	p.ExportState(0, 1)

	mustEngineWrite(t, engine, "!")
	p.ExportState(0, 2)
	idx := p.changeIndex
	if idx.RowChangedRevision[0] != 2 {
		t.Fatalf("edited row revision=%d, want 2", idx.RowChangedRevision[0])
	}
	for r := 1; r < 5; r++ {
		if idx.RowChangedRevision[r] != 1 {
			t.Fatalf("untouched row %d revision=%d, want 1", r, idx.RowChangedRevision[r])
		}
	}

	// 换行写入：旧光标行与新内容行都标 dirty。
	mustEngineWrite(t, engine, "\r\nx")
	p.ExportState(0, 3)
	if idx.RowChangedRevision[0] != 3 || idx.RowChangedRevision[1] != 3 {
		t.Fatalf("rows 0/1 revision=%d/%d, want 3/3",
			idx.RowChangedRevision[0], idx.RowChangedRevision[1])
	}
	for r := 2; r < 5; r++ {
		if idx.RowChangedRevision[r] != 1 {
			t.Fatalf("untouched row %d revision=%d, want 1", r, idx.RowChangedRevision[r])
		}
	}
}

// 规则 2/6/7：元数据仅值变化时推进；无变化的导出不推进；同一窗口内变回
// 原值不留痕（只与上一权威投影比一次）。
func TestChangeIndex_MetadataChangeNoChangeAndChangeBack(t *testing.T) {
	engine, _, p := newChangeIndexFixture(5, 10)
	mustEngineWrite(t, engine, "hello")
	p.ExportState(0, 1)

	mustEngineWrite(t, engine, "\x1b]0;title-a\x07")
	p.ExportState(0, 2)
	if p.changeIndex.TitleChangedRevision != 2 {
		t.Fatalf("title revision=%d, want 2", p.changeIndex.TitleChangedRevision)
	}
	if p.changeIndex.CursorChangedRevision != 1 || p.changeIndex.CWDChangedRevision != 1 {
		t.Fatal("unrelated metadata must not advance on title change")
	}

	// 无变化的导出：任何组件 revision 都不得推进。
	p.ExportState(0, 3)
	if p.changeIndex.TitleChangedRevision != 2 || p.changeIndex.CursorChangedRevision != 1 {
		t.Fatalf("no-op export advanced revisions: %+v", p.changeIndex)
	}

	// 同一导出窗口内 title 设为 title-b 又改回 title-a：最终值与上一权威
	// 投影相同，不留中间痕。
	mustEngineWrite(t, engine, "\x1b]0;title-b\x07")
	mustEngineWrite(t, engine, "\x1b]0;title-a\x07")
	p.ExportState(0, 4)
	if p.changeIndex.TitleChangedRevision != 2 {
		t.Fatalf("change-back within one window advanced title revision to %d, want 2",
			p.changeIndex.TitleChangedRevision)
	}

	// 真正变成 title-b：推进。
	mustEngineWrite(t, engine, "\x1b]0;title-b\x07")
	p.ExportState(0, 5)
	if p.changeIndex.TitleChangedRevision != 5 {
		t.Fatalf("title revision=%d, want 5", p.changeIndex.TitleChangedRevision)
	}
}

// 规则 3：style/link 字典世代内只追加，新增项记录 created revision。
func TestChangeIndex_StyleLinkCreatedRevisions(t *testing.T) {
	engine, _, p := newChangeIndexFixture(3, 20)
	mustEngineWrite(t, engine, "hi")
	p.ExportState(0, 1)
	if len(p.changeIndex.StyleCreatedRevision) != 0 {
		t.Fatal("plain text must not create styles")
	}

	mustEngineWrite(t, engine, "\x1b[31mred")
	p.ExportState(0, 2)
	if got := p.changeIndex.StyleCreatedRevision; len(got) != 1 || got[0] != 2 {
		t.Fatalf("style created revisions=%v, want [2]", got)
	}

	mustEngineWrite(t, engine, "\x1b[32mgreen")
	p.ExportState(0, 3)
	if got := p.changeIndex.StyleCreatedRevision; len(got) != 2 || got[0] != 2 || got[1] != 3 {
		t.Fatalf("style created revisions=%v, want [2 3]", got)
	}

	mustEngineWrite(t, engine, "\x1b]8;;https://example.com\x07link\x1b]8;;\x07")
	p.ExportState(0, 4)
	if got := p.changeIndex.LinkCreatedRevision; len(got) != 1 || got[0] != 4 {
		t.Fatalf("link created revisions=%v, want [4]", got)
	}
	if len(p.changeIndex.StyleCreatedRevision) != 2 {
		t.Fatal("link insertion must not create styles")
	}
}

// main/alternate buffer 切换（§4.2）：推进 barrier，活动行全部重新定义
// （row 索引重置为切换 revision）；未变化的元数据不推进。
func TestChangeIndex_BufferSwitchAdvancesBarrierAndResetsRows(t *testing.T) {
	engine, _, p := newChangeIndexFixture(5, 10)
	mustEngineWrite(t, engine, "main\r\nsecond")
	p.ExportState(0, 1)
	mustEngineWrite(t, engine, "!")
	p.ExportState(0, 2)

	mustEngineWrite(t, engine, "\x1b[?1049h")
	state := p.ExportState(0, 3)
	if state.ActiveBuffer != terminalengine.BufferAlternate {
		t.Fatal("expected alternate buffer")
	}
	if p.changeIndex.SnapshotBarrierRevision != 3 {
		t.Fatalf("barrier=%d after buffer switch, want 3", p.changeIndex.SnapshotBarrierRevision)
	}
	for r, rev := range p.changeIndex.RowChangedRevision {
		if rev != 3 {
			t.Fatalf("row %d revision=%d after buffer switch, want 3", r, rev)
		}
	}
	if p.changeIndex.TitleChangedRevision != 1 {
		t.Fatalf("unchanged title advanced to %d on buffer switch", p.changeIndex.TitleChangedRevision)
	}

	// 备用屏上的后续变化正常推进，barrier 不动（1049h 保留光标位置，
	// 先回首页确保写在第 0 行）。
	mustEngineWrite(t, engine, "\x1b[Halt")
	p.ExportState(0, 4)
	if p.changeIndex.RowChangedRevision[0] != 4 {
		t.Fatalf("alt row 0 revision=%d, want 4", p.changeIndex.RowChangedRevision[0])
	}
	if p.changeIndex.SnapshotBarrierRevision != 3 {
		t.Fatalf("barrier=%d, want 3", p.changeIndex.SnapshotBarrierRevision)
	}

	// 切回主屏再次推进 barrier 并重置 row 索引。
	mustEngineWrite(t, engine, "\x1b[?1049l")
	p.ExportState(0, 5)
	if p.changeIndex.SnapshotBarrierRevision != 5 {
		t.Fatalf("barrier=%d after switching back, want 5", p.changeIndex.SnapshotBarrierRevision)
	}
	for r, rev := range p.changeIndex.RowChangedRevision {
		if rev != 5 {
			t.Fatalf("row %d revision=%d after switching back, want 5", r, rev)
		}
	}
}

// epoch 变化：ChangeIndex 整体重置（同 epoch 客户端不可能跨 epoch resume），
// barrier 取新 epoch 首个导出 revision，字典 created 索引随字典重建。
func TestChangeIndex_EpochChangeResetsIndex(t *testing.T) {
	engine, _, p := newChangeIndexFixture(5, 10)
	mustEngineWrite(t, engine, "\x1b[31mred\x1b]0;t\x07")
	p.ExportState(0, 1)
	mustEngineWrite(t, engine, "!")
	p.ExportState(0, 2)

	engine.Resize(6, 12)
	frame := p.ExportState(1, 3)
	idx := p.changeIndex
	if idx.SnapshotBarrierRevision != 3 {
		t.Fatalf("barrier=%d after epoch change, want 3", idx.SnapshotBarrierRevision)
	}
	if len(idx.RowChangedRevision) != 6 {
		t.Fatalf("row index len=%d after resize, want 6", len(idx.RowChangedRevision))
	}
	for r, rev := range idx.RowChangedRevision {
		if rev != 3 {
			t.Fatalf("row %d revision=%d after epoch change, want 3", r, rev)
		}
	}
	if idx.CursorChangedRevision != 3 || idx.TitleChangedRevision != 3 ||
		idx.ModesChangedRevision != 3 || idx.PaletteChangedRevision != 3 ||
		idx.CWDChangedRevision != 3 {
		t.Fatalf("component revisions not recreated at epoch export: %+v", idx)
	}
	if len(idx.StyleCreatedRevision) != len(frame.Styles) {
		t.Fatalf("style created index len=%d, want %d",
			len(idx.StyleCreatedRevision), len(frame.Styles))
	}
	for i, rev := range idx.StyleCreatedRevision {
		if rev != 3 {
			t.Fatalf("style %d created revision=%d after epoch rebuild, want 3", i, rev)
		}
	}
}

// 字典世代轮转（>4096 项，现有 ForceSnapshot 事件）：推进 barrier 并重建
// created-revision 索引。
func TestChangeIndex_DictionaryRotationAdvancesBarrierAndRebuilds(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(100, nil)
	engine := terminalengine.NewEngine(3, 24, sb)
	p := NewProjector(engine, sb, "s1", "i1")

	mustEngineWrite(t, engine, "start")
	p.ExportState(0, 1)

	seq := uint64(2)
	styleCounter := 0
	repaint := func() {
		var buf strings.Builder
		buf.WriteByte('\r')
		for c := 0; c < 12; c++ {
			styleCounter++
			fmt.Fprintf(&buf, "\x1b[38;2;%d;%d;%dmX", styleCounter&0xFF, (styleCounter>>8)&0xFF, (styleCounter>>16)&0xFF)
		}
		mustEngineWrite(t, engine, buf.String())
	}

	var rotatedSeq uint64
	for i := 0; i < 2000; i++ {
		repaint()
		state := p.ExportState(0, seq)
		if state.ForceSnapshot {
			rotatedSeq = seq
			break
		}
		seq++
	}
	if rotatedSeq == 0 {
		t.Fatalf("dictionary never rotated after %d styles", styleCounter)
	}
	idx := p.changeIndex
	if idx.SnapshotBarrierRevision != rotatedSeq {
		t.Fatalf("barrier=%d after rotation, want %d", idx.SnapshotBarrierRevision, rotatedSeq)
	}
	if len(idx.StyleCreatedRevision) == 0 {
		t.Fatal("rotated dictionary exported no styles")
	}
	for i, rev := range idx.StyleCreatedRevision {
		if rev != rotatedSeq {
			t.Fatalf("style %d created revision=%d after rotation rebuild, want %d",
				i, rev, rotatedSeq)
		}
	}
}

// barrier 在同一 epoch 内单调不减：元数据/行变化不推进；普通清屏（ED 2）
// 不推进；buffer 切换推进。
func TestChangeIndex_BarrierMonotonicWithinEpoch(t *testing.T) {
	engine, _, p := newChangeIndexFixture(5, 10)
	mustEngineWrite(t, engine, "hello")
	p.ExportState(0, 1)

	mustEngineWrite(t, engine, "\x1b]0;t\x07")
	p.ExportState(0, 2)
	if p.changeIndex.SnapshotBarrierRevision != 1 {
		t.Fatalf("title change advanced barrier to %d", p.changeIndex.SnapshotBarrierRevision)
	}

	mustEngineWrite(t, engine, "\x1b[?1049h")
	p.ExportState(0, 3)
	if p.changeIndex.SnapshotBarrierRevision != 3 {
		t.Fatalf("barrier=%d after buffer switch, want 3", p.changeIndex.SnapshotBarrierRevision)
	}

	mustEngineWrite(t, engine, "\x1b]0;u\x07alt-content")
	p.ExportState(0, 4)
	if p.changeIndex.SnapshotBarrierRevision != 3 {
		t.Fatalf("metadata/row changes advanced barrier to %d", p.changeIndex.SnapshotBarrierRevision)
	}

	mustEngineWrite(t, engine, "\x1b[?1049l")
	p.ExportState(0, 5)
	if p.changeIndex.SnapshotBarrierRevision != 5 {
		t.Fatalf("barrier=%d after switching back, want 5", p.changeIndex.SnapshotBarrierRevision)
	}

	// 普通清屏（ED 2）与全屏重绘不推进 barrier。
	mustEngineWrite(t, engine, "\x1b[2J")
	p.ExportState(0, 6)
	if p.changeIndex.SnapshotBarrierRevision != 5 {
		t.Fatalf("plain clear advanced barrier to %d, want 5", p.changeIndex.SnapshotBarrierRevision)
	}
}

// 历史 LineID 体系重置（ResetForReflow 的探测 seam 是 nextID 回退）：
// 推进 barrier；重置后新写入不再误推进。普通 Clear 只推进历史水位，
// 不重置 LineID 空间。
func TestChangeIndex_HistoryLineIDResetAdvancesBarrier(t *testing.T) {
	engine, sb, p := newChangeIndexFixture(5, 10)
	for i := 0; i < 20; i++ {
		mustEngineWrite(t, engine, fmt.Sprintf("line-%02d\r\n", i))
	}
	p.ExportState(0, 1)
	if sb.NextID() <= 1 {
		t.Fatal("expected scrollback lines after scrolling output")
	}

	mustEngineWrite(t, engine, "more\r\n")
	p.ExportState(0, 2)
	if p.changeIndex.SnapshotBarrierRevision != 1 {
		t.Fatalf("barrier=%d before reset, want 1", p.changeIndex.SnapshotBarrierRevision)
	}

	sb.ResetForReflow(sb.LayoutEpoch())
	p.ExportState(0, 3)
	if p.changeIndex.SnapshotBarrierRevision != 3 {
		t.Fatalf("barrier=%d after LineID reset, want 3", p.changeIndex.SnapshotBarrierRevision)
	}

	mustEngineWrite(t, engine, "new-line\r\n")
	p.ExportState(0, 4)
	if p.changeIndex.SnapshotBarrierRevision != 3 {
		t.Fatalf("barrier=%d after new history push, want 3", p.changeIndex.SnapshotBarrierRevision)
	}
}
