package screenprotocol

import (
	"strings"
	"testing"

	"google.golang.org/protobuf/proto"
	"webterm/go-core/internal/screenprojection"
	pb "webterm/go-core/internal/screenprotocol/generated"
	"webterm/go-core/internal/terminalengine"
)

func TestEncodePatch_HistoryWatermarkPresence(t *testing.T) {
	frame := terminalengine.ScreenFrame{
		Kind:         terminalengine.FramePatch,
		InstanceID:   "i1",
		Epoch:        1,
		BaseRevision: 1,
		Seq:          2,
		History: terminalengine.HistoryWindow{
			FirstAvailableLineID: 42,
		},
	}

	encoded, err := EncodeFrame(frame)
	if err != nil {
		t.Fatal(err)
	}
	var envelope pb.ScreenEnvelope
	if err := proto.Unmarshal(encoded, &envelope); err != nil {
		t.Fatal(err)
	}
	if envelope.GetPatch().HistoryTrimBeforeId != nil {
		t.Fatal("watermark must be absent without explicit presence flag")
	}

	frame.FirstAvailableHistoryLineIDChanged = true
	encoded, err = EncodeFrame(frame)
	if err != nil {
		t.Fatal(err)
	}
	envelope.Reset()
	if err := proto.Unmarshal(encoded, &envelope); err != nil {
		t.Fatal(err)
	}
	if envelope.GetPatch().HistoryTrimBeforeId == nil || envelope.GetPatch().GetHistoryTrimBeforeId() != 42 {
		t.Fatalf("watermark=%v, want present 42", envelope.GetPatch().HistoryTrimBeforeId)
	}
}

func TestEncodeFrame_SnapshotRoundTrip(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(5, 10, sb)
	engine.Write([]byte("hello\nworld\n"))

	frame := screenprojection.ExportSnapshot(engine, sb, "s1", "i1", 0, 1)
	data, err := EncodeFrame(frame)
	if err != nil {
		t.Fatal(err)
	}

	var envelope pb.ScreenEnvelope
	if err := proto.Unmarshal(data, &envelope); err != nil {
		t.Fatal(err)
	}

	snapshot, ok := envelope.Payload.(*pb.ScreenEnvelope_Snapshot)
	if !ok {
		t.Fatalf("expected snapshot, got %T", envelope.Payload)
	}
	if snapshot.Snapshot.SessionId != "s1" {
		t.Fatalf("session id mismatch")
	}
	if len(snapshot.Snapshot.ScreenLines) != 5 {
		t.Fatalf("expected 5 rows, got %d", len(snapshot.Snapshot.ScreenLines))
	}
}

func TestEncodeFrameWithCompactLines_UsesUTF8TextMetadataAndSpans(t *testing.T) {
	base := terminalengine.ScreenFrame{
		Kind: terminalengine.FrameSnapshot, SessionID: "s1", InstanceID: "i1", Epoch: 1, Seq: 1,
		Rows: 5, Cols: 10,
		Screen: []terminalengine.Line{{ID: 1, Version: 1, Row: 0, Runs: []terminalengine.CellRun{{Col: 0,
			Cells: []terminalengine.Cell{{Text: "a", Width: 1}, {Text: "b", Width: 1, StyleID: 7}}}}}},
	}
	data, err := EncodeFrameWithCompactLines(base, true)
	if err != nil {
		t.Fatal(err)
	}
	var envelope pb.ScreenEnvelope
	if err := proto.Unmarshal(data, &envelope); err != nil {
		t.Fatal(err)
	}
	line := envelope.GetSnapshot().GetScreenLines()[0]
	if line.GetText() != "ab" || string(line.GetCellMeta()) != "\x01\x01" || len(line.GetRuns()) != 0 {
		t.Fatalf("compact line=%+v, want text-only encoding", line)
	}
	if len(line.GetStyleSpans()) != 1 || line.GetStyleSpans()[0].GetStartCol() != 1 {
		t.Fatalf("compact style spans=%+v", line.GetStyleSpans())
	}

	base.Screen[0].Runs[0].Cells = []terminalengine.Cell{{Text: " ", Width: 1}, {Text: " ", Width: 1}}
	data, err = EncodeFrameWithCompactLines(base, true)
	if err != nil {
		t.Fatal(err)
	}
	envelope.Reset()
	if err := proto.Unmarshal(data, &envelope); err != nil {
		t.Fatal(err)
	}
	line = envelope.GetSnapshot().GetScreenLines()[0]
	if line.GetText() != "" || len(line.GetCellMeta()) != 0 || len(line.GetRuns()) != 0 || len(line.GetStyleSpans()) != 0 {
		t.Fatalf("default blank line must be represented by client padding: %+v", line)
	}

	base.Screen[0].Runs[0].Cells[1] = terminalengine.Cell{Text: "界", Width: 2}
	data, err = EncodeFrameWithCompactLines(base, true)
	if err != nil {
		t.Fatal(err)
	}
	envelope.Reset()
	if err := proto.Unmarshal(data, &envelope); err != nil {
		t.Fatal(err)
	}
	line = envelope.GetSnapshot().GetScreenLines()[0]
	if line.GetText() != " 界" || string(line.GetCellMeta()) != "\x01\x81" || len(line.GetRuns()) != 0 {
		t.Fatalf("wide line must use UTF-8 compact encoding: %+v", line)
	}
}

