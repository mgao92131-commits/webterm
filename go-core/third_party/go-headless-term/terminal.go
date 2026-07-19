package headlessterm

import (
	"image/color"
	"io"
	"strings"
	"sync"
	"unicode/utf8"

	"github.com/danielgatis/go-ansicode"
	"github.com/rivo/uniseg"
)

// PTYWriter writes terminal responses (e.g., cursor position reports) back to the PTY.
// This is used for DSR (Device Status Report), DA (Device Attributes), and other
// terminal queries that require a response to be sent back to the controlling process.
type PTYWriter = io.Writer

// NoopPTYWriter discards all response data (useful when responses are not needed).
type NoopPTYWriter struct{}

func (NoopPTYWriter) Write(p []byte) (n int, err error) {
	return len(p), nil
}

// Ensure Terminal implements ansicode.Handler
var _ ansicode.Handler = (*Terminal)(nil)

// TerminalMode is a bitmask of terminal behavior flags.
// Multiple modes can be active simultaneously.
type TerminalMode uint32

const (
	// ModeCursorKeys enables cursor key mode (DECCKM).
	ModeCursorKeys TerminalMode = 1 << iota
	// ModeColumnMode enables 132-column mode.
	ModeColumnMode
	// ModeInsert enables insert mode (characters shift right instead of overwrite).
	ModeInsert
	// ModeOrigin enables origin mode (cursor positioning relative to scroll region).
	ModeOrigin
	// ModeLineWrap enables automatic line wrapping at column boundaries.
	ModeLineWrap
	// ModeBlinkingCursor enables blinking cursor.
	ModeBlinkingCursor
	// ModeLineFeedNewLine makes line feed also move to column 0.
	ModeLineFeedNewLine
	// ModeShowCursor makes the cursor visible.
	ModeShowCursor
	// ModeReportMouseClicks enables mouse click reporting.
	ModeReportMouseClicks
	// ModeReportCellMouseMotion enables mouse motion reporting (cell-based).
	ModeReportCellMouseMotion
	// ModeReportAllMouseMotion enables reporting of all mouse motion events.
	ModeReportAllMouseMotion
	// ModeReportFocusInOut enables focus in/out event reporting.
	ModeReportFocusInOut
	// ModeUTF8Mouse enables UTF-8 mouse encoding.
	ModeUTF8Mouse
	// ModeSGRMouse enables SGR mouse encoding.
	ModeSGRMouse
	// ModeAlternateScroll enables alternate scroll mode.
	ModeAlternateScroll
	// ModeUrgencyHints enables urgency hints.
	ModeUrgencyHints
	// ModeSwapScreenAndSetRestoreCursor swaps to alternate screen and saves cursor.
	// When unset, restores primary screen and cursor position.
	ModeSwapScreenAndSetRestoreCursor
	// ModeBracketedPaste enables bracketed paste mode.
	ModeBracketedPaste
	// ModeKeypadApplication enables application keypad mode.
	ModeKeypadApplication
)

const (
	// DEFAULT_ROWS is the default number of terminal rows.
	DEFAULT_ROWS = 24
	// DEFAULT_COLS is the default number of terminal columns.
	DEFAULT_COLS = 80
)

// Selection defines a rectangular text region in the terminal.
// Start and End are normalized so Start is always before or equal to End.
type Selection struct {
	Start  Position
	End    Position
	Active bool
}

// Terminal emulates a VT220-compatible terminal without a display.
// It maintains two buffers: primary (with scrollback) and alternate (no scrollback).
// The active buffer switches when entering/exiting alternate screen mode.
// All operations are thread-safe via internal locking.
type Terminal struct {
	mu sync.RWMutex

	// Dimensions
	rows int
	cols int

	// Buffers
	primaryBuffer   *Buffer
	alternateBuffer *Buffer
	activeBuffer    *Buffer

	// Cursor
	cursor      *Cursor
	savedCursor *SavedCursor

	// Current cell attributes
	template CellTemplate

	// Charsets
	charsets      [4]Charset
	activeCharset int
	// TODO(doc): clarify semantics - charsetIndexes appears unused
	charsetIndexes [4]CharsetIndex

	// Scrolling region
	scrollTop    int
	scrollBottom int

	// Modes
	modes TerminalMode

	// Title
	title      string
	titleStack []string

	// Colors
	colors map[int]color.Color

	// Hyperlink
	currentHyperlink *Hyperlink

	// Keyboard mode
	keyboardModes   []ansicode.KeyboardMode
	modifyOtherKeys ansicode.ModifyOtherKeys

	// Internal ANSI decoder
	decoder *ansicode.Decoder

	// Selection
	selection Selection

	// Scrollback provider
	scrollbackStorage ScrollbackProvider

	// Middleware for handler interception
	middleware *Middleware

	// Providers for external data/actions
	ptyWriter         PTYWriter
	bellProvider      BellProvider
	titleProvider     TitleProvider
	apcProvider       APCProvider
	pmProvider        PMProvider
	sosProvider       SOSProvider
	clipboardProvider ClipboardProvider

	// AutoResize mode: terminal grows instead of scrolling/wrapping
	autoResize bool

	// Recording provider for capturing raw input
	recordingProvider RecordingProvider

	// Semantic prompt handler (OSC 133)
	semanticPromptHandler SemanticPromptHandler
	promptMarks           []PromptMark

	// Working directory (OSC 7)
	workingDir string

	// Size provider for pixel-level queries
	sizeProvider SizeProvider

	// Image manager for Sixel and Kitty graphics
	images *ImageManager

	// Image protocol flags
	sixelEnabled bool
	kittyEnabled bool

	// Pending grapheme cluster input. The ansicode decoder feeds one rune at a
	// time via Input, so keep this as bytes to avoid allocating and copying a
	// new Go string for every printable rune.
	pendingInput []byte

	// Notification provider for OSC 99 (Kitty desktop notifications)
	notificationProvider NotificationProvider

	// User variables (OSC 1337 SetUserVar)
	userVars map[string]string

	// Cursor baseline from the last consumed projection (row/col/style/
	// visibility). ReadProjection compares against it to mark both the old
	// and new cursor rows dirty; ConsumeProjectionDirty updates it.
	lastCursor ProjectionCursor
}

