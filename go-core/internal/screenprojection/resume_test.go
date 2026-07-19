package screenprojection

import (
	"fmt"
	"strings"
	"testing"

	"webterm/go-core/internal/terminalengine"
)

func exportLineText(line terminalengine.Line) string {
	var b strings.Builder
	for _, run := range line.Runs {
		for _, cell := range run.Cells {
			b.WriteString(cell.Text)
		}
	}
	return b.String()
}

func findPatchRow(t *testing.T, frame terminalengine.ScreenFrame, row int) terminalengine.Line {
	t.Helper()
	for _, line := range frame.Screen {
		if line.Row == row {
			return line
		}
	}
	t.Fatalf("patch omitted row %d", row)
	return terminalengine.Line{}
}

// revision 跳跃（100→150 式）：累计 patch 只携带 clientRevision 之后的最终
// 状态，不回放中间 revision。
func TestDeriveResumeFrame_RevisionJumpCarriesFinalState(t *testing.T) {
	engine, _, p := newChangeIndexFixture(5, 20)
	mustEngineWrite(t, engine, "hello")
	p.ExportState(0, 1)

	mustEngineWrite(t, engine, "\rA")
	p.ExportState(0, 2)
	mustEngineWrite(t, engine, "\rB")
	p.ExportState(0, 3)
	mustEngineWrite(t, engine, "\x1b[2;1Hrow2")
	state := p.ExportState(0, 4)

	d := p.DeriveResumeFrame(state, 1)
	if d.Outcome != ResumeOutcomePatch {
		t.Fatalf("outcome=%v reason=%q, want patch", d.Outcome, d.Reason)
	}
	frame := d.Frame
	if frame.Kind != terminalengine.FramePatch {
		t.Fatalf("frame kind=%d, want FramePatch", frame.Kind)
	}
	if frame.BaseRevision != 1 || frame.Seq != 4 {
		t.Fatalf("base=%d seq=%d, want 1/4", frame.BaseRevision, frame.Seq)
	}
	// 第 0 行中间值是 "Aello"，最终值 "Bello"；patch 必须只含最终值。
	if got := exportLineText(findPatchRow(t, frame, 0)); !strings.HasPrefix(got, "Bello") {
		t.Fatalf("row 0 final text=%q, want prefix Bello", got)
	}
	if got := exportLineText(findPatchRow(t, frame, 1)); !strings.HasPrefix(got, "row2") {
		t.Fatalf("row 1 final text=%q, want prefix row2", got)
	}
}

func TestDeriveResumeFrame_LayoutChangeCarriesSelfContainedLayout(t *testing.T) {
	state := terminalengine.ScreenFrame{
		Version: 1, Kind: terminalengine.FrameSnapshot, InstanceID: "instance", Epoch: 1,
		Seq: 2, Rows: 2, Cols: 4,
		Screen: []terminalengine.Line{
			{ID: 22, Version: 1, Row: 0, Runs: []terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{{Text: "b", Width: 1}}}}},
			{ID: 11, Version: 1, Row: 1, Runs: []terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{{Text: "a", Width: 1}}}}},
		},
	}
	idx := &ChangeIndex{
		SnapshotBarrierRevision: 1,
		RowChangedRevision:      []uint64{2, 2},
		LayoutChangedRevision:   2,
	}
	d := DeriveResumeFrame(state, idx, 1)
	if d.Outcome != ResumeOutcomePatch {
		t.Fatalf("outcome=%v reason=%q, want patch", d.Outcome, d.Reason)
	}
	if got, want := d.Frame.Layout, []uint64{22, 11}; !sameLayout(got, want) {
		t.Fatalf("layout=%v, want %v", got, want)
	}
	if len(d.Frame.Screen) != len(state.Screen) {
		t.Fatalf("layout patch line updates=%d, want self-contained %d", len(d.Frame.Screen), len(state.Screen))
	}
}

