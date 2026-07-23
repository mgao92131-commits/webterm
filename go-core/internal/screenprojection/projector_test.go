package screenprojection

import (
	"fmt"
	"strings"
	"testing"

	"google.golang.org/protobuf/proto"

	pb "webterm/go-core/internal/screenprotocol/generatedv2"
	"webterm/go-core/internal/screenprotocolv2"
	"webterm/go-core/internal/terminalengine"
)

func TestProjector_FirstFrameIsSnapshot(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(5, 10, sb)
	engine.Write([]byte("hello\n"))

	p := NewProjector(engine, sb, "s1", "i1")
	var deriver FrameDeriver
	frame := deriver.FrameForState(p.ExportState(0, 1))
	if frame.Kind != terminalengine.FrameSnapshot {
		t.Fatalf("expected snapshot, got kind=%v", frame.Kind)
	}
}

func TestProjector_PatchAfterBaseline(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(5, 10, sb)
	engine.Write([]byte("hello\n"))

	p := NewProjector(engine, sb, "s1", "i1")
	var deriver FrameDeriver
	deriver.FrameForState(p.ExportState(0, 1))

	engine.Write([]byte("world\n"))
	frame := deriver.FrameForState(p.ExportState(0, 2))
	if frame.BaseRevision != 1 {
		t.Fatalf("expected patch base=1, got %d", frame.BaseRevision)
	}
	if len(frame.Screen) == 0 {
		t.Fatal("dirty-row revision index must select the changed row")
	}
}

// Regression for the blank-terminal startup path: a snapshot establishes the
// stable layout first, then the shell prompt modifies an existing LineID. The
// next frame must be a Patch containing LineData, not a silently empty delta.
func TestProjector_BlankSnapshotThenPromptProducesLinePatch(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(100, nil)
	engine := terminalengine.NewEngine(5, 24, sb)
	p := NewProjector(engine, sb, "s", "i")
	var deriver FrameDeriver
	initial := deriver.FrameForState(p.ExportState(1, 1))
	if initial.Kind != terminalengine.FrameSnapshot || len(initial.Screen) == 0 {
		t.Fatal("empty terminal did not produce initial snapshot layout")
	}
	promptID := initial.Screen[0].ID
	if err := engine.Write([]byte("user@host:~$ ")); err != nil {
		t.Fatal(err)
	}
	patch := deriver.FrameForState(p.ExportState(1, 2))
	if patch.Kind != terminalengine.FramePatch || patch.BaseRevision != 1 || patch.Layout != nil {
		t.Fatalf("prompt frame kind/base/layout=%v/%d/%v, want patch/1/no layout", patch.Kind, patch.BaseRevision, patch.Layout)
	}
	if len(patch.Screen) != 1 || patch.Screen[0].ID != promptID || !strings.Contains(exportLineText(patch.Screen[0]), "user@host:~$") {
		t.Fatalf("prompt LineData missing or wrong: %+v", patch.Screen)
	}
	encoded, err := screenprotocolv2.EncodeScreenPatch(patch, 1)
	if err != nil {
		t.Fatal(err)
	}
	var envelope pb.ScreenEnvelope
	if err := proto.Unmarshal(encoded, &envelope); err != nil {
		t.Fatal(err)
	}
	if envelope.GetScreenPatch() == nil || len(envelope.GetScreenPatch().GetScreenLineUpdates()) != 1 {
		t.Fatalf("protobuf patch omitted prompt line: %+v", envelope.GetScreenPatch())
	}
}

func TestChangedScreenRows_UsesRevisionIndexWhenPresent(t *testing.T) {
	old := terminalengine.ScreenFrame{Seq: 10, Screen: []terminalengine.Line{{Row: 0}, {Row: 1}}}
	newState := old
	newState.Seq = 11
	newState.Screen = []terminalengine.Line{{Row: 0}, {Row: 1, Runs: []terminalengine.CellRun{{Col: 0}}}}
	newState.RowChangedRevision = []uint64{10, 11}

	rows := changedScreenRows(old, newState)
	if len(rows) != 1 || rows[0].Row != 1 {
		t.Fatalf("revision index selected rows=%+v, want only row 1", rows)
	}
}

