package session

import (
	"fmt"
	"image/color"
	"io"
	"strings"
	"sync"

	headlessterm "github.com/danielgatis/go-headless-term"
)

const screenScrollbackLines = 10000

type ScreenDirtyCell struct {
	Row  int    `json:"row"`
	Col  int    `json:"col"`
	Char string `json:"char"`
}

type ScreenDelta struct {
	Seq   uint64            `json:"seq"`
	Cells []ScreenDirtyCell `json:"cells"`
}

type ScreenSnapshot = headlessterm.Snapshot

type ScreenState struct {
	mu       sync.Mutex
	terminal *headlessterm.Terminal
}

func NewScreenState(rows int, cols int, responses io.Writer, title headlessterm.TitleProvider) *ScreenState {
	if rows <= 0 {
		rows = DefaultRows
	}
	if cols <= 0 {
		cols = DefaultCols
	}

	options := []headlessterm.Option{
		headlessterm.WithSize(rows, cols),
		headlessterm.WithScrollback(headlessterm.NewMemoryScrollback(screenScrollbackLines)),
	}
	if responses != nil {
		options = append(options, headlessterm.WithPTYWriter(responses))
	}
	if title != nil {
		options = append(options, headlessterm.WithTitle(title))
	}

	return &ScreenState{terminal: headlessterm.New(options...)}
}

func (screen *ScreenState) Write(data []byte) error {
	screen.mu.Lock()
	defer screen.mu.Unlock()
	_, err := screen.terminal.Write(data)
	return err
}

func (screen *ScreenState) WriteAndWorkingDirectoryPath(data []byte) (string, error) {
	screen.mu.Lock()
	defer screen.mu.Unlock()
	_, err := screen.terminal.Write(data)
	return screen.terminal.WorkingDirectoryPath(), err
}

func (screen *ScreenState) WorkingDirectoryPath() string {
	screen.mu.Lock()
	defer screen.mu.Unlock()
	return screen.terminal.WorkingDirectoryPath()
}

func (screen *ScreenState) Resize(rows int, cols int) {
	screen.mu.Lock()
	defer screen.mu.Unlock()
	screen.terminal.Resize(rows, cols)
}

func (screen *ScreenState) Snapshot(detail headlessterm.SnapshotDetail) *headlessterm.Snapshot {
	screen.mu.Lock()
	defer screen.mu.Unlock()
	return screen.terminal.Snapshot(detail)
}

func (screen *ScreenState) Text() string {
	screen.mu.Lock()
	defer screen.mu.Unlock()
	return screen.terminal.String()
}

func (screen *ScreenState) DirtyDelta(seq uint64) ScreenDelta {
	screen.mu.Lock()
	defer screen.mu.Unlock()

	positions := screen.terminal.DirtyCells()
	cells := make([]ScreenDirtyCell, 0, len(positions))
	for _, position := range positions {
		cell := screen.terminal.Cell(position.Row, position.Col)
		if cell == nil {
			continue
		}
		cells = append(cells, ScreenDirtyCell{
			Row:  position.Row,
			Col:  position.Col,
			Char: string(cell.Char),
		})
	}
	screen.terminal.ClearDirty()
	return ScreenDelta{Seq: seq, Cells: cells}
}

// Note: The thread-safety of terminalTitleProvider (SetTitle, PushTitle, PopTitle)
// relies entirely on the caller holding screen.mu. In the current design, all calls
// originate from screen.Write, which is protected by screen.mu.
type terminalTitleProvider struct {
	onTitle func(string)
	stack   []string
	current string
}

func (provider *terminalTitleProvider) SetTitle(title string) {
	provider.current = title
	if provider.onTitle != nil {
		provider.onTitle(title)
	}
}

func (provider *terminalTitleProvider) PushTitle() {
	provider.stack = append(provider.stack, provider.current)
}

func (provider *terminalTitleProvider) PopTitle() {
	if len(provider.stack) == 0 {
		return
	}
	last := len(provider.stack) - 1
	title := provider.stack[last]
	provider.stack = provider.stack[:last]
	provider.SetTitle(title)
}