// Option configures a Terminal during construction.
type Option func(*Terminal)

// WithSize sets the terminal dimensions.
// Values <= 0 are replaced with defaults (24x80).
func WithSize(rows, cols int) Option {
	if rows <= 0 {
		rows = DEFAULT_ROWS
	}

	if cols <= 0 {
		cols = DEFAULT_COLS
	}

	return func(t *Terminal) {
		t.rows = rows
		t.cols = cols
	}
}

// WithPTYWriter sets the writer for terminal responses (e.g., cursor position reports).
// If nil, responses are discarded.
func WithPTYWriter(p PTYWriter) Option {
	return func(t *Terminal) {
		t.ptyWriter = p
	}
}

// WithResponse is an alias for WithPTYWriter, kept for test compatibility.
func WithResponse(p PTYWriter) Option {
	return WithPTYWriter(p)
}

// WithBell sets the handler for bell/beep events.
// Defaults to a no-op if not set.
func WithBell(p BellProvider) Option {
	return func(t *Terminal) {
		t.bellProvider = p
	}
}

// WithTitle sets the handler for window title changes.
// Defaults to a no-op if not set.
func WithTitle(p TitleProvider) Option {
	return func(t *Terminal) {
		t.titleProvider = p
	}
}

// WithAPC sets the handler for Application Program Command sequences.
// Defaults to a no-op if not set.
func WithAPC(p APCProvider) Option {
	return func(t *Terminal) {
		t.apcProvider = p
	}
}

// WithPM sets the handler for Privacy Message sequences.
// Defaults to a no-op if not set.
func WithPM(p PMProvider) Option {
	return func(t *Terminal) {
		t.pmProvider = p
	}
}

// WithSOS sets the handler for Start of String sequences.
// Defaults to a no-op if not set.
func WithSOS(p SOSProvider) Option {
	return func(t *Terminal) {
		t.sosProvider = p
	}
}

// WithClipboard sets the handler for clipboard read/write operations (OSC 52).
// Defaults to a no-op if not set.
func WithClipboard(p ClipboardProvider) Option {
	return func(t *Terminal) {
		t.clipboardProvider = p
	}
}

// WithScrollback sets the storage for scrollback lines.
// Lines scrolled off the top are pushed here. Defaults to a no-op if not set.
func WithScrollback(storage ScrollbackProvider) Option {
	return func(t *Terminal) {
		t.scrollbackStorage = storage
	}
}

// WithMiddleware sets functions to intercept ANSI handler calls.
// Each middleware receives the original parameters and a next function to call the default implementation.
func WithMiddleware(mw *Middleware) Option {
	return func(t *Terminal) {
		if t.middleware == nil {
			t.middleware = &Middleware{}
		}
		t.middleware.Merge(mw)
	}
}

// WithAutoResize enables growth mode: the buffer expands instead of scrolling or wrapping.
// Useful for capturing complete output without truncation.
func WithAutoResize() Option {
	return func(t *Terminal) {
		t.autoResize = true
	}
}

// WithRecording sets the handler for capturing raw input bytes before ANSI parsing.
// Useful for replay, debugging, or regression testing.
func WithRecording(p RecordingProvider) Option {
	return func(t *Terminal) {
		t.recordingProvider = p
	}
}

// WithSemanticPromptHandler sets the handler for semantic prompt events (OSC 133).
func WithSemanticPromptHandler(h SemanticPromptHandler) Option {
	return func(t *Terminal) {
		t.semanticPromptHandler = h
	}
}

// WithSizeProvider sets the provider for pixel dimension queries.
func WithSizeProvider(p SizeProvider) Option {
	return func(t *Terminal) {
		t.sizeProvider = p
	}
}

// WithSixel enables or disables Sixel graphics protocol support.
// When disabled, Sixel sequences are ignored.
// Default is true (enabled).
func WithSixel(enabled bool) Option {
	return func(t *Terminal) {
		t.sixelEnabled = enabled
	}
}

// WithKitty enables or disables Kitty graphics protocol support.
// When disabled, Kitty graphics APC sequences are ignored.
// Default is true (enabled).
func WithKitty(enabled bool) Option {
	return func(t *Terminal) {
		t.kittyEnabled = enabled
	}
}