func TestCompactLine_UnicodeAndGridRoundTrip(t *testing.T) {
	tests := []struct {
		name string
		runs []terminalengine.CellRun
		cols int
		text string
		meta []byte
	}{
		{"ascii", []terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{{Text: "A", Width: 1}}}}, 4, "A", []byte{1}},
		{"chinese", []terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{{Text: "中", Width: 2}}}}, 4, "中", []byte{0x81}},
		{"mixed", []terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{{Text: "A", Width: 1}, {Text: "中", Width: 2}, {Text: "B", Width: 1}}}}, 4, "A中B", []byte{1, 0x81, 1}},
		{"combining", []terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{{Text: "é", Width: 1}}}}, 2, "é", []byte{2}},
		{"variation", []terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{{Text: "❤️", Width: 2}}}}, 2, "❤️", []byte{0x82}},
		{"skin-tone", []terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{{Text: "👍🏻", Width: 2}}}}, 2, "👍🏻", []byte{0x82}},
		{"zwj", []terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{{Text: "👨‍👩‍👧‍👦", Width: 2}}}}, 2, "👨‍👩‍👧‍👦", []byte{0x87}},
		{"flag", []terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{{Text: "🇯🇵", Width: 2}}}}, 2, "🇯🇵", []byte{0x82}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			text, meta, _, err := compactLine(tt.runs, tt.cols)
			if err != nil {
				t.Fatal(err)
			}
			if text != tt.text || string(meta) != string(tt.meta) {
				t.Fatalf("compact=(%q,%v), want (%q,%v)", text, meta, tt.text, tt.meta)
			}
		})
	}
}

func TestCompactLine_StylesAndTrailingSpaces(t *testing.T) {
	runs := []terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{
		{Text: "中", Width: 2, StyleID: 3, LinkID: 7},
		{Text: "A", Width: 1, StyleID: 4},
		{Text: " ", Width: 1},
		{Text: " ", Width: 1, StyleID: 5},
	}}}
	text, meta, spans, err := compactLine(runs, 6)
	if err != nil {
		t.Fatal(err)
	}
	if text != "中A  " || string(meta) != string([]byte{0x81, 1, 1, 1}) {
		t.Fatalf("unexpected compact payload text=%q meta=%v", text, meta)
	}
	if len(spans) != 3 || spans[0].StartCol != 0 || spans[0].EndCol != 2 || spans[0].StyleId != 3 || spans[0].LinkId != 7 || spans[2].StartCol != 4 {
		t.Fatalf("unexpected terminal-column spans: %+v", spans)
	}
	text, meta, _, err = compactLine([]terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{{Text: "中", Width: 2}, {Text: " ", Width: 1}, {Text: " ", Width: 1}}}}, 4)
	if err != nil || text != "中" || string(meta) != string([]byte{0x81}) {
		t.Fatalf("trailing defaults must trim without dropping wide cell: text=%q meta=%v err=%v", text, meta, err)
	}
}

