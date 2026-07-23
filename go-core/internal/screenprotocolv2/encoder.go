package screenprotocolv2

import (
	"fmt"

	"google.golang.org/protobuf/proto"

	pb "webterm/go-core/internal/screenprotocol/generatedv2"
	"webterm/go-core/internal/terminalengine"
)

const ProtocolVersion uint32 = 2

func EncodeBaseline(frame terminalengine.ScreenFrame, generation uint64) ([]byte, error) {
	screen := encodeLines(screenLines(frame.Screen))
	history := encodeLines(historyLines(frame.History.Lines))
	baseline := &pb.Baseline{
		SessionId:        frame.SessionID,
		InstanceId:       frame.InstanceID,
		LayoutEpoch:      frame.Epoch,
		ScreenRevision:   frame.Seq,
		StreamGeneration: generation,
		Geometry:         &pb.Geometry{Rows: int32(frame.Rows), Cols: int32(frame.Cols)},
		ActiveBuffer:     encodeBuffer(frame.ActiveBuffer),
		HistoryExtent:    encodeHistoryWindowExtent(frame.History),
		HistoryTail: &pb.HistoryTail{
			Extent: encodeHistoryWindowExtent(frame.History),
			Lines:  history,
		},
		ScreenLayout:     &pb.ScreenLayout{LineIds: lineIDs(frame.Screen)},
		ScreenLines:      screen,
		Cursor:           encodeCursor(frame.Cursor),
		Modes:            encodeModes(frame.Modes),
		Palette:          encodePalette(frame),
		Dictionary:       encodeDictionary(frame.Styles, frame.Links),
		Title:            proto.String(frame.Title),
		WorkingDirectory: proto.String(frame.WorkingDir),
	}
	return marshalPayload(&pb.ScreenEnvelope_Baseline{Baseline: baseline})
}

