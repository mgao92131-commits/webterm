package headlessterm

import (
	"image/color"
	"strings"
	"testing"
)

// projectionRowIndices returns the set of row indices in a projection.
func projectionRowIndices(p ProjectionRead) map[int]bool {
	set := make(map[int]bool, len(p.DirtyRows))
	for _, r := range p.DirtyRows {
		set[r.Index] = true
	}
	return set
}

// consumeInitial reads and consumes the initial full projection so tests
// start from a clean dirty state.
func consumeInitial(t *testing.T, term *Terminal) {
	t.Helper()
	p := term.ReadProjection()
	if !p.Full {
		t.Fatal("expected full initial projection")
	}
	term.ConsumeProjectionDirty(p)
}

func TestProjectionSingleCellWrite(t *testing.T) {
	term := New(WithSize(24, 80))
	consumeInitial(t, term)

	term.WriteString("A")

	p := term.ReadProjection()
	if p.Full {
		t.Fatal("expected incremental projection after single-cell write")
	}
	if len(p.DirtyRows) != 1 {
		t.Fatalf("expected exactly 1 dirty row, got %d", len(p.DirtyRows))
	}
	row := p.DirtyRows[0]
	if row.Index != 0 {
		t.Errorf("expected dirty row 0, got row %d", row.Index)
	}
	if len(row.Cells) != 80 {
		t.Errorf("expected 80 cells in row copy, got %d", len(row.Cells))
	}
	if row.Cells[0].Char != "A" {
		t.Errorf("expected 'A' at (0,0), got %q", row.Cells[0].Char)
	}
}

func TestProjectionCursorMoveDirtyRows(t *testing.T) {
	term := New(WithSize(24, 80))
	consumeInitial(t, term)

	term.Goto(10, 5)

	p := term.ReadProjection()
	if p.Full {
		t.Fatal("expected incremental projection after cursor move")
	}
	set := projectionRowIndices(p)
	if !set[0] {
		t.Error("expected old cursor row 0 in dirty rows")
	}
	if !set[10] {
		t.Error("expected new cursor row 10 in dirty rows")
	}
	if len(set) != 2 {
		t.Errorf("expected exactly 2 dirty rows, got %v", set)
	}
	if p.Cursor.Row != 10 || p.Cursor.Col != 5 {
		t.Errorf("expected cursor at (10,5), got (%d,%d)", p.Cursor.Row, p.Cursor.Col)
	}

	// Cursor visibility change alone must also mark both rows.
	term.ConsumeProjectionDirty(p)
	term.WriteString("\x1b[?25l") // DECTCEM: hide cursor
	p = term.ReadProjection()
	set = projectionRowIndices(p)
	if len(set) != 1 || !set[10] {
		t.Errorf("expected cursor row 10 dirty after visibility change, got %v", set)
	}
	if p.Cursor.Visible {
		t.Error("expected cursor hidden in projection")
	}
}

func TestProjectionScrollFullDirty(t *testing.T) {
	storage := &testScrollback{lines: make([]ScrollbackLine, 0)}
	storage.SetMaxLines(100)
	term := New(WithSize(5, 80), WithScrollback(storage))
	consumeInitial(t, term)

	// Scroll the screen: 7 lines into a 5-row terminal.
	for i := 0; i < 7; i++ {
		term.WriteString("Line\n")
	}

	p := term.ReadProjection()
	if !p.Full {
		t.Fatal("expected full projection after scroll")
	}
	if len(p.DirtyRows) != 5 {
		t.Errorf("expected all 5 rows in full projection, got %d", len(p.DirtyRows))
	}

	// History append is unchanged: the first lines scrolled into scrollback.
	if term.ScrollbackLen() < 2 {
		t.Fatalf("expected scrollback to receive scrolled lines, got %d", term.ScrollbackLen())
	}
	if got := lineCellsText(term.ScrollbackLine(0)); got != "Line" {
		t.Errorf("expected first scrollback line %q, got %q", "Line", got)
	}
}

// lineCellsText renders a scrollback line as text for assertions.
func lineCellsText(line ScrollbackLine) string {
	var sb strings.Builder
	for _, cell := range line.Cells {
		if cell.IsWideSpacer() {
			continue
		}
		if cell.Char == "" {
			sb.WriteRune(' ')
		} else {
			sb.WriteString(cell.Char)
		}
	}
	return strings.TrimRight(sb.String(), " ")
}

func TestProjectionEraseCharsDirty(t *testing.T) {
	term := New(WithSize(24, 80))
	consumeInitial(t, term)

	term.WriteString("ABCDE")
	term.ConsumeProjectionDirty(term.ReadProjection())
	term.Goto(0, 1)
	term.ConsumeProjectionDirty(term.ReadProjection())

	term.EraseChars(2)

	p := term.ReadProjection()
	if p.Full {
		t.Fatal("expected incremental projection after ECH")
	}
	set := projectionRowIndices(p)
	if len(set) != 1 || !set[0] {
		t.Fatalf("expected only row 0 dirty, got %v", set)
	}
	// ECH semantics unchanged: cells reset to default, no shifting.
	if got := term.LineContent(0); got != "A  DE" {
		t.Errorf("expected %q after ECH, got %q", "A  DE", got)
	}
}

