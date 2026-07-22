package terminalcapture

import (
	"strconv"

	"webterm/go-core/internal/terminalengine"
)

// 本文件把传输无关的 ScreenFrame 及旁路记录转换为 schema 稳定的 JSON DTO。
// 字段含义明确、可由脚本读取；原始二进制（PTY/wire bytes）不进 JSON，单独保存，
// 索引中保存 offset/length/hash/revision。这些函数是纯转换，不持有 ring，
// 只在导出时（capture 专用 goroutine）调用，不在热路径执行。

// JSONScreenFrame 是权威帧/派生帧的稳定 JSON 表示。
type JSONScreenFrame struct {
	Kind                 string      `json:"kind"`
	SessionID            string      `json:"sessionId,omitempty"`
	InstanceID           string      `json:"instanceId,omitempty"`
	LayoutEpoch          uint64      `json:"layoutEpoch"`
	ScreenRevision       uint64      `json:"screenRevision"`
	BaseRevision         uint64      `json:"baseRevision,omitempty"`
	Rows                 int         `json:"rows"`
	Cols                 int         `json:"cols"`
	ActiveBuffer         string      `json:"activeBuffer"`
	Cursor               JSONCursor  `json:"cursor"`
	Modes                JSONModes   `json:"modes"`
	Palette              JSONPalette `json:"palette"`
	Layout               []uint64    `json:"layout,omitempty"`
	Screen               []JSONLine  `json:"screen"`
	History              JSONHistory `json:"history"`
	HistoryAppendSeqs    []uint64    `json:"historyAppendSeqs,omitempty"`
	Styles               []JSONStyle `json:"styles,omitempty"`
	Links                []JSONLink  `json:"links,omitempty"`
	Title                string      `json:"title,omitempty"`
	WorkingDirectory     string      `json:"workingDirectory,omitempty"`
	TitleChanged         bool        `json:"titleChanged,omitempty"`
	WorkingDirChanged    bool        `json:"workingDirectoryChanged,omitempty"`
	CursorChanged        bool        `json:"cursorChanged,omitempty"`
	ModesChanged         bool        `json:"modesChanged,omitempty"`
	PaletteChanged       bool        `json:"paletteChanged,omitempty"`
	DictionaryGeneration uint64      `json:"dictionaryGeneration,omitempty"`
}

// JSONHistory 是历史窗口的稳定 JSON 表示。
type JSONHistory struct {
	FirstAvailableHistorySeq uint64     `json:"firstAvailableHistorySeq"`
	FirstIncludedHistorySeq  uint64     `json:"firstIncludedHistorySeq"`
	LastIncludedHistorySeq   uint64     `json:"lastIncludedHistorySeq"`
	HasMoreBefore            bool       `json:"hasMoreBefore"`
	Lines                    []JSONLine `json:"lines,omitempty"`
}

// JSONLine 是一行的稳定 JSON 表示。
type JSONLine struct {
	LineID     uint64        `json:"lineId"`
	Version    uint64        `json:"version"`
	HistorySeq uint64        `json:"historySeq,omitempty"`
	Wrapped    bool          `json:"wrapped,omitempty"`
	Runs       []JSONCellRun `json:"runs,omitempty"`
}

// JSONCellRun 是连续 cell 段。
type JSONCellRun struct {
	Col   int        `json:"col"`
	Cells []JSONCell `json:"cells"`
}

// JSONCell 是单元格。
type JSONCell struct {
	Text    string `json:"text"`
	Width   int    `json:"width"`
	StyleID uint32 `json:"styleId,omitempty"`
	LinkID  uint32 `json:"linkId,omitempty"`
}

// JSONCursor 是光标。
type JSONCursor struct {
	Row     int    `json:"row"`
	Col     int    `json:"col"`
	Visible bool   `json:"visible"`
	Shape   string `json:"shape"`
	Blink   bool   `json:"blink,omitempty"`
}

// JSONModes 是终端模式。
type JSONModes struct {
	ApplicationCursor bool   `json:"applicationCursor,omitempty"`
	ApplicationKeypad bool   `json:"applicationKeypad,omitempty"`
	BracketedPaste    bool   `json:"bracketedPaste,omitempty"`
	MouseTracking     string `json:"mouseTracking,omitempty"`
	MouseEncoding     string `json:"mouseEncoding,omitempty"`
	FocusReporting    bool   `json:"focusReporting,omitempty"`
}

// JSONPalette 是调色板；indexed 仅列出被设置的索引（避免 256 项十进制数组）。
type JSONPalette struct {
	ReverseVideo bool              `json:"reverseVideo,omitempty"`
	DefaultFG    JSONColor         `json:"defaultFg"`
	DefaultBG    JSONColor         `json:"defaultBg"`
	CursorColor  JSONColor         `json:"cursorColor"`
	Indexed      map[string]uint32 `json:"indexed,omitempty"`
	Generation   uint64            `json:"generation,omitempty"`
}

