package screenprojection

import (
	"encoding/binary"
	"fmt"
	"testing"

	"google.golang.org/protobuf/encoding/protowire"
	"google.golang.org/protobuf/proto"
	pb "webterm/go-core/internal/screenprotocol/generatedv3"
	"webterm/go-core/internal/screenprotocolv3"
	"webterm/go-core/internal/terminalengine"
)

// BenchmarkProjectionPayloadBytes reports protobuf envelope sizes. These are
// not TLS, TCP, cellular, or total network-wire byte counts.
func BenchmarkProjectionPayloadBytes(b *testing.B) {
	asciiBaseline := payloadBaseline(24, 80, "user@host:~$ ")
	oneLine := payloadCommit(24, 80, payloadLine(100, 2, 23, 0, "echo hello"))
	color := payloadCommit(24, 80, payloadLine(100, 2, 23, 0, "user@host:~$ "))
	color.Styles = []terminalengine.TerminalStyle{{ID: 1}}
	cjk := payloadCommit(24, 80, payloadLine(100, 2, 23, 0, "你好，终端"))
	emoji := payloadCommit(24, 80, payloadLine(100, 2, 23, 0, "e\u0301 👩‍💻 🚀"))
	scrollOne := payloadCommit(24, 80, payloadLine(200, 1, 23, 0, "new tail"))
	scrollOne.ScreenScroll = &terminalengine.ScreenScroll{
		TopRow: 0, BottomRowExclusive: 24, DeltaRows: 1,
	}
	scroll100 := scrollOne
	scroll100.Seq = 101
	scroll100.BaseRevision = 1
	scroll100.History = payloadHistory(1, 64, "history")
	scroll100.FirstAvailableHistorySeqChanged = true
	promotion := scrollOne
	promotion.History = terminalengine.HistoryWindow{
		FirstAvailableHistorySeq: 1, LastIncludedHistorySeq: 1,
	}
	promotion.FirstAvailableHistorySeqChanged = true
	promotion.HistoryPushes = []terminalengine.HistoryPush{{
		LineID: 1, HistorySeq: 1,
	}}
	fullHistory := promotion
	fullHistory.History.Lines = []terminalengine.Line{payloadLine(1, 1, -1, 1, "existing body")}
	budget500 := scroll100
	budget500.History.LastIncludedHistorySeq = 500
	noDictionary := oneLine
	newStyle := oneLine
	newStyle.Styles = []terminalengine.TerminalStyle{{ID: 1}}
	activeBuffer := payloadCommit(24, 80)
	activeBuffer.ActiveBuffer = terminalengine.BufferAlternate
	activeBuffer.ActiveBufferChanged = true
	activeBuffer.Screen = asciiBaseline.Screen
	coldTail := asciiBaseline
	coldTail.History = payloadHistory(873, 1000, "cold")
	prompt80 := payloadCommit(24, 80,
		paddedPayloadLine(300, 2, 23, 80, "user@host:~$ ", false))
	command120 := payloadCommit(24, 120,
		paddedPayloadLine(301, 2, 23, 120, "git status --short", false))
	styledBlank80 := payloadCommit(24, 80,
		paddedPayloadLine(302, 2, 23, 80, "prompt", true))
	styledBlank80.Styles = []terminalengine.TerminalStyle{{ID: 1}}
	cjkEmoji80 := payloadCommit(24, 80,
		paddedPayloadLine(303, 2, 23, 80, "你好 👩‍💻 e\u0301", false))

	type benchmarkCase struct {
		name   string
		encode func() ([]byte, error)
	}
	cases := []benchmarkCase{
		{"01_80x24_ASCII_Baseline", func() ([]byte, error) { return screenprotocolv3.EncodeBaseline(asciiBaseline, 0) }},
		{"01a_Compact_80ASCII_Line", compactASCII80LinePayload},
		{"01b_LegacyCellRuns_80ASCII_Line", func() ([]byte, error) {
			return legacyCellRunsLinePayload(80), nil
		}},
		{"02_SingleLine_ASCII_Commit", func() ([]byte, error) { return screenprotocolv3.EncodeTerminalCommit(oneLine, 0) }},
		{"03_ColorPrompt_Commit", func() ([]byte, error) { return screenprotocolv3.EncodeTerminalCommit(color, 0) }},
		{"04_CJK_Commit", func() ([]byte, error) { return screenprotocolv3.EncodeTerminalCommit(cjk, 0) }},
		{"05_EmojiCombining_Commit", func() ([]byte, error) { return screenprotocolv3.EncodeTerminalCommit(emoji, 0) }},
		{"06_OneLineScroll_Commit", func() ([]byte, error) { return screenprotocolv3.EncodeTerminalCommit(scrollOne, 0) }},
		{"07_Scroll100_BudgetedCommit", func() ([]byte, error) { return screenprotocolv3.EncodeTerminalCommit(scroll100, 0) }},
		{"08a_HistoryPush", func() ([]byte, error) { return screenprotocolv3.EncodeTerminalCommit(promotion, 0) }},
		{"08b_FullScrollbackEntry", func() ([]byte, error) { return screenprotocolv3.EncodeTerminalCommit(fullHistory, 0) }},
		{"09_History500_BudgetedCommit", func() ([]byte, error) { return screenprotocolv3.EncodeTerminalCommit(budget500, 0) }},
		{"10_NoDictionaryAddition", func() ([]byte, error) { return screenprotocolv3.EncodeTerminalCommit(noDictionary, 0) }},
		{"11_NewStyleAddition", func() ([]byte, error) { return screenprotocolv3.EncodeTerminalCommit(newStyle, 0) }},
		{"12_UnchangedReconnect_ResumeAccepted", func() ([]byte, error) { return screenprotocolv3.EncodeResumeAccepted(asciiBaseline) }},
		{"13_OneLineReconnect_ResumeCommit", func() ([]byte, error) { return screenprotocolv3.EncodeTerminalCommit(oneLine, 0) }},
		{"14a_ActiveBuffer_Commit", func() ([]byte, error) { return screenprotocolv3.EncodeTerminalCommit(activeBuffer, 0) }},
		{"14b_ActiveBuffer_Baseline", func() ([]byte, error) { return screenprotocolv3.EncodeBaseline(asciiBaseline, 0) }},
		{"15a_MailboxOverflow_ResumeAccepted", func() ([]byte, error) { return screenprotocolv3.EncodeResumeAccepted(asciiBaseline) }},
		{"15b_MailboxOverflow_Baseline", func() ([]byte, error) { return screenprotocolv3.EncodeBaseline(asciiBaseline, 0) }},
		{"16_ColdBaselineTail", func() ([]byte, error) { return screenprotocolv3.EncodeBaseline(coldTail, 0) }},
		{"19a_Prompt80_UntrimmedBefore", func() ([]byte, error) {
			return encodeCommitWithUntrimmedLines(prompt80)
		}},
		{"19b_Prompt80_TrimmedAfter", func() ([]byte, error) {
			return screenprotocolv3.EncodeTerminalCommit(prompt80, 0)
		}},
		{"20a_Command120_UntrimmedBefore", func() ([]byte, error) {
			return encodeCommitWithUntrimmedLines(command120)
		}},
		{"20b_Command120_TrimmedAfter", func() ([]byte, error) {
			return screenprotocolv3.EncodeTerminalCommit(command120, 0)
		}},
		{"21a_StyledBlank80_UntrimmedBefore", func() ([]byte, error) {
			return encodeCommitWithUntrimmedLines(styledBlank80)
		}},
		{"21b_StyledBlank80_TrimmedAfter", func() ([]byte, error) {
			return screenprotocolv3.EncodeTerminalCommit(styledBlank80, 0)
		}},
		{"22a_CJKEmoji80_UntrimmedBefore", func() ([]byte, error) {
			return encodeCommitWithUntrimmedLines(cjkEmoji80)
		}},
		{"22b_CJKEmoji80_TrimmedAfter", func() ([]byte, error) {
			return screenprotocolv3.EncodeTerminalCommit(cjkEmoji80, 0)
		}},
	}
	for _, tc := range cases {
		b.Run(tc.name, func(b *testing.B) {
			payload, err := tc.encode()
			if err != nil {
				b.Fatal(err)
			}
			b.SetBytes(int64(len(payload)))
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				if _, err := tc.encode(); err != nil {
					b.Fatal(err)
				}
			}
			b.ReportMetric(float64(len(payload)), "protobuf_payload_bytes")
		})
	}
}