func TestCompactLine_RejectsInvalidModelData(t *testing.T) {
	tooLong := strings.Repeat("a", maxCellTextBytes+1)
	invalidUTF8 := string([]byte{0xff})
	tests := []struct {
		name string
		runs []terminalengine.CellRun
		cols int
	}{
		{"invalid-width", []terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{{Text: "x", Width: 3}}}}, 4},
		{"invalid-utf8", []terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{{Text: invalidUTF8, Width: 1}}}}, 4},
		{"too-long", []terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{{Text: tooLong, Width: 1}}}}, 4},
		{"wide-out-of-bounds", []terminalengine.CellRun{{Col: 1, Cells: []terminalengine.Cell{{Text: "中", Width: 2}}}}, 2},
		{"overlaps-wide-spacer", []terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{{Text: "中", Width: 2}}}, {Col: 1, Cells: []terminalengine.Cell{{Text: "x", Width: 1}}}}, 3},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if _, _, _, err := compactLine(tt.runs, tt.cols); err == nil {
				t.Fatal("expected compact model error")
			}
		})
	}
}

func TestUTF8CompactSerializedSizes(t *testing.T) {
	cases := []struct {
		name  string
		cells []terminalengine.Cell
		cols  int
	}{
		{"ascii-200", repeatedCells("a", 1, 200), 200},
		{"chinese-100", repeatedCells("中", 2, 100), 200},
		{"mixed-150", alternatingCells("A", "中", 75), 225},
		{"chinese-path", []terminalengine.Cell{{Text: "/", Width: 1}, {Text: "用", Width: 2}, {Text: "户", Width: 2}, {Text: "/", Width: 1}, {Text: "项目", Width: 2}, {Text: "/", Width: 1}, {Text: "日志", Width: 2}}, 11},
		{"styled-unicode", []terminalengine.Cell{{Text: "中", Width: 2, StyleID: 4}, {Text: "文", Width: 2, StyleID: 4}, {Text: "😀", Width: 2, StyleID: 5}, {Text: "é", Width: 1, LinkID: 7}}, 7},
		{"emoji-combining", []terminalengine.Cell{{Text: "❤️", Width: 2}, {Text: "👍🏻", Width: 2}, {Text: "👨‍👩‍👧‍👦", Width: 2}, {Text: "é", Width: 1}}, 7},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			frame := terminalengine.ScreenFrame{Kind: terminalengine.FrameSnapshot, SessionID: "size", InstanceID: "size", Epoch: 1, Seq: 1,
				Rows: 5, Cols: tc.cols, Screen: []terminalengine.Line{{ID: 1, Version: 1, Runs: []terminalengine.CellRun{{Col: 0, Cells: tc.cells}}}}}
			compact, err := EncodeFrameWithCompactLines(frame, true)
			if err != nil {
				t.Fatal(err)
			}
			runs, err := EncodeFrameWithCompactLines(frame, false)
			if err != nil {
				t.Fatal(err)
			}
			t.Logf("[UTF8CompactSize] scenario=%s compact_bytes=%d cellrun_bytes=%d delta=%d", tc.name, len(compact), len(runs), len(compact)-len(runs))
			if len(compact) >= len(runs) {
				t.Fatalf("compact=%d must be smaller than CellRun=%d", len(compact), len(runs))
			}
		})
	}
}

func repeatedCells(text string, width uint8, count int) []terminalengine.Cell {
	cells := make([]terminalengine.Cell, count)
	for i := range cells {
		cells[i] = terminalengine.Cell{Text: text, Width: width}
	}
	return cells
}

func alternatingCells(ascii, wide string, pairs int) []terminalengine.Cell {
	cells := make([]terminalengine.Cell, 0, pairs*2)
	for i := 0; i < pairs; i++ {
		cells = append(cells, terminalengine.Cell{Text: ascii, Width: 1}, terminalengine.Cell{Text: wide, Width: 2})
	}
	return cells
}

func BenchmarkEncodeUTF8CompactMixed(b *testing.B) {
	frame := terminalengine.ScreenFrame{Kind: terminalengine.FrameSnapshot, SessionID: "bench", InstanceID: "bench", Epoch: 1, Seq: 1,
		Rows: 5, Cols: 300, Screen: []terminalengine.Line{{ID: 1, Version: 1,
			Runs: []terminalengine.CellRun{{Col: 0, Cells: alternatingCells("A", "中", 100)}}}}}
	for _, compact := range []bool{true, false} {
		name := "cellrun"
		if compact {
			name = "utf8-compact"
		}
		b.Run(name, func(b *testing.B) {
			b.ReportAllocs()
			for i := 0; i < b.N; i++ {
				if _, err := EncodeFrameWithCompactLines(frame, compact); err != nil {
					b.Fatal(err)
				}
			}
		})
	}
}

