package screenprojection

import (
	"fmt"
	"reflect"
	"strings"
	"testing"

	"webterm/go-core/internal/terminalengine"
)

// 本文件覆盖阶段 2b（Projector 脏行增量导出，见
// docs/go-android-terminal-performance-optimization-plan.md §6.3）：
//   - 增量导出复用未变化行的 Line 对象（指针相等）
//   - 光标移动只替换新旧两行，且渲染与全量路径逐格一致
//   - 空闲 flush 全部行复用
//   - dirtyAll（scroll/ClearAll/resize/切屏）走全量重建且内容正确
//   - attach/resync 导出能派生完整 snapshot
//   - 关键不变量：增量路径与全量路径（含旧逐格 exportSnapshot）内容完全相等

// newFilledRig 构造每行都有不同文本的终端（每行导出的 Line 都有 runs，
// 指针身份可观测），光标停在末行文本末尾。
func newFilledRig(t *testing.T, rows, cols int) (*terminalengine.Engine, *terminalengine.TrackedScrollback, *Projector) {
	t.Helper()
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(rows, cols, sb)
	var buf strings.Builder
	buf.WriteString("\x1b[H")
	for r := 0; r < rows; r++ {
		fmt.Fprintf(&buf, "row%dtext", r)
		if r < rows-1 {
			buf.WriteString("\r\n")
		}
	}
	if err := engine.Write([]byte(buf.String())); err != nil {
		t.Fatal(err)
	}
	return engine, sb, NewProjector(engine, sb, "s1", "i1")
}

// forceFullExport 丢弃 Projector 的行缓存，使下一次导出走全量重建路径
// （同一 exporter/字典）。用于对比增量导出与全量导出的内容一致性。
func forceFullExport(p *Projector, seq uint64) terminalengine.ScreenFrame {
	p.mu.Lock()
	p.projected = projectedState{}
	p.mu.Unlock()
	return p.ExportState(0, seq)
}

// assertSameLine 断言两个帧的同一行复用了同一个 Line 对象（runs 底层数组
// 指针相等）。测试行必须非空，否则无法证明身份。
func assertSameLine(t *testing.T, row int, a, b terminalengine.Line) {
	t.Helper()
	if len(a.Runs) == 0 || len(b.Runs) == 0 {
		t.Fatalf("row %d: test requires non-empty runs for pointer identity", row)
	}
	if &a.Runs[0] != &b.Runs[0] {
		t.Fatalf("row %d: expected reused Line object, got a new allocation", row)
	}
}

// assertNewLine 断言行被重建为新对象。
func assertNewLine(t *testing.T, row int, a, b terminalengine.Line) {
	t.Helper()
	if len(a.Runs) > 0 && len(b.Runs) > 0 && &a.Runs[0] == &b.Runs[0] {
		t.Fatalf("row %d: expected a rebuilt Line, got the reused object", row)
	}
}

// assertStateEquivalent 深度比较两个完整状态的内容（屏幕逐行逐格、历史、
// 光标、模式、几何、标题/cwd），忽略 Seq/世代等帧序号字段。两状态须出自
// 同一 Projector（同一字典）。
func assertStateEquivalent(t *testing.T, a, b terminalengine.ScreenFrame) {
	t.Helper()
	if !reflect.DeepEqual(a.Screen, b.Screen) {
		t.Fatalf("screen mismatch:\na=%v\nb=%v", a.Screen, b.Screen)
	}
	if !reflect.DeepEqual(a.History, b.History) {
		t.Fatalf("history mismatch:\na=%v\nb=%v", a.History, b.History)
	}
	if a.Cursor != b.Cursor {
		t.Fatalf("cursor mismatch: %+v vs %+v", a.Cursor, b.Cursor)
	}
	if a.Modes != b.Modes {
		t.Fatalf("modes mismatch: %+v vs %+v", a.Modes, b.Modes)
	}
	if a.Rows != b.Rows || a.Cols != b.Cols || a.ActiveBuffer != b.ActiveBuffer {
		t.Fatalf("geometry/buffer mismatch: %dx%d buf=%v vs %dx%d buf=%v",
			a.Rows, a.Cols, a.ActiveBuffer, b.Rows, b.Cols, b.ActiveBuffer)
	}
	if a.Title != b.Title || a.WorkingDir != b.WorkingDir {
		t.Fatalf("metadata mismatch: title %q/%q cwd %q/%q",
			a.Title, b.Title, a.WorkingDir, b.WorkingDir)
	}
}