func paddedPayloadLine(
	id, version uint64, row, columns int, text string, styledTrailingBlank bool,
) terminalengine.Line {
	cells := make([]terminalengine.Cell, 0, columns)
	physicalColumns := 0
	for _, r := range []rune(text) {
		width := uint8(1)
		if r > 0x2fff {
			width = 2
		}
		cells = append(cells, terminalengine.Cell{Text: string(r), Width: width})
		physicalColumns += int(width)
	}
	for physicalColumns < columns {
		cell := terminalengine.Cell{Text: " ", Width: 1}
		if styledTrailingBlank && physicalColumns == columns-1 {
			cell.StyleID = 1
		}
		cells = append(cells, cell)
		physicalColumns++
	}
	return terminalengine.Line{
		ID: id, Version: version, Row: row,
		Runs: []terminalengine.CellRun{{Col: 0, Cells: cells}},
	}
}

// encodeCommitWithUntrimmedLines recreates the immediately previous compact
// encoder's behavior for benchmark comparison only: every default trailing
// blank contributes UTF-8 and glyph metadata.
func encodeCommitWithUntrimmedLines(frame terminalengine.ScreenFrame) ([]byte, error) {
	wire, err := screenprotocolv3.EncodeTerminalCommit(frame, 0)
	if err != nil {
		return nil, err
	}
	var envelope pb.ScreenEnvelope
	if err := proto.Unmarshal(wire, &envelope); err != nil {
		return nil, err
	}
	for i, line := range frame.Screen {
		if i >= len(envelope.GetTerminalCommit().GetBodyUpserts()) {
			envelope.GetTerminalCommit().BodyUpserts = append(
				envelope.GetTerminalCommit().BodyUpserts, untrimmedLineBodyRecord(line))
			continue
		}
		envelope.GetTerminalCommit().BodyUpserts[i] = untrimmedLineBodyRecord(line)
	}
	return proto.Marshal(&envelope)
}