// WithNotification sets the handler for OSC 99 desktop notifications (Kitty protocol).
// The provider receives notification payloads and can display native desktop notifications.
// Query requests (PayloadType == "?") should return a capability string that is written back to the terminal.
// Defaults to a no-op if not set.
//
// See: https://sw.kovidgoyal.net/kitty/desktop-notifications/
func WithNotification(p NotificationProvider) Option {
	return func(t *Terminal) {
		t.notificationProvider = p
	}
}

// SixelEnabled returns true if Sixel graphics protocol is enabled.
func (t *Terminal) SixelEnabled() bool {
	return t.sixelEnabled
}

// KittyEnabled returns true if Kitty graphics protocol is enabled.
func (t *Terminal) KittyEnabled() bool {
	return t.kittyEnabled
}

// New creates a terminal with the given options.
// Defaults to 24x80 with line wrap and cursor visible.
func New(opts ...Option) *Terminal {
	t := &Terminal{
		rows:                 DEFAULT_ROWS,
		cols:                 DEFAULT_COLS,
		colors:               make(map[int]color.Color),
		keyboardModes:        make([]ansicode.KeyboardMode, 0),
		bellProvider:         NoopBell{},
		titleProvider:        NoopTitle{},
		apcProvider:          NoopAPC{},
		pmProvider:           NoopPM{},
		sosProvider:          NoopSOS{},
		clipboardProvider:    NoopClipboard{},
		recordingProvider:    NoopRecording{},
		notificationProvider: NoopNotification{},
		sixelEnabled:         true,
		kittyEnabled:         true,
		userVars:             make(map[string]string),
	}

	for _, opt := range opts {
		opt(t)
	}

	// Create primary buffer with scrollback provider
	if t.scrollbackStorage == nil {
		t.scrollbackStorage = NoopScrollback{}
	}
	nextLineID := uint64(1)
	t.primaryBuffer = NewBufferWithStorageAndLineIDs(t.rows, t.cols, t.scrollbackStorage, &nextLineID)
	t.alternateBuffer = NewBufferWithStorageAndLineIDs(t.rows, t.cols, NoopScrollback{}, &nextLineID) // Alternate buffer has no scrollback
	t.activeBuffer = t.primaryBuffer

	t.cursor = NewCursor()
	t.template = NewCellTemplate()

	t.scrollTop = 0
	t.scrollBottom = t.rows

	t.modes = ModeLineWrap | ModeShowCursor

	// Create internal decoder
	t.decoder = ansicode.NewDecoder(t)

	// Create image manager
	t.images = NewImageManager()

	return t
}

// Rows returns the terminal height in character rows.
func (t *Terminal) Rows() int {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.rows
}

// Cols returns the terminal width in character columns.
func (t *Terminal) Cols() int {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.cols
}

// Cell returns the cell at (row, col) in the active buffer.
// Row is viewport-relative (0 to Rows()-1), not absolute.
// Returns nil if coordinates are out of bounds.
func (t *Terminal) Cell(row, col int) *Cell {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.activeBuffer.Cell(row, col)
}

// CursorPos returns the current cursor position (0-based).
func (t *Terminal) CursorPos() (row, col int) {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.cursor.Row, t.cursor.Col
}

// CursorVisible returns true if the cursor is currently visible.
func (t *Terminal) CursorVisible() bool {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.cursor.Visible
}

// CursorStyle returns the current cursor rendering style.
func (t *Terminal) CursorStyle() CursorStyle {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.cursor.Style
}

// Title returns the current window title string.
func (t *Terminal) Title() string {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.title
}

// HasMode returns true if the specified mode flag is enabled.
func (t *Terminal) HasMode(mode TerminalMode) bool {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.modes&mode != 0
}