const styleFlagsMask = headlessterm.CellFlagBold |
	headlessterm.CellFlagDim |
	headlessterm.CellFlagItalic |
	headlessterm.CellFlagUnderline |
	headlessterm.CellFlagDoubleUnderline |
	headlessterm.CellFlagCurlyUnderline |
	headlessterm.CellFlagDottedUnderline |
	headlessterm.CellFlagDashedUnderline |
	headlessterm.CellFlagBlinkSlow |
	headlessterm.CellFlagBlinkFast |
	headlessterm.CellFlagReverse |
	headlessterm.CellFlagHidden |
	headlessterm.CellFlagStrike

func colorEquals(c1, c2 color.Color) bool {
	if c1 == c2 {
		return true
	}
	if c1 == nil || c2 == nil {
		return false
	}

	nc1, ok1 := c1.(*headlessterm.NamedColor)
	nc2, ok2 := c2.(*headlessterm.NamedColor)
	if ok1 && ok2 {
		return nc1.Name == nc2.Name
	}
	if ok1 || ok2 {
		return false
	}

	ic1, ok1 := c1.(*headlessterm.IndexedColor)
	ic2, ok2 := c2.(*headlessterm.IndexedColor)
	if ok1 && ok2 {
		return ic1.Index == ic2.Index
	}
	if ok1 || ok2 {
		return false
	}

	r1, g1, b1, a1 := c1.RGBA()
	r2, g2, b2, a2 := c2.RGBA()
	return r1 == r2 && g1 == g2 && b1 == b2 && a1 == a2
}

func appendFgSGR(sgrParams []string, fg color.Color) []string {
	if fg == nil {
		return append(sgrParams, "39")
	}
	switch v := fg.(type) {
	case *headlessterm.IndexedColor:
		if v.Index >= 0 && v.Index < 8 {
			sgrParams = append(sgrParams, fmt.Sprintf("%d", 30+v.Index))
		} else if v.Index >= 8 && v.Index < 16 {
			sgrParams = append(sgrParams, fmt.Sprintf("%d", 90+v.Index-8))
		} else {
			sgrParams = append(sgrParams, "38", "5", fmt.Sprintf("%d", v.Index))
		}
	case *headlessterm.NamedColor:
		name := v.Name
		if name >= 0 && name < 8 {
			sgrParams = append(sgrParams, fmt.Sprintf("%d", 30+name))
		} else if name >= 8 && name < 16 {
			sgrParams = append(sgrParams, fmt.Sprintf("%d", 90+name-8))
		} else {
			switch name {
			case headlessterm.NamedColorForeground:
				sgrParams = append(sgrParams, "39")
			case headlessterm.NamedColorBackground:
				sgrParams = append(sgrParams, "39")
			case headlessterm.NamedColorBrightForeground:
				sgrParams = append(sgrParams, "97")
			case headlessterm.NamedColorDimForeground:
				sgrParams = append(sgrParams, "2", "39")
			case headlessterm.NamedColorCursor:
				sgrParams = append(sgrParams, "39")
			default:
				if name >= 259 && name <= 266 {
					sgrParams = append(sgrParams, "2", fmt.Sprintf("%d", 30+(name-259)))
				} else {
					sgrParams = append(sgrParams, "39")
				}
			}
		}
	default:
		r, g, b, _ := fg.RGBA()
		sgrParams = append(sgrParams, "38", "2", fmt.Sprintf("%d", uint8(r>>8)), fmt.Sprintf("%d", uint8(g>>8)), fmt.Sprintf("%d", uint8(b>>8)))
	}
	return sgrParams
}

func appendBgSGR(sgrParams []string, bg color.Color) []string {
	if bg == nil {
		return append(sgrParams, "49")
	}
	switch v := bg.(type) {
	case *headlessterm.IndexedColor:
		if v.Index >= 0 && v.Index < 8 {
			sgrParams = append(sgrParams, fmt.Sprintf("%d", 40+v.Index))
		} else if v.Index >= 8 && v.Index < 16 {
			sgrParams = append(sgrParams, fmt.Sprintf("%d", 100+v.Index-8))
		} else {
			sgrParams = append(sgrParams, "48", "5", fmt.Sprintf("%d", v.Index))
		}
	case *headlessterm.NamedColor:
		name := v.Name
		if name >= 0 && name < 8 {
			sgrParams = append(sgrParams, fmt.Sprintf("%d", 40+name))
		} else if name >= 8 && name < 16 {
			sgrParams = append(sgrParams, fmt.Sprintf("%d", 100+name-8))
		} else {
			switch name {
			case headlessterm.NamedColorBackground:
				sgrParams = append(sgrParams, "49")
			case headlessterm.NamedColorForeground:
				sgrParams = append(sgrParams, "49")
			case headlessterm.NamedColorCursor:
				sgrParams = append(sgrParams, "49")
			default:
				if name >= 259 && name <= 266 {
					sgrParams = append(sgrParams, fmt.Sprintf("%d", 40+(name-259)))
				} else {
					sgrParams = append(sgrParams, "49")
				}
			}
		}
	default:
		r, g, b, _ := bg.RGBA()
		sgrParams = append(sgrParams, "48", "2", fmt.Sprintf("%d", uint8(r>>8)), fmt.Sprintf("%d", uint8(g>>8)), fmt.Sprintf("%d", uint8(b>>8)))
	}
	return sgrParams
}