// metadata-only：title 变化的 resume patch 置 TitleChanged 且不携带屏幕行。
func TestDeriveResumeFrame_MetadataOnly(t *testing.T) {
	engine, _, p := newChangeIndexFixture(5, 20)
	mustEngineWrite(t, engine, "hello")
	p.ExportState(0, 1)
	mustEngineWrite(t, engine, "\x1b]0;meta\x07")
	state := p.ExportState(0, 2)

	d := p.DeriveResumeFrame(state, 1)
	if d.Outcome != ResumeOutcomePatch {
		t.Fatalf("outcome=%v, want patch", d.Outcome)
	}
	if !d.Frame.TitleChanged || d.Frame.Title != "meta" {
		t.Fatalf("title flag=%v value=%q, want true/meta", d.Frame.TitleChanged, d.Frame.Title)
	}
	if d.Frame.WorkingDirChanged {
		t.Fatal("unchanged cwd must not set WorkingDirChanged")
	}
	if len(d.Frame.Screen) != 0 || len(d.Frame.Styles) != 0 || len(d.Frame.Links) != 0 {
		t.Fatal("metadata-only patch must not carry rows or dictionary entries")
	}
}

// cursor-only：patch 总是携带 cursor 当前值（与 diffToPatch 的 wire 行为一致）。
// 光标移动会把新旧光标行标 dirty，patch 冗余携带这些行的最终值（规则 7 允许）。
func TestDeriveResumeFrame_CursorOnly(t *testing.T) {
	engine, _, p := newChangeIndexFixture(5, 20)
	mustEngineWrite(t, engine, "hi")
	p.ExportState(0, 1)
	mustEngineWrite(t, engine, "\x1b[3;5H")
	state := p.ExportState(0, 2)

	d := p.DeriveResumeFrame(state, 1)
	if d.Outcome != ResumeOutcomePatch {
		t.Fatalf("outcome=%v, want patch", d.Outcome)
	}
	if d.Frame.Cursor != state.Cursor {
		t.Fatalf("patch cursor=%+v, want current %+v", d.Frame.Cursor, state.Cursor)
	}
	if d.Frame.Cursor.Row != 2 || d.Frame.Cursor.Col != 4 {
		t.Fatalf("cursor=%+v, want row 2 col 4", d.Frame.Cursor)
	}
}

// title 清空：三态中“变为空串”——TitleChanged=true 且 Title=""。
func TestDeriveResumeFrame_TitleClearedKeepsThreeState(t *testing.T) {
	engine, _, p := newChangeIndexFixture(5, 20)
	mustEngineWrite(t, engine, "\x1b]0;keep\x07")
	p.ExportState(0, 1)
	mustEngineWrite(t, engine, "\x1b]0;\x07")
	state := p.ExportState(0, 2)

	d := p.DeriveResumeFrame(state, 1)
	if d.Outcome != ResumeOutcomePatch {
		t.Fatalf("outcome=%v, want patch", d.Outcome)
	}
	if !d.Frame.TitleChanged || d.Frame.Title != "" {
		t.Fatalf("cleared title: flag=%v value=%q, want true/empty",
			d.Frame.TitleChanged, d.Frame.Title)
	}
}

// 字典追加：只携带 CreatedRevision > clientRevision 的条目。
func TestDeriveResumeFrame_DictionaryAppend(t *testing.T) {
	engine, _, p := newChangeIndexFixture(5, 20)
	mustEngineWrite(t, engine, "plain")
	p.ExportState(0, 1)
	mustEngineWrite(t, engine, "\x1b[31mred")
	p.ExportState(0, 2)

	d := p.DeriveResumeFrame(p.ExportState(0, 2), 1)
	if d.Outcome != ResumeOutcomePatch {
		t.Fatalf("outcome=%v, want patch", d.Outcome)
	}
	if len(d.Frame.Styles) != 1 {
		t.Fatalf("resume patch styles=%d, want the single new entry", len(d.Frame.Styles))
	}

	// 客户端已有该 style（revision 2）之后：普通文本变化不再重发字典项。
	mustEngineWrite(t, engine, " more")
	state := p.ExportState(0, 3)
	d = p.DeriveResumeFrame(state, 2)
	if d.Outcome != ResumeOutcomePatch {
		t.Fatalf("outcome=%v, want patch", d.Outcome)
	}
	if len(d.Frame.Styles) != 0 {
		t.Fatalf("style created before clientRevision was resent: %d entries", len(d.Frame.Styles))
	}
	if len(d.Frame.Screen) == 0 {
		t.Fatal("row change after clientRevision must be in the patch")
	}
}