// Resize changes the terminal dimensions and adjusts buffers accordingly.
// When shrinking rows, lines above cursor are moved to scrollback to preserve
// content near the cursor. Cursor position is clamped to the new bounds.
// Invalid dimensions (<= 0) are ignored.
func (t *Terminal) Resize(rows, cols int) {
	if rows <= 0 || cols <= 0 {
		return
	}

	t.mu.Lock()
	defer t.mu.Unlock()

	oldRows := t.rows

	// When shrinking rows on primary buffer, scroll lines to scrollback
	// to preserve content near cursor
	if rows < oldRows && t.activeBuffer == t.primaryBuffer {
		linesToScroll := oldRows - rows
		// Only scroll if cursor would be pushed off screen
		if t.cursor.Row >= rows {
			// Scroll up to keep cursor visible
			t.primaryBuffer.ScrollUp(0, oldRows, linesToScroll)
			t.cursor.Row -= linesToScroll
			if t.cursor.Row < 0 {
				t.cursor.Row = 0
			}
		}
	}

	// Update dimensions and resize buffers FIRST
	t.rows = rows
	t.cols = cols
	t.primaryBuffer.Resize(rows, cols)
	t.alternateBuffer.Resize(rows, cols)

	// When growing rows on primary buffer, pull lines from scrollback
	// to restore previously scrolled content
	// NOTE: This must happen AFTER buffer resize so ScrollDown has room to shift content
	if rows > oldRows && t.activeBuffer == t.primaryBuffer {
		scrollback := t.primaryBuffer.ScrollbackProvider()
		if scrollback != nil && scrollback.Len() > 0 {
			linesToPull := rows - oldRows
			if linesToPull > scrollback.Len() {
				linesToPull = scrollback.Len()
			}

			// Pop lines from scrollback (most recent first) and collect them
			// We need to reverse because Pop returns newest first
			lines := make([]ScrollbackLine, linesToPull)
			for i := linesToPull - 1; i >= 0; i-- {
				line := scrollback.Pop()
				if line.Cells == nil {
					linesToPull = linesToPull - 1 - i
					lines = lines[linesToPull-1-i:]
					break
				}
				lines[i] = line
			}

			if len(lines) > 0 {
				// Shift existing content down to make room at top
				// Now buffer has 'rows' height, so there's room to shift
				t.primaryBuffer.ScrollDown(0, rows, len(lines))

				// Copy popped lines to the top of the buffer
				for i, line := range lines {
					t.primaryBuffer.RestoreRow(i, line.Cells, line.Wrapped, line.LineID, line.LineVersion)
				}

				// Adjust cursor position to account for the shift
				t.cursor.Row += len(lines)
			}
		}
	}

	// Clamp cursor to bounds
	if t.cursor.Row >= rows {
		t.cursor.Row = rows - 1
	}
	if t.cursor.Row < 0 {
		t.cursor.Row = 0
	}
	if t.cursor.Col >= cols {
		t.cursor.Col = cols - 1
	}
	if t.cursor.Col < 0 {
		t.cursor.Col = 0
	}

	// Adjust scroll region
	t.scrollTop = 0
	t.scrollBottom = rows
}

// Write processes raw bytes, parsing ANSI escape sequences and updating the terminal state.
// Implements io.Writer.
func (t *Terminal) Write(data []byte) (int, error) {
	t.recordingProvider.Record(data)
	n, err := t.decoder.Write(data)
	// PTY reads are arbitrary transport chunks, not grapheme boundaries. We do
	// publish the trailing cluster for low-latency projection, but a later Write
	// may reopen and extend that last visible cell (combining/VS/ZWJ/RI).
	t.flushPendingInput()
	return n, err
}

// WriteString is a convenience method that converts the string to bytes and calls Write.
func (t *Terminal) WriteString(s string) (int, error) {
	return t.Write([]byte(s))
}

// flushPendingInput writes any incomplete grapheme cluster that is still buffered.
// It must be called before any non-Input handler so that buffered printable data
// is committed at the correct cursor position.
func (t *Terminal) flushPendingInput() {
	t.mu.Lock()
	defer t.mu.Unlock()

	if len(t.pendingInput) == 0 {
		return
	}

	cluster := string(t.pendingInput)
	t.pendingInput = t.pendingInput[:0]
	t.commitCluster(cluster)
}

// commitCluster publishes a segmenter-emitted cluster. A cluster that begins
// with a combining continuation may be emitted immediately before a following
// base rune in the same decoder call, so extension must happen here as well as
// at the end-of-Write low-latency flush.
// Caller must hold t.mu.
func (t *Terminal) commitCluster(cluster string) {
	if t.extendPreviousCluster(cluster) {
		return
	}
	width := clusterWidth(cluster)
	if width != 0 {
		t.writeCluster(cluster, width)
	}
}

// extendPreviousCluster reopens the visible cell immediately before the
// cursor when a later PTY chunk continues the same Unicode grapheme. This
// decouples segmentation from Terminal.Write boundaries while retaining
// immediate display of ordinary trailing text.
// Caller must hold t.mu.
func (t *Terminal) extendPreviousCluster(suffix string) bool {
	if suffix == "" || t.cursor.Row < 0 || t.cursor.Row >= t.rows {
		return false
	}
	col := t.cursor.Col - 1
	if col >= t.cols {
		col = t.cols - 1
	}
	if col < 0 {
		return false
	}
	cell := t.activeBuffer.Cell(t.cursor.Row, col)
	if cell != nil && cell.IsWideSpacer() {
		col--
		cell = t.activeBuffer.Cell(t.cursor.Row, col)
	}
	if cell == nil || cell.Char == "" || cell.Char == " " {
		return false
	}
	combined := cell.Char + suffix
	_, rest, _, _ := uniseg.Step([]byte(combined), -1)
	if len(rest) != 0 {
		return false
	}

	oldWidth := 1
	if cell.IsWide() {
		oldWidth = 2
	}
	newWidth := clusterWidth(combined)
	if newWidth == 0 || newWidth > 2 {
		return false
	}
	if oldWidth == 1 && newWidth == 2 {
		spacerCol := col + 1
		if spacerCol >= t.cols {
			// A width-changing continuation at the right edge must behave like
			// the unsplit grapheme: remove the provisional narrow glyph and let
			// writeCluster perform normal wrap/scroll handling.
			provisional := cell.Copy()
			cell.Reset()
			t.activeBuffer.MarkDirty(t.cursor.Row, col)
			t.cursor.Col = col
			originalTemplate := t.template
			t.template.Cell = provisional
			t.writeCluster(combined, newWidth)
			t.template = originalTemplate
			return true
		}
		if t.modes&ModeInsert != 0 {
			t.activeBuffer.InsertBlanks(t.cursor.Row, spacerCol, 1)
		}
		spacer := t.activeBuffer.Cell(t.cursor.Row, spacerCol)
		if spacer != nil {
			spacer.Reset()
			spacer.Char = ""
			spacer.Fg = cell.Fg
			spacer.Bg = cell.Bg
			spacer.SetFlag(CellFlagWideCharSpacer)
			t.activeBuffer.MarkDirty(t.cursor.Row, spacerCol)
		}
		cell.SetFlag(CellFlagWideChar)
		t.cursor.Col++
	} else if oldWidth == 2 && newWidth == 1 {
		spacer := t.activeBuffer.Cell(t.cursor.Row, col+1)
		if spacer != nil && spacer.IsWideSpacer() {
			spacer.Reset()
			t.activeBuffer.MarkDirty(t.cursor.Row, col+1)
		}
		cell.ClearFlag(CellFlagWideChar)
		if t.cursor.Col > 0 {
			t.cursor.Col--
		}
	}
	cell.Char = combined
	t.activeBuffer.MarkDirty(t.cursor.Row, col)
	return true
}

