package screenprotocol

import (
	"fmt"
	"strings"
	"unicode/utf8"

	"google.golang.org/protobuf/proto"

	pb "webterm/go-core/internal/screenprotocol/generated"
	"webterm/go-core/internal/terminalengine"
)

// EncodeFrame 把 ScreenFrame 编码为 ScreenEnvelope 二进制。
// 帧类型只由 frame.Kind 决定，禁止再用 BaseRevision == 0 判断。
func EncodeFrame(frame terminalengine.ScreenFrame) ([]byte, error) {
	return EncodeFrameWithCompactLines(frame, true)
}

// EncodeFrameWithCompactLines emits the final Compact representation for every valid
// line. CellRun remains available only when a caller explicitly disables Compact.
func EncodeFrameWithCompactLines(frame terminalengine.ScreenFrame, compactLines bool) ([]byte, error) {
	var envelope *pb.ScreenEnvelope
	switch frame.Kind {
	case terminalengine.FrameSnapshot:
		snapshot, err := encodeSnapshot(frame, compactLines)
		if err != nil {
			return nil, fmt.Errorf("encode snapshot session=%q: %w", frame.SessionID, err)
		}
		envelope = &pb.ScreenEnvelope{
			ProtocolVersion: 1,
			Payload:         &pb.ScreenEnvelope_Snapshot{Snapshot: snapshot},
		}
	case terminalengine.FramePatch:
		patch, err := encodePatch(frame, compactLines)
		if err != nil {
			return nil, fmt.Errorf("encode patch session=%q: %w", frame.SessionID, err)
		}
		envelope = &pb.ScreenEnvelope{
			ProtocolVersion: 1,
			Payload:         &pb.ScreenEnvelope_Patch{Patch: patch},
		}
	default:
		return nil, fmt.Errorf("unknown screen frame kind: %d", frame.Kind)
	}
	return proto.Marshal(envelope)
}

// EncodeHistoryPage 编码按需历史页。
func EncodeHistoryPage(requestID string, epoch, revision uint64, page terminalengine.HistoryPageData) ([]byte, error) {
	return EncodeHistoryPageWithCompactLines(requestID, epoch, revision, page, true)
}

// EncodeHistoryPageWithCompactLines follows the same LineData encoding rule as frames.
func EncodeHistoryPageWithCompactLines(requestID string, epoch, revision uint64,
	page terminalengine.HistoryPageData, compactLines bool) ([]byte, error) {
	w := page.Window
	lines, err := encodeLineData(w.Lines, compactLines, 0)
	if err != nil {
		return nil, fmt.Errorf("encode history page request=%q: %w", requestID, err)
	}
	return proto.Marshal(&pb.ScreenEnvelope{ProtocolVersion: 1, Payload: &pb.ScreenEnvelope_HistoryPage{HistoryPage: &pb.HistoryPage{
		RequestId: requestID, LayoutEpoch: epoch, AsOfRevision: revision,
		FirstAvailableLineId: w.FirstAvailableLineID, HasMoreBefore: w.HasMoreBefore,
		Lines: lines, Styles: encodeStyles(page.Styles), Links: encodeLinks(page.Links),
	}}})
}

// EncodeHistoryTrim 编码服务端历史裁剪水位。
func EncodeHistoryTrim(epoch, firstAvailableID uint64) ([]byte, error) {
	return proto.Marshal(&pb.ScreenEnvelope{ProtocolVersion: 1, Payload: &pb.ScreenEnvelope_HistoryTrim{HistoryTrim: &pb.HistoryTrim{
		LayoutEpoch: epoch, FirstAvailableLineId: firstAvailableID,
	}}})
}

// EncodeEffect 编码网格之外的终端副作用。
func EncodeEffect(instanceID string, revision uint64, effect terminalengine.Effect) ([]byte, error) {
	pbEffect := &pb.TerminalEffect{InstanceId: instanceID, ScreenRevision: revision}
	switch effect.Kind {
	case terminalengine.EffectBell:
		pbEffect.Effect = &pb.TerminalEffect_Bell{Bell: &pb.Bell{}}
	case terminalengine.EffectTitle:
		pbEffect.Effect = &pb.TerminalEffect_Title{Title: &pb.TitleChanged{Title: effect.Text}}
	case terminalengine.EffectWorkingDirectory:
		pbEffect.Effect = &pb.TerminalEffect_Cwd{Cwd: &pb.WorkingDirectoryChanged{Path: effect.Text}}
	case terminalengine.EffectClipboardRead:
		pbEffect.Effect = &pb.TerminalEffect_ClipboardRead{ClipboardRead: &pb.ClipboardReadRequest{RequestId: effect.RequestID, Clipboard: effect.Clipboard}}
	case terminalengine.EffectClipboardWrite:
		pbEffect.Effect = &pb.TerminalEffect_ClipboardWrite{ClipboardWrite: &pb.ClipboardWriteRequest{RequestId: effect.RequestID, Clipboard: effect.Clipboard, Data: effect.Data}}
	default:
		return nil, fmt.Errorf("unsupported terminal effect: %d", effect.Kind)
	}
	return proto.Marshal(&pb.ScreenEnvelope{ProtocolVersion: 1, Payload: &pb.ScreenEnvelope_Effect{Effect: pbEffect}})
}

