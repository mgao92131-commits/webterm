package screenprojection

import (
	"image/color"

	headlessterm "github.com/danielgatis/go-headless-term"
	"webterm/go-core/internal/terminalengine"
)

// exporter 把 headless-term 状态导出为传输无关 ScreenFrame。
type exporter struct {
	styleTable *terminalengine.StyleTable
	linkTable  *terminalengine.LinkTable
}

func newExporter(defaultFG, defaultBG terminalengine.Color) *exporter {
	return &exporter{
		styleTable: terminalengine.NewStyleTable(defaultFG, defaultBG),
		linkTable:  terminalengine.NewLinkTable(),
	}
}

// ExportSnapshot 导出一个独立完整快照。连续投影应使用 Projector，以保持字典 ID 稳定。
func ExportSnapshot(engine *terminalengine.Engine, scrollback *terminalengine.TrackedScrollback, sessionID, instanceID string, epoch, seq uint64) terminalengine.ScreenFrame {
	exp := newExporter(
		terminalengine.Color{Kind: terminalengine.ColorDefaultFG},
		terminalengine.Color{Kind: terminalengine.ColorDefaultBG},
	)
	return exp.exportSnapshot(engine, scrollback, sessionID, instanceID, epoch, seq)
}

// ExportSnapshot 从 Engine 导出完整 ScreenFrame。
func (exp *exporter) exportSnapshot(engine *terminalengine.Engine, scrollback *terminalengine.TrackedScrollback, sessionID, instanceID string, epoch, seq uint64) terminalengine.ScreenFrame {
	rows := engine.Rows()
	cols := engine.Cols()
	cursorRow, cursorCol := engine.CursorPos()

	activeBuffer := terminalengine.BufferMain
	if engine.IsAlternateScreen() {
		activeBuffer = terminalengine.BufferAlternate
	}

	screen := make([]terminalengine.Line, rows)
	for r := 0; r < rows; r++ {
		screen[r] = exp.exportScreenRow(engine, r, cols, cursorRow, cursorCol)
	}
	cursorStyle := engine.CursorStyle()

	history := terminalengine.HistoryWindow{}
	// 备用屏是完整 TUI 的当前画面，绝不能混入主屏 scrollback。
	// 切屏会触发 snapshot，客户端据此清空旧历史并只渲染该屏内容。
	if activeBuffer == terminalengine.BufferMain {
		history = exp.exportHistoryWindow(scrollback)
	}

	return terminalengine.ScreenFrame{
		Version:      1,
		Kind:         terminalengine.FrameSnapshot,
		SessionID:    sessionID,
		InstanceID:   instanceID,
		Epoch:        epoch,
		Seq:          seq,
		Rows:         rows,
		Cols:         cols,
		ActiveBuffer: activeBuffer,
		ReverseVideo: false,
		DefaultFG:    terminalengine.Color{Kind: terminalengine.ColorDefaultFG},
		DefaultBG:    terminalengine.Color{Kind: terminalengine.ColorDefaultBG},
		CursorColor:  terminalengine.Color{Kind: terminalengine.ColorCursor},
		Cursor: terminalengine.Cursor{
			Row:     cursorRow,
			Col:     cursorCol,
			Visible: engine.CursorVisible(),
			Shape:   exportCursorShape(cursorStyle),
			Blink:   cursorStyle == headlessterm.CursorStyleBlinkingBlock || cursorStyle == headlessterm.CursorStyleBlinkingBar || cursorStyle == headlessterm.CursorStyleBlinkingUnderline,
		},
		Modes:      exportModes(engine),
		History:    history,
		Screen:     screen,
		Styles:     exp.styleTable.Styles(),
		Links:      exp.linkTable.Links(),
		Title:      engine.Title(),
		WorkingDir: engine.WorkingDirectory(),
	}
}

func (exp *exporter) exportScreenRow(engine *terminalengine.Engine, row, cols, cursorRow, cursorCol int) terminalengine.Line {
	// 逐格读取只保留给独立 ExportSnapshot 路径（fixture 生成、会话初始
	// 快照）；连续投影走 Projector 的投影缓存（exportProjectionRow）。
	// engine.Cell 仅在越界时返回 nil，这里坐标恒在界内，nil 分支不可达。
	cells := make([]headlessterm.Cell, 0, cols)
	for c := 0; c < cols; c++ {
		if cell := engine.Cell(row, c); cell != nil {
			cells = append(cells, *cell)
		}
	}
	return terminalengine.Line{
		Row:     row,
		Wrapped: engine.IsWrapped(row),
		Runs:    exp.exportCells(cells, row, cursorRow, cursorCol),
	}
}

