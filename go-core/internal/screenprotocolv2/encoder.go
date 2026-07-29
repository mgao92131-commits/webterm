package screenprotocolv2

import (
	"encoding/binary"
	"fmt"

	"google.golang.org/protobuf/proto"

	pb "webterm/go-core/internal/screenprotocol/generatedv2"
	"webterm/go-core/internal/terminalengine"
)

const ProtocolVersion uint32 = 2

func EncodeBaseline(frame terminalengine.ScreenFrame, _ uint32) ([]byte, error) {
	if err := validateBaselineFrame(frame); err != nil {
		return nil, err
	}
	screen := encodeLines(screenLines(frame.Screen))
	baseline := &pb.Baseline{
		SessionId:            frame.SessionID,
		InstanceId:           frame.InstanceID,
		LayoutEpoch:          frame.Epoch,
		ScreenRevision:       frame.Seq,
		Geometry:             &pb.Geometry{Rows: int32(frame.Rows), Cols: int32(frame.Cols)},
		ActiveBuffer:         encodeBuffer(frame.ActiveBuffer),
		HistoryExtent:        encodeHistoryWindowExtent(frame.History),
		ScreenLayout:         &pb.ScreenLayout{LineIds: lineIDs(frame.Screen)},
		ScreenLines:          screen,
		Cursor:               encodeCursor(frame.Cursor),
		Modes:                encodeModes(frame.Modes),
		Palette:              encodePalette(frame),
		Dictionary:           encodeDictionary(frame.Styles, frame.Links),
		DictionaryGeneration: frame.DictionaryGeneration,
		HistoryGeneration:    frame.HistoryGeneration,
	}
	for _, binding := range frame.ScrollbackLineage {
		if binding.HistorySeq < baseline.HistoryExtent.FirstSeq ||
			binding.HistorySeq > baseline.HistoryExtent.LastSeq {
			continue
		}
		baseline.HistoryBindings = append(baseline.HistoryBindings, &pb.HistoryPush{
			HistorySeq:  binding.HistorySeq,
			LineId:      binding.LineID,
			LineVersion: binding.LineVersion,
		})
	}
	expectedBindings := uint64(0)
	if baseline.HistoryExtent.LastSeq >= baseline.HistoryExtent.FirstSeq {
		expectedBindings = baseline.HistoryExtent.LastSeq - baseline.HistoryExtent.FirstSeq + 1
	}
	if uint64(len(baseline.HistoryBindings)) != expectedBindings {
		return nil, fmt.Errorf(
			"incomplete baseline history catalog: got %d bindings, want %d",
			len(baseline.HistoryBindings), expectedBindings)
	}
	return marshalPayload(&pb.ScreenEnvelope_Baseline{Baseline: baseline})
}

func EncodeTerminalCommit(frame terminalengine.ScreenFrame, _ uint64) ([]byte, error) {
	if frame.Kind != terminalengine.FramePatch {
		return nil, fmt.Errorf("terminal commit requires patch frame")
	}
	commit := &pb.TerminalCommit{
		InstanceId:           frame.InstanceID,
		LayoutEpoch:          frame.Epoch,
		BaseRevision:         frame.BaseRevision,
		Revision:             frame.Seq,
		DictionaryGeneration: frame.DictionaryGeneration,
		HistoryGeneration:    frame.HistoryGeneration,
	}
	if frame.ScreenScroll != nil || len(frame.Screen) > 0 {
		mutation := &pb.ScreenMutation{}
		if frame.ScreenScroll != nil {
			mutation.Scroll = &pb.ScreenScroll{
				TopRow:             int32(frame.ScreenScroll.TopRow),
				BottomRowExclusive: int32(frame.ScreenScroll.BottomRowExclusive),
				DeltaRows:          int32(frame.ScreenScroll.DeltaRows),
			}
		}
		for _, line := range frame.Screen {
			if line.HistorySeq != 0 || line.Row < 0 || line.Row >= frame.Rows {
				return nil, fmt.Errorf("invalid commit screen row")
			}
			mutation.Writes = append(mutation.Writes, &pb.ScreenRowWrite{
				Row: int32(line.Row), Line: encodeLines([]terminalengine.Line{line})[0],
			})
		}
		commit.Screen = mutation
	}
	if frame.FirstAvailableHistorySeqChanged || len(frame.HistoryPushes) > 0 {
		commit.History = &pb.HistoryMutation{
			FinalExtent: &pb.HistoryExtent{
				FirstSeq: canonicalHistoryFirst(frame.History.FirstAvailableHistorySeq),
				LastSeq:  frame.History.LastIncludedHistorySeq,
			},
		}
		for _, push := range frame.HistoryPushes {
			commit.History.Pushes = append(commit.History.Pushes, &pb.HistoryPush{
				HistorySeq: push.HistorySeq, LineId: push.LineID,
				LineVersion: push.LineVersion,
			})
		}
	}
	if frame.CursorChanged {
		commit.Cursor = encodeCursor(frame.Cursor)
	}
	if frame.ModesChanged {
		commit.Modes = encodeModes(frame.Modes)
	}
	if frame.PaletteChanged {
		commit.Palette = encodePalette(frame)
	}
	commit.DictionaryAdditions = encodeDictionary(frame.Styles, frame.Links)
	if frame.ActiveBufferChanged {
		buffer := encodeBuffer(frame.ActiveBuffer)
		commit.ActiveBuffer = &buffer
	}
	return marshalPayload(&pb.ScreenEnvelope_TerminalCommit{TerminalCommit: commit})
}