func TestFrameDeriver_LayoutReentryCarriesLineDataAfterHistoryPrune(t *testing.T) {
	line := func(id, version uint64, row int, text string) terminalengine.Line {
		return terminalengine.Line{ID: id, Version: version, Row: row,
			Runs: []terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{{Text: text, Width: 1}}}}}
	}
	old := terminalengine.ScreenFrame{
		Version: 1, Kind: terminalengine.FrameSnapshot, InstanceID: "i", Epoch: 1, Seq: 1,
		Rows: 2, Cols: 2,
		Screen:  []terminalengine.Line{line(10, 1, 0, "x"), line(20, 1, 1, "y")},
		History: terminalengine.HistoryWindow{Lines: []terminalengine.Line{line(30, 1, -1, "h")}},
	}
	current := old
	current.Seq = 2
	current.Screen = []terminalengine.Line{line(30, 1, 0, "h"), line(20, 1, 1, "y")}
	patch := frameForBaseline(&old, current)
	if patch.Kind != terminalengine.FramePatch || !sameLayout(patch.Layout, []uint64{30, 20}) {
		t.Fatalf("patch kind/layout=%d/%v, want patch/[30 20]", patch.Kind, patch.Layout)
	}
	if len(patch.Screen) != 1 || patch.Screen[0].ID != 30 {
		t.Fatalf("re-entered history line was not self-contained: %+v", patch.Screen)
	}
}

func TestProjector_DynamicPaletteProducesMetadataOnlyPatch(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(5, 10, sb)
	p := NewProjector(engine, sb, "s1", "i1")
	var deriver FrameDeriver
	initial := deriver.FrameForState(p.ExportState(1, 1))

	if err := engine.Write([]byte("\x1b]4;42;#010203\x07" +
		"\x1b]10;#112233\x07\x1b]11;#223344\x07\x1b]12;#334455\x07")); err != nil {
		t.Fatal(err)
	}
	patch := deriver.FrameForState(p.ExportState(1, 2))
	if patch.Kind != terminalengine.FramePatch || len(patch.Screen) != 0 {
		t.Fatalf("palette-only update kind=%d rows=%d, want metadata patch", patch.Kind, len(patch.Screen))
	}
	if patch.IndexedPaletteSet[0]&(uint64(1)<<42) == 0 || patch.IndexedPalette[42] != 0x010203 {
		t.Fatalf("indexed palette[42]=%06x set=%x", patch.IndexedPalette[42], patch.IndexedPaletteSet[0])
	}
	if patch.DefaultFG.RGB != 0x112233 || patch.DefaultBG.RGB != 0x223344 ||
		patch.CursorColor.RGB != 0x334455 {
		t.Fatalf("dynamic defaults fg=%06x bg=%06x cursor=%06x",
			patch.DefaultFG.RGB, patch.DefaultBG.RGB, patch.CursorColor.RGB)
	}
	if patch.PaletteGeneration <= initial.PaletteGeneration {
		t.Fatalf("palette generation=%d, initial=%d", patch.PaletteGeneration, initial.PaletteGeneration)
	}

	if err := engine.Write([]byte("\x1b]104;42\x07\x1b]110\x07\x1b]111\x07\x1b]112\x07")); err != nil {
		t.Fatal(err)
	}
	reset := deriver.FrameForState(p.ExportState(1, 3))
	if reset.Kind != terminalengine.FramePatch || len(reset.Screen) != 0 {
		t.Fatalf("palette reset kind=%d rows=%d, want metadata patch", reset.Kind, len(reset.Screen))
	}
	if reset.IndexedPaletteSet[0]&(uint64(1)<<42) != 0 {
		t.Fatal("OSC 104 did not remove indexed palette override")
	}
	if reset.DefaultFG.Kind != terminalengine.ColorDefaultFG ||
		reset.DefaultBG.Kind != terminalengine.ColorDefaultBG ||
		reset.CursorColor.Kind != terminalengine.ColorCursor {
		t.Fatalf("OSC palette reset did not restore semantic defaults: fg=%v bg=%v cursor=%v",
			reset.DefaultFG.Kind, reset.DefaultBG.Kind, reset.CursorColor.Kind)
	}
	if reset.PaletteGeneration <= patch.PaletteGeneration {
		t.Fatalf("reset generation=%d, set generation=%d", reset.PaletteGeneration, patch.PaletteGeneration)
	}
}