func encodeSnapshot(frame terminalengine.ScreenFrame, compactLines bool) (*pb.ScreenSnapshot, error) {
	screenLines, err := encodeLineData(frame.Screen, compactLines, frame.Cols)
	if err != nil {
		return nil, err
	}
	historyLines, err := encodeLineData(frame.History.Lines, compactLines, frame.Cols)
	if err != nil {
		return nil, err
	}
	return &pb.ScreenSnapshot{
		SessionId:                   frame.SessionID,
		InstanceId:                  frame.InstanceID,
		LayoutEpoch:                 frame.Epoch,
		ScreenRevision:              frame.Seq,
		Geometry:                    encodeSize(frame.Rows, frame.Cols),
		ActiveBuffer:                encodeBufferKind(frame.ActiveBuffer),
		Layout:                      encodeLayout(frame.Screen),
		ScreenLines:                 screenLines,
		HistoryTailIds:              historySeqs(frame.History.Lines),
		HistoryTailLines:            historyLines,
		DictionaryGeneration:        frame.DictionaryGeneration,
		Styles:                      encodeStyles(frame.Styles),
		Links:                       encodeLinks(frame.Links),
		Cursor:                      encodeCursor(frame.Cursor),
		Modes:                       encodeModes(frame.Modes),
		Palette:                     encodePalette(frame),
		Title:                       proto.String(frame.Title),
		WorkingDirectory:            proto.String(frame.WorkingDir),
		FirstAvailableHistoryLineId: frame.History.FirstAvailableLineID,
		HasMoreHistoryBefore:        frame.History.HasMoreBefore,
	}, nil
}

func encodePatch(frame terminalengine.ScreenFrame, compactLines bool) (*pb.ScreenPatch, error) {
	lineUpdates, err := encodeLineData(uniqueLineUpdates(frame.Screen, frame.History.Lines), compactLines, frame.Cols)
	if err != nil {
		return nil, err
	}
	patch := &pb.ScreenPatch{
		InstanceId:           frame.InstanceID,
		LayoutEpoch:          frame.Epoch,
		BaseRevision:         frame.BaseRevision,
		ScreenRevision:       frame.Seq,
		LineUpdates:          lineUpdates,
		HistoryAppendIds:     frame.HistoryAppendIDs,
		DictionaryGeneration: frame.DictionaryGeneration,
		NewStyles:            encodeStyles(frame.Styles),
		NewLinks:             encodeLinks(frame.Links),
	}
	if frame.Layout != nil {
		patch.Layout = &pb.ScreenLayout{LineIds: frame.Layout}
	}
	if frame.CursorChanged {
		patch.Cursor = encodeCursor(frame.Cursor)
	}
	if frame.ModesChanged {
		patch.Modes = encodeModes(frame.Modes)
	}
	if frame.PaletteChanged {
		patch.Palette = encodePalette(frame)
	}
	// title/cwd 是 proto3 optional：只在显式变化标志置位时出现，
	// 以此在 wire 上区分“未变化”和“变为空串”。
	if frame.TitleChanged {
		patch.Title = proto.String(frame.Title)
	}
	if frame.WorkingDirChanged {
		patch.WorkingDirectory = proto.String(frame.WorkingDir)
	}
	if frame.FirstAvailableHistoryLineIDChanged {
		patch.HistoryTrimBeforeId = proto.Uint64(frame.History.FirstAvailableLineID)
	}
	return patch, nil
}

func encodeSize(rows, cols int) *pb.Size {
	return &pb.Size{Rows: int32(rows), Cols: int32(cols)}
}

func encodeBufferKind(k terminalengine.BufferKind) pb.BufferKind {
	switch k {
	case terminalengine.BufferAlternate:
		return pb.BufferKind_BUFFER_KIND_ALTERNATE
	default:
		return pb.BufferKind_BUFFER_KIND_MAIN
	}
}