func canonicalHistoryFirst(first uint64) uint64 {
	if first == 0 {
		return 1
	}
	return first
}

func EncodePong(revision uint64) ([]byte, error) {
	return marshalPayload(&pb.ScreenEnvelope_Pong{Pong: &pb.Pong{ScreenRevision: revision}})
}

func EncodeResumeAccepted(frame terminalengine.ScreenFrame) ([]byte, error) {
	return marshalPayload(&pb.ScreenEnvelope_ResumeAccepted{ResumeAccepted: &pb.ResumeAccepted{
		InstanceId: frame.InstanceID, LayoutEpoch: frame.Epoch, ScreenRevision: frame.Seq,
		DictionaryGeneration: frame.DictionaryGeneration,
		HistoryGeneration:    frame.HistoryGeneration,
		HistoryExtent:        encodeHistoryWindowExtent(frame.History),
	}})
}

// oneof 包装类型不能直接实现本地接口，下面的 marshalPayload 负责类型分派。
func marshalPayload(payload any) ([]byte, error) {
	env := &pb.ScreenEnvelope{ProtocolVersion: ProtocolVersion}
	switch p := payload.(type) {
	case *pb.ScreenEnvelope_Baseline:
		env.Payload = p
	case *pb.ScreenEnvelope_TerminalCommit:
		env.Payload = p
	case *pb.ScreenEnvelope_ResumeAccepted:
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

func encodeLines(lines []terminalengine.Line) []*pb.LineData {
	out := make([]*pb.LineData, len(lines))
	for i, line := range lines {
		out[i] = encodeLine(line)
	}
	return out
}

func encodeLine(line terminalengine.Line) *pb.LineData {
	wire := &pb.LineData{LineId: line.ID, LineVersion: line.Version,
		Wrapped: line.Wrapped, HistorySeq: line.HistorySeq,
		PhysicalColumns: uint32(line.PhysicalColumns)}
	type positionedCell struct {
		col  int
		cell terminalengine.Cell
	}
	var cells []positionedCell
	lastCol := 0
	for _, run := range line.Runs {
		col := run.Col
		for _, cell := range run.Cells {
			width := int(cell.Width)
			if width != 2 {
				width = 1
			}
			cells = append(cells, positionedCell{col: col, cell: cell})
			col += width
			if !isDefaultTrailingCell(cell) && col > lastCol {
				lastCol = col
			}
		}
	}
	byCol := make(map[int]terminalengine.Cell, len(cells))
	for _, item := range cells {
		byCol[item.col] = item.cell
	}
	var span *pb.StyleSpan
	for col := 0; col < lastCol; {
		cell, ok := byCol[col]
		if !ok {
			cell = terminalengine.Cell{Text: " ", Width: 1}
		}
		width := int(cell.Width)
		if width != 2 {
			width = 1
		}
		text := cell.Text
		if text == "" {
			text = " "
		}
		encoded := []byte(text)
		wire.Utf8Text = append(wire.Utf8Text, encoded...)
		var scratch [binary.MaxVarintLen64]byte
		n := binary.PutUvarint(scratch[:], uint64(len(encoded))<<1|uint64(width-1))
		wire.GlyphMeta = append(wire.GlyphMeta, scratch[:n]...)
		if cell.StyleID != 0 || cell.LinkID != 0 {
			if span != nil && span.EndCol == int32(col) && span.StyleId == cell.StyleID && span.LinkId == cell.LinkID {
				span.EndCol = int32(col + width)
			} else {
				span = &pb.StyleSpan{StartCol: int32(col), EndCol: int32(col + width), StyleId: cell.StyleID, LinkId: cell.LinkID}
				wire.StyleSpans = append(wire.StyleSpans, span)
			}
		} else {
			span = nil
		}
		col += width
	}
	return wire
}

func isDefaultTrailingCell(cell terminalengine.Cell) bool {
	return cell.Width != 2 &&
		(cell.Text == "" || cell.Text == " ") &&
		cell.StyleID == 0 && cell.LinkID == 0
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
