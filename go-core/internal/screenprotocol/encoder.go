package screenprotocol

import (
	"fmt"
	"google.golang.org/protobuf/proto"

	pb "webterm/go-core/internal/screenprotocol/generated"
	"webterm/go-core/internal/terminalengine"
)

// EncodeFrame 把 ScreenFrame 编码为 ScreenEnvelope 二进制。
// 帧类型只由 frame.Kind 决定，禁止再用 BaseRevision == 0 判断。
func EncodeFrame(frame terminalengine.ScreenFrame) ([]byte, error) {
	return EncodeFrameWithCompactLines(frame, true)
}

// EncodeFrameWithCompactLines emits compact text/span for simple ASCII rows. Complex
// wide/combining rows continue to use CellRun; this is an encoding choice, not version
// negotiation, because webterm.screen.v1 has one stable LineData schema.
func EncodeFrameWithCompactLines(frame terminalengine.ScreenFrame, compactLines bool) ([]byte, error) {
	var envelope *pb.ScreenEnvelope
	switch frame.Kind {
	case terminalengine.FrameSnapshot:
		envelope = &pb.ScreenEnvelope{
			ProtocolVersion: 1,
			Payload:         &pb.ScreenEnvelope_Snapshot{Snapshot: encodeSnapshot(frame, compactLines)},
		}
	case terminalengine.FramePatch:
		envelope = &pb.ScreenEnvelope{
			ProtocolVersion: 1,
			Payload:         &pb.ScreenEnvelope_Patch{Patch: encodePatch(frame, compactLines)},
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
	return proto.Marshal(&pb.ScreenEnvelope{ProtocolVersion: 1, Payload: &pb.ScreenEnvelope_HistoryPage{HistoryPage: &pb.HistoryPage{
		RequestId: requestID, LayoutEpoch: epoch, AsOfRevision: revision,
		FirstAvailableLineId: w.FirstAvailableLineID, HasMoreBefore: w.HasMoreBefore,
		Lines: encodeLineData(w.Lines, compactLines, 0), Styles: encodeStyles(page.Styles), Links: encodeLinks(page.Links),
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

func encodeSnapshot(frame terminalengine.ScreenFrame, compactLines bool) *pb.ScreenSnapshot {
	return &pb.ScreenSnapshot{
		SessionId:                   frame.SessionID,
		InstanceId:                  frame.InstanceID,
		LayoutEpoch:                 frame.Epoch,
		ScreenRevision:              frame.Seq,
		Geometry:                    encodeSize(frame.Rows, frame.Cols),
		ActiveBuffer:                encodeBufferKind(frame.ActiveBuffer),
		Layout:                      encodeLayout(frame.Screen),
		ScreenLines:                 encodeLineData(frame.Screen, compactLines, frame.Cols),
		HistoryTailIds:              lineIDs(frame.History.Lines),
		HistoryTailLines:            encodeLineData(frame.History.Lines, compactLines, frame.Cols),
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
	}
}

func encodePatch(frame terminalengine.ScreenFrame, compactLines bool) *pb.ScreenPatch {
	patch := &pb.ScreenPatch{
		InstanceId:           frame.InstanceID,
		LayoutEpoch:          frame.Epoch,
		BaseRevision:         frame.BaseRevision,
		ScreenRevision:       frame.Seq,
		LineUpdates:          encodeLineData(uniqueLineUpdates(frame.Screen, frame.History.Lines), compactLines, frame.Cols),
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
	return patch
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

func encodeLineData(lines []terminalengine.Line, compactLines bool, columns int) []*pb.LineData {
	out := make([]*pb.LineData, len(lines))
	for i, line := range lines {
		encoded := &pb.LineData{
			LineId:      line.ID,
			LineVersion: line.Version,
			Wrapped:     line.Wrapped,
			Runs:        encodeCellRuns(line.Runs),
		}
		if compactLines {
			if text, spans, ok := compactASCII(line.Runs, columns); ok {
				encoded.Runs = nil
				encoded.Text = text
				encoded.StyleSpans = spans
			}
		}
		out[i] = encoded
	}
	return out
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

// compactASCII is deliberately narrow: Go remains authoritative for display width.
// The compact form is used only when every visible cell is an ordinary one-column ASCII byte;
// wide, combining and spacer cells use the established CellRun representation unchanged.
func compactASCII(runs []terminalengine.CellRun, requestedColumns int) (string, []*pb.StyleSpan, bool) {
	columns := requestedColumns
	for _, run := range runs {
		end := run.Col
		for _, cell := range run.Cells {
			if cell.Width != 1 {
				return "", nil, false
			}
			end++
		}
		if end > columns {
			columns = end
		}
	}
	if columns <= 0 {
		return "", nil, false
	}
	chars := make([]byte, columns)
	styles := make([]uint32, columns)
	links := make([]uint32, columns)
	for i := range chars {
		chars[i] = ' '
	}
	for _, run := range runs {
		col := run.Col
		if col < 0 {
			return "", nil, false
		}
		for _, cell := range run.Cells {
			if col >= columns || cell.Width != 1 {
				return "", nil, false
			}
			text := cell.Text
			if text == "" {
				text = " "
			}
			if len(text) != 1 || text[0] < 0x20 || text[0] > 0x7e {
				return "", nil, false
			}
			chars[col] = text[0]
			styles[col] = cell.StyleID
			links[col] = cell.LinkID
			col++
		}
	}
	// 行尾默认空格在渲染、选择和布局上都等价于客户端补齐的 EMPTY；不把它们放上
	// wire，避免大屏 Snapshot 为每一行重复传输整列空格。带 style/link 的空格仍是
	// 可见/可交互状态，必须保留。
	encodedColumns := columns
	for encodedColumns > 0 {
		last := encodedColumns - 1
		if chars[last] != ' ' || styles[last] != 0 || links[last] != 0 {
			break
		}
		encodedColumns--
	}

	spans := make([]*pb.StyleSpan, 0)
	for start := 0; start < encodedColumns; {
		style, link := styles[start], links[start]
		end := start + 1
		for end < encodedColumns && styles[end] == style && links[end] == link {
			end++
		}
		if style != 0 || link != 0 {
			spans = append(spans, &pb.StyleSpan{StartCol: int32(start), EndCol: int32(end), StyleId: style, LinkId: link})
		}
		start = end
	}
	return string(chars[:encodedColumns]), spans, true
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
