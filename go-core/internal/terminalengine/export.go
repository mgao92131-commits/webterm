package terminalengine

// BufferKind 表示当前活动 buffer。
type BufferKind int

const (
	BufferMain BufferKind = iota
	BufferAlternate
)

// FrameKind 显式标识屏幕帧类型。编码器只依据它区分 snapshot/patch，
// 不再使用 BaseRevision == 0 的惯例；零值表示未设置，编码时必须报错。
type FrameKind uint8

const (
	FrameSnapshot FrameKind = iota + 1
	FramePatch
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
	ID      uint64 // session 内稳定行 ID；屏幕与历史共用同一命名空间
	Version uint64 // 行内容版本；行移动不改变，内容/宽度变化递增
	// PhysicalColumns 是该正文产生时的物理列数。历史行 resize 后仍按原列宽解码。
	PhysicalColumns int
	// HistorySeq is non-zero only while this Line is represented in scrollback.
	// It orders history entrance and pagination independently from the stable
	// logical Line ID, which is allowed to move in non-monotonic order.
	HistorySeq uint64
	Row        int // 仅 Projector 内部缓存的当前位置，永不参与 wire 语义
	Wrapped    bool
	Runs       []CellRun
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
	FirstAvailableHistorySeq uint64
	FirstIncludedHistorySeq  uint64
	LastIncludedHistorySeq   uint64
	HasMoreBefore            bool
	Lines                    []Line
}

type HistoryRangeData struct {
	Status            HistoryRangeStatus
	InstanceID        string
	LayoutEpoch       uint64
	Extent            HistoryExtent
	Lines             []Line
	Styles            []TerminalStyle
	Links             []Hyperlink
	RetryAfterMS      uint32
	HistoryGeneration uint64
}

// ScreenScroll 是 Commit 内可选的全屏连续滚动压缩描述。
type ScreenScroll struct {
	TopRow             int
	BottomRowExclusive int
	DeltaRows          int
}

type ScreenRowWrite struct {
	Row  int
	Line Line
}

// ScreenFrame 是传输无关的权威屏幕帧，也可作为 patch 的载体。
// Kind 显式区分 snapshot 与 patch；BaseRevision 只表达 patch 基线，
// snapshot 的 base 不参与语义。
type ScreenFrame struct {
	Version           int
	Kind              FrameKind
	SessionID         string
	InstanceID        string
	Epoch             uint64
	Seq               uint64
	BaseRevision      uint64 // patch 使用，snapshot 为 0
	Rows              int
	Cols              int
	ActiveBuffer      BufferKind
	ReverseVideo      bool
	DefaultFG         Color
	DefaultBG         Color
	CursorColor       Color
	IndexedPalette    [256]uint32
	IndexedPaletteSet [4]uint64
	PaletteGeneration uint64
	Cursor            Cursor
	Modes             Modes
	// CursorChanged/ModesChanged/PaletteChanged are patch-only presence flags.
	// Snapshots always carry all three components so they remain independently usable.
	CursorChanged       bool
	ModesChanged        bool
	PaletteChanged      bool
	ActiveBufferChanged bool
	History             HistoryWindow
	Screen              []Line
	// ScreenScroll 是 patch/commit 的可选全屏滚动描述；Screen 中的行按 Row 写入。
	ScreenScroll *ScreenScroll
	// Layout is patch-only presence data. Snapshot layout is always derived from
	// Screen, while a patch omits it when line positions did not change.
	Layout []uint64
	Styles []TerminalStyle
	Links  []Hyperlink
	// FirstAvailableHistorySeqChanged 表示 Commit 必须携带最终历史 extent。
	FirstAvailableHistorySeqChanged bool
	// RowChangedRevision is process-local projection metadata. It stamps each screen row with
	// the last authoritative export revision that touched it, allowing per-client derivation to
	// select changed rows without deep-comparing every cell. It is never encoded on the wire.
	RowChangedRevision []uint64
	// ForceSnapshot is process-local projection metadata. It is never encoded;
	// it tells a per-client sender that a style/link dictionary rotation made
	// its old baseline invalid even though terminal geometry did not change.
	ForceSnapshot bool
	// DictionaryGeneration is process-local projection metadata. It is never
	// encoded; unlike ForceSnapshot (a single-frame hint a mailbox can drop),
	// it travels on every state after a style/link dictionary rebuild, so a
	// FrameDeriver whose baseline predates the rebuild must emit a full
	// snapshot instead of a patch referencing dictionary IDs the client never
	// received.
	DictionaryGeneration uint64
	// HistoryGeneration 标识 historySeq -> LineID lineage；同一 generation 内允许
	// 追加、trim 以及 Resize Pop 后的尾部位置重绑定。
	HistoryGeneration uint64
	// HistoryPushes 是 HistorySeq -> LineID + LineVersion 的位置绑定，不含正文。
	HistoryPushes []HistoryPush
	// ScrollbackLineage 是完整的权威位置索引，只供派生器计算 Push，不编码到 wire。
	ScrollbackLineage []HistoryPush
	// HistoryLineageVersion 是进程内 mutation 标记。相同非零版本表示 lineage
	// 切片完全相同，派生器可跳过完整历史比较；不编码到 wire。
	HistoryLineageVersion uint64
	// HistoryMutationHead 是进程内持久 mutation 链。每个节点覆盖
	// (BaseVersion, Version]，让慢客户端按最后成功写出的 lineage version
	// 读取变化；链不编码到 wire，覆盖不足时派生器安全退回 snapshot。
	HistoryMutationHead *HistoryMutationBatch
	// HistoryLineageView 是不可变分页位置视图。普通实时 State 只共享该视图；
	// 仅在真正编码 Baseline 时才物化连续 ScrollbackLineage。
	HistoryLineageView *HistoryLineageView
}