// FrameDeriver 的所有产出路径都必须打上正确的 Kind；patch 以显式标志表达
// title/cwd 三态（未变化不置标志）。
func TestFrameDeriver_FrameKindAndTitleCwdFlags(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(5, 10, sb)
	if err := engine.Write([]byte("hello")); err != nil {
		t.Fatal(err)
	}
	p := NewProjector(engine, sb, "s1", "i1")
	var deriver FrameDeriver

	// 首帧：snapshot。
	first := deriver.FrameForState(p.ExportState(0, 1))
	if first.Kind != terminalengine.FrameSnapshot {
		t.Fatalf("first frame kind=%d, want FrameSnapshot", first.Kind)
	}

	// 小变化：patch；title/cwd 未变化，标志必须为 false。
	if err := engine.Write([]byte("!")); err != nil {
		t.Fatal(err)
	}
	patch := deriver.FrameForState(p.ExportState(0, 2))
	if patch.Kind != terminalengine.FramePatch {
		t.Fatalf("patch kind=%d, want FramePatch", patch.Kind)
	}
	if patch.TitleChanged || patch.WorkingDirChanged {
		t.Fatal("unchanged title/cwd must not set change flags")
	}

	// title/cwd 变化：patch 上显式置标志并携带新值。
	if err := engine.Write([]byte("\x1b]0;new-title\x07")); err != nil {
		t.Fatal(err)
	}
	if err := engine.Write([]byte("\x1b]7;file://localhost/tmp/work\x07")); err != nil {
		t.Fatal(err)
	}
	metaPatch := deriver.FrameForState(p.ExportState(0, 3))
	if metaPatch.Kind != terminalengine.FramePatch {
		t.Fatalf("metadata patch kind=%d, want FramePatch", metaPatch.Kind)
	}
	if !metaPatch.TitleChanged || metaPatch.Title != "new-title" {
		t.Fatalf("title change not flagged: changed=%v title=%q",
			metaPatch.TitleChanged, metaPatch.Title)
	}
	if !metaPatch.WorkingDirChanged || metaPatch.WorkingDir != "/tmp/work" {
		t.Fatalf("cwd change not flagged: changed=%v cwd=%q",
			metaPatch.WorkingDirChanged, metaPatch.WorkingDir)
	}

	// 在线 baseline 连续时，即使超过 60% 行变化，也必须保留 Patch，避免
	// TUI 全屏重绘重复发送整份 history snapshot。
	if err := engine.Write([]byte("row0\r\nrow1\r\nrow2\r\nrow3")); err != nil {
		t.Fatal(err)
	}
	full := deriver.FrameForState(p.ExportState(0, 4))
	if full.Kind != terminalengine.FramePatch {
		t.Fatalf("full-screen delta kind=%d, want FramePatch", full.Kind)
	}
	if full.BaseRevision != 3 {
		t.Fatalf("full-screen delta base=%d, want 3", full.BaseRevision)
	}
}