func TestProjectionSubstituteDirty(t *testing.T) {
	term := New(WithSize(24, 80))
	consumeInitial(t, term)

	term.Goto(3, 7)
	term.ConsumeProjectionDirty(term.ReadProjection())

	term.Substitute()

	p := term.ReadProjection()
	if p.Full {
		t.Fatal("expected incremental projection after SUB")
	}
	set := projectionRowIndices(p)
	if len(set) != 1 || !set[3] {
		t.Fatalf("expected only row 3 dirty, got %v", set)
	}
	if cell := term.Cell(3, 7); cell == nil || cell.Char != "?" {
		t.Errorf("expected '?' at (3,7), got %v", cell)
	}
}

func TestProjectionSetWrappedDirty(t *testing.T) {
	term := New(WithSize(24, 80))
	consumeInitial(t, term)

	term.SetWrapped(2, true)

	p := term.ReadProjection()
	if p.Full {
		t.Fatal("expected incremental projection after SetWrapped")
	}
	set := projectionRowIndices(p)
	if len(set) != 1 || !set[2] {
		t.Fatalf("expected only row 2 dirty, got %v", set)
	}
	if !p.DirtyRows[0].Wrapped {
		t.Error("expected wrapped flag in exported row")
	}
}

func TestProjectionAlternateScreenSwitchDirty(t *testing.T) {
	term := New(WithSize(24, 80))
	consumeInitial(t, term)

	// Enter alternate screen: alternate buffer must be fully dirty.
	term.WriteString("\x1b[?1049h")
	p := term.ReadProjection()
	if !p.Full {
		t.Fatal("expected full projection after switching to alternate screen")
	}
	if p.ActiveBuffer != BufferKindAlternate {
		t.Errorf("expected alternate buffer, got %v", p.ActiveBuffer)
	}
	term.ConsumeProjectionDirty(p)

	// Draw on the alternate screen, then switch back: the primary buffer
	// must be fully dirty even though nothing wrote to it meanwhile.
	term.WriteString("alt content")
	term.ConsumeProjectionDirty(term.ReadProjection())

	term.WriteString("\x1b[?1049l")
	p = term.ReadProjection()
	if !p.Full {
		t.Fatal("expected full projection after switching back to primary screen")
	}
	if p.ActiveBuffer != BufferKindPrimary {
		t.Errorf("expected primary buffer, got %v", p.ActiveBuffer)
	}
	if len(p.DirtyRows) != 24 {
		t.Errorf("expected all 24 primary rows, got %d", len(p.DirtyRows))
	}
}

func TestProjectionResizeFullDirty(t *testing.T) {
	term := New(WithSize(24, 80))
	consumeInitial(t, term)

	term.Resize(10, 40)

	p := term.ReadProjection()
	if !p.Full {
		t.Fatal("expected full projection after resize")
	}
	if p.Rows != 10 || p.Cols != 40 {
		t.Errorf("expected 10x40, got %dx%d", p.Rows, p.Cols)
	}
	if len(p.DirtyRows) != 10 {
		t.Errorf("expected 10 rows after resize, got %d", len(p.DirtyRows))
	}
	for i, row := range p.DirtyRows {
		if row.Index != i {
			t.Errorf("expected row index %d, got %d", i, row.Index)
		}
		if len(row.Cells) != 40 {
			t.Errorf("expected 40 cells in row %d, got %d", i, len(row.Cells))
		}
	}
}

func TestBufferResizeDirtyRows(t *testing.T) {
	b := NewBuffer(24, 80)
	b.TakeDirty() // discard initial full dirty

	b.Resize(10, 40)

	rows, all := b.TakeDirty()
	if !all {
		t.Error("expected dirtyAll after resize")
	}
	if len(rows) != 10 {
		t.Errorf("expected dirtyRows length 10 after resize, got %d", len(rows))
	}
}

func TestProjectionRowsAreCopies(t *testing.T) {
	term := New(WithSize(24, 80))
	consumeInitial(t, term)

	term.WriteString("HELLO")
	p := term.ReadProjection()
	if len(p.DirtyRows) != 1 {
		t.Fatalf("expected 1 dirty row, got %d", len(p.DirtyRows))
	}

	// Mutating the projection must not affect terminal state.
	p.DirtyRows[0].Cells[0].Char = "X"
	p.DirtyRows[0].Cells[0].SetFlag(CellFlagBold)
	p.DirtyRows[0].Wrapped = !p.DirtyRows[0].Wrapped

	if cell := term.Cell(0, 0); cell.Char != "H" || cell.HasFlag(CellFlagBold) {
		t.Errorf("terminal cell mutated through projection: %+v", cell)
	}
	if term.IsWrapped(0) {
		t.Error("terminal wrapped flag mutated through projection")
	}
}