// exportProjectionRow 把投影中的一行（已是不可变拷贝）转换为导出 Line。
func (exp *exporter) exportProjectionRow(row headlessterm.ProjectionRow, cursorRow, cursorCol int) terminalengine.Line {
	return terminalengine.Line{
		Row:     row.Index,
		Wrapped: row.Wrapped,
		Runs:    exp.exportCells(row.Cells, row.Index, cursorRow, cursorCol),
	}
}

// exportCells 把一行 cell 拷贝转换为 run 列表。cells 必须是不可变拷贝
// （投影行或逐格收集的副本）；软光标处理与逐格读取时代完全一致。
func (exp *exporter) exportCells(cells []headlessterm.Cell, row, cursorRow, cursorCol int) []terminalengine.CellRun {
	var runs []terminalengine.CellRun
	var current *terminalengine.CellRun

	for c := 0; c < len(cells); {
		cell := cells[c]
		// Claude Code paints its own software caret as a reverse-video space.
		// Some redraw paths leave an old, isolated caret behind. The terminal's
		// authoritative cursor position identifies the one live caret; exporting
		// any other plain reverse-space would turn it into a permanent ghost block
		// on remote clients.
		if staleSoftCursor(cell, row, c, cursorRow, cursorCol) {
			c++
			continue
		}

		exported := exp.exportCell(cell)
		if exported.Width == 0 {
			// spacer cell：跳过，不绘制。
			c++
			continue
		}

		if current != nil && cellsSameStyle(current.Cells[len(current.Cells)-1], exported) {
			current.Cells = append(current.Cells, exported)
		} else {
			run := terminalengine.CellRun{Col: c, Cells: []terminalengine.Cell{exported}}
			runs = append(runs, run)
			current = &runs[len(runs)-1]
		}
		c += int(exported.Width)
	}

	return runs
}

func staleSoftCursor(cell headlessterm.Cell, row, col, cursorRow, cursorCol int) bool {
	if row == cursorRow && col == cursorCol {
		return false
	}
	if cell.Char != " " || !cell.HasFlag(headlessterm.CellFlagReverse) || cell.Hyperlink != nil || cell.Image != nil {
		return false
	}
	const nonCursorFlags = ^headlessterm.CellFlagReverse
	return cell.Flags&nonCursorFlags == 0
}

func (exp *exporter) exportCell(cell headlessterm.Cell) terminalengine.Cell {
	attrs := terminalengine.CellAttrs{
		Bold:            cell.HasFlag(headlessterm.CellFlagBold),
		Dim:             cell.HasFlag(headlessterm.CellFlagDim),
		Italic:          cell.HasFlag(headlessterm.CellFlagItalic),
		Underline:       cell.HasFlag(headlessterm.CellFlagUnderline),
		DoubleUnderline: cell.HasFlag(headlessterm.CellFlagDoubleUnderline),
		CurlyUnderline:  cell.HasFlag(headlessterm.CellFlagCurlyUnderline),
		DottedUnderline: cell.HasFlag(headlessterm.CellFlagDottedUnderline),
		DashedUnderline: cell.HasFlag(headlessterm.CellFlagDashedUnderline),
		BlinkSlow:       cell.HasFlag(headlessterm.CellFlagBlinkSlow),
		BlinkFast:       cell.HasFlag(headlessterm.CellFlagBlinkFast),
		Reverse:         cell.HasFlag(headlessterm.CellFlagReverse),
		Hidden:          cell.HasFlag(headlessterm.CellFlagHidden),
		Strike:          cell.HasFlag(headlessterm.CellFlagStrike),
	}

	fg := cellColor(cell.Fg)
	bg := cellColor(cell.Bg)
	ul := cellColor(cell.UnderlineColor)
	styleID := exp.styleTable.Lookup(fg, bg, ul, attrs)

	width := uint8(1)
	if cell.HasFlag(headlessterm.CellFlagWideChar) {
		width = 2
	} else if cell.HasFlag(headlessterm.CellFlagWideCharSpacer) {
		width = 0
	}

	linkID := uint32(0)
	if cell.Hyperlink != nil {
		linkID = exp.linkTable.Lookup(cell.Hyperlink.URI)
	}

	text := cell.Char
	if text == "" {
		text = " "
	}

	return terminalengine.Cell{
		Text:    text,
		Width:   width,
		StyleID: styleID,
		LinkID:  linkID,
	}
}