func TestFrameDeriver_FullScreenPatchOnlyCarriesHistoryDelta(t *testing.T) {
	const rows = 52
	const historySize = 300

	makeLine := func(id, version uint64, row int, text string) terminalengine.Line {
		return terminalengine.Line{Row: row, ID: id, Version: version, HistorySeq: id, Runs: []terminalengine.CellRun{{
			Col:   0,
			Cells: []terminalengine.Cell{{Text: text, Width: 1}},
		}}}
	}
	makeScreen := func(version uint64, text string) []terminalengine.Line {
		screen := make([]terminalengine.Line, rows)
		for row := range screen {
			screen[row] = makeLine(1000+uint64(row), version, row, fmt.Sprintf("%s-%d", text, row))
		}
		return screen
	}
	history := make([]terminalengine.Line, historySize)
	for index := range history {
		history[index] = makeLine(uint64(index+1), 1, 0, fmt.Sprintf("history-%d", index+1))
	}
	baseline := terminalengine.ScreenFrame{
		Version: 1, SessionID: "s1", InstanceID: "i1", Epoch: 1, Seq: 1,
		Rows: rows, Cols: 60, ActiveBuffer: terminalengine.BufferMain,
		Screen: makeScreen(1, "before"),
		History: terminalengine.HistoryWindow{
			FirstAvailableHistorySeq: 1, FirstIncludedHistorySeq: 1, LastIncludedHistorySeq: historySize,
			Lines: history,
		},
	}
	currentHistory := append([]terminalengine.Line(nil), history[1:]...)
	currentHistory = append(currentHistory, makeLine(historySize+1, 1, 0, "history-301"))
	current := baseline
	current.Seq = 2
	current.Screen = makeScreen(2, "after")
	current.History = terminalengine.HistoryWindow{
		FirstAvailableHistorySeq: 1, FirstIncludedHistorySeq: 2, LastIncludedHistorySeq: historySize + 1,
		Lines: currentHistory,
	}

	var deriver FrameDeriver
	deriver.Seed(baseline)
	patch := deriver.FrameForState(current)
	if patch.Kind != terminalengine.FramePatch || patch.BaseRevision != baseline.Seq {
		t.Fatalf("kind=%d base=%d, want patch based on %d", patch.Kind, patch.BaseRevision, baseline.Seq)
	}
	if len(patch.Screen) != rows {
		t.Fatalf("screen rows=%d, want all %d changed rows", len(patch.Screen), rows)
	}
	if len(patch.History.Lines) != 1 || patch.History.Lines[0].ID != historySize+1 {
		t.Fatalf("history delta=%+v, want only line %d", patch.History.Lines, historySize+1)
	}
}

func TestFrameDeriver_ExtentOnlyChangeDoesNotCreateScreenPatch(t *testing.T) {
	screen := []terminalengine.Line{{ID: 1, Version: 1}}
	baseline := terminalengine.ScreenFrame{
		Version: 1, SessionID: "s1", InstanceID: "i1", Epoch: 1, Seq: 1,
		Rows: 1, Cols: 1, Screen: screen,
		History: terminalengine.HistoryWindow{
			FirstAvailableHistorySeq: 1,
			FirstIncludedHistorySeq:  1,
			LastIncludedHistorySeq:   5,
		},
	}
	next := baseline
	next.Seq = 2
	next.History.FirstAvailableHistorySeq = 2
	next.History.FirstIncludedHistorySeq = 2

	var deriver FrameDeriver
	deriver.Seed(baseline)
	frame := deriver.FrameForState(next)
	if !frame.HistoryOnlyPatch {
		t.Fatal("extent-only change must be represented as HistoryDelta-only")
	}
	if hasScreenChanges(frame) {
		t.Fatal("extent-only frame unexpectedly contains screen changes")
	}
}

func TestProjector_OneExportFeedsIndependentClientBaselines(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(5, 10, sb)
	p := NewProjector(engine, sb, "s1", "i1")
	var d1, d2 FrameDeriver

	if err := engine.Write([]byte("hello\n")); err != nil {
		t.Fatal(err)
	}
	initial := p.ExportState(0, 1)
	if first := d1.FrameForState(initial); first.Kind != terminalengine.FrameSnapshot {
		t.Fatalf("c1 first frame must be snapshot, got kind=%v", first.Kind)
	}
	if first := d2.FrameForState(initial); first.Kind != terminalengine.FrameSnapshot {
		t.Fatalf("c2 first frame must be snapshot, got kind=%v", first.Kind)
	}

	if err := engine.Write([]byte("world\n")); err != nil {
		t.Fatal(err)
	}
	next := p.ExportState(0, 2)
	for clientID, deriver := range map[string]*FrameDeriver{"c1": &d1, "c2": &d2} {
		if patch := deriver.FrameForState(next); patch.BaseRevision != 1 {
			t.Fatalf("%s patch base=%d, want 1", clientID, patch.BaseRevision)
		}
	}
}