func TestEncodeFrame_PatchRoundTrip(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(10000, nil)
	engine := terminalengine.NewEngine(5, 10, sb)
	engine.Write([]byte("hello\n"))

	frame := screenprojection.ExportSnapshot(engine, sb, "s1", "i1", 0, 1)
	frame.Kind = terminalengine.FramePatch
	frame.BaseRevision = 1
	frame.Seq = 2
	frame.Screen = frame.Screen[:1]
	frame.Screen[0].Row = 3

	data, err := EncodeFrame(frame)
	if err != nil {
		t.Fatal(err)
	}

	var envelope pb.ScreenEnvelope
	if err := proto.Unmarshal(data, &envelope); err != nil {
		t.Fatal(err)
	}

	patch, ok := envelope.Payload.(*pb.ScreenEnvelope_Patch)
	if !ok {
		t.Fatalf("expected patch, got %T", envelope.Payload)
	}
	if patch.Patch.BaseRevision != 1 {
		t.Fatalf("base revision mismatch")
	}
	if patch.Patch.InstanceId != "i1" {
		t.Fatalf("instance id mismatch: %q", patch.Patch.InstanceId)
	}
	if got := patch.Patch.LineUpdates[0].LineId; got == 0 {
		t.Fatalf("patch line id must be stable and non-zero")
	}
}

// 帧类型只由 Kind 决定：patch 即使 BaseRevision 为 0 也按 patch 编码，
// snapshot 即使携带非零 BaseRevision 也按 snapshot 编码；Kind 未设置必须报错。
func TestEncodeFrame_KindDrivesFrameType(t *testing.T) {
	base := terminalengine.ScreenFrame{
		Version: 1, SessionID: "s1", InstanceID: "i1", Epoch: 1, Seq: 2,
		Rows: 5, Cols: 10,
	}

	patchFrame := base
	patchFrame.Kind = terminalengine.FramePatch
	patchFrame.BaseRevision = 0 // 旧惯例下会被误判为 snapshot
	data, err := EncodeFrame(patchFrame)
	if err != nil {
		t.Fatal(err)
	}
	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(data, &env); err != nil {
		t.Fatal(err)
	}
	if env.GetPatch() == nil {
		t.Fatalf("Kind=FramePatch must encode as patch, got %T", env.Payload)
	}

	snapshotFrame := base
	snapshotFrame.Kind = terminalengine.FrameSnapshot
	snapshotFrame.BaseRevision = 7 // snapshot 的 base 不参与语义
	data, err = EncodeFrame(snapshotFrame)
	if err != nil {
		t.Fatal(err)
	}
	env = pb.ScreenEnvelope{}
	if err := proto.Unmarshal(data, &env); err != nil {
		t.Fatal(err)
	}
	if env.GetSnapshot() == nil {
		t.Fatalf("Kind=FrameSnapshot must encode as snapshot, got %T", env.Payload)
	}

	if _, err := EncodeFrame(base); err == nil {
		t.Fatal("Kind 未设置的帧必须报错")
	}
}