type HistoryPush struct {
	LineID      uint64
	LineVersion uint64
	HistorySeq  uint64
}

type HistoryMutationBatch struct {
	BaseVersion uint64
	Version     uint64
	Pushes      []HistoryPush
	Previous    *HistoryMutationBatch
}

const historyLineagePageSize = uint64(128)

type HistoryLineageView struct {
	firstSeq uint64
	lastSeq  uint64
	basePage uint64
	pages    [][]HistoryPush
}

func NewHistoryLineageView(
	firstSeq, lastSeq uint64, bindings []HistoryPush,
) *HistoryLineageView {
	view := &HistoryLineageView{firstSeq: firstSeq, lastSeq: lastSeq}
	return view.WithChanges(firstSeq, lastSeq, bindings)
}

func (v *HistoryLineageView) WithChanges(
	firstSeq, lastSeq uint64, changes []HistoryPush,
) *HistoryLineageView {
	next := &HistoryLineageView{firstSeq: firstSeq, lastSeq: lastSeq}
	if lastSeq+1 != firstSeq {
		next.basePage = (firstSeq - 1) / historyLineagePageSize
		lastPage := (lastSeq - 1) / historyLineagePageSize
		next.pages = make([][]HistoryPush, int(lastPage-next.basePage+1))
		if v != nil {
			for index := range next.pages {
				page := next.basePage + uint64(index)
				if page >= v.basePage && page-v.basePage < uint64(len(v.pages)) {
					next.pages[index] = v.pages[page-v.basePage]
				}
			}
		}
	}
	copiedPages := make([]bool, len(next.pages))
	for _, change := range changes {
		if change.HistorySeq < firstSeq || change.HistorySeq > lastSeq {
			continue
		}
		page := (change.HistorySeq - 1) / historyLineagePageSize
		pageIndex := int(page - next.basePage)
		if !copiedPages[pageIndex] {
			entries := make([]HistoryPush, historyLineagePageSize)
			copy(entries, next.pages[pageIndex])
			next.pages[pageIndex] = entries
			copiedPages[pageIndex] = true
		}
		next.pages[pageIndex][(change.HistorySeq-1)%historyLineagePageSize] = change
	}
	return next
}

func (v *HistoryLineageView) Materialize() []HistoryPush {
	if v == nil || v.lastSeq+1 == v.firstSeq {
		return nil
	}
	result := make([]HistoryPush, 0, v.lastSeq-v.firstSeq+1)
	for _, page := range v.pages {
		for _, binding := range page {
			if binding.HistorySeq >= v.firstSeq && binding.HistorySeq <= v.lastSeq {
				result = append(result, binding)
			}
		}
	}
	return result
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