func TestProjectionMetadata(t *testing.T) {
	term := New(WithSize(24, 80))
	consumeInitial(t, term)

	term.WriteString("\x1b]2;my title\x07")
	term.WriteString("\x1b]7;file://host/tmp/work\x07")
	term.WriteString("\x1b[?2004h") // bracketed paste on
	term.SetColor(42, color.RGBA{R: 1, G: 2, B: 3, A: 255})

	p := term.ReadProjection()
	if p.Title != "my title" {
		t.Errorf("expected title %q, got %q", "my title", p.Title)
	}
	if p.WorkingDir != "/tmp/work" {
		t.Errorf("expected cwd %q, got %q", "/tmp/work", p.WorkingDir)
	}
	if p.Modes&ModeBracketedPaste == 0 {
		t.Error("expected bracketed paste mode in projection")
	}
	if p.Modes&ModeLineWrap == 0 {
		t.Error("expected default line wrap mode in projection")
	}
	c, ok := p.Colors[42]
	if !ok {
		t.Fatal("expected palette override in projection")
	}
	if rgba, ok := c.(color.RGBA); !ok || rgba != (color.RGBA{R: 1, G: 2, B: 3, A: 255}) {
		t.Errorf("expected palette color preserved, got %v", c)
	}
	// The palette copy must not alias terminal state.
	p.Colors[42] = color.RGBA{}
	p2 := term.ReadProjection()
	if _, ok := p2.Colors[42].(color.RGBA); !ok || p2.Colors[42] == (color.RGBA{}) {
		t.Error("palette mutation leaked into terminal state")
	}
}

func TestProjectionConsumeClearsDirty(t *testing.T) {
	term := New(WithSize(24, 80))
	consumeInitial(t, term)

	term.WriteString("A")
	p := term.ReadProjection()
	if len(p.DirtyRows) == 0 {
		t.Fatal("expected dirty rows before consume")
	}

	term.ConsumeProjectionDirty(p)

	clean := term.ReadProjection()
	if clean.Full {
		t.Error("expected Full=false after consume")
	}
	if len(clean.DirtyRows) != 0 {
		t.Errorf("expected no dirty rows after consume, got %d", len(clean.DirtyRows))
	}

	// Writes after consume are tracked again from a clean state.
	term.WriteString("B")
	p = term.ReadProjection()
	if p.Full || len(p.DirtyRows) != 1 || p.DirtyRows[0].Index != 0 {
		t.Errorf("expected row 0 dirty after new write, got %+v", p)
	}
}

func TestProjectionAutoResizeGrowRows(t *testing.T) {
	term := New(WithSize(5, 80), WithAutoResize())
	consumeInitial(t, term)

	// In autoResize mode the buffer grows instead of scrolling.
	for i := 0; i < 8; i++ {
		term.WriteString("Line\n")
	}

	p := term.ReadProjection()
	if !p.Full {
		t.Fatal("expected full projection after GrowRows")
	}
	if p.Rows != term.Rows() {
		t.Errorf("projection rows %d != terminal rows %d", p.Rows, term.Rows())
	}
	if len(p.DirtyRows) != p.Rows {
		t.Errorf("expected %d rows in full projection, got %d", p.Rows, len(p.DirtyRows))
	}
}

func TestReadFullProjectionIgnoresDirtyState(t *testing.T) {
	term := New(WithSize(4, 10))
	consumeInitial(t, term)

	term.WriteString("hello")
	p := term.ReadProjection()
	term.ConsumeProjectionDirty(p)

	// No dirty state at all: ReadProjection would return zero rows, but
	// ReadFullProjection must still return every row marked Full.
	full := term.ReadFullProjection()
	if !full.Full {
		t.Error("expected Full=true from ReadFullProjection on a clean terminal")
	}
	if len(full.DirtyRows) != 4 {
		t.Fatalf("expected 4 rows, got %d", len(full.DirtyRows))
	}
	for i, row := range full.DirtyRows {
		if row.Index != i {
			t.Errorf("row %d has index %d", i, row.Index)
		}
		if len(row.Cells) != 10 {
			t.Errorf("row %d has %d cells, want 10", i, len(row.Cells))
		}
	}
	if got := full.DirtyRows[0].Cells[0].Char; got != "h" {
		t.Errorf("expected row 0 to start with 'h', got %q", got)
	}

	// Dirty state is untouched: the next incremental read still sees a clean
	// terminal, and consuming the full projection keeps it clean.
	term.ConsumeProjectionDirty(full)
	clean := term.ReadProjection()
	if clean.Full || len(clean.DirtyRows) != 0 {
		t.Errorf("expected clean incremental projection after full consume, got %+v", clean)
	}
}