func untrimmedLineBodyRecord(line terminalengine.Line) *pb.LineBodyRecord {
	result := &pb.LineBodyRecord{
		Key: &pb.LineKey{LineId: line.ID, BodyVersion: line.Version},
		Wrapped: line.Wrapped,
	}
	var open *pb.StyleSpan
	for _, run := range line.Runs {
		col := run.Col
		for _, cell := range run.Cells {
			width := int(cell.Width)
			if width != 2 {
				width = 1
			}
			text := cell.Text
			if text == "" {
				text = " "
			}
			encoded := []byte(text)
			result.Utf8Text = append(result.Utf8Text, encoded...)
			var scratch [binary.MaxVarintLen64]byte
			n := binary.PutUvarint(
				scratch[:], uint64(len(encoded))<<1|uint64(width-1))
			result.GlyphMeta = append(result.GlyphMeta, scratch[:n]...)
			if cell.StyleID != 0 || cell.LinkID != 0 {
				if open != nil && open.EndCol == int32(col) &&
					open.StyleId == cell.StyleID && open.LinkId == cell.LinkID {
					open.EndCol = int32(col + width)
				} else {
					open = &pb.StyleSpan{
						StartCol: int32(col), EndCol: int32(col + width),
						StyleId: cell.StyleID, LinkId: cell.LinkID,
					}
					result.StyleSpans = append(result.StyleSpans, open)
				}
			} else {
				open = nil
			}
			col += width
		}
	}
	return result
}