// writeCluster writes a complete grapheme cluster to the active buffer at the
// current cursor position, handling wide-character spacers and wrapping.
// Caller must hold t.mu.
func (t *Terminal) writeCluster(cluster string, width int) {
	if cluster == "" || width == 0 {
		return
	}

	// Handle line drawing charset for single-rune clusters.
	runes := []rune(cluster)
	if len(runes) == 1 && t.activeCharset >= 0 && t.activeCharset < 4 && t.charsets[t.activeCharset] == CharsetLineDrawing {
		runes[0] = t.translateLineDrawing(runes[0])
		cluster = string(runes)
	}

	// Check if we need to wrap.
	if t.cursor.Col+width > t.cols {
		if t.autoResize {
			// Grow the line instead of wrapping.
			t.activeBuffer.GrowCols(t.cursor.Row, t.cursor.Col+width)
			t.cols = t.activeBuffer.Cols()
			// Revalidate cursor position after buffer growth.
			if t.cursor.Col >= t.cols {
				t.cursor.Col = t.cols - 1
			}
		} else if t.modes&ModeLineWrap != 0 {
			// Mark the current line as wrapped (not explicit newline).
			t.activeBuffer.SetWrapped(t.cursor.Row, true)
			t.cursor.Col = 0
			t.cursor.Row++
			// Validate cursor row is within bounds before scrolling.
			if t.cursor.Row >= t.rows {
				t.scrollIfNeeded()
			}
		} else {
			// Can't fit wide character at end of line.
			if width == 2 {
				return
			}
			t.cursor.Col = t.cols - 1
		}
	}

	// Insert mode: shift characters to the right.
	if t.modes&ModeInsert != 0 {
		t.activeBuffer.InsertBlanks(t.cursor.Row, t.cursor.Col, width)
	}

	// Validate cursor position is within bounds before writing.
	if t.cursor.Row < 0 || t.cursor.Row >= t.rows || t.cursor.Col < 0 {
		return
	}

	// Write the character only if within column bounds.
	if t.cursor.Col < t.cols {
		cell := t.activeBuffer.Cell(t.cursor.Row, t.cursor.Col)
		if cell != nil {
			cell.Char = cluster
			cell.Fg = t.template.Fg
			cell.Bg = t.template.Bg
			cell.UnderlineColor = t.template.UnderlineColor
			cell.Flags = t.template.Flags
			cell.Hyperlink = t.currentHyperlink

			// Mark as wide character if needed.
			if width == 2 {
				cell.SetFlag(CellFlagWideChar)
			} else {
				cell.ClearFlag(CellFlagWideChar | CellFlagWideCharSpacer)
			}

			// Mark as dirty.
			t.activeBuffer.MarkDirty(t.cursor.Row, t.cursor.Col)
		}
	}

	t.cursor.Col++

	// For wide characters, add a spacer cell.
	if width == 2 && t.cursor.Col < t.cols {
		spacer := t.activeBuffer.Cell(t.cursor.Row, t.cursor.Col)
		if spacer != nil {
			spacer.Reset()
			spacer.Char = ""
			spacer.Fg = t.template.Fg
			spacer.Bg = t.template.Bg
			spacer.SetFlag(CellFlagWideCharSpacer)
			t.activeBuffer.MarkDirty(t.cursor.Row, t.cursor.Col)
		}
		t.cursor.Col++
	}

	// Ensure cursor stays within bounds after all operations.
	// Only clamp if we're not in a state that will handle overflow (wrap/scroll/auto-resize).
	if t.cursor.Col >= t.cols && !t.autoResize && t.modes&ModeLineWrap == 0 {
		t.cursor.Col = t.cols - 1
	}
	if t.cursor.Row >= t.rows && !t.autoResize {
		// scrollIfNeeded will handle this, but ensure we don't go beyond buffer.
		if t.cursor.Row >= t.activeBuffer.Rows() {
			t.cursor.Row = t.activeBuffer.Rows() - 1
		}
	}
	if t.cursor.Col < 0 {
		t.cursor.Col = 0
	}
	if t.cursor.Row < 0 {
		t.cursor.Row = 0
	}
}