// clientRevision < barrier：拒绝产 patch，要求 snapshot。
func TestDeriveResumeFrame_BarrierRejected(t *testing.T) {
	engine, _, p := newChangeIndexFixture(5, 20)
	mustEngineWrite(t, engine, "main")
	p.ExportState(0, 1)
	mustEngineWrite(t, engine, "!")
	p.ExportState(0, 2)
	mustEngineWrite(t, engine, "\x1b[?1049h")
	p.ExportState(0, 3) // barrier=3
	mustEngineWrite(t, engine, "\x1b[Halt")
	state := p.ExportState(0, 4)

	d := p.DeriveResumeFrame(state, 2)
	if d.Outcome != ResumeOutcomeSnapshot || d.Reason != ResumeReasonBarrier {
		t.Fatalf("outcome=%v reason=%q, want snapshot/barrier", d.Outcome, d.Reason)
	}

	// barrier 之后的客户端可以正常 resume。
	d = p.DeriveResumeFrame(state, 3)
	if d.Outcome != ResumeOutcomePatch {
		t.Fatalf("outcome=%v for client at barrier, want patch", d.Outcome)
	}
}

// 成本降级（§6.1 第 1 条）：变化活动行 >60% 时优先 snapshot；恰好 60% 仍产 patch。
func TestDeriveResumeFrame_PatchCostFallback(t *testing.T) {
	build := func(changedRows int) (terminalengine.ScreenFrame, *Projector) {
		engine, _, p := newChangeIndexFixture(10, 20)
		mustEngineWrite(t, engine, "x")
		p.ExportState(0, 1)
		for r := 0; r < changedRows; r++ {
			mustEngineWrite(t, engine, fmt.Sprintf("\x1b[%d;1Hchanged-%d", r+1, r))
		}
		return p.ExportState(0, 2), p
	}

	state, p := build(7) // 7 > 10*60/100=6
	d := p.DeriveResumeFrame(state, 1)
	if d.Outcome != ResumeOutcomeSnapshot || d.Reason != ResumeReasonPatchCost {
		t.Fatalf("outcome=%v reason=%q, want snapshot/patch_cost", d.Outcome, d.Reason)
	}

	state, p = build(6) // 恰好 60%：不超过阈值
	d = p.DeriveResumeFrame(state, 1)
	if d.Outcome != ResumeOutcomePatch {
		t.Fatalf("outcome=%v at exactly 60%%, want patch", d.Outcome)
	}
	if len(d.Frame.Screen) != 6 {
		t.Fatalf("patch rows=%d, want 6", len(d.Frame.Screen))
	}
}

// clientRevision == current：exact resume，拒绝产 patch（Task 4 走 ResumeAck）。
// clientRevision > current：future_revision → snapshot。
func TestDeriveResumeFrame_ExactAndFuture(t *testing.T) {
	engine, _, p := newChangeIndexFixture(5, 20)
	mustEngineWrite(t, engine, "hello")
	state := p.ExportState(0, 1)

	if d := p.DeriveResumeFrame(state, 1); d.Outcome != ResumeOutcomeExact {
		t.Fatalf("clientRevision==current: outcome=%v, want exact", d.Outcome)
	}
	d := p.DeriveResumeFrame(state, 2)
	if d.Outcome != ResumeOutcomeSnapshot || d.Reason != ResumeReasonFutureRevision {
		t.Fatalf("clientRevision>current: outcome=%v reason=%q, want snapshot/future_revision",
			d.Outcome, d.Reason)
	}
}

// revision gap 内只有 bell 等不可观察输出：客户端内容与权威状态一致，
// 按 exact 处理（空 patch 违反 §10.1，Task 4 以 ResumeAck 推进 revision）。
func TestDeriveResumeFrame_BellOnlyGapIsExact(t *testing.T) {
	engine, _, p := newChangeIndexFixture(5, 20)
	mustEngineWrite(t, engine, "hello")
	p.ExportState(0, 1)
	mustEngineWrite(t, engine, "\x07")
	p.ExportState(0, 2)
	mustEngineWrite(t, engine, "\x07")
	state := p.ExportState(0, 3)

	if d := p.DeriveResumeFrame(state, 1); d.Outcome != ResumeOutcomeExact {
		t.Fatalf("bell-only gap: outcome=%v reason=%q, want exact", d.Outcome, d.Reason)
	}
}