func encodeCursor(c terminalengine.Cursor) *pb.Cursor {
	return &pb.Cursor{
		Row:     int32(c.Row),
		Col:     int32(c.Col),
		Visible: c.Visible,
		Shape:   encodeCursorShape(c.Shape),
		Blink:   c.Blink,
	}
}

func encodeCursorShape(s terminalengine.CursorShape) pb.CursorShape {
	switch s {
	case terminalengine.CursorBar:
		return pb.CursorShape_CURSOR_SHAPE_BAR
	case terminalengine.CursorUnderline:
		return pb.CursorShape_CURSOR_SHAPE_UNDERLINE
	default:
		return pb.CursorShape_CURSOR_SHAPE_BLOCK
	}
}

func encodeModes(m terminalengine.Modes) *pb.Modes {
	return &pb.Modes{
		ApplicationCursor: m.ApplicationCursor,
		ApplicationKeypad: m.ApplicationKeypad,
		BracketedPaste:    m.BracketedPaste,
		MouseTracking:     encodeMouseTracking(m.MouseTracking),
		MouseEncoding:     encodeMouseEncoding(m.MouseEncoding),
		FocusReporting:    m.FocusReporting,
	}
}

func encodeMouseTracking(t terminalengine.MouseTracking) pb.MouseTracking {
	switch t {
	case terminalengine.MouseX10:
		return pb.MouseTracking_MOUSE_TRACKING_X10
	case terminalengine.MouseVT200:
		return pb.MouseTracking_MOUSE_TRACKING_VT200
	case terminalengine.MouseVT200Highlight:
		return pb.MouseTracking_MOUSE_TRACKING_VT200_HIGHLIGHT
	case terminalengine.MouseButtonEvent:
		return pb.MouseTracking_MOUSE_TRACKING_BUTTON_EVENT
	case terminalengine.MouseAnyEvent:
		return pb.MouseTracking_MOUSE_TRACKING_ANY_EVENT
	case terminalengine.MouseSGRPixels:
		return pb.MouseTracking_MOUSE_TRACKING_SGR_PIXELS
	default:
		return pb.MouseTracking_MOUSE_TRACKING_NONE
	}
}

func encodeMouseEncoding(e terminalengine.MouseEncoding) pb.MouseEncoding {
	switch e {
	case terminalengine.MouseEncodingUTF8:
		return pb.MouseEncoding_MOUSE_ENCODING_UTF8
	case terminalengine.MouseEncodingSGR:
		return pb.MouseEncoding_MOUSE_ENCODING_SGR
	case terminalengine.MouseEncodingURXVT:
		return pb.MouseEncoding_MOUSE_ENCODING_URXVT
	default:
		return pb.MouseEncoding_MOUSE_ENCODING_X10
	}
}

func encodePalette(frame terminalengine.ScreenFrame) *pb.TerminalPalette {
	palette := &pb.TerminalPalette{
		DefaultFg:    encodeColor(frame.DefaultFG),
		DefaultBg:    encodeColor(frame.DefaultBG),
		CursorColor:  encodeColor(frame.CursorColor),
		ReverseVideo: frame.ReverseVideo,
		Generation:   frame.PaletteGeneration,
	}
	for index, rgb := range frame.IndexedPalette {
		if frame.IndexedPaletteSet[index/64]&(uint64(1)<<uint(index%64)) == 0 {
			continue
		}
		palette.IndexedColors = append(palette.IndexedColors, &pb.IndexedPaletteColor{
			Index: int32(index), Rgb: rgb,
		})
	}
	return palette
}

func encodeColor(c terminalengine.Color) *pb.Color {
	return &pb.Color{
		Kind:  encodeColorKind(c.Kind),
		Index: int32(c.Index),
		Rgb:   c.RGB,
	}
}

func encodeColorKind(k terminalengine.ColorKind) pb.ColorKind {
	switch k {
	case terminalengine.ColorDefaultBG:
		return pb.ColorKind_COLOR_KIND_DEFAULT_BG
	case terminalengine.ColorCursor:
		return pb.ColorKind_COLOR_KIND_CURSOR
	case terminalengine.ColorIndexed:
		return pb.ColorKind_COLOR_KIND_INDEXED
	case terminalengine.ColorRGB:
		return pb.ColorKind_COLOR_KIND_RGB
	default:
		return pb.ColorKind_COLOR_KIND_DEFAULT_FG
	}
}