// normalizeLines 把行列表渲染为与字典无关的规范文本：StyleID 解析回样式
// 内容、LinkID 解析回 URI。用于跨路径（增量投影 vs 旧逐格 exportSnapshot，
// 两者字典独立）比较导出内容。
func normalizeLines(frame terminalengine.ScreenFrame, lines []terminalengine.Line) string {
	styles := map[uint32]terminalengine.TerminalStyle{}
	for _, s := range frame.Styles {
		styles[s.ID] = s
	}
	links := map[uint32]string{}
	for _, l := range frame.Links {
		links[l.ID] = l.URI
	}
	var sb strings.Builder
	for _, line := range lines {
		fmt.Fprintf(&sb, "row %d wrapped=%v|", line.Row, line.Wrapped)
		for _, run := range line.Runs {
			fmt.Fprintf(&sb, "@%d", run.Col)
			for _, c := range run.Cells {
				st := styles[c.StyleID]
				st.ID = 0
				fmt.Fprintf(&sb, "[%q w%d %+v link=%q]", c.Text, c.Width, st, links[c.LinkID])
			}
		}
		sb.WriteByte('\n')
	}
	return sb.String()
}

func TestProjector_IncrementalSingleRowChangeReusesUnchangedLines(t *testing.T) {
	engine, _, p := newFilledRig(t, 5, 10)
	first := p.ExportState(0, 1)

	// 在光标所在行覆盖一个字符，光标不离开该行：只有该行应被重建。
	if err := engine.Write([]byte("\rZ")); err != nil {
		t.Fatal(err)
	}
	second := p.ExportState(0, 2)

	if len(second.Screen) != 5 {
		t.Fatalf("expected full 5-row state, got %d rows", len(second.Screen))
	}
	for r := 0; r < 4; r++ {
		assertSameLine(t, r, first.Screen[r], second.Screen[r])
	}
	assertNewLine(t, 4, first.Screen[4], second.Screen[4])
	if got := second.Screen[4].Runs[0].Cells[0].Text; got != "Z" {
		t.Fatalf("changed row did not contain new text: %q", got)
	}

	// 增量结果与同一状态下的全量重建逐格一致。
	assertStateEquivalent(t, second, forceFullExport(p, 3))
}

func TestProjector_CursorMoveReplacesOldAndNewRowsOnly(t *testing.T) {
	engine, _, p := newFilledRig(t, 5, 10) // 光标在末行 (4, 8)
	first := p.ExportState(0, 1)

	if err := engine.Write([]byte("\x1b[2;3H")); err != nil { // 光标移到 (1, 2)，不写内容
		t.Fatal(err)
	}
	second := p.ExportState(0, 2)

	for _, r := range []int{0, 2, 3} {
		assertSameLine(t, r, first.Screen[r], second.Screen[r])
	}
	for _, r := range []int{1, 4} {
		assertNewLine(t, r, first.Screen[r], second.Screen[r])
	}
	if second.Cursor.Row != 1 || second.Cursor.Col != 2 {
		t.Fatalf("cursor mismatch: %+v", second.Cursor)
	}

	// 新旧光标行的软光标渲染与全量路径逐格一致。
	assertStateEquivalent(t, second, forceFullExport(p, 3))
}

func TestProjector_IdleExportReusesAllLines(t *testing.T) {
	_, _, p := newFilledRig(t, 5, 10)
	first := p.ExportState(0, 1)
	second := p.ExportState(0, 2) // 无写入：空闲 flush

	for r := range first.Screen {
		assertSameLine(t, r, first.Screen[r], second.Screen[r])
	}
	assertStateEquivalent(t, first, second)
}

// 局部滚动（滚动区域 [2,3] 行）置 dirtyAll：区域外未变化的行也必须被全量
// 重建为新对象，而内容保持不变。
func TestProjector_ScrollForcesFullRebuild(t *testing.T) {
	engine, _, p := newFilledRig(t, 4, 10)
	first := p.ExportState(0, 1)

	// DECSTBM 设滚动区域为 1-based 行 2-3；光标置于区域底行后换行触发区域滚动。
	if err := engine.Write([]byte("\x1b[2;3r\x1b[3;1HZ\r\n")); err != nil {
		t.Fatal(err)
	}
	second := p.ExportState(0, 2)

	// 区域外行 0、3 内容未变，但全量路径必须重建对象。
	for _, r := range []int{0, 3} {
		assertNewLine(t, r, first.Screen[r], second.Screen[r])
		if !reflect.DeepEqual(first.Screen[r], second.Screen[r]) {
			t.Fatalf("row %d outside scroll region changed content", r)
		}
	}
	assertStateEquivalent(t, second, forceFullExport(p, 3))
}