func appendUnderlineColorSGR(sgrParams []string, uc color.Color) []string {
	if uc == nil {
		return append(sgrParams, "59")
	}
	switch v := uc.(type) {
	case *headlessterm.IndexedColor:
		sgrParams = append(sgrParams, "58", "5", fmt.Sprintf("%d", v.Index))
	case *headlessterm.NamedColor:
		name := v.Name
		if name >= 0 && name < 16 {
			sgrParams = append(sgrParams, "58", "5", fmt.Sprintf("%d", name))
		} else {
			rgba := headlessterm.ResolveDefaultColor(uc, true)
			sgrParams = append(sgrParams, "58", "2", fmt.Sprintf("%d", rgba.R), fmt.Sprintf("%d", rgba.G), fmt.Sprintf("%d", rgba.B))
		}
	default:
		r, g, b, _ := uc.RGBA()
		sgrParams = append(sgrParams, "58", "2", fmt.Sprintf("%d", uint8(r>>8)), fmt.Sprintf("%d", uint8(g>>8)), fmt.Sprintf("%d", uint8(b>>8)))
	}
	return sgrParams
}

var ambiguousWideRunes = map[rune]bool{
	'✔': true,
	'❯': true,
}

func isDefaultFg(fg color.Color) bool {
	if fg == nil {
		return true
	}
	if nc, ok := fg.(*headlessterm.NamedColor); ok {
		return nc.Name == headlessterm.NamedColorForeground
	}
	return false
}

func isDefaultBg(bg color.Color) bool {
	if bg == nil {
		return true
	}
	if nc, ok := bg.(*headlessterm.NamedColor); ok {
		return nc.Name == headlessterm.NamedColorBackground
	}
	return false
}

func isDefaultStyle(cell headlessterm.Cell) bool {
	cellStyleFlags := cell.Flags & styleFlagsMask
	return cellStyleFlags == 0 &&
		isDefaultFg(cell.Fg) &&
		isDefaultBg(cell.Bg) &&
		cell.UnderlineColor == nil
}

func lastActiveCol(line []headlessterm.Cell) int {
	for c := len(line) - 1; c >= 0; c-- {
		cell := line[c]
		if cell.IsWideSpacer() {
			continue
		}
		if cell.Char != ' ' && cell.Char != 0 {
			return c
		}
		if !isDefaultFg(cell.Fg) {
			return c
		}
		if !isDefaultBg(cell.Bg) {
			return c
		}
		if (cell.Flags & styleFlagsMask) != 0 {
			return c
		}
	}
	return -1
}

func lastActiveRow(cells [][]headlessterm.Cell) int {
	for r := len(cells) - 1; r >= 0; r-- {
		if lastActiveCol(cells[r]) >= 0 {
			return r
		}
	}
	return -1
}