// clamp ensures the value is within the given range.
func clamp(val, min, max int) int {
	if val < min {
		return min
	}
	if val > max {
		return max
	}
	return val
}

// effectiveRow returns the effective row considering origin mode.
func (t *Terminal) effectiveRow(row int) int {
	if t.modes&ModeOrigin != 0 {
		return row + t.scrollTop
	}
	return row
}

// scrollIfNeeded performs scrolling if cursor is outside scroll region.
// In autoResize mode, grows the buffer instead of scrolling.
func (t *Terminal) scrollIfNeeded() {
	if t.cursor.Row >= t.scrollBottom {
		if t.autoResize {
			// Grow the buffer instead of scrolling
			rowsToAdd := t.cursor.Row - t.scrollBottom + 1
			t.activeBuffer.GrowRows(rowsToAdd)
			t.rows = t.activeBuffer.Rows()
			t.scrollBottom = t.rows
		} else {
			linesToScroll := t.cursor.Row - t.scrollBottom + 1
			t.activeBuffer.ScrollUp(t.scrollTop, t.scrollBottom, linesToScroll)
			t.cursor.Row = t.scrollBottom - 1
		}
	} else if t.cursor.Row < t.scrollTop {
		linesToScroll := t.scrollTop - t.cursor.Row
		t.activeBuffer.ScrollDown(t.scrollTop, t.scrollBottom, linesToScroll)
		t.cursor.Row = t.scrollTop
	}
}

// SetPTYWriter sets the PTY writer at runtime.
func (t *Terminal) SetPTYWriter(p PTYWriter) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.ptyWriter = p
}

// PTYWriter returns the current PTY writer.
func (t *Terminal) PTYWriter() PTYWriter {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.ptyWriter
}

// SetBellProvider sets the bell provider at runtime.
func (t *Terminal) SetBellProvider(p BellProvider) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.bellProvider = p
}

// BellProvider returns the current bell provider.
func (t *Terminal) BellProvider() BellProvider {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.bellProvider
}

// SetTitleProvider sets the title provider at runtime.
func (t *Terminal) SetTitleProvider(p TitleProvider) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.titleProvider = p
}

// TitleProvider returns the current title provider.
func (t *Terminal) TitleProvider() TitleProvider {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.titleProvider
}

// SetAPCProvider sets the APC provider at runtime.
func (t *Terminal) SetAPCProvider(p APCProvider) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.apcProvider = p
}

// APCProvider returns the current APC provider.
func (t *Terminal) APCProvider() APCProvider {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.apcProvider
}

// SetPMProvider sets the PM provider at runtime.
func (t *Terminal) SetPMProvider(p PMProvider) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.pmProvider = p
}

// PMProvider returns the current PM provider.
func (t *Terminal) PMProvider() PMProvider {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.pmProvider
}

// SetSOSProvider sets the SOS provider at runtime.
func (t *Terminal) SetSOSProvider(p SOSProvider) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.sosProvider = p
}

// SOSProvider returns the current SOS provider.
func (t *Terminal) SOSProvider() SOSProvider {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.sosProvider
}

// SetClipboardProvider sets the clipboard provider at runtime.
func (t *Terminal) SetClipboardProvider(c ClipboardProvider) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.clipboardProvider = c
}

// ClipboardProvider returns the current clipboard provider.
func (t *Terminal) ClipboardProvider() ClipboardProvider {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.clipboardProvider
}

// SetNotificationProvider sets the notification provider at runtime.
func (t *Terminal) SetNotificationProvider(p NotificationProvider) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.notificationProvider = p
}

// NotificationProvider returns the current notification provider.
func (t *Terminal) NotificationProvider() NotificationProvider {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.notificationProvider
}

// SetMiddleware sets the middleware at runtime.
func (t *Terminal) SetMiddleware(mw *Middleware) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.middleware = mw
}

// Middleware returns the current middleware.
func (t *Terminal) Middleware() *Middleware {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.middleware
}

// writeResponse writes a response back via the PTY writer if set.
// Thread-safe: reads ptyWriter with lock to avoid race conditions.
func (t *Terminal) writeResponse(data []byte) {
	t.mu.RLock()
	writer := t.ptyWriter
	t.mu.RUnlock()

	if writer != nil {
		writer.Write(data)
	}
}

// writeResponseString writes a string response back via the writer if set.
func (t *Terminal) writeResponseString(s string) {
	t.writeResponse([]byte(s))
}

// --- Scrollback Methods ---

// ScrollbackLen returns the number of lines stored in scrollback (primary buffer only).
func (t *Terminal) ScrollbackLen() int {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.primaryBuffer.ScrollbackLen()
}

// ScrollbackLine returns a line from scrollback, where 0 is the oldest line.
// Returns a zero value if index is out of range.
func (t *Terminal) ScrollbackLine(index int) ScrollbackLine {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.primaryBuffer.ScrollbackLine(index)
}