// patch 帧 title/cwd 三态：未变化（字段 absent）、变为空串（present 且为空）、
// 非空新值。
func TestEncodeFrame_PatchTitleWorkingDirPresence(t *testing.T) {
	encode := func(frame terminalengine.ScreenFrame) *pb.ScreenPatch {
		t.Helper()
		data, err := EncodeFrame(frame)
		if err != nil {
			t.Fatal(err)
		}
		var env pb.ScreenEnvelope
		if err := proto.Unmarshal(data, &env); err != nil {
			t.Fatal(err)
		}
		patch := env.GetPatch()
		if patch == nil {
			t.Fatalf("expected patch, got %T", env.Payload)
		}
		return patch
	}
	patchFrame := func() terminalengine.ScreenFrame {
		return terminalengine.ScreenFrame{
			Version: 1, Kind: terminalengine.FramePatch, InstanceID: "i1",
			Epoch: 1, Seq: 2, BaseRevision: 1, Rows: 5, Cols: 10,
		}
	}

	// 未变化：字段不出现在 wire 上。
	patch := encode(patchFrame())
	if patch.Title != nil || patch.WorkingDirectory != nil {
		t.Fatalf("unchanged title/cwd must be absent, got title=%v cwd=%v",
			patch.Title, patch.WorkingDirectory)
	}
	if patch.GetTitle() != "" || patch.GetWorkingDirectory() != "" {
		t.Fatal("absent optional fields must read as empty via getters")
	}

	// 变为空串：字段 present 且为空。
	frame := patchFrame()
	frame.TitleChanged = true
	frame.WorkingDirChanged = true
	patch = encode(frame)
	if patch.Title == nil || *patch.Title != "" {
		t.Fatalf("cleared title must be present and empty, got %v", patch.Title)
	}
	if patch.WorkingDirectory == nil || *patch.WorkingDirectory != "" {
		t.Fatalf("cleared cwd must be present and empty, got %v", patch.WorkingDirectory)
	}

	// 非空新值：字段 present 且值正确。
	frame = patchFrame()
	frame.Title = "new title"
	frame.TitleChanged = true
	frame.WorkingDir = "/tmp/work"
	frame.WorkingDirChanged = true
	patch = encode(frame)
	if patch.Title == nil || *patch.Title != "new title" {
		t.Fatalf("title mismatch: %v", patch.Title)
	}
	if patch.WorkingDirectory == nil || *patch.WorkingDirectory != "/tmp/work" {
		t.Fatalf("cwd mismatch: %v", patch.WorkingDirectory)
	}
}

func TestEncodeFrame_PatchMetadataPresence(t *testing.T) {
	base := terminalengine.ScreenFrame{
		Version: 1, Kind: terminalengine.FramePatch, InstanceID: "i1",
		Epoch: 1, Seq: 2, BaseRevision: 1, Rows: 5, Cols: 10,
	}
	decode := func(frame terminalengine.ScreenFrame) *pb.ScreenPatch {
		t.Helper()
		data, err := EncodeFrame(frame)
		if err != nil {
			t.Fatal(err)
		}
		var env pb.ScreenEnvelope
		if err := proto.Unmarshal(data, &env); err != nil {
			t.Fatal(err)
		}
		return env.GetPatch()
	}

	patch := decode(base)
	if patch.Cursor != nil || patch.Modes != nil || patch.Palette != nil {
		t.Fatalf("unchanged metadata must be absent: cursor=%v modes=%v palette=%v",
			patch.Cursor, patch.Modes, patch.Palette)
	}

	base.CursorChanged = true
	base.ModesChanged = true
	base.PaletteChanged = true
	patch = decode(base)
	if patch.Cursor == nil || patch.Modes == nil || patch.Palette == nil {
		t.Fatalf("changed metadata must be present: cursor=%v modes=%v palette=%v",
			patch.Cursor, patch.Modes, patch.Palette)
	}
}

// ResumeAck 在 Go 与 wire 间可正确 marshal/unmarshal。
func TestResumeAck_WireRoundTrip(t *testing.T) {
	data, err := proto.Marshal(&pb.ScreenEnvelope{
		ProtocolVersion: 1,
		Payload: &pb.ScreenEnvelope_ResumeAck{ResumeAck: &pb.ResumeAck{
			InstanceId:     "i1",
			LayoutEpoch:    3,
			ScreenRevision: 42,
		}},
	})
	if err != nil {
		t.Fatal(err)
	}

	var env pb.ScreenEnvelope
	if err := proto.Unmarshal(data, &env); err != nil {
		t.Fatal(err)
	}
	ack := env.GetResumeAck()
	if ack == nil {
		t.Fatalf("expected resume ack, got %T", env.Payload)
	}
	if ack.GetInstanceId() != "i1" || ack.GetLayoutEpoch() != 3 || ack.GetScreenRevision() != 42 {
		t.Fatalf("resume ack mismatch: %+v", ack)
	}
}

// ResumeAck 是服务端→客户端专属消息；Go 服务端收到时与 inbound
// snapshot/patch 一样按协议错误处理。
func TestHandleMessage_ResumeAckInboundRejected(t *testing.T) {
	data, err := proto.Marshal(&pb.ScreenEnvelope{
		ProtocolVersion: 1,
		Payload: &pb.ScreenEnvelope_ResumeAck{ResumeAck: &pb.ResumeAck{
			InstanceId: "i1", LayoutEpoch: 1, ScreenRevision: 1,
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := NewHandler().HandleMessage(data); err == nil {
		t.Fatal("inbound ResumeAck must be rejected as unsupported payload")
	}
}