func TestProjector_PatchOnlyCarriesNewDictionaryEntries(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(3, 12, sb)
	p := NewProjector(engine, sb, "s1", "i1")
	var deriver FrameDeriver
	deriver.FrameForState(p.ExportState(0, 1))

	if err := engine.Write([]byte("\x1b[31mred")); err != nil {
		t.Fatal(err)
	}
	firstStyledPatch := deriver.FrameForState(p.ExportState(0, 2))
	if len(firstStyledPatch.Styles) == 0 {
		t.Fatal("first styled patch omitted its new style")
	}

	if err := engine.Write([]byte(" more")); err != nil {
		t.Fatal(err)
	}
	reusedStylePatch := deriver.FrameForState(p.ExportState(0, 3))
	if len(reusedStylePatch.Styles) != 0 {
		t.Fatalf("reused style was resent in patch: %d entries", len(reusedStylePatch.Styles))
	}
}

func TestFrameDeriver_CanSkipIntermediateStatesWithoutRevisionGap(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(4, 12, sb)
	p := NewProjector(engine, sb, "s1", "i1")
	var deriver FrameDeriver

	if err := engine.Write([]byte("one")); err != nil {
		t.Fatal(err)
	}
	first := deriver.FrameForState(p.ExportState(0, 1))
	if first.Kind != terminalengine.FrameSnapshot {
		t.Fatalf("first frame must be snapshot, got kind=%v", first.Kind)
	}

	if err := engine.Write([]byte(" two")); err != nil {
		t.Fatal(err)
	}
	_ = p.ExportState(0, 2) // This state is deliberately coalesced away.
	if err := engine.Write([]byte(" three")); err != nil {
		t.Fatal(err)
	}
	latest := deriver.FrameForState(p.ExportState(0, 3))
	if latest.BaseRevision != 1 {
		t.Fatalf("coalesced frame base=%d, want the last sent revision 1", latest.BaseRevision)
	}
	if len(latest.Screen) == 0 {
		t.Fatal("coalesced frame omitted the latest screen change")
	}
}

func TestProjector_ClearLineIsExportedAsScreenPatch(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(3, 12, sb)
	if err := engine.Write([]byte("stale-block")); err != nil {
		t.Fatal(err)
	}
	p := NewProjector(engine, sb, "s1", "i1")
	var deriver FrameDeriver
	deriver.FrameForState(p.ExportState(0, 1))

	// Typical TUI redraw: return to the line, erase it, then draw a shorter value.
	if err := engine.Write([]byte("\r\x1b[2Kok")); err != nil {
		t.Fatal(err)
	}
	patch := deriver.FrameForState(p.ExportState(0, 2))
	if patch.BaseRevision != 1 {
		t.Fatalf("expected patch after baseline, got base=%d", patch.BaseRevision)
	}
	if len(patch.Screen) != 1 {
		t.Fatalf("expected one changed screen row after erase, got %d", len(patch.Screen))
	}
	if got := patch.Screen[0].Runs[0].Cells[0].Text; got != "o" {
		t.Fatalf("cleared row did not contain replacement text: %q", got)
	}
}