func compactASCII80LinePayload() ([]byte, error) {
	text := make([]byte, 80)
	meta := make([]byte, 80)
	for i := range text {
		text[i] = 'x'
		meta[i] = 2 // utf8 byte length 1, display width 1.
	}
	return proto.Marshal(&pb.LineBodyRecord{
		Key: &pb.LineKey{LineId: 1, BodyVersion: 1}, Utf8Text: text, GlyphMeta: meta,
	})
}

// Recreates legacy cell-runs wire layout for benchmark comparison only.
// enough to compare protobuf payload size. It is benchmark-only evidence, not
// a production compatibility encoder.
func legacyCellRunsLinePayload(columns int) []byte {
	var cells []byte
	for i := 0; i < columns; i++ {
		var cell []byte
		cell = protowire.AppendTag(cell, 1, protowire.BytesType)
		cell = protowire.AppendString(cell, "x")
		cell = protowire.AppendTag(cell, 2, protowire.VarintType)
		cell = protowire.AppendVarint(cell, 1)
		cells = protowire.AppendTag(cells, 2, protowire.BytesType)
		cells = protowire.AppendBytes(cells, cell)
	}
	var line []byte
	line = protowire.AppendTag(line, 1, protowire.VarintType)
	line = protowire.AppendVarint(line, 1)
	line = protowire.AppendTag(line, 2, protowire.VarintType)
	line = protowire.AppendVarint(line, 1)
	line = protowire.AppendTag(line, 6, protowire.BytesType)
	return protowire.AppendBytes(line, cells)
}

func payloadBaseline(rows, cols int, prefix string) terminalengine.ScreenFrame {
	frame := terminalengine.ScreenFrame{
		Version: 2, Kind: terminalengine.FrameSnapshot, SessionID: "session",
		InstanceID: "instance", Epoch: 1, Seq: 1, Rows: rows, Cols: cols,
		ActiveBuffer: terminalengine.BufferMain, DictionaryGeneration: 1,
		HistoryGeneration: 1,
	}
	for row := 0; row < rows; row++ {
		frame.Screen = append(frame.Screen,
			payloadLine(uint64(row+1), 1, row, 0, fmt.Sprintf("%s%02d", prefix, row)))
	}
	return frame
}

func payloadCommit(rows, cols int, lines ...terminalengine.Line) terminalengine.ScreenFrame {
	return terminalengine.ScreenFrame{
		Version: 2, Kind: terminalengine.FramePatch, InstanceID: "instance",
		Epoch: 1, BaseRevision: 1, Seq: 2, Rows: rows, Cols: cols,
		ActiveBuffer: terminalengine.BufferMain, Screen: lines,
		DictionaryGeneration: 1, HistoryGeneration: 1,
	}
}

func payloadHistory(first, last uint64, prefix string) terminalengine.HistoryWindow {
	window := terminalengine.HistoryWindow{
		FirstAvailableHistorySeq: first, FirstIncludedHistorySeq: first,
		LastIncludedHistorySeq: last,
	}
	for seq := first; seq <= last; seq++ {
		window.Lines = append(window.Lines,
			payloadLine(seq+10_000, 1, -1, seq, fmt.Sprintf("%s-%d", prefix, seq)))
	}
	return window
}

func payloadLine(id, version uint64, row int, historySeq uint64, text string) terminalengine.Line {
	cells := make([]terminalengine.Cell, 0, len([]rune(text)))
	for _, r := range []rune(text) {
		width := uint8(1)
		if r > 0x2fff {
			width = 2
		}
		cells = append(cells, terminalengine.Cell{Text: string(r), Width: width})
	}
	return terminalengine.Line{
		ID: id, Version: version, Row: row, HistorySeq: historySeq,
		Runs: []terminalengine.CellRun{{Col: 0, Cells: cells}},
	}
}
