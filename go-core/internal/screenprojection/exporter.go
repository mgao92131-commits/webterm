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
	return terminalengine.Line{
		Row:     row,
		Wrapped: engine.IsWrapped(row),
		Runs:    exp.exportCells(engine, row, cols, cursorRow, cursorCol),
	}
}

func (exp *exporter) exportCells(engine *terminalengine.Engine, row, cols, cursorRow, cursorCol int) []terminalengine.CellRun {
	var runs []terminalengine.CellRun
	var current *terminalengine.CellRun

	for c := 0; c < cols; {
		cell := engine.Cell(row, c)
		if cell == nil {
			c++
			continue
		}
		// Claude Code paints its own software caret as a reverse-video space.
		// Some redraw paths leave an old, isolated caret behind. The terminal's
		// authoritative cursor position identifies the one live caret; exporting
		// any other plain reverse-space would turn it into a permanent ghost block
		// on remote clients.
		if staleSoftCursor(*cell, row, c, cursorRow, cursorCol) {
			c++
			continue
		}

		exported := exp.exportCell(*cell)
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
	const nonCursorFlags = ^(headlessterm.CellFlagReverse | headlessterm.CellFlagDirty)
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

func (exp *exporter) exportHistoryWindow(scrollback *terminalengine.TrackedScrollback) terminalengine.HistoryWindow {
	if scrollback == nil {
		return terminalengine.HistoryWindow{}
	}

	const snapshotTailLines = 300
	total := scrollback.Len()
	firstAvailable := scrollback.FirstID()
	if total == 0 {
		return terminalengine.HistoryWindow{
			FirstAvailableLineID: firstAvailable,
			FirstIncludedLineID:  firstAvailable,
			LastIncludedLineID:   firstAvailable - 1,
			HasMoreBefore:        false,
			Lines:                nil,
		}
	}

	startIdx := 0
	if total > snapshotTailLines {
		startIdx = total - snapshotTailLines
	}

	lines := make([]terminalengine.Line, 0, total-startIdx)
	for i := startIdx; i < total; i++ {
		hl, ok := scrollback.LineByID(firstAvailable + uint64(i))
		if !ok {
			continue
		}
		lines = append(lines, terminalengine.Line{
			ID:      hl.ID,
			Row:     -1,
			Wrapped: hl.Wrapped,
			Runs:    exp.exportHistoryCells(hl.Cells),
		})
	}

	return terminalengine.HistoryWindow{
		FirstAvailableLineID: firstAvailable,
		FirstIncludedLineID:  firstAvailable + uint64(startIdx),
		LastIncludedLineID:   firstAvailable + uint64(total) - 1,
		HasMoreBefore:        startIdx > 0,
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