// 复现截图对应的时序：旧行含 CJK 和 projected 尾巴，TUI 的一次重绘被拆成
// “先擦除”与“再写短文本”两个 PTY/投影批次，而中间擦除状态被 mailbox 合并掉。
// 最终 patch 必须直接从最后已发送 baseline 覆盖整行，不能留下 "ojected"。
func TestFrameDeriver_CoalescedEraseAndRewriteClearsOldMixedWidthTail(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(4, 16, sb)
	if err := engine.Write([]byte("中文projected")); err != nil {
		t.Fatal(err)
	}
	p := NewProjector(engine, sb, "s1", "i1")
	var deriver FrameDeriver
	first := deriver.FrameForState(p.ExportState(0, 1))
	if first.Kind != terminalengine.FrameSnapshot {
		t.Fatalf("first frame kind=%d, want snapshot", first.Kind)
	}

	if err := engine.Write([]byte("\r\x1b[2K")); err != nil {
		t.Fatal(err)
	}
	_ = p.ExportState(0, 2) // 单槽 mailbox 覆盖掉这次中间擦除状态。
	if err := engine.Write([]byte("ok")); err != nil {
		t.Fatal(err)
	}
	patch := deriver.FrameForState(p.ExportState(0, 3))
	if patch.Kind != terminalengine.FramePatch || patch.BaseRevision != 1 || patch.Seq != 3 {
		t.Fatalf("coalesced frame kind=%d base=%d seq=%d, want patch 1->3",
			patch.Kind, patch.BaseRevision, patch.Seq)
	}
	if len(patch.Screen) != 1 || patch.Screen[0].Row != 0 {
		t.Fatalf("changed rows=%v, want only row 0", patch.Screen)
	}
	line := patch.Screen[0]
	for _, run := range line.Runs {
		col := run.Col
		for _, cell := range run.Cells {
			if col == 0 && cell.Text != "o" {
				t.Fatalf("column 0=%q, want o", cell.Text)
			}
			if col == 1 && cell.Text != "k" {
				t.Fatalf("column 1=%q, want k", cell.Text)
			}
			if col >= 2 && cell.Text != " " {
				t.Fatalf("stale tail survived at column %d: %q", col, cell.Text)
			}
			col += int(cell.Width)
		}
	}
}

func TestProjector_LayoutEpochChangeIsSnapshot(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(5, 10, sb)
	engine.Write([]byte("hello\n"))

	p := NewProjector(engine, sb, "s1", "i1")
	var deriver FrameDeriver
	deriver.FrameForState(p.ExportState(0, 1))

	engine.Resize(6, 12)
	frame := deriver.FrameForState(p.ExportState(1, 2))
	if frame.Kind != terminalengine.FrameSnapshot {
		t.Fatalf("expected snapshot after epoch change, got kind=%v", frame.Kind)
	}
}

// A dictionary rotation invalidates every client's style/link baseline, but
// the rotation frame only carries the single-frame ForceSnapshot hint. A
// single-slot mailbox can overwrite that frame with a later ordinary state,
// so the durable DictionaryGeneration on every state must force a snapshot.
func TestProjector_DictionaryGenerationForcesSnapshotAfterMailboxOverwrite(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(100, nil)
	engine := terminalengine.NewEngine(3, 24, sb)
	p := NewProjector(engine, sb, "s1", "i1")
	var deriver FrameDeriver

	if err := engine.Write([]byte("start")); err != nil {
		t.Fatal(err)
	}
	first := deriver.FrameForState(p.ExportState(0, 1))
	if first.Kind != terminalengine.FrameSnapshot {
		t.Fatalf("first frame must be snapshot, got kind=%v", first.Kind)
	}
	if first.DictionaryGeneration != 0 {
		t.Fatalf("initial dictionary generation=%d, want 0", first.DictionaryGeneration)
	}

	// Rewrite one row with fresh RGB colors per export. The dictionary only
	// grows, so after >4096 lookups the exporter rotates. The visible window
	// stays tiny (one 12-cell row), keeping the post-rotation re-export well
	// under the 4096-entry protocol limit.
	seq := uint64(2)
	styleCounter := 0
	repaint := func() {
		var buf strings.Builder
		buf.WriteByte('\r')
		for c := 0; c < 12; c++ {
			styleCounter++
			fmt.Fprintf(&buf, "\x1b[38;2;%d;%d;%dmX", styleCounter&0xFF, (styleCounter>>8)&0xFF, (styleCounter>>16)&0xFF)
		}
		if err := engine.Write([]byte(buf.String())); err != nil {
			t.Fatal(err)
		}
	}

	var rotated terminalengine.ScreenFrame
	for i := 0; i < 2000; i++ {
		repaint()
		state := p.ExportState(0, seq)
		seq++
		if state.ForceSnapshot {
			rotated = state
			break
		}
		// Keep the deriver baseline current with every delivered state; a
		// single changed row must stay on the patch path before rotation.
		if frame := deriver.FrameForState(state); frame.Kind == terminalengine.FrameSnapshot {
			t.Fatalf("pre-rotation frame seq=%d unexpectedly became snapshot", state.Seq)
		}
	}
	if !rotated.ForceSnapshot {
		t.Fatalf("dictionary never rotated after %d styles", styleCounter)
	}
	if rotated.DictionaryGeneration == first.DictionaryGeneration {
		t.Fatal("dictionary rotation did not advance the generation")
	}

	// Simulate the single-slot mailbox dropping the rotation frame: never
	// deliver `rotated` to the deriver, then submit the next ordinary state.
	repaint()
	next := p.ExportState(0, seq)
	seq++
	if next.DictionaryGeneration != rotated.DictionaryGeneration {
		t.Fatalf("ordinary state generation=%d, want rotated generation %d",
			next.DictionaryGeneration, rotated.DictionaryGeneration)
	}
	frame := deriver.FrameForState(next)
	if frame.Kind != terminalengine.FrameSnapshot {
		t.Fatalf("frame after dropped rotation must be snapshot, got kind=%v rev=%d",
			frame.Kind, frame.Seq)
	}

	// Within the same generation, later changes may be patches again.
	repaint()
	later := p.ExportState(0, seq)
	patch := deriver.FrameForState(later)
	if patch.BaseRevision == 0 {
		t.Fatal("same-generation frame after the snapshot should be a patch")
	}
	if patch.BaseRevision != frame.Seq {
		t.Fatalf("patch base=%d, want last delivered revision %d", patch.BaseRevision, frame.Seq)
	}
}