func encodeLineData(lines []terminalengine.Line, compactLines bool, columns int) ([]*pb.LineData, error) {
	out := make([]*pb.LineData, len(lines))
	for i, line := range lines {
		encoded := &pb.LineData{
			LineId:      line.ID,
			LineVersion: line.Version,
			HistorySeq:  line.HistorySeq,
			Wrapped:     line.Wrapped,
			Runs:        encodeCellRuns(line.Runs),
		}
		if compactLines {
			text, meta, spans, err := compactLine(line.Runs, columns)
			if err != nil {
				return nil, fmt.Errorf("compact line id=%d row=%d: %w", line.ID, line.Row, err)
			}
			encoded.Runs = nil
			encoded.Text = text
			encoded.CellMeta = meta
			encoded.StyleSpans = spans
		}
		out[i] = encoded
	}
	return out, nil
}

func encodeLayout(lines []terminalengine.Line) *pb.ScreenLayout {
	return &pb.ScreenLayout{LineIds: lineIDs(lines)}
}

func lineIDs(lines []terminalengine.Line) []uint64 {
	ids := make([]uint64, len(lines))
	for i := range lines {
		ids[i] = lines[i].ID
	}
	return ids
}

func historySeqs(lines []terminalengine.Line) []uint64 {
	seqs := make([]uint64, len(lines))
	for i := range lines {
		seqs[i] = lines[i].HistorySeq
	}
	return seqs
}

func uniqueLineUpdates(groups ...[]terminalengine.Line) []terminalengine.Line {
	seen := make(map[uint64]struct{})
	var out []terminalengine.Line
	for _, lines := range groups {
		for _, line := range lines {
			if _, exists := seen[line.ID]; !exists {
				seen[line.ID] = struct{}{}
				out = append(out, line)
			}
		}
	}
	return out
}

type compactCell struct {
	text    string
	width   uint8
	styleID uint32
	linkID  uint32
	present bool
	spacer  bool
}

// compactLine converts the transport-neutral grid into UTF-8 text plus one
// metadata byte per logical cell. The high metadata bit carries Go's already
// resolved display width; the low seven bits carry the Cell.Text code point
// count so Android never needs to infer grapheme boundaries or terminal width.
func compactLine(runs []terminalengine.CellRun, requestedColumns int) (string, []byte, []*pb.StyleSpan, error) {
	columns := requestedColumns
	for _, run := range runs {
		if run.Col < 0 {
			return "", nil, nil, fmt.Errorf("negative run column: %d", run.Col)
		}
		end := run.Col
		for _, cell := range run.Cells {
			if cell.Width != 1 && cell.Width != 2 {
				return "", nil, nil, fmt.Errorf("invalid cell width at col=%d: %d", end, cell.Width)
			}
			end += int(cell.Width)
		}
		if requestedColumns > 0 && end > requestedColumns {
			return "", nil, nil, fmt.Errorf("run exceeds compact grid: end=%d columns=%d", end, requestedColumns)
		}
		if requestedColumns <= 0 && end > columns {
			columns = end
		}
	}
	if columns == 0 {
		return "", nil, nil, nil
	}
	if columns < 0 || columns > maxCols {
		return "", nil, nil, fmt.Errorf("invalid compact column count: %d", columns)
	}

	grid := make([]compactCell, columns)
	for _, run := range runs {
		col := run.Col
		for _, cell := range run.Cells {
			if cell.Width != 1 && cell.Width != 2 {
				return "", nil, nil, fmt.Errorf("invalid cell width at col=%d: %d", col, cell.Width)
			}
			if col < 0 || col+int(cell.Width) > columns {
				return "", nil, nil, fmt.Errorf("cell exceeds compact grid at col=%d width=%d columns=%d", col, cell.Width, columns)
			}
			if grid[col].present || grid[col].spacer {
				return "", nil, nil, fmt.Errorf("overlapping logical cell at col=%d", col)
			}
			text := cell.Text
			if text == "" {
				text = " "
			}
			if len(text) > maxCellTextBytes || !utf8.ValidString(text) {
				return "", nil, nil, fmt.Errorf("invalid UTF-8 cell text at col=%d bytes=%d", col, len(text))
			}
			codePoints := utf8.RuneCountInString(text)
			if codePoints < 1 || codePoints > 127 {
				return "", nil, nil, fmt.Errorf("invalid cell code point count at col=%d: %d", col, codePoints)
			}
			grid[col] = compactCell{text: text, width: cell.Width, styleID: cell.StyleID, linkID: cell.LinkID, present: true}
			if cell.Width == 2 {
				if grid[col+1].present || grid[col+1].spacer {
					return "", nil, nil, fmt.Errorf("wide cell spacer collision at col=%d", col+1)
				}
				grid[col+1].spacer = true
			}
			col += int(cell.Width)
		}
	}

	// Default trailing cells are represented by Android's padded EMPTY cells.
	// A spacer is never trimmable: it belongs to the preceding width=2 cell.
	effectiveColumns := columns
	for effectiveColumns > 0 {
		last := grid[effectiveColumns-1]
		if last.spacer || (last.present && (last.text != " " || last.width != 1 || last.styleID != 0 || last.linkID != 0)) {
			break
		}
		effectiveColumns--
	}
	if effectiveColumns == 0 {
		return "", nil, nil, nil
	}

	styles := make([]uint32, effectiveColumns)
	links := make([]uint32, effectiveColumns)
	var text strings.Builder
	text.Grow(effectiveColumns)
	meta := make([]byte, 0, effectiveColumns)
	for col := 0; col < effectiveColumns; {
		entry := grid[col]
		if entry.spacer {
			return "", nil, nil, fmt.Errorf("standalone wide-cell spacer at col=%d", col)
		}
		if !entry.present {
			entry = compactCell{text: " ", width: 1, present: true}
		}
		if entry.width != 1 && entry.width != 2 || col+int(entry.width) > effectiveColumns {
			return "", nil, nil, fmt.Errorf("invalid logical cell extent at col=%d width=%d", col, entry.width)
		}
		if entry.width == 2 && !grid[col+1].spacer {
			return "", nil, nil, fmt.Errorf("wide cell missing spacer at col=%d", col+1)
		}
		codePoints := utf8.RuneCountInString(entry.text)
		metaByte := byte(codePoints)
		if entry.width == 2 {
			metaByte |= 0x80
		}
		meta = append(meta, metaByte)
		text.WriteString(entry.text)
		for offset := 0; offset < int(entry.width); offset++ {
			styles[col+offset] = entry.styleID
			links[col+offset] = entry.linkID
		}
		col += int(entry.width)
	}

	spans := compactStyleSpans(styles, links)
	return text.String(), meta, spans, nil
}