// ViewportRowToAbsolute converts a viewport row (0 to Rows()-1) to an absolute row.
// Absolute rows include the scrollback offset and are used by shell integration
// functions like NextPromptRow, PrevPromptRow, and GetPromptMarkAt.
func (t *Terminal) ViewportRowToAbsolute(viewportRow int) int {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return viewportRow + t.primaryBuffer.ScrollbackLen()
}

// AbsoluteRowToViewport converts an absolute row to a viewport row.
// Returns -1 if the row is in scrollback or beyond the visible viewport.
// Use this to convert shell integration row values (like PromptMark.Row)
// to viewport coordinates for functions like Cell() or LineContent().
func (t *Terminal) AbsoluteRowToViewport(absoluteRow int) int {
	t.mu.RLock()
	defer t.mu.RUnlock()
	scrollbackLen := t.primaryBuffer.ScrollbackLen()
	viewportRow := absoluteRow - scrollbackLen
	if viewportRow < 0 || viewportRow >= t.rows {
		return -1
	}
	return viewportRow
}

// ClearScrollback removes all stored scrollback lines.
func (t *Terminal) ClearScrollback() {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.primaryBuffer.ClearScrollback()
}

// SetMaxScrollback sets the maximum number of scrollback lines to retain.
// Older lines are automatically removed when the limit is exceeded.
func (t *Terminal) SetMaxScrollback(max int) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.primaryBuffer.SetMaxScrollback(max)
}

// MaxScrollback returns the current maximum scrollback capacity.
func (t *Terminal) MaxScrollback() int {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.primaryBuffer.MaxScrollback()
}

// SetScrollbackProvider replaces the scrollback storage implementation at runtime.
func (t *Terminal) SetScrollbackProvider(storage ScrollbackProvider) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.scrollbackStorage = storage
	t.primaryBuffer.SetScrollbackProvider(storage)
}

// ScrollbackProvider returns the current scrollback storage implementation.
func (t *Terminal) ScrollbackProvider() ScrollbackProvider {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.primaryBuffer.ScrollbackProvider()
}

// --- Selection Methods ---

// SetSelection sets the active text selection region.
// Start and end are automatically normalized so start is before or equal to end.
func (t *Terminal) SetSelection(start, end Position) {
	t.mu.Lock()
	defer t.mu.Unlock()

	// Normalize: ensure start is before end
	if end.Before(start) {
		start, end = end, start
	}

	t.selection = Selection{
		Start:  start,
		End:    end,
		Active: true,
	}
}

// ClearSelection deactivates the current selection.
func (t *Terminal) ClearSelection() {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.selection.Active = false
}

// GetSelection returns the current selection state.
func (t *Terminal) GetSelection() Selection {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.selection
}

// HasSelection returns true if a selection is currently active.
func (t *Terminal) HasSelection() bool {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.selection.Active
}

// IsSelected returns true if the cell at (row, col) is within the active selection.
func (t *Terminal) IsSelected(row, col int) bool {
	t.mu.RLock()
	defer t.mu.RUnlock()

	if !t.selection.Active {
		return false
	}

	pos := Position{Row: row, Col: col}
	start := t.selection.Start
	end := t.selection.End

	// Check if position is within selection range
	if pos.Before(start) {
		return false
	}
	if end.Before(pos) {
		return false
	}
	return true
}

// GetSelectedText extracts and returns the text content within the active selection.
// Empty cells are converted to spaces, and newlines separate rows.
func (t *Terminal) GetSelectedText() string {
	t.mu.RLock()
	defer t.mu.RUnlock()

	if !t.selection.Active {
		return ""
	}

	start := t.selection.Start
	end := t.selection.End

	var result strings.Builder

	for row := start.Row; row <= end.Row && row < t.rows; row++ {
		startCol := 0
		endCol := t.cols

		if row == start.Row {
			startCol = start.Col
		}
		if row == end.Row {
			endCol = end.Col + 1
		}

		for col := startCol; col < endCol && col < t.cols; col++ {
			cell := t.activeBuffer.Cell(row, col)
			if cell != nil && !cell.IsWideSpacer() {
				if cell.Char == "" {
					result.WriteRune(' ')
				} else {
					result.WriteString(cell.Char)
				}
			}
		}

		// Add newline between rows (but not after last row)
		if row < end.Row {
			result.WriteRune('\n')
		}
	}

	return result.String()
}

// --- Convenience Methods ---

// LineContent returns the text content of a line, trimming trailing spaces.
// Row is viewport-relative (0 to Rows()-1), not absolute.
// Returns empty string if the line contains only spaces or is out of bounds.
func (t *Terminal) LineContent(row int) string {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.activeBuffer.LineContent(row)
}

// String returns the visible screen content as a newline-separated string.
// Trailing empty lines are omitted. Implements fmt.Stringer.
func (t *Terminal) String() string {
	t.mu.RLock()
	defer t.mu.RUnlock()

	var lines []string
	lastNonEmpty := -1

	for row := range make([]struct{}, t.rows) {
		line := t.activeBuffer.LineContent(row)
		lines = append(lines, line)
		if line != "" {
			lastNonEmpty = row
		}
	}

	if lastNonEmpty < 0 {
		return ""
	}

	result := ""
	for i, line := range lines[:lastNonEmpty+1] {
		if i > 0 {
			result += "\n"
		}
		result += line
	}

	return result
}