func TestProjector_LayoutEpochChangeAdvancesDictionaryGeneration(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(100, nil)
	engine := terminalengine.NewEngine(3, 12, sb)
	p := NewProjector(engine, sb, "s1", "i1")

	before := p.ExportState(0, 1)
	engine.Resize(4, 12)
	after := p.ExportState(1, 2)
	if after.DictionaryGeneration != before.DictionaryGeneration+1 {
		t.Fatalf("epoch change generation=%d, want %d",
			after.DictionaryGeneration, before.DictionaryGeneration+1)
	}
}

// 空 patch 抑制（计划 §3.4/§10.1）：输出只推进 revision 而无任何可观察变化
// （bell、title 设为原值）时，deriver 返回 Kind==0 的零值帧且不推进
// baseline；下一个真实 patch 的 base 仍等于最后实际写出的 revision。
func TestFrameDeriver_SuppressesEmptyPatch(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(5, 10, sb)
	if err := engine.Write([]byte("hello")); err != nil {
		t.Fatal(err)
	}
	p := NewProjector(engine, sb, "s1", "i1")
	var deriver FrameDeriver

	first := deriver.FrameForState(p.ExportState(0, 1))
	if first.Kind != terminalengine.FrameSnapshot {
		t.Fatalf("first frame must be snapshot, got kind=%v", first.Kind)
	}

	// bell 是副作用，屏幕状态不变。
	if err := engine.Write([]byte("\x07")); err != nil {
		t.Fatal(err)
	}
	if frame := deriver.FrameForState(p.ExportState(0, 2)); frame.Kind != 0 {
		t.Fatalf("bell-only state must be suppressed, got kind=%v", frame.Kind)
	}

	// title 设为原值（空串）：三态中属于"未变化"，同样不得产出 patch。
	if err := engine.Write([]byte("\x1b]0;\x07")); err != nil {
		t.Fatal(err)
	}
	if frame := deriver.FrameForState(p.ExportState(0, 3)); frame.Kind != 0 {
		t.Fatalf("same-title state must be suppressed, got kind=%v", frame.Kind)
	}

	// 真实变化：base 必须仍是最后实际写出的 revision 1。
	if err := engine.Write([]byte("!")); err != nil {
		t.Fatal(err)
	}
	patch := deriver.FrameForState(p.ExportState(0, 4))
	if patch.Kind != terminalengine.FramePatch {
		t.Fatalf("real change must derive patch, got kind=%v", patch.Kind)
	}
	if patch.BaseRevision != 1 {
		t.Fatalf("patch base=%d after suppressed empties, want last written revision 1", patch.BaseRevision)
	}
	if patch.Seq != 4 {
		t.Fatalf("patch seq=%d, want 4", patch.Seq)
	}
}
