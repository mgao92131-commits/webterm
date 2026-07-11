package terminalengine

// BufferKind 表示当前活动 buffer。
type BufferKind int

const (
	BufferMain BufferKind = iota
	BufferAlternate
)

// ColorKind 表示颜色语义。
type ColorKind string

const (
	ColorDefaultFG ColorKind = "default-fg"
	ColorDefaultBG ColorKind = "default-bg"
	ColorCursor    ColorKind = "cursor"
	ColorIndexed   ColorKind = "indexed"
	ColorRGB       ColorKind = "rgb"
)

// Color 保留语义类型，不只输出已解析 RGB。
type Color struct {
	Kind  ColorKind
	Index int
	RGB   uint32
}

// CellAttrs 对应 SGR 属性。
type CellAttrs struct {
	Bold            bool
	Dim             bool
	Italic          bool
	Underline       bool
	DoubleUnderline bool
	CurlyUnderline  bool
	DottedUnderline bool
	DashedUnderline bool
	BlinkSlow       bool
	BlinkFast       bool
	Reverse         bool
	Hidden          bool
	Strike          bool
}

// Cell 是传输无关的屏幕单元格。style/link 通过 ID 引用字典。
type Cell struct {
	Text    string
	Width   uint8
	StyleID uint32
	LinkID  uint32
}

// Hyperlink 对应 OSC 8。
type Hyperlink struct {
	ID  uint32
	URI string
}

// CellRun 是一行中从某列开始的连续 Cell。
type CellRun struct {
	Col   int
	Cells []Cell
}

// Line 是屏幕或历史的一行内容。
type Line struct {
	ID      uint64 // 历史行使用；活动屏幕行为 0
	Row     int    // 活动屏幕行索引；历史行使用 -1
	Wrapped bool
	Runs    []CellRun
}

// Cursor 是光标状态。
type Cursor struct {
	Row     int
	Col     int
	Visible bool
	Shape   CursorShape
	Blink   bool
}

// CursorShape 是光标形状。
type CursorShape int

const (
	CursorBlock CursorShape = iota
	CursorBar
	CursorUnderline
)

// Modes 是终端模式。
type Modes struct {
	ApplicationCursor bool
	ApplicationKeypad bool
	BracketedPaste    bool
	MouseTracking     MouseTracking
	MouseEncoding     MouseEncoding
	FocusReporting    bool
}

// MouseTracking 是鼠标追踪模式。
type MouseTracking int

const (
	MouseNone MouseTracking = iota
	MouseX10
	MouseVT200
	MouseVT200Highlight
	MouseButtonEvent
	MouseAnyEvent
	MouseSGRPixels
)

// MouseEncoding 是鼠标编码。
type MouseEncoding int

const (
	MouseEncodingX10 MouseEncoding = iota
	MouseEncodingUTF8
	MouseEncodingSGR
	MouseEncodingURXVT
)

// HistoryWindow 是快照附带的历史窗口。
type HistoryWindow struct {
	FirstAvailableLineID uint64
	FirstIncludedLineID  uint64
	LastIncludedLineID   uint64
	HasMoreBefore        bool
	Lines                []Line
}

// HistoryPageData 是按需历史页及其所依赖的字典。
type HistoryPageData struct {
	Window HistoryWindow
	Styles []TerminalStyle
	Links  []Hyperlink
}

// ScreenFrame 是传输无关的权威屏幕帧，也可作为 patch 的载体。
// BaseRevision == 0 表示完整 snapshot；否则为 patch。
type ScreenFrame struct {
	Version      int
	SessionID    string
	InstanceID   string
	Epoch        uint64
	Seq          uint64
	BaseRevision uint64 // patch 使用，snapshot 为 0
	Rows         int
	Cols         int
	ActiveBuffer BufferKind
	ReverseVideo bool
	DefaultFG    Color
	DefaultBG    Color
	CursorColor  Color
	Cursor       Cursor
	Modes        Modes
	History      HistoryWindow
	Screen       []Line
	Styles       []TerminalStyle
	Links        []Hyperlink
	Title        string
	WorkingDir   string
	PromotedRows []PromotedRow
}

// PromotedRow 表示活动行滚入历史时的 ID 映射。
type PromotedRow struct {
	ScreenRow     int
	HistoryLineID uint64
}

type EffectKind uint8

const (
	EffectBell EffectKind = iota
	EffectTitle
	EffectWorkingDirectory
	EffectClipboardRead
	EffectClipboardWrite
)

// Effect 是不属于屏幕网格的终端副作用。
type Effect struct {
	Kind      EffectKind
	Text      string
	RequestID string
	Clipboard string
	Data      []byte
}

// TerminalStyle 是导出 style 字典项。
type TerminalStyle struct {
	ID      uint32
	FG      Color
	BG      Color
	ULColor Color
	Attrs   CellAttrs
}

// StyleTable 维护 style ID 到 style 的映射。
type StyleTable struct {
	styles []TerminalStyle
	index  map[styleKey]uint32
}

type styleKey struct {
	fg    colorKey
	bg    colorKey
	ul    colorKey
	attrs CellAttrs
}

type colorKey struct {
	kind  ColorKind
	index int
	rgb   uint32
}

// NewStyleTable 创建 style 字典。
func NewStyleTable(defaultFG, defaultBG Color) *StyleTable {
	t := &StyleTable{index: make(map[styleKey]uint32)}
	t.initDefault(defaultFG, defaultBG)
	return t
}

func (t *StyleTable) initDefault(defaultFG, defaultBG Color) {
	t.index[styleKey{fg: colorKeyOf(defaultFG), bg: colorKeyOf(defaultBG), ul: colorKeyOf(Color{Kind: ColorDefaultFG})}] = 0
}

// Lookup 查找或创建 style ID。
func (t *StyleTable) Lookup(fg, bg, ul Color, attrs CellAttrs) uint32 {
	key := styleKey{
		fg:    colorKeyOf(fg),
		bg:    colorKeyOf(bg),
		ul:    colorKeyOf(ul),
		attrs: attrs,
	}
	if id, ok := t.index[key]; ok {
		return id
	}
	id := uint32(len(t.styles) + 1)
	t.styles = append(t.styles, TerminalStyle{
		ID:      id,
		FG:      fg,
		BG:      bg,
		ULColor: ul,
		Attrs:   attrs,
	})
	t.index[key] = id
	return id
}

// Styles 返回当前字典内容（不含 ID 0 默认）。
func (t *StyleTable) Styles() []TerminalStyle {
	return t.styles
}

// LinkTable 维护 hyperlink ID 到 URI 的映射。
type LinkTable struct {
	links []Hyperlink
	index map[string]uint32
}

// NewLinkTable 创建 link 字典。
func NewLinkTable() *LinkTable {
	return &LinkTable{index: make(map[string]uint32)}
}

// Lookup 查找或创建 link ID。
func (t *LinkTable) Lookup(uri string) uint32 {
	if uri == "" {
		return 0
	}
	if id, ok := t.index[uri]; ok {
		return id
	}
	id := uint32(len(t.links) + 1)
	t.links = append(t.links, Hyperlink{ID: id, URI: uri})
	t.index[uri] = id
	return id
}

// Links 返回当前字典内容。
func (t *LinkTable) Links() []Hyperlink {
	return t.links
}

func colorKeyOf(c Color) colorKey {
	return colorKey{kind: c.Kind, index: c.Index, rgb: c.RGB}
}
