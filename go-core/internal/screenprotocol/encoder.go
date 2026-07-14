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
	var envelope *pb.ScreenEnvelope
	switch frame.Kind {
	case terminalengine.FrameSnapshot:
		envelope = &pb.ScreenEnvelope{
			ProtocolVersion: 1,
			Payload:         &pb.ScreenEnvelope_Snapshot{Snapshot: encodeSnapshot(frame)},
		}
	case terminalengine.FramePatch:
		envelope = &pb.ScreenEnvelope{
			ProtocolVersion: 1,
			Payload:         &pb.ScreenEnvelope_Patch{Patch: encodePatch(frame)},
		}
	default:
		return nil, fmt.Errorf("unknown screen frame kind: %d", frame.Kind)
	}
	return proto.Marshal(envelope)
}

// EncodeHistoryPage 编码按需历史页。
func EncodeHistoryPage(requestID string, epoch, revision uint64, page terminalengine.HistoryPageData) ([]byte, error) {
	w := page.Window
	return proto.Marshal(&pb.ScreenEnvelope{ProtocolVersion: 1, Payload: &pb.ScreenEnvelope_HistoryPage{HistoryPage: &pb.HistoryPage{
		RequestId: requestID, LayoutEpoch: epoch, AsOfRevision: revision,
		FirstAvailableLineId: w.FirstAvailableLineID, HasMoreBefore: w.HasMoreBefore,
		Lines: encodeHistoryLines(w.Lines), Styles: encodeStyles(page.Styles), Links: encodeLinks(page.Links),
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

func encodeSnapshot(frame terminalengine.ScreenFrame) *pb.ScreenSnapshot {
	return &pb.ScreenSnapshot{
		SessionId:        frame.SessionID,
		InstanceId:       frame.InstanceID,
		LayoutEpoch:      frame.Epoch,
		ScreenRevision:   frame.Seq,
		Geometry:         encodeSize(frame.Rows, frame.Cols),
		ActiveBuffer:     encodeBufferKind(frame.ActiveBuffer),
		Cursor:           encodeCursor(frame.Cursor),
		Modes:            encodeModes(frame.Modes),
		Palette:          encodePalette(frame.DefaultFG, frame.DefaultBG, frame.CursorColor, frame.ReverseVideo),
		History:          encodeHistoryWindow(frame.History),
		Screen:           encodeScreenLines(frame.Screen),
		Styles:           encodeStyles(frame.Styles),
		Links:            encodeLinks(frame.Links),
		Title:            frame.Title,
		WorkingDirectory: frame.WorkingDir,
	}
}

func encodePatch(frame terminalengine.ScreenFrame) *pb.ScreenPatch {
	patch := &pb.ScreenPatch{
		InstanceId:     frame.InstanceID,
		LayoutEpoch:    frame.Epoch,
		BaseRevision:   frame.BaseRevision,
		ScreenRevision: frame.Seq,
		HistoryAppend:  encodeHistoryLines(frame.History.Lines),
		ScreenRows:     encodeScreenLines(frame.Screen),
		Cursor:         encodeCursor(frame.Cursor),
		Modes:          encodeModes(frame.Modes),
		Palette:        encodePalette(frame.DefaultFG, frame.DefaultBG, frame.CursorColor, frame.ReverseVideo),
		NewStyles:      encodeStyles(frame.Styles),
		NewLinks:       encodeLinks(frame.Links),
		PromotedRows:   encodePromotedRows(frame.PromotedRows),
	}
	// title/cwd 是 proto3 optional：只在显式变化标志置位时出现，
	// 以此在 wire 上区分“未变化”和“变为空串”。
	if frame.TitleChanged {
		patch.Title = proto.String(frame.Title)
	}
	if frame.WorkingDirChanged {
		patch.WorkingDirectory = proto.String(frame.WorkingDir)
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

func encodePalette(defaultFG, defaultBG, cursorColor terminalengine.Color, reverseVideo bool) *pb.TerminalPalette {
	return &pb.TerminalPalette{
		DefaultFg:    encodeColor(defaultFG),
		DefaultBg:    encodeColor(defaultBG),
		CursorColor:  encodeColor(cursorColor),
		ReverseVideo: reverseVideo,
	}
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

func encodeHistoryWindow(hw terminalengine.HistoryWindow) *pb.HistoryWindow {
	return &pb.HistoryWindow{
		FirstAvailableLineId: hw.FirstAvailableLineID,
		FirstIncludedLineId:  hw.FirstIncludedLineID,
		LastIncludedLineId:   hw.LastIncludedLineID,
		HasMoreBefore:        hw.HasMoreBefore,
		Lines:                encodeHistoryLines(hw.Lines),
	}
}

func encodeHistoryLines(lines []terminalengine.Line) []*pb.HistoryLine {
	out := make([]*pb.HistoryLine, len(lines))
	for i, line := range lines {
		out[i] = &pb.HistoryLine{
			Id:      line.ID,
			Wrapped: line.Wrapped,
			Runs:    encodeCellRuns(line.Runs),
		}
	}
	return out
}

func encodeScreenLines(lines []terminalengine.Line) []*pb.TerminalLine {
	out := make([]*pb.TerminalLine, len(lines))
	for i, line := range lines {
		out[i] = &pb.TerminalLine{
			Row:     int32(line.Row),
			Wrapped: line.Wrapped,
			Runs:    encodeCellRuns(line.Runs),
		}
	}
	return out
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

func encodePromotedRows(rows []terminalengine.PromotedRow) []*pb.PromotedRow {
	out := make([]*pb.PromotedRow, len(rows))
	for i, r := range rows {
		out[i] = &pb.PromotedRow{
			ScreenRow:     int32(r.ScreenRow),
			HistoryLineId: r.HistoryLineID,
		}
	}
	return out
}