func EncodeScreenPatch(frame terminalengine.ScreenFrame, generation uint64) ([]byte, error) {
	if frame.Kind != terminalengine.FramePatch {
		return nil, fmt.Errorf("screen patch requires patch frame")
	}
	updates := make([]terminalengine.Line, 0, len(frame.Screen))
	for _, line := range frame.Screen {
		if line.HistorySeq == 0 {
			updates = append(updates, line)
		}
	}
	patch := &pb.ScreenPatch{
		InstanceId:         frame.InstanceID,
		LayoutEpoch:        frame.Epoch,
		StreamGeneration:   generation,
		BaseScreenRevision: frame.BaseRevision,
		ScreenRevision:     frame.Seq,
		ScreenLineUpdates:  encodeLines(updates),
		Dictionary:         encodeDictionaryForLines(updates, frame.Styles, frame.Links),
	}
	if frame.Layout != nil {
		patch.ScreenLayout = &pb.ScreenLayout{LineIds: append([]uint64(nil), frame.Layout...)}
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
	if frame.TitleChanged {
		patch.Title = proto.String(frame.Title)
	}
	if frame.WorkingDirChanged {
		patch.WorkingDirectory = proto.String(frame.WorkingDir)
	}
	return marshalPayload(&pb.ScreenEnvelope_ScreenPatch{ScreenPatch: patch})
}

func EncodeHistoryDelta(state terminalengine.ScreenFrame, generation uint64) ([]byte, error) {
	delta := &pb.HistoryDelta{
		InstanceId:       state.InstanceID,
		LayoutEpoch:      state.Epoch,
		StreamGeneration: generation,
		AvailableExtent:  encodeHistoryWindowExtent(state.History),
		Lines:            encodeLines(historyLines(state.History.Lines)),
		Dictionary: encodeDictionaryForLines(
			historyLines(state.History.Lines), state.Styles, state.Links),
	}
	return marshalPayload(&pb.ScreenEnvelope_HistoryDelta{HistoryDelta: delta})
}

func EncodeHistoryRangeResponse(
	requestID, instanceID string,
	epoch uint64,
	data terminalengine.HistoryRangeData,
) ([]byte, error) {
	status := pb.HistoryRangeStatus_HISTORY_RANGE_STATUS_OK
	switch data.Status {
	case terminalengine.HistoryRangeTrimmed:
		status = pb.HistoryRangeStatus_HISTORY_RANGE_STATUS_TRIMMED
	case terminalengine.HistoryRangeRetryable:
		status = pb.HistoryRangeStatus_HISTORY_RANGE_STATUS_RETRYABLE
	}
	response := &pb.HistoryRangeResponse{
		RequestId:       requestID,
		InstanceId:      instanceID,
		LayoutEpoch:     epoch,
		Status:          status,
		AvailableExtent: encodeExtent(data.Extent),
		Lines:           encodeLines(historyLines(data.Lines)),
		Dictionary: encodeDictionaryForLines(
			historyLines(data.Lines), data.Styles, data.Links),
		RetryAfterMs: data.RetryAfterMS,
	}
	return marshalPayload(&pb.ScreenEnvelope_HistoryRangeResponse{HistoryRangeResponse: response})
}

func EncodeStaleHistoryRange(
	requestID, instanceID string, epoch uint64, extent terminalengine.HistoryExtent,
) ([]byte, error) {
	return marshalPayload(&pb.ScreenEnvelope_HistoryRangeResponse{
		HistoryRangeResponse: &pb.HistoryRangeResponse{
			RequestId:       requestID,
			InstanceId:      instanceID,
			LayoutEpoch:     epoch,
			Status:          pb.HistoryRangeStatus_HISTORY_RANGE_STATUS_STALE_PROJECTION,
			AvailableExtent: encodeExtent(extent),
		},
	})
}

func EncodeTailStatus(
	instanceID string, epoch, generation, revision uint64,
	extent terminalengine.HistoryExtent, exited bool, exitCode int,
) ([]byte, error) {
	return marshalPayload(&pb.ScreenEnvelope_TailStatus{TailStatus: &pb.TailStatus{
		InstanceId:           instanceID,
		LayoutEpoch:          epoch,
		StreamGeneration:     generation,
		LatestScreenRevision: revision,
		LatestHistoryExtent:  encodeExtent(extent),
		Exited:               exited,
		ExitCode:             int32(exitCode),
	}})
}

func EncodePong(revision uint64) ([]byte, error) {
	return marshalPayload(&pb.ScreenEnvelope_Pong{Pong: &pb.Pong{ScreenRevision: revision}})
}

// oneof 包装类型不能直接实现本地接口，下面的 marshalPayload 负责类型分派。
func marshalPayload(payload any) ([]byte, error) {
	env := &pb.ScreenEnvelope{ProtocolVersion: ProtocolVersion}
	switch p := payload.(type) {
	case *pb.ScreenEnvelope_Baseline:
		env.Payload = p
	case *pb.ScreenEnvelope_ScreenPatch:
		env.Payload = p
	case *pb.ScreenEnvelope_HistoryDelta:
		env.Payload = p
	case *pb.ScreenEnvelope_HistoryRangeResponse:
		env.Payload = p
	case *pb.ScreenEnvelope_TailStatus:
		env.Payload = p
	case *pb.ScreenEnvelope_Pong:
		env.Payload = p
	case *pb.ScreenEnvelope_Effect:
		env.Payload = p
	default:
		return nil, fmt.Errorf("unsupported v2 payload %T", payload)
	}
	return proto.Marshal(env)
}

func EncodeEffect(instanceID string, revision uint64, effect terminalengine.Effect) ([]byte, error) {
	wire := &pb.TerminalEffect{InstanceId: instanceID, ScreenRevision: revision}
	switch effect.Kind {
	case terminalengine.EffectBell:
		wire.Effect = &pb.TerminalEffect_Bell{Bell: &pb.Bell{}}
	case terminalengine.EffectTitle:
		wire.Effect = &pb.TerminalEffect_Title{Title: &pb.TitleChanged{Title: effect.Text}}
	case terminalengine.EffectWorkingDirectory:
		wire.Effect = &pb.TerminalEffect_Cwd{Cwd: &pb.WorkingDirectoryChanged{Path: effect.Text}}
	case terminalengine.EffectClipboardRead:
		wire.Effect = &pb.TerminalEffect_ClipboardRead{ClipboardRead: &pb.ClipboardReadRequest{
			RequestId: effect.RequestID, Clipboard: effect.Clipboard,
		}}
	case terminalengine.EffectClipboardWrite:
		wire.Effect = &pb.TerminalEffect_ClipboardWrite{ClipboardWrite: &pb.ClipboardWriteRequest{
			RequestId: effect.RequestID, Clipboard: effect.Clipboard, Data: effect.Data,
		}}
	default:
		return nil, fmt.Errorf("unsupported terminal effect: %d", effect.Kind)
	}
	return marshalPayload(&pb.ScreenEnvelope_Effect{Effect: wire})
}

func encodeHistoryWindowExtent(window terminalengine.HistoryWindow) *pb.HistoryExtent {
	first := window.FirstAvailableHistorySeq
	if first == 0 {
		first = 1
	}
	last := window.LastIncludedHistorySeq
	if len(window.Lines) > 0 {
		last = window.Lines[len(window.Lines)-1].HistorySeq
	} else if last >= first {
		last = first - 1
	}
	return &pb.HistoryExtent{FirstSeq: first, LastSeq: last}
}

func encodeExtent(extent terminalengine.HistoryExtent) *pb.HistoryExtent {
	return &pb.HistoryExtent{FirstSeq: extent.FirstSeq, LastSeq: extent.LastSeq}
}

func screenLines(lines []terminalengine.Line) []terminalengine.Line {
	out := make([]terminalengine.Line, 0, len(lines))
	for _, line := range lines {
		if line.HistorySeq == 0 {
			out = append(out, line)
		}
	}
	return out
}

func historyLines(lines []terminalengine.Line) []terminalengine.Line {
	out := make([]terminalengine.Line, 0, len(lines))
	for _, line := range lines {
		if line.HistorySeq != 0 {
			out = append(out, line)
		}
	}
	return out
}

func encodeLines(lines []terminalengine.Line) []*pb.LineData {
	out := make([]*pb.LineData, len(lines))
	for i, line := range lines {
		out[i] = &pb.LineData{
			LineId:      line.ID,
			LineVersion: line.Version,
			Wrapped:     line.Wrapped,
			HistorySeq:  line.HistorySeq,
			Runs:        encodeRuns(line.Runs),
		}
	}
	return out
}

func encodeRuns(runs []terminalengine.CellRun) []*pb.CellRun {
	out := make([]*pb.CellRun, len(runs))
	for i, run := range runs {
		cells := make([]*pb.Cell, len(run.Cells))
		for j, cell := range run.Cells {
			cells[j] = &pb.Cell{
				Text: cell.Text, Width: uint32(cell.Width),
				StyleId: cell.StyleID, LinkId: cell.LinkID,
			}
		}
		out[i] = &pb.CellRun{Col: int32(run.Col), Cells: cells}
	}
	return out
}

func lineIDs(lines []terminalengine.Line) []uint64 {
	out := make([]uint64, len(lines))
	for i, line := range lines {
		out[i] = line.ID
	}
	return out
}

func encodeDictionary(styles []terminalengine.TerminalStyle, links []terminalengine.Hyperlink) *pb.Dictionary {
	dict := &pb.Dictionary{}
	for _, style := range styles {
		dict.Styles = append(dict.Styles, &pb.TerminalStyle{
			Id: style.ID, Fg: encodeColor(style.FG), Bg: encodeColor(style.BG),
			UnderlineColor: encodeColor(style.ULColor), Attrs: encodeAttrs(style.Attrs),
		})
	}
	for _, link := range links {
		dict.Links = append(dict.Links, &pb.Hyperlink{Id: link.ID, Uri: link.URI})
	}
	return dict
}

// v2 的每个携带 LineData 的消息都必须能独立解析，不能依赖前一帧字典。
// 这里只复制本消息实际引用的条目，避免把整个会话字典重复塞进每个 Patch。
func encodeDictionaryForLines(
	lines []terminalengine.Line,
	styles []terminalengine.TerminalStyle,
	links []terminalengine.Hyperlink,
) *pb.Dictionary {
	styleIDs := make(map[uint32]struct{})
	linkIDs := make(map[uint32]struct{})
	for _, line := range lines {
		for _, run := range line.Runs {
			for _, cell := range run.Cells {
				if cell.StyleID != 0 {
					styleIDs[cell.StyleID] = struct{}{}
				}
				if cell.LinkID != 0 {
					linkIDs[cell.LinkID] = struct{}{}
				}
			}
		}
	}
	selectedStyles := make([]terminalengine.TerminalStyle, 0, len(styleIDs))
	for _, style := range styles {
		if _, ok := styleIDs[style.ID]; ok {
			selectedStyles = append(selectedStyles, style)
		}
	}
	selectedLinks := make([]terminalengine.Hyperlink, 0, len(linkIDs))
	for _, link := range links {
		if _, ok := linkIDs[link.ID]; ok {
			selectedLinks = append(selectedLinks, link)
		}
	}
	return encodeDictionary(selectedStyles, selectedLinks)
}

func encodeAttrs(a terminalengine.CellAttrs) *pb.CellAttrs {
	return &pb.CellAttrs{
		Bold: a.Bold, Dim: a.Dim, Italic: a.Italic, Underline: a.Underline,
		DoubleUnderline: a.DoubleUnderline, CurlyUnderline: a.CurlyUnderline,
		DottedUnderline: a.DottedUnderline, DashedUnderline: a.DashedUnderline,
		BlinkSlow: a.BlinkSlow, BlinkFast: a.BlinkFast, Reverse: a.Reverse,
		Hidden: a.Hidden, Strike: a.Strike,
	}
}

func encodeColor(c terminalengine.Color) *pb.Color {
	kind := pb.ColorKind_COLOR_KIND_DEFAULT_FG
	switch c.Kind {
	case terminalengine.ColorDefaultBG:
		kind = pb.ColorKind_COLOR_KIND_DEFAULT_BG
	case terminalengine.ColorCursor:
		kind = pb.ColorKind_COLOR_KIND_CURSOR
	case terminalengine.ColorIndexed:
		kind = pb.ColorKind_COLOR_KIND_INDEXED
	case terminalengine.ColorRGB:
		kind = pb.ColorKind_COLOR_KIND_RGB
	}
	return &pb.Color{Kind: kind, Index: int32(c.Index), Rgb: c.RGB}
}

func encodeBuffer(buffer terminalengine.BufferKind) pb.BufferKind {
	if buffer == terminalengine.BufferAlternate {
		return pb.BufferKind_BUFFER_KIND_ALTERNATE
	}
	return pb.BufferKind_BUFFER_KIND_MAIN
}

func encodeCursor(c terminalengine.Cursor) *pb.Cursor {
	shape := pb.CursorShape_CURSOR_SHAPE_BLOCK
	if c.Shape == terminalengine.CursorBar {
		shape = pb.CursorShape_CURSOR_SHAPE_BAR
	} else if c.Shape == terminalengine.CursorUnderline {
		shape = pb.CursorShape_CURSOR_SHAPE_UNDERLINE
	}
	return &pb.Cursor{Row: int32(c.Row), Col: int32(c.Col), Visible: c.Visible, Shape: shape, Blink: c.Blink}
}

func encodeModes(m terminalengine.Modes) *pb.Modes {
	tracking := pb.MouseTracking_MOUSE_TRACKING_NONE
	switch m.MouseTracking {
	case terminalengine.MouseX10:
		tracking = pb.MouseTracking_MOUSE_TRACKING_X10
	case terminalengine.MouseVT200:
		tracking = pb.MouseTracking_MOUSE_TRACKING_VT200
	case terminalengine.MouseVT200Highlight:
		tracking = pb.MouseTracking_MOUSE_TRACKING_VT200_HIGHLIGHT
	case terminalengine.MouseButtonEvent:
		tracking = pb.MouseTracking_MOUSE_TRACKING_BUTTON_EVENT
	case terminalengine.MouseAnyEvent:
		tracking = pb.MouseTracking_MOUSE_TRACKING_ANY_EVENT
	case terminalengine.MouseSGRPixels:
		tracking = pb.MouseTracking_MOUSE_TRACKING_SGR_PIXELS
	}
	encoding := pb.MouseEncoding_MOUSE_ENCODING_X10
	switch m.MouseEncoding {
	case terminalengine.MouseEncodingUTF8:
		encoding = pb.MouseEncoding_MOUSE_ENCODING_UTF8
	case terminalengine.MouseEncodingSGR:
		encoding = pb.MouseEncoding_MOUSE_ENCODING_SGR
	case terminalengine.MouseEncodingURXVT:
		encoding = pb.MouseEncoding_MOUSE_ENCODING_URXVT
	}
	return &pb.Modes{
		ApplicationCursor: m.ApplicationCursor, ApplicationKeypad: m.ApplicationKeypad,
		BracketedPaste: m.BracketedPaste, MouseTracking: tracking,
		MouseEncoding: encoding, FocusReporting: m.FocusReporting,
	}
}

func encodePalette(frame terminalengine.ScreenFrame) *pb.TerminalPalette {
	p := &pb.TerminalPalette{
		DefaultFg: encodeColor(frame.DefaultFG), DefaultBg: encodeColor(frame.DefaultBG),
		CursorColor: encodeColor(frame.CursorColor), ReverseVideo: frame.ReverseVideo,
		Generation: frame.PaletteGeneration,
	}
	for index, rgb := range frame.IndexedPalette {
		if frame.IndexedPaletteSet[index/64]&(uint64(1)<<uint(index%64)) != 0 {
			p.IndexedColors = append(p.IndexedColors, &pb.IndexedPaletteColor{Index: int32(index), Rgb: rgb})
		}
	}
	return p
}