// JSONColor 是颜色。
type JSONColor struct {
	Kind  string `json:"kind"`
	Index int    `json:"index,omitempty"`
	RGB   uint32 `json:"rgb,omitempty"`
}

// JSONStyle 是 style 字典项。
type JSONStyle struct {
	ID      uint32        `json:"id"`
	FG      JSONColor     `json:"fg"`
	BG      JSONColor     `json:"bg"`
	ULColor JSONColor     `json:"ulColor,omitempty"`
	Attrs   JSONCellAttrs `json:"attrs"`
}

// JSONCellAttrs 是 SGR 属性。
type JSONCellAttrs struct {
	Bold            bool `json:"bold,omitempty"`
	Dim             bool `json:"dim,omitempty"`
	Italic          bool `json:"italic,omitempty"`
	Underline       bool `json:"underline,omitempty"`
	DoubleUnderline bool `json:"doubleUnderline,omitempty"`
	CurlyUnderline  bool `json:"curlyUnderline,omitempty"`
	DottedUnderline bool `json:"dottedUnderline,omitempty"`
	DashedUnderline bool `json:"dashedUnderline,omitempty"`
	BlinkSlow       bool `json:"blinkSlow,omitempty"`
	BlinkFast       bool `json:"blinkFast,omitempty"`
	Reverse         bool `json:"reverse,omitempty"`
	Hidden          bool `json:"hidden,omitempty"`
	Strike          bool `json:"strike,omitempty"`
}

// JSONLink 是 hyperlink 字典项。
type JSONLink struct {
	ID  uint32 `json:"id"`
	URI string `json:"uri"`
}

// FrameToJSON 把 ScreenFrame 转换为稳定 JSON DTO。
func FrameToJSON(frame terminalengine.ScreenFrame) JSONScreenFrame {
	layout := frame.Layout
	if len(layout) == 0 {
		// Snapshot 的 layout 由 Screen 派生；导出时显式给出便于脚本比对。
		layout = make([]uint64, len(frame.Screen))
		for i, line := range frame.Screen {
			layout[i] = line.ID
		}
	}
	return JSONScreenFrame{
		Kind:                 frameKindString(frame.Kind),
		SessionID:            frame.SessionID,
		InstanceID:           frame.InstanceID,
		LayoutEpoch:          frame.Epoch,
		ScreenRevision:       frame.Seq,
		BaseRevision:         frame.BaseRevision,
		Rows:                 frame.Rows,
		Cols:                 frame.Cols,
		ActiveBuffer:         bufferKindString(frame.ActiveBuffer),
		Cursor:               cursorToJSON(frame.Cursor),
		Modes:                modesToJSON(frame.Modes),
		Palette:              paletteToJSON(frame),
		Layout:               layout,
		Screen:               linesToJSON(frame.Screen),
		History:              historyToJSON(frame.History),
		HistoryAppendSeqs:    frame.HistoryAppendSeqs,
		Styles:               stylesToJSON(frame.Styles),
		Links:                linksToJSON(frame.Links),
		Title:                frame.Title,
		WorkingDirectory:     frame.WorkingDir,
		TitleChanged:         frame.TitleChanged,
		WorkingDirChanged:    frame.WorkingDirChanged,
		CursorChanged:        frame.CursorChanged,
		ModesChanged:         frame.ModesChanged,
		PaletteChanged:       frame.PaletteChanged,
		DictionaryGeneration: frame.DictionaryGeneration,
	}
}

func linesToJSON(lines []terminalengine.Line) []JSONLine {
	out := make([]JSONLine, len(lines))
	for i, line := range lines {
		runs := make([]JSONCellRun, len(line.Runs))
		for j, run := range line.Runs {
			cells := make([]JSONCell, len(run.Cells))
			for k, cell := range run.Cells {
				cells[k] = JSONCell{Text: cell.Text, Width: int(cell.Width), StyleID: cell.StyleID, LinkID: cell.LinkID}
			}
			runs[j] = JSONCellRun{Col: run.Col, Cells: cells}
		}
		out[i] = JSONLine{LineID: line.ID, Version: line.Version, HistorySeq: line.HistorySeq, Wrapped: line.Wrapped, Runs: runs}
	}
	return out
}

func historyToJSON(h terminalengine.HistoryWindow) JSONHistory {
	return JSONHistory{
		FirstAvailableHistorySeq: h.FirstAvailableHistorySeq,
		FirstIncludedHistorySeq:  h.FirstIncludedHistorySeq,
		LastIncludedHistorySeq:   h.LastIncludedHistorySeq,
		HasMoreBefore:            h.HasMoreBefore,
		Lines:                    linesToJSON(h.Lines),
	}
}