func compactStyleSpans(styles, links []uint32) []*pb.StyleSpan {
	spans := make([]*pb.StyleSpan, 0)
	for start := 0; start < len(styles); {
		style, link := styles[start], links[start]
		end := start + 1
		for end < len(styles) && styles[end] == style && links[end] == link {
			end++
		}
		if style != 0 || link != 0 {
			spans = append(spans, &pb.StyleSpan{StartCol: int32(start), EndCol: int32(end), StyleId: style, LinkId: link})
		}
		start = end
	}
	return spans
}

func encodeCellRuns(runs []terminalengine.CellRun) []*pb.CellRun {
	out := make([]*pb.CellRun, len(runs))
	for i, run := range runs {
		out[i] = &pb.CellRun{
			Col:   int32(run.Col),
			Cells: encodeCells(run.Cells),
		}
	}
	return out
}

func encodeCells(cells []terminalengine.Cell) []*pb.Cell {
	out := make([]*pb.Cell, len(cells))
	for i, cell := range cells {
		out[i] = &pb.Cell{
			Text:    cell.Text,
			Width:   uint32(cell.Width),
			StyleId: cell.StyleID,
			LinkId:  cell.LinkID,
		}
	}
	return out
}

func encodeStyles(styles []terminalengine.TerminalStyle) []*pb.TerminalStyle {
	out := make([]*pb.TerminalStyle, len(styles))
	for i, s := range styles {
		out[i] = &pb.TerminalStyle{
			Id:             s.ID,
			Fg:             encodeColor(s.FG),
			Bg:             encodeColor(s.BG),
			UnderlineColor: encodeColor(s.ULColor),
			Attrs:          encodeCellAttrs(s.Attrs),
		}
	}
	return out
}

func encodeCellAttrs(attrs terminalengine.CellAttrs) *pb.CellAttrs {
	return &pb.CellAttrs{
		Bold:            attrs.Bold,
		Dim:             attrs.Dim,
		Italic:          attrs.Italic,
		Underline:       attrs.Underline,
		DoubleUnderline: attrs.DoubleUnderline,
		CurlyUnderline:  attrs.CurlyUnderline,
		DottedUnderline: attrs.DottedUnderline,
		DashedUnderline: attrs.DashedUnderline,
		BlinkSlow:       attrs.BlinkSlow,
		BlinkFast:       attrs.BlinkFast,
		Reverse:         attrs.Reverse,
		Hidden:          attrs.Hidden,
		Strike:          attrs.Strike,
	}
}

func encodeLinks(links []terminalengine.Hyperlink) []*pb.Hyperlink {
	out := make([]*pb.Hyperlink, len(links))
	for i, link := range links {
		out[i] = &pb.Hyperlink{
			Id:  link.ID,
			Uri: link.URI,
		}
	}
	return out
}
