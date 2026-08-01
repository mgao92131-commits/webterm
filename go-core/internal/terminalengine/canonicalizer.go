package terminalengine

import (
	"image/color"

	headlessterm "github.com/danielgatis/go-headless-term"
)

// CursorContext 描述规范化时是否应用活动 software caret 过滤。
//
// 对单行 BuildCanonicalLine：Present=true 表示“本行是光标行”，仅 Col 处的
// reverse-space caret 保留；历史行 Present=false，清除全部 software caret。
// 带绝对行号时请用 BuildCanonicalLineAtRow。
type CursorContext struct {
	Present bool
	Row     int
	Col     int
}

// BuildCanonicalLine 把 headless-term 源行规范化为唯一正文表示。
// Screen / History / Snapshot / Commit Body / BodyBatch 必须共用此实现。
//
// lineID 仅供调用方关联 LineStore，不进入正文。
func BuildCanonicalLine(
	_ uint64,
	cells []headlessterm.Cell,
	wrapped bool,
	cursor CursorContext,
) CanonicalLineBody {
	out := make([]CanonicalCell, len(cells))
	for col := 0; col < len(cells); col++ {
		cell := cells[col]
		if isStaleSoftCursor(cell, col, cursor) {
			out[col] = defaultCanonicalCell()
			continue
		}
		out[col] = canonicalCellFromSource(cell)
	}
	return CanonicalLineBody{
		PhysicalColumns: len(cells),
		Wrapped:         wrapped,
		Cells:           out,
	}
}

func isStaleSoftCursor(cell headlessterm.Cell, col int, cursor CursorContext) bool {
	if !isSoftCursorCell(cell) {
		return false
	}
	// Present 表示“当前正在规范化光标所在行”。历史行 Present=false，
	// 所有 software caret 都规范化删除；屏幕光标行仅保留权威列。
	if cursor.Present && col == cursor.Col {
		return false
	}
	return true
}

// BuildCanonicalLineAtRow 用绝对行号把投影光标上下文投影到单行 CursorContext。
func BuildCanonicalLineAtRow(
	lineID uint64,
	row int,
	cells []headlessterm.Cell,
	wrapped bool,
	cursor CursorContext,
) CanonicalLineBody {
	lineCursor := CursorContext{Present: false}
	if cursor.Present && cursor.Row == row {
		lineCursor = CursorContext{Present: true, Row: row, Col: cursor.Col}
	}
	return BuildCanonicalLine(lineID, cells, wrapped, lineCursor)
}

func isSoftCursorCell(cell headlessterm.Cell) bool {
	if cell.Char != " " || !cell.HasFlag(headlessterm.CellFlagReverse) || cell.Hyperlink != nil || cell.Image != nil {
		return false
	}
	const nonCursorFlags = ^headlessterm.CellFlagReverse
	return cell.Flags&nonCursorFlags == 0
}

func canonicalCellFromSource(cell headlessterm.Cell) CanonicalCell {
	width := uint8(1)
	text := cell.Char
	if cell.HasFlag(headlessterm.CellFlagWideChar) {
		width = 2
	} else if cell.HasFlag(headlessterm.CellFlagWideCharSpacer) {
		width = 0
		text = ""
	}
	if width != 0 && text == "" {
		text = " "
	}

	link := ""
	if cell.Hyperlink != nil {
		link = cell.Hyperlink.URI
	}

	return CanonicalCell{
		Text:  text,
		Width: width,
		Style: CanonicalStyle{
			FG:      sourceCellColor(cell.Fg),
			BG:      sourceCellColor(cell.Bg),
			ULColor: sourceCellColor(cell.UnderlineColor),
			Attrs: CellAttrs{
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
			},
		},
		Link: link,
	}
}

func defaultCanonicalCell() CanonicalCell {
	return CanonicalCell{
		Text:  " ",
		Width: 1,
		Style: CanonicalStyle{
			FG: Color{Kind: ColorDefaultFG},
			BG: Color{Kind: ColorDefaultBG},
		},
	}
}

func sourceCellColor(c color.Color) Color {
	if c == nil {
		return Color{Kind: ColorDefaultFG}
	}
	switch v := c.(type) {
	case *headlessterm.NamedColor:
		switch v.Name {
		case headlessterm.NamedColorForeground:
			return Color{Kind: ColorDefaultFG}
		case headlessterm.NamedColorBackground:
			return Color{Kind: ColorDefaultBG}
		case headlessterm.NamedColorCursor:
			return Color{Kind: ColorCursor}
		}
		return Color{Kind: ColorIndexed, Index: int(v.Name)}
	case *headlessterm.IndexedColor:
		return Color{Kind: ColorIndexed, Index: v.Index}
	default:
		r, g, b, _ := c.RGBA()
		return Color{Kind: ColorRGB, RGB: (uint32(r>>8) << 16) | (uint32(g>>8) << 8) | uint32(b>>8)}
	}
}