func cellsSameStyle(a, b terminalengine.Cell) bool {
	return a.StyleID == b.StyleID && a.LinkID == b.LinkID
}

// snapshotTailLines 是快照附带的历史窗口行数上限。
const snapshotTailLines = 300

// exportHistoryWindow 全量导出尾部历史窗口，用于独立快照与历史缓存重建路径。
func (exp *exporter) exportHistoryWindow(scrollback *terminalengine.TrackedScrollback) terminalengine.HistoryWindow {
	if scrollback == nil {
		return terminalengine.HistoryWindow{}
	}

	w := scrollback.Window(snapshotTailLines)
	if len(w.Lines) == 0 {
		return terminalengine.HistoryWindow{
			FirstAvailableLineID: w.FirstID,
			FirstIncludedLineID:  w.FirstID,
			LastIncludedLineID:   w.FirstID - 1,
			HasMoreBefore:        false,
			Lines:                nil,
		}
	}

	lines := exportHistoryLines(exp, w.Lines)
	return historyWindowFromLines(lines, w.FirstID)
}

// exportHistoryLines 把不可变历史行批量转换为导出 Line。
func exportHistoryLines(exp *exporter, lines []terminalengine.HistoryLine) []terminalengine.Line {
	if len(lines) == 0 {
		return nil
	}
	out := make([]terminalengine.Line, len(lines))
	for i, hl := range lines {
		out[i] = exp.exportHistoryLine(hl)
	}
	return out
}

func (exp *exporter) exportHistoryLine(hl terminalengine.HistoryLine) terminalengine.Line {
	return terminalengine.Line{
		ID:      hl.ID,
		Row:     -1,
		Wrapped: hl.Wrapped,
		Runs:    exp.exportHistoryCells(hl.Cells),
	}
}

// historyWindowFromLines 由连续 ID 的导出窗口行与 firstAvailable 组装窗口边界。
func historyWindowFromLines(lines []terminalengine.Line, firstAvailable uint64) terminalengine.HistoryWindow {
	if len(lines) == 0 {
		return terminalengine.HistoryWindow{
			FirstAvailableLineID: firstAvailable,
			FirstIncludedLineID:  firstAvailable,
			LastIncludedLineID:   firstAvailable - 1,
			HasMoreBefore:        false,
			Lines:                nil,
		}
	}
	return terminalengine.HistoryWindow{
		FirstAvailableLineID: firstAvailable,
		FirstIncludedLineID:  lines[0].ID,
		LastIncludedLineID:   lines[len(lines)-1].ID,
		HasMoreBefore:        lines[0].ID > firstAvailable,
		Lines:                lines,
	}
}

func (exp *exporter) exportHistoryCells(cells []headlessterm.Cell) []terminalengine.CellRun {
	var runs []terminalengine.CellRun
	var current *terminalengine.CellRun

	for c := 0; c < len(cells); {
		cell := cells[c]
		exported := exp.exportCell(cell)
		if exported.Width == 0 {
			c++
			continue
		}

		if current != nil && cellsSameStyle(current.Cells[len(current.Cells)-1], exported) {
			current.Cells = append(current.Cells, exported)
		} else {
			run := terminalengine.CellRun{Col: c, Cells: []terminalengine.Cell{exported}}
			runs = append(runs, run)
			current = &runs[len(runs)-1]
		}
		c += int(exported.Width)
	}

	return runs
}

func exportCursorShape(s headlessterm.CursorStyle) terminalengine.CursorShape {
	switch s {
	case headlessterm.CursorStyleBlinkingBar, headlessterm.CursorStyleSteadyBar:
		return terminalengine.CursorBar
	case headlessterm.CursorStyleBlinkingUnderline, headlessterm.CursorStyleSteadyUnderline:
		return terminalengine.CursorUnderline
	default:
		return terminalengine.CursorBlock
	}
}

