package screenprojection

import (
	"fmt"
	"testing"

	"google.golang.org/protobuf/encoding/protowire"
	"google.golang.org/protobuf/proto"
	pb "webterm/go-core/internal/screenprotocol/generatedv2"
	"webterm/go-core/internal/screenprotocolv2"
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
	promotion.HistoryPromotions = []terminalengine.HistoryPromotion{{
		LineID: 1, LineVersion: 1, HistorySeq: 1,
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
	preserve := coldTail
	preserve.PreserveCompatibleHistory = true
	historyRange := terminalengine.HistoryRangeData{
		Status: terminalengine.HistoryRangeOK, Extent: terminalengine.HistoryExtent{FirstSeq: 1, LastSeq: 32},
		Lines: payloadHistory(1, 32, "page").Lines, HistoryGeneration: 1,
	}

	type benchmarkCase struct {
		name   string
		encode func() ([]byte, error)
	}
	cases := []benchmarkCase{
		{"01_80x24_ASCII_Baseline", func() ([]byte, error) { return screenprotocolv2.EncodeBaseline(asciiBaseline, 0) }},
		{"01a_Compact_80ASCII_Line", compactASCII80LinePayload},
		{"01b_LegacyCellRuns_80ASCII_Line", func() ([]byte, error) {
			return legacyCellRunsLinePayload(80), nil
		}},
		{"02_SingleLine_ASCII_Commit", func() ([]byte, error) { return screenprotocolv2.EncodeTerminalCommit(oneLine, 0) }},
		{"03_ColorPrompt_Commit", func() ([]byte, error) { return screenprotocolv2.EncodeTerminalCommit(color, 0) }},
		{"04_CJK_Commit", func() ([]byte, error) { return screenprotocolv2.EncodeTerminalCommit(cjk, 0) }},
		{"05_EmojiCombining_Commit", func() ([]byte, error) { return screenprotocolv2.EncodeTerminalCommit(emoji, 0) }},
		{"06_OneLineScroll_Commit", func() ([]byte, error) { return screenprotocolv2.EncodeTerminalCommit(scrollOne, 0) }},
		{"07_Scroll100_BudgetedCommit", func() ([]byte, error) { return screenprotocolv2.EncodeTerminalCommit(scroll100, 0) }},
		{"08a_HistoryPromotion", func() ([]byte, error) { return screenprotocolv2.EncodeTerminalCommit(promotion, 0) }},
		{"08b_FullScrollbackEntry", func() ([]byte, error) { return screenprotocolv2.EncodeTerminalCommit(fullHistory, 0) }},
		{"09_History500_BudgetedCommit", func() ([]byte, error) { return screenprotocolv2.EncodeTerminalCommit(budget500, 0) }},
		{"10_NoDictionaryAddition", func() ([]byte, error) { return screenprotocolv2.EncodeTerminalCommit(noDictionary, 0) }},
		{"11_NewStyleAddition", func() ([]byte, error) { return screenprotocolv2.EncodeTerminalCommit(newStyle, 0) }},
		{"12_UnchangedReconnect_ResumeAccepted", func() ([]byte, error) { return screenprotocolv2.EncodeResumeAccepted(asciiBaseline) }},
		{"13_OneLineReconnect_ResumeCommit", func() ([]byte, error) { return screenprotocolv2.EncodeTerminalCommit(oneLine, 0) }},
		{"14a_ActiveBuffer_Commit", func() ([]byte, error) { return screenprotocolv2.EncodeTerminalCommit(activeBuffer, 0) }},
		{"14b_ActiveBuffer_Baseline", func() ([]byte, error) { return screenprotocolv2.EncodeBaseline(asciiBaseline, 0) }},
		{"15a_MailboxOverflow_ResumeAccepted", func() ([]byte, error) { return screenprotocolv2.EncodeResumeAccepted(asciiBaseline) }},
		{"15b_MailboxOverflow_Baseline", func() ([]byte, error) { return screenprotocolv2.EncodeBaseline(asciiBaseline, 0) }},
		{"16_ColdBaselineTail", func() ([]byte, error) { return screenprotocolv2.EncodeBaseline(coldTail, 0) }},
		{"17_PreserveCompatibleBaseline", func() ([]byte, error) { return screenprotocolv2.EncodeBaseline(preserve, 0) }},
		{"18_HistoryRangeLocalDictionary", func() ([]byte, error) {
			return screenprotocolv2.EncodeHistoryRangeResponse("r", "instance", 1, historyRange)
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

func compactASCII80LinePayload() ([]byte, error) {
	text := make([]byte, 80)
	meta := make([]byte, 80)
	for i := range text {
		text[i] = 'x'
		meta[i] = 2 // utf8 byte length 1, display width 1.
	}
	return proto.Marshal(&pb.LineData{
		LineId: 1, LineVersion: 1, Utf8Text: text, GlyphMeta: meta,
	})
}

// Recreates the removed v2 LineData.runs/CellRun/Cell wire layout exactly
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
