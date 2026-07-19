package headlessterm

import (
	"image/color"
)

// This file implements the atomic read-only projection API (performance plan
// §6.1, docs/go-android-terminal-performance-optimization-plan.md). It lets a
// consumer read a consistent snapshot of terminal metadata plus exactly the
// rows that changed, under a single read lock, instead of walking the grid
// cell by cell with per-call locking.

// BufferKind identifies which terminal buffer a projection was read from.
type BufferKind int

const (
	// BufferKindPrimary is the main screen, backed by scrollback.
	BufferKindPrimary BufferKind = iota
	// BufferKindAlternate is the full-screen buffer without scrollback.
	BufferKindAlternate
)

// ProjectionCursor is an immutable snapshot of the cursor state.
type ProjectionCursor struct {
	Row     int
	Col     int
	Style   CursorStyle
	Visible bool
}

// ProjectionRow is an immutable copy of one buffer row.
// Cells is deep-copied; its length is the row's actual cell count
// (normally equal to ProjectionRead.Cols).
type ProjectionRow struct {
	Index       int
	LineID      uint64
	LineVersion uint64
	Wrapped     bool
	Cells       []Cell
}

// ProjectionRead is an atomic, immutable view of terminal state.
//
// DirtyRows contains copied rows: every row when Full is true, otherwise
// only the rows modified since the last ConsumeProjectionDirty call, plus
// the old and new cursor rows when the cursor changed. Rows not present in
// DirtyRows are unchanged and must be reused from the consumer's cache.
//
// Title, WorkingDir, Modes, Cursor, Colors and ActiveBuffer always carry the
// current values, even when DirtyRows is empty, so metadata-only changes
// (title, cwd, palette, mode) are observable without any row being dirty.
type ProjectionRead struct {
	Rows         int
	Cols         int
	Cursor       ProjectionCursor
	Modes        TerminalMode
	ActiveBuffer BufferKind
	Title        string
	WorkingDir   string
	// Colors is a copy of the palette overrides set via SetColor/OSC
	// sequences (nil when no overrides exist).
	Colors    map[int]color.Color
	DirtyRows []ProjectionRow
	Full      bool

	// source is the buffer this projection was read from. It lets
	// ConsumeProjectionDirty clear exactly the state that was read, even if
	// the active buffer changed afterwards. Unexported: consumers can only
	// pass the whole ProjectionRead back to ConsumeProjectionDirty.
	source *Buffer
}

// ReadProjection returns an atomic read-only projection of the terminal:
// dimensions, cursor, modes, active buffer, title, working directory and
// copies of all dirty rows, taken under a single read lock.
//
// If the active buffer reports dirtyAll (scroll, clear, resize/reflow,
// buffer switch, initial state), the projection is Full and contains every
// row. Otherwise it contains only dirty rows; when the cursor moved since
// the last ConsumeProjectionDirty, both the previous and the current cursor
// rows are added to that set (deduplicated).
//
// ReadProjection does not clear dirty state: the consumer must first merge
// the rows into its own cache and then call ConsumeProjectionDirty. Cell
// slices are copies; mutating the returned data does not affect the terminal.
func (t *Terminal) ReadProjection() ProjectionRead {
	return t.readProjection(false)
}

// ReadFullProjection returns a complete projection of the terminal: every row
// copied, Full set to true, regardless of dirty state. It is the cache-rebuild
// companion of ReadProjection for consumers that must re-export everything
// even when no rows are dirty (first export after a cache drop, dictionary
// rotation, epoch change without geometry dirty marks).
//
// Like ReadProjection it does not clear dirty state; pair it with
// ConsumeProjectionDirty after the rows have been merged into the consumer's
// cache.
func (t *Terminal) ReadFullProjection() ProjectionRead {
	return t.readProjection(true)
}

func (t *Terminal) readProjection(forceFull bool) ProjectionRead {
	t.mu.RLock()
	defer t.mu.RUnlock()

	b := t.activeBuffer
	dirtyRows, dirtyAll := b.dirtyState()

	cursor := ProjectionCursor{
		Row:     t.cursor.Row,
		Col:     t.cursor.Col,
		Style:   t.cursor.Style,
		Visible: t.cursor.Visible,
	}

	var rows []ProjectionRow
	if dirtyAll || forceFull {
		rows = make([]ProjectionRow, 0, b.rows)
		for r := 0; r < b.rows; r++ {
			rows = append(rows, b.copyRow(r))
		}
	} else {
		// Cursor moved: the old cursor row must be redrawn without the
		// cursor, the new one with it. Merge both into the dirty set.
		if cursor != t.lastCursor {
			markProjectionRow(dirtyRows, t.lastCursor.Row)
			markProjectionRow(dirtyRows, cursor.Row)
		}
		for r := 0; r < b.rows; r++ {
			if dirtyRows[r] {
				rows = append(rows, b.copyRow(r))
			}
		}
	}

	kind := BufferKindPrimary
	if b == t.alternateBuffer {
		kind = BufferKindAlternate
	}

	var colors map[int]color.Color
	if len(t.colors) > 0 {
		colors = make(map[int]color.Color, len(t.colors))
		for i, c := range t.colors {
			colors[i] = c
		}
	}

	return ProjectionRead{
		Rows:         t.rows,
		Cols:         t.cols,
		Cursor:       cursor,
		Modes:        t.modes,
		ActiveBuffer: kind,
		Title:        t.title,
		WorkingDir:   workingDirPathFromURI(t.workingDir),
		Colors:       colors,
		DirtyRows:    rows,
		Full:         dirtyAll || forceFull,
		source:       b,
	}
}

// ConsumeProjectionDirty clears the dirty state read by an earlier
// ReadProjection and promotes that projection's cursor to the cursor
// baseline used for the next cursor-delta check.
//
// Call this only after the projection's rows have been merged into the
// consumer's cache (plan §6.1: dirty is cleared against the Projector cache,
// never against client acknowledgements). The caller must not let any write
// reach the terminal between ReadProjection and ConsumeProjectionDirty —
// the runtime actor serializes producers and the consumer on one goroutine,
// so this holds by construction; an interleaved write would be cleared
// without ever being observed. The dirty state of the exact buffer the
// projection was read from is cleared, so a buffer switch between read and
// consume cannot leak or lose marks.
func (t *Terminal) ConsumeProjectionDirty(p ProjectionRead) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if p.source != nil {
		p.source.clearDirty()
	}
	t.lastCursor = p.Cursor
}

// markProjectionRow sets row in the dirty set if it is in bounds.
func markProjectionRow(dirtyRows []bool, row int) {
	if row >= 0 && row < len(dirtyRows) {
		dirtyRows[row] = true
	}
}

// copyRow returns an immutable copy of row r.
// The caller must hold the terminal lock.
func (b *Buffer) copyRow(r int) ProjectionRow {
	cells := make([]Cell, len(b.cells[r]))
	for i := range b.cells[r] {
		cells[i] = b.cells[r][i].Copy()
	}
	return ProjectionRow{
		Index:       r,
		LineID:      b.lineID[r],
		LineVersion: b.lineVersion[r],
		Wrapped:     b.wrapped[r],
		Cells:       cells,
	}
}