func (screen *ScreenState) AnsiText() string {
	screen.mu.Lock()
	rows := screen.terminal.Rows()
	cols := screen.terminal.Cols()
	scrollbackLen := screen.terminal.ScrollbackLen()

	totalRows := scrollbackLen + rows
	cells := make([][]headlessterm.Cell, totalRows)
	wrapped := make([]bool, totalRows)

	for r := 0; r < scrollbackLen; r++ {
		historyLine := screen.terminal.ScrollbackLine(r)
		cells[r] = make([]headlessterm.Cell, cols)
		for c := 0; c < cols; c++ {
			if c < len(historyLine) {
				cells[r][c] = historyLine[c].Copy()
			} else {
				cells[r][c] = headlessterm.NewCell()
			}
		}
		wrapped[r] = false
	}

	for r := 0; r < rows; r++ {
		targetRowIdx := scrollbackLen + r
		cells[targetRowIdx] = make([]headlessterm.Cell, cols)
		for c := 0; c < cols; c++ {
			cellPtr := screen.terminal.Cell(r, c)
			if cellPtr != nil {
				cells[targetRowIdx][c] = cellPtr.Copy()
			} else {
				cells[targetRowIdx][c] = headlessterm.NewCell()
			}
		}
		wrapped[targetRowIdx] = screen.terminal.IsWrapped(r)
	}

	curRow, curCol := screen.terminal.CursorPos()
	cursorVisible := screen.terminal.CursorVisible()
	isAltScreen := screen.terminal.IsAlternateScreen()
	screen.mu.Unlock()

	// 转换 Ambiguous 宽字符为单宽，以对齐 Node 端的 xterm.js 宽度映射
	for r := 0; r < len(cells); r++ {
		for c := 0; c < cols; c++ {
			cell := &cells[r][c]
			if ambiguousWideRunes[cell.Char] {
				cell.ClearFlag(headlessterm.CellFlagWideChar)
				if c+1 < cols && cells[r][c+1].HasFlag(headlessterm.CellFlagWideCharSpacer) {
					cells[r][c+1] = headlessterm.NewCell()
				}
			}
		}
	}

	lastRow := totalRows - 1
	if scrollbackLen == 0 {
		lastRow = lastActiveRow(cells)
	}
	if lastRow < 0 {
		absRow := scrollbackLen + curRow
		if absRow > 0 || curCol > 0 {
			var buf strings.Builder
			buf.WriteString(fmt.Sprintf("\x1b[%d;%dH", curRow+1, curCol+1))
			if cursorVisible {
				buf.WriteString("\x1b[?25h")
			} else {
				buf.WriteString("\x1b[?25l")
			}
			return buf.String()
		}
		return ""
	}

	var buf strings.Builder

	// Alt Buffer 切换（与 Node.js 端一致）
	if isAltScreen {
		buf.WriteString("\x1b[?1049h\x1b[H")
	}

	defaultFg := &headlessterm.NamedColor{Name: headlessterm.NamedColorForeground}
	defaultBg := &headlessterm.NamedColor{Name: headlessterm.NamedColorBackground}

	var activeFg color.Color = defaultFg
	var activeBg color.Color = defaultBg
	var activeUlColor color.Color = nil
	var activeFlags headlessterm.CellFlags = 0

	for r := 0; r <= lastRow; r++ {
		lastCol := lastActiveCol(cells[r])
		// 对于折行行，强制遍历到行末，以便将行尾空白也推进到行末，
		// 确保物理光标到达行尾从而让客户端能正确自动折行
		if wrapped[r] && r < lastRow {
			lastCol = cols - 1
		}
		nullCellCount := 0

		for c := 0; c <= lastCol; c++ {
			cell := cells[r][c]
			if cell.IsWideSpacer() {
				continue
			}

			isEmpty := cell.Char == ' ' || cell.Char == 0
			if isEmpty && isDefaultStyle(cell) {
				// 如果当前活动样式不是默认的，需要在跳过之前将其重置为默认，
				// 否则在后续输出跳过的单元格（如 ECH \x1b[NX）时会发生样式（背景色）泄漏。
				if (activeFlags&styleFlagsMask) != 0 || !isDefaultFg(activeFg) || !isDefaultBg(activeBg) || activeUlColor != nil {
					buf.WriteString("\x1b[0m")
					activeFg = defaultFg
					activeBg = defaultBg
					activeUlColor = nil
					activeFlags = 0
				}
				nullCellCount++
				continue
			}

			if nullCellCount > 0 {
				if !isDefaultBg(activeBg) {
					buf.WriteString(fmt.Sprintf("\x1b[%dX", nullCellCount))
				}
				buf.WriteString(fmt.Sprintf("\x1b[%dC", nullCellCount))
				nullCellCount = 0
			}

			cellStyleFlags := cell.Flags & styleFlagsMask
			activeStyleFlags := activeFlags & styleFlagsMask

			if !colorEquals(cell.Fg, activeFg) || !colorEquals(cell.Bg, activeBg) || !colorEquals(cell.UnderlineColor, activeUlColor) || cellStyleFlags != activeStyleFlags {
				var sgrParams []string
				sgrParams = append(sgrParams, "0")

				if cell.HasFlag(headlessterm.CellFlagBold) {
					sgrParams = append(sgrParams, "1")
				}
				if cell.HasFlag(headlessterm.CellFlagDim) {
					sgrParams = append(sgrParams, "2")
				}
				if cell.HasFlag(headlessterm.CellFlagItalic) {
					sgrParams = append(sgrParams, "3")
				}

				switch {
				case cell.HasFlag(headlessterm.CellFlagDashedUnderline):
					sgrParams = append(sgrParams, "4:5")
				case cell.HasFlag(headlessterm.CellFlagDottedUnderline):
					sgrParams = append(sgrParams, "4:4")
				case cell.HasFlag(headlessterm.CellFlagCurlyUnderline):
					sgrParams = append(sgrParams, "4:3")
				case cell.HasFlag(headlessterm.CellFlagDoubleUnderline):
					sgrParams = append(sgrParams, "4:2")
				case cell.HasFlag(headlessterm.CellFlagUnderline):
					sgrParams = append(sgrParams, "4")
				}

				if cell.HasFlag(headlessterm.CellFlagBlinkSlow) {
					sgrParams = append(sgrParams, "5")
				} else if cell.HasFlag(headlessterm.CellFlagBlinkFast) {
					sgrParams = append(sgrParams, "6")
				}
				if cell.HasFlag(headlessterm.CellFlagReverse) {
					sgrParams = append(sgrParams, "7")
				}
				if cell.HasFlag(headlessterm.CellFlagHidden) {
					sgrParams = append(sgrParams, "8")
				}
				if cell.HasFlag(headlessterm.CellFlagStrike) {
					sgrParams = append(sgrParams, "9")
				}

				sgrParams = appendFgSGR(sgrParams, cell.Fg)
				sgrParams = appendBgSGR(sgrParams, cell.Bg)
				sgrParams = appendUnderlineColorSGR(sgrParams, cell.UnderlineColor)

				buf.WriteString("\x1b[" + strings.Join(sgrParams, ";") + "m")
				activeFg = cell.Fg
				activeBg = cell.Bg
				activeUlColor = cell.UnderlineColor
				activeFlags = cell.Flags
			}

			if cell.Char == 0 {
				buf.WriteRune(' ')
			} else {
				buf.WriteRune(cell.Char)
			}
		}

		if nullCellCount > 0 {
			if !isDefaultBg(activeBg) {
				buf.WriteString(fmt.Sprintf("\x1b[%dX", nullCellCount))
			}
			// 对于折行行，输出真实空格以确保物理光标到达行尾，
			// 从而让客户端能正确自动折行（否则下一行数据会拼接到本行右侧）
			if wrapped[r] && r < lastRow {
				buf.WriteString(strings.Repeat(" ", nullCellCount))
			}
		}

		if (activeFlags&styleFlagsMask) != 0 || !isDefaultFg(activeFg) || !isDefaultBg(activeBg) || activeUlColor != nil {
			buf.WriteString("\x1b[0m")
			activeFg = defaultFg
			activeBg = defaultBg
			activeUlColor = nil
			activeFlags = 0
		}
		if r < lastRow {
			if wrapped[r] {
				// line wrap, omit newline character for reflow
			} else {
				buf.WriteString("\r\n") // 行尾分隔符完全对齐为 CRLF
			}
		}
	}

	absRow := scrollbackLen + curRow
	if absRow > 0 || curCol > 0 {
		if activeFg != defaultFg || activeBg != defaultBg || activeUlColor != nil || activeFlags != 0 {
			buf.WriteString("\x1b[0m")
		}
	}

	// 检查是否需要恢复光标位置。仅在实际光标位置不在文本自然结束处时，才追加定位序列
	lastCol := lastActiveCol(cells[lastRow])
	expectedNaturalCol := lastCol + 1
	if lastCol >= 0 && cells[lastRow][lastCol].HasFlag(headlessterm.CellFlagWideChar) {
		// 最后一个有效字符是宽字符（如中文/Emoji），物理光标实际在 lastCol+2
		expectedNaturalCol = lastCol + 2
	}
	if absRow != lastRow || curCol != expectedNaturalCol {
		buf.WriteString(fmt.Sprintf("\x1b[%d;%dH", curRow+1, curCol+1))
	}

	if !cursorVisible {
		buf.WriteString("\x1b[?25l")
	} else if absRow != lastRow || curCol != expectedNaturalCol {
		buf.WriteString("\x1b[?25h")
	}

	return buf.String()
}