func TestProjector_ClearAllForcesFullRebuild(t *testing.T) {
	engine, _, p := newFilledRig(t, 5, 10)
	p.ExportState(0, 1)

	if err := engine.Write([]byte("\x1b[H\x1b[2J")); err != nil {
		t.Fatal(err)
	}
	second := p.ExportState(0, 2)

	if len(second.Screen) != 5 {
		t.Fatalf("expected full 5-row state, got %d rows", len(second.Screen))
	}
	for r, line := range second.Screen {
		for _, run := range line.Runs {
			for _, cell := range run.Cells {
				if cell.Text != " " || cell.StyleID != 0 {
					t.Fatalf("row %d not blank after ED2: run@%d cell %q style=%d", r, run.Col, cell.Text, cell.StyleID)
				}
			}
		}
	}
	assertStateEquivalent(t, second, forceFullExport(p, 3))
}

func TestProjector_ResizeRebuildsCacheWithNewGeometry(t *testing.T) {
	engine, _, p := newFilledRig(t, 5, 10)
	first := p.ExportState(0, 1)

	engine.Resize(6, 12)
	var deriver FrameDeriver
	deriver.FrameForState(first)
	frame := deriver.FrameForState(p.ExportState(1, 2))
	if frame.Kind != terminalengine.FrameSnapshot {
		t.Fatalf("epoch change must derive snapshot, got kind=%v", frame.Kind)
	}
	if frame.Rows != 6 || frame.Cols != 12 || len(frame.Screen) != 6 {
		t.Fatalf("resized frame geometry wrong: %dx%d rows=%d", frame.Rows, frame.Cols, len(frame.Screen))
	}
	if got := frame.Screen[0].Runs[0].Cells[0].Text; got != "r" {
		t.Fatalf("top-left content lost on resize: %q", got)
	}
	assertStateEquivalent(t, frame, forceFullExport(p, 3))
}

func TestProjector_AlternateBufferSwitchExportsFullSnapshot(t *testing.T) {
	engine, _, p := newFilledRig(t, 5, 10)
	var deriver FrameDeriver
	deriver.FrameForState(p.ExportState(0, 1))

	if err := engine.Write([]byte("\x1b[?1049h\x1b[Halt")); err != nil {
		t.Fatal(err)
	}
	frame := deriver.FrameForState(p.ExportState(0, 2))
	if frame.Kind != terminalengine.FrameSnapshot {
		t.Fatalf("buffer switch must derive snapshot, got kind=%v", frame.Kind)
	}
	if frame.ActiveBuffer != terminalengine.BufferAlternate {
		t.Fatalf("expected alternate buffer, got %v", frame.ActiveBuffer)
	}
	if len(frame.History.Lines) != 0 {
		t.Fatalf("alternate buffer leaked %d main-history lines", len(frame.History.Lines))
	}
	if got := frame.Screen[0].Runs[0].Cells[0].Text; got != "a" {
		t.Fatalf("alternate screen content wrong: %q", got)
	}
	assertStateEquivalent(t, frame, forceFullExport(p, 3))
}

// attach/resync：导出无 dirty 行时缓存不变、State 仍完整，新 FrameDeriver
// 能派生全量 snapshot。
func TestProjector_AttachResyncDerivesCompleteSnapshot(t *testing.T) {
	engine, _, p := newFilledRig(t, 5, 10)
	var d1 FrameDeriver
	d1.FrameForState(p.ExportState(0, 1))

	// 广播一次增量修订。
	if err := engine.Write([]byte("\rZ")); err != nil {
		t.Fatal(err)
	}
	broadcast := p.ExportState(0, 2)
	if patch := d1.FrameForState(broadcast); patch.BaseRevision != 1 {
		t.Fatalf("existing client expected patch base=1, got %d", patch.BaseRevision)
	}

	// 新客户端 attach：无新写入，导出必须服务完整当前状态。
	var d2 FrameDeriver
	attach := p.ExportState(0, 3)
	snap := d2.FrameForState(attach)
	if snap.Kind != terminalengine.FrameSnapshot {
		t.Fatalf("attach must derive snapshot, got kind=%v", snap.Kind)
	}
	if len(snap.Screen) != 5 {
		t.Fatalf("attach snapshot incomplete: %d rows", len(snap.Screen))
	}
	assertStateEquivalent(t, snap, forceFullExport(p, 4))

	// resync：同样无变化，新 deriver 仍得完整 snapshot。
	var d3 FrameDeriver
	resync := d3.FrameForState(p.ExportState(0, 5))
	if resync.Kind != terminalengine.FrameSnapshot {
		t.Fatalf("resync must derive snapshot, got kind=%v", resync.Kind)
	}
	assertStateEquivalent(t, resync, p.ExportState(0, 6))
}