// Search finds all occurrences of pattern in the visible screen content.
// Returns positions of the first character of each match.
func (t *Terminal) Search(pattern string) []Position {
	t.mu.RLock()
	defer t.mu.RUnlock()

	if pattern == "" {
		return nil
	}

	var matches []Position
	patternRunes := []rune(pattern)

	for row := 0; row < t.rows; row++ {
		line := t.activeBuffer.LineContent(row)
		lineRunes := []rune(line)

		for col := 0; col <= len(lineRunes)-len(patternRunes); col++ {
			found := true
			for i, pr := range patternRunes {
				if lineRunes[col+i] != pr {
					found = false
					break
				}
			}
			if found {
				matches = append(matches, Position{Row: row, Col: col})
			}
		}
	}

	return matches
}

// SearchScrollback finds all occurrences of pattern in scrollback lines.
// Returned row values are negative, where -1 is the most recent scrollback line.
func (t *Terminal) SearchScrollback(pattern string) []Position {
	t.mu.RLock()
	defer t.mu.RUnlock()

	if pattern == "" {
		return nil
	}

	var matches []Position
	scrollbackLen := t.primaryBuffer.ScrollbackLen()

	for i := 0; i < scrollbackLen; i++ {
		line := t.primaryBuffer.ScrollbackLine(i)
		if line.Cells == nil {
			continue
		}

		// Convert line to string.
		var lineText strings.Builder
		for _, cell := range line.Cells {
			if cell.IsWideSpacer() {
				continue
			}
			if cell.Char == "" {
				lineText.WriteRune(' ')
			} else {
				lineText.WriteString(cell.Char)
			}
		}

		lineStr := lineText.String()
		for idx := strings.Index(lineStr, pattern); idx != -1; idx = strings.Index(lineStr[idx+1:], pattern) {
			// Convert byte offset to rune index to match historical column semantics.
			col := utf8.RuneCountInString(lineStr[:idx])
			// Negative row indicates scrollback (0 is oldest)
			matches = append(matches, Position{Row: -(scrollbackLen - i), Col: col})
		}
	}

	return matches
}

// IsAlternateScreen returns true if the alternate buffer is currently active.
// The alternate buffer has no scrollback and is typically used by full-screen applications.
func (t *Terminal) IsAlternateScreen() bool {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.activeBuffer == t.alternateBuffer
}

// ScrollRegion returns the current scrolling boundaries (0-based, exclusive bottom).
// When origin mode is enabled, cursor positioning is relative to scrollTop.
func (t *Terminal) ScrollRegion() (top, bottom int) {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.scrollTop, t.scrollBottom
}

// --- Wrapped Line Tracking ---

// IsWrapped returns true if the line was wrapped due to column overflow, false if it ended with an explicit newline.
// Row is viewport-relative (0 to Rows()-1), not absolute.
func (t *Terminal) IsWrapped(row int) bool {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.activeBuffer.IsWrapped(row)
}

// SetWrapped sets whether the line was wrapped or ended with an explicit newline.
func (t *Terminal) SetWrapped(row int, wrapped bool) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.activeBuffer.SetWrapped(row, wrapped)
}

// AutoResize returns true if growth mode is enabled (buffer expands instead of scrolling/wrapping).
func (t *Terminal) AutoResize() bool {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.autoResize
}

// --- Recording Methods ---

// SetRecordingProvider replaces the recording handler at runtime.
func (t *Terminal) SetRecordingProvider(p RecordingProvider) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.recordingProvider = p
}

// RecordingProvider returns the current recording handler.
func (t *Terminal) RecordingProvider() RecordingProvider {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.recordingProvider
}

// RecordedData returns all raw input bytes captured since the last ClearRecording call.
func (t *Terminal) RecordedData() []byte {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.recordingProvider.Data()
}

// ClearRecording discards all captured input data.
func (t *Terminal) ClearRecording() {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.recordingProvider.Clear()
}

// --- Image Methods ---

// Image returns the image data for the given ID, or nil if not found.
func (t *Terminal) Image(id uint32) *ImageData {
	return t.images.Image(id)
}

// ImagePlacements returns all current image placements.
func (t *Terminal) ImagePlacements() []*ImagePlacement {
	return t.images.Placements()
}

// ImageCount returns the number of stored images.
func (t *Terminal) ImageCount() int {
	return t.images.ImageCount()
}

// ImagePlacementCount returns the number of active image placements.
func (t *Terminal) ImagePlacementCount() int {
	return t.images.PlacementCount()
}

// ImageUsedMemory returns the current image memory usage in bytes.
func (t *Terminal) ImageUsedMemory() int64 {
	return t.images.UsedMemory()
}

// SetImageMaxMemory sets the maximum memory budget for images.
func (t *Terminal) SetImageMaxMemory(bytes int64) {
	t.images.SetMaxMemory(bytes)
}

// ClearImages removes all images and placements.
func (t *Terminal) ClearImages() {
	t.images.Clear()
}

// SetSizeProvider sets the provider for pixel dimension queries.
func (t *Terminal) SetSizeProvider(p SizeProvider) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.sizeProvider = p
}