// exportProjectionCursor 把投影光标快照转换为导出光标。与 exportSnapshot
// 中逐字段读取的结果一致。
func exportProjectionCursor(c headlessterm.ProjectionCursor) terminalengine.Cursor {
	return terminalengine.Cursor{
		Row:     c.Row,
		Col:     c.Col,
		Visible: c.Visible,
		Shape:   exportCursorShape(c.Style),
		Blink:   c.Style == headlessterm.CursorStyleBlinkingBlock || c.Style == headlessterm.CursorStyleBlinkingBar || c.Style == headlessterm.CursorStyleBlinkingUnderline,
	}
}

// exportProjectionModes 从投影的模式位掩码构建导出 Modes，与 exportModes
// 逐项 HasMode 的结果一致。
func exportProjectionModes(m headlessterm.TerminalMode) terminalengine.Modes {
	has := func(mode headlessterm.TerminalMode) bool { return m&mode != 0 }

	tracking := terminalengine.MouseNone
	switch {
	case has(headlessterm.ModeReportAllMouseMotion):
		tracking = terminalengine.MouseAnyEvent
	case has(headlessterm.ModeReportCellMouseMotion):
		tracking = terminalengine.MouseVT200
	case has(headlessterm.ModeReportMouseClicks):
		tracking = terminalengine.MouseX10
	}

	encoding := terminalengine.MouseEncodingX10
	switch {
	case has(headlessterm.ModeSGRMouse):
		encoding = terminalengine.MouseEncodingSGR
	case has(headlessterm.ModeUTF8Mouse):
		encoding = terminalengine.MouseEncodingUTF8
	}

	return terminalengine.Modes{
		ApplicationCursor: has(headlessterm.ModeCursorKeys),
		ApplicationKeypad: has(headlessterm.ModeKeypadApplication),
		BracketedPaste:    has(headlessterm.ModeBracketedPaste),
		MouseTracking:     tracking,
		MouseEncoding:     encoding,
		FocusReporting:    has(headlessterm.ModeReportFocusInOut),
	}
}

func exportModes(engine *terminalengine.Engine) terminalengine.Modes {
	tracking := terminalengine.MouseNone
	switch {
	case engine.HasMode(headlessterm.ModeReportAllMouseMotion):
		tracking = terminalengine.MouseAnyEvent
	case engine.HasMode(headlessterm.ModeReportCellMouseMotion):
		tracking = terminalengine.MouseVT200
	case engine.HasMode(headlessterm.ModeReportMouseClicks):
		tracking = terminalengine.MouseX10
	}

	encoding := terminalengine.MouseEncodingX10
	switch {
	case engine.HasMode(headlessterm.ModeSGRMouse):
		encoding = terminalengine.MouseEncodingSGR
	case engine.HasMode(headlessterm.ModeUTF8Mouse):
		encoding = terminalengine.MouseEncodingUTF8
	}

	return terminalengine.Modes{
		ApplicationCursor: engine.HasMode(headlessterm.ModeCursorKeys),
		ApplicationKeypad: engine.HasMode(headlessterm.ModeKeypadApplication),
		BracketedPaste:    engine.HasMode(headlessterm.ModeBracketedPaste),
		MouseTracking:     tracking,
		MouseEncoding:     encoding,
		FocusReporting:    engine.HasMode(headlessterm.ModeReportFocusInOut),
	}
}

func cellColor(c color.Color) terminalengine.Color {
	if c == nil {
		return terminalengine.Color{Kind: terminalengine.ColorDefaultFG}
	}
	switch v := c.(type) {
	case *headlessterm.NamedColor:
		switch v.Name {
		case headlessterm.NamedColorForeground:
			return terminalengine.Color{Kind: terminalengine.ColorDefaultFG}
		case headlessterm.NamedColorBackground:
			return terminalengine.Color{Kind: terminalengine.ColorDefaultBG}
		case headlessterm.NamedColorCursor:
			return terminalengine.Color{Kind: terminalengine.ColorCursor}
		}
		return terminalengine.Color{Kind: terminalengine.ColorIndexed, Index: int(v.Name)}
	case *headlessterm.IndexedColor:
		return terminalengine.Color{Kind: terminalengine.ColorIndexed, Index: v.Index}
	default:
		r, g, b, _ := c.RGBA()
		return terminalengine.Color{Kind: terminalengine.ColorRGB, RGB: (uint32(r>>8) << 16) | (uint32(g>>8) << 8) | uint32(b>>8)}
	}
}