func stylesToJSON(styles []terminalengine.TerminalStyle) []JSONStyle {
	if len(styles) == 0 {
		return nil
	}
	out := make([]JSONStyle, len(styles))
	for i, s := range styles {
		out[i] = JSONStyle{ID: s.ID, FG: colorToJSON(s.FG), BG: colorToJSON(s.BG), ULColor: colorToJSON(s.ULColor), Attrs: attrsToJSON(s.Attrs)}
	}
	return out
}

func linksToJSON(links []terminalengine.Hyperlink) []JSONLink {
	if len(links) == 0 {
		return nil
	}
	out := make([]JSONLink, len(links))
	for i, l := range links {
		out[i] = JSONLink{ID: l.ID, URI: l.URI}
	}
	return out
}

func cursorToJSON(c terminalengine.Cursor) JSONCursor {
	return JSONCursor{Row: c.Row, Col: c.Col, Visible: c.Visible, Shape: cursorShapeString(c.Shape), Blink: c.Blink}
}

func modesToJSON(m terminalengine.Modes) JSONModes {
	return JSONModes{
		ApplicationCursor: m.ApplicationCursor,
		ApplicationKeypad: m.ApplicationKeypad,
		BracketedPaste:    m.BracketedPaste,
		MouseTracking:     mouseTrackingString(m.MouseTracking),
		MouseEncoding:     mouseEncodingString(m.MouseEncoding),
		FocusReporting:    m.FocusReporting,
	}
}

func paletteToJSON(frame terminalengine.ScreenFrame) JSONPalette {
	indexed := map[string]uint32{}
	for word := 0; word < 4; word++ {
		bits := frame.IndexedPaletteSet[word]
		for bit := 0; bit < 64; bit++ {
			if bits&(uint64(1)<<uint(bit)) != 0 {
				idx := word*64 + bit
				indexed[strconv.Itoa(idx)] = frame.IndexedPalette[idx]
			}
		}
	}
	var indexedOut map[string]uint32
	if len(indexed) > 0 {
		indexedOut = indexed
	}
	return JSONPalette{
		ReverseVideo: frame.ReverseVideo,
		DefaultFG:    colorToJSON(frame.DefaultFG),
		DefaultBG:    colorToJSON(frame.DefaultBG),
		CursorColor:  colorToJSON(frame.CursorColor),
		Indexed:      indexedOut,
		Generation:   frame.PaletteGeneration,
	}
}

func colorToJSON(c terminalengine.Color) JSONColor {
	return JSONColor{Kind: string(c.Kind), Index: c.Index, RGB: c.RGB}
}

func attrsToJSON(a terminalengine.CellAttrs) JSONCellAttrs {
	return JSONCellAttrs{
		Bold: a.Bold, Dim: a.Dim, Italic: a.Italic, Underline: a.Underline,
		DoubleUnderline: a.DoubleUnderline, CurlyUnderline: a.CurlyUnderline,
		DottedUnderline: a.DottedUnderline, DashedUnderline: a.DashedUnderline,
		BlinkSlow: a.BlinkSlow, BlinkFast: a.BlinkFast, Reverse: a.Reverse,
		Hidden: a.Hidden, Strike: a.Strike,
	}
}

func frameKindString(k terminalengine.FrameKind) string {
	switch k {
	case terminalengine.FrameSnapshot:
		return "snapshot"
	case terminalengine.FramePatch:
		return "patch"
	default:
		return "unknown"
	}
}

func bufferKindString(b terminalengine.BufferKind) string {
	if b == terminalengine.BufferAlternate {
		return "alternate"
	}
	return "main"
}

func cursorShapeString(s terminalengine.CursorShape) string {
	switch s {
	case terminalengine.CursorBar:
		return "bar"
	case terminalengine.CursorUnderline:
		return "underline"
	default:
		return "block"
	}
}

func mouseTrackingString(t terminalengine.MouseTracking) string {
	switch t {
	case terminalengine.MouseX10:
		return "x10"
	case terminalengine.MouseVT200:
		return "vt200"
	case terminalengine.MouseVT200Highlight:
		return "vt200_highlight"
	case terminalengine.MouseButtonEvent:
		return "button_event"
	case terminalengine.MouseAnyEvent:
		return "any_event"
	case terminalengine.MouseSGRPixels:
		return "sgr_pixels"
	default:
		return "none"
	}
}

func mouseEncodingString(e terminalengine.MouseEncoding) string {
	switch e {
	case terminalengine.MouseEncodingUTF8:
		return "utf8"
	case terminalengine.MouseEncodingSGR:
		return "sgr"
	case terminalengine.MouseEncodingURXVT:
		return "urxvt"
	default:
		return "x10"
	}
}