// 关键不变量：同一终端状态下，增量路径产出的 State 与全量路径（含旧的逐格
// exportSnapshot）内容完全相等。覆盖颜色、宽字符、hyperlink、软光标和
// 换行文本的混合负载。
func TestProjector_IncrementalMatchesFullExport(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(6, 12, sb)
	payload := strings.Join([]string{
		"\x1b[H",
		"\x1b[31mred\x1b[0m plain", // 颜色 + 默认样式混合
		"\r\n中文 width",             // 宽字符
		"\r\n\x1b]8;;https://example.com\x07link\x1b]8;;\x07 tail", // hyperlink
		"\r\nwrap around text beyond twelve columns",               // 软换行
		"\r\n\x1b[5;2H\x1b[7m \x1b[0m",                             //  stale 软光标（非光标位置的 reverse 空格）
		"\x1b[6;3H\x1b[7m \x1b[0m\x1b[1D",                          // 活软光标位于光标处
	}, "")
	if err := engine.Write([]byte(payload)); err != nil {
		t.Fatal(err)
	}
	p := NewProjector(engine, sb, "s1", "i1")
	first := p.ExportState(0, 1)

	// 光标停在活软光标上：活光标格保留非默认样式，stale 格被丢弃。
	if got := styleAt(first.Screen[5], 2); got == 0 {
		t.Fatal("live soft cursor lost its style on full export")
	}
	if got := styleAt(first.Screen[4], 1); got != 0 {
		t.Fatalf("stale soft cursor leaked on full export: style=%d", got)
	}

	// 制造一次增量：改一个带新颜色的格子，并把光标移走（原活软光标格随之
	// 变为 stale，两条路径都应丢弃）。
	if err := engine.Write([]byte("\x1b[2;1H\x1b[32mG")); err != nil {
		t.Fatal(err)
	}
	incremental := p.ExportState(0, 2)

	// 1) 与同一字典的全量重建逐格相等（含软光标处理）。
	assertStateEquivalent(t, incremental, forceFullExport(p, 3))

	// 2) 与旧逐格 exportSnapshot 路径在字典归一化后完全相等。
	legacy := ExportSnapshot(engine, sb, "s1", "i1", 0, 99)
	if got, want := normalizeLines(incremental, incremental.Screen), normalizeLines(legacy, legacy.Screen); got != want {
		t.Fatalf("incremental vs legacy snapshot screen mismatch:\nincremental:\n%s\nlegacy:\n%s", got, want)
	}
	if got, want := normalizeLines(incremental, incremental.History.Lines), normalizeLines(legacy, legacy.History.Lines); got != want {
		t.Fatalf("incremental vs legacy snapshot history mismatch:\nincremental:\n%s\nlegacy:\n%s", got, want)
	}
	if incremental.Cursor != legacy.Cursor {
		t.Fatalf("cursor mismatch vs legacy: %+v vs %+v", incremental.Cursor, legacy.Cursor)
	}
	if incremental.Modes != legacy.Modes {
		t.Fatalf("modes mismatch vs legacy: %+v vs %+v", incremental.Modes, legacy.Modes)
	}
	if incremental.Title != legacy.Title || incremental.WorkingDir != legacy.WorkingDir {
		t.Fatal("title/cwd mismatch vs legacy snapshot")
	}

	// 光标移走后，增量路径上两处 reverse 空格均为 stale，同样被丢弃。
	if got := styleAt(incremental.Screen[5], 2); got != 0 {
		t.Fatalf("stale soft cursor leaked through incremental path: style=%d", got)
	}
	if got := styleAt(incremental.Screen[4], 1); got != 0 {
		t.Fatalf("stale soft cursor leaked through incremental path: style=%d", got)
	}
}
