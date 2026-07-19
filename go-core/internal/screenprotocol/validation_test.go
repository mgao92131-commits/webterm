package screenprotocol

import (
	"strings"
	"testing"

	"google.golang.org/protobuf/proto"
	pb "webterm/go-core/internal/screenprotocol/generated"
)

// malformed Hello 矩阵（计划 §3.5）：hasProjection=false 时 token 字段必须
// 全部为默认值；hasProjection=true 时 token 必须完整。
func TestValidateHello_ProjectionTokenConsistency(t *testing.T) {
	tests := []struct {
		name    string
		hello   *pb.Hello
		wantErr bool
	}{
		// hasProjection=false：干净 Hello（现有客户端的形态）。
		{name: "cold clean", hello: &pb.Hello{Version: 1}},
		{name: "cold with geometry", hello: &pb.Hello{Version: 1, Cols: 120, Rows: 40}},
		// hasProjection=false：携带任一 token 字段即拒绝。
		{name: "cold with instance", hello: &pb.Hello{Version: 1, InstanceId: "i1"}, wantErr: true},
		{name: "cold with epoch", hello: &pb.Hello{Version: 1, LayoutEpoch: 1}, wantErr: true},
		{name: "cold with revision", hello: &pb.Hello{Version: 1, ScreenRevision: 1}, wantErr: true},
		{name: "projection complete", hello: &pb.Hello{Version: 1, HasProjection: true, InstanceId: "i1", LayoutEpoch: 1, ScreenRevision: 1}},
		{name: "projection with geometry", hello: &pb.Hello{Version: 1, Cols: 120, Rows: 40, HasProjection: true, InstanceId: "i1", LayoutEpoch: 2, ScreenRevision: 9}},
		// hasProjection=true：任一字段缺失即拒绝。
		{name: "projection missing instance", hello: &pb.Hello{Version: 1, HasProjection: true, LayoutEpoch: 1, ScreenRevision: 1}, wantErr: true},
		{name: "projection zero epoch", hello: &pb.Hello{Version: 1, HasProjection: true, InstanceId: "i1", ScreenRevision: 1}, wantErr: true},
		{name: "projection zero revision", hello: &pb.Hello{Version: 1, HasProjection: true, InstanceId: "i1", LayoutEpoch: 1}, wantErr: true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			err := ValidateHello(tc.hello)
			if tc.wantErr && err == nil {
				t.Fatal("expected validation error")
			}
			if !tc.wantErr && err != nil {
				t.Fatalf("unexpected validation error: %v", err)
			}
		})
	}
}

func TestValidateHello_VersionAndGeometry(t *testing.T) {
	tests := []struct {
		name    string
		hello   *pb.Hello
		wantErr bool
	}{
		{name: "unsupported version", hello: &pb.Hello{Version: 2}, wantErr: true},
		{name: "zero version", hello: &pb.Hello{}, wantErr: true},
		{name: "cols below min", hello: &pb.Hello{Version: 1, Cols: 9}, wantErr: true},
		{name: "cols above max", hello: &pb.Hello{Version: 1, Cols: 501}, wantErr: true},
		{name: "rows below min", hello: &pb.Hello{Version: 1, Rows: 4}, wantErr: true},
		{name: "rows above max", hello: &pb.Hello{Version: 1, Rows: 201}, wantErr: true},
		{name: "cols zero means no resize", hello: &pb.Hello{Version: 1, Cols: 0, Rows: 0}},
		{name: "boundary geometry", hello: &pb.Hello{Version: 1, Cols: 500, Rows: 200}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			err := ValidateHello(tc.hello)
			if tc.wantErr && err == nil {
				t.Fatal("expected validation error")
			}
			if !tc.wantErr && err != nil {
				t.Fatalf("unexpected validation error: %v", err)
			}
		})
	}
}

func TestValidateInput_RequiresReliabilityAnchor(t *testing.T) {
	valid := &pb.TerminalInput{
		LeaseId: "lease-1", ClientInstanceId: "android-1", InputSeq: 7,
		Input: &pb.TerminalInput_Text{Text: &pb.TextInput{Data: "x"}},
	}
	if err := ValidateInput(valid); err != nil {
		t.Fatalf("valid input rejected: %v", err)
	}
	withoutClient := proto.Clone(valid).(*pb.TerminalInput)
	withoutClient.ClientInstanceId = ""
	if err := ValidateInput(withoutClient); err == nil {
		t.Fatal("input without client instance id must be rejected")
	}
	withoutSeq := proto.Clone(valid).(*pb.TerminalInput)
	withoutSeq.InputSeq = 0
	if err := ValidateInput(withoutSeq); err == nil {
		t.Fatal("input without sequence must be rejected")
	}
}

// HandleMessage 是全部 inbound 消息的唯一入口：envelope 超过 2 MiB 必须在
// 解析前被拒绝。
func TestHandleMessage_EnvelopeSizeLimit(t *testing.T) {
	oversized := make([]byte, maxEnvelopeBytes+1)
	if err := NewHandler().HandleMessage(oversized); err == nil {
		t.Fatal("oversized envelope must be rejected")
	}
	if err := ValidateEnvelopeSize(make([]byte, maxEnvelopeBytes)); err != nil {
		t.Fatalf("exactly %d bytes must be accepted: %v", maxEnvelopeBytes, err)
	}
}

// 资源上限（proto 头部注释）：history page 1..500、clipboard <=1 MiB、
// rows 5..200、cols 10..500 在 inbound 侧必须强制执行。
func TestValidate_InboundResourceLimits(t *testing.T) {
	if err := ValidateHistoryRequest(&pb.HistoryRequest{Limit: 0}); err == nil {
		t.Fatal("history limit 0 must be rejected")
	}
	if err := ValidateHistoryRequest(&pb.HistoryRequest{Limit: 501}); err == nil {
		t.Fatal("history limit > 500 must be rejected")
	}
	if err := ValidateHistoryRequest(&pb.HistoryRequest{Limit: 500}); err != nil {
		t.Fatalf("history limit 500 must be accepted: %v", err)
	}

	if err := ValidateResize(&pb.Resize{Cols: 10, Rows: 5, LeaseId: "l1"}); err != nil {
		t.Fatalf("boundary resize must be accepted: %v", err)
	}
	if err := ValidateResize(&pb.Resize{Cols: 501, Rows: 5, LeaseId: "l1"}); err == nil {
		t.Fatal("resize cols > 500 must be rejected")
	}
	if err := ValidateResize(&pb.Resize{Cols: 100, Rows: 201, LeaseId: "l1"}); err == nil {
		t.Fatal("resize rows > 200 must be rejected")
	}
	if err := ValidateResize(&pb.Resize{Cols: 100, Rows: 40}); err == nil {
		t.Fatal("resize without lease must be rejected")
	}

	clipboardLimit := 1024 * 1024
	if err := ValidateClipboardResponse(&pb.ClipboardResponse{RequestId: "r1", Data: make([]byte, clipboardLimit)}); err != nil {
		t.Fatalf("clipboard payload of exactly 1 MiB must be accepted: %v", err)
	}
	if err := ValidateClipboardResponse(&pb.ClipboardResponse{RequestId: "r1", Data: make([]byte, clipboardLimit+1)}); err == nil {
		t.Fatal("clipboard payload > 1 MiB must be rejected")
	}
	if err := ValidateClipboardResponse(&pb.ClipboardResponse{Data: []byte("x")}); err == nil {
		t.Fatal("clipboard response without request id must be rejected")
	}
}

// 超大 title/cwd（>4 KiB）与非法 UTF-8 必须在分配前被拒绝。
func TestValidate_TitleCWDStringLimits(t *testing.T) {
	oversized := strings.Repeat("a", 4097)
	if err := validateString(oversized, maxTitleBytes, "title"); err == nil {
		t.Fatal("title > 4 KiB must be rejected")
	}
	if err := validateString("\xff\xfe", maxTitleBytes, "title"); err == nil {
		t.Fatal("invalid UTF-8 title must be rejected")
	}
	if err := validateString(strings.Repeat("a", 4096), maxTitleBytes, "title"); err != nil {
		t.Fatalf("title of exactly 4 KiB must be accepted: %v", err)
	}
}

// Handler 对 inbound Hello 的校验失败必须返回协议错误（不进入回调）。
func TestHandleMessage_MalformedHelloRejected(t *testing.T) {
	called := false
	h := NewHandler(WithHelloCallback(func(*pb.Hello) { called = true }))
	data, err := proto.Marshal(&pb.ScreenEnvelope{
		ProtocolVersion: 1,
		Payload: &pb.ScreenEnvelope_Hello{Hello: &pb.Hello{
			Version: 1, InstanceId: "i1", // hasProjection=false 却携带 token
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := h.HandleMessage(data); err == nil {
		t.Fatal("malformed hello must be rejected")
	}
	if called {
		t.Fatal("hello callback must not run for a rejected hello")
	}
}

func TestValidateLines_RejectsExplicitSpacerCells(t *testing.T) {
	lines := []*pb.LineData{{
		LineId:      1,
		LineVersion: 1,
		Runs: []*pb.CellRun{{
			Col:   0,
			Cells: []*pb.Cell{{Text: "", Width: 0}},
		}},
	}}
	if _, err := validateLineData(lines, 10); err == nil {
		t.Fatal("explicit width=0 spacer must be rejected")
	}
}

func TestValidateCompactLine_UTF8Metadata(t *testing.T) {
	valid := []*pb.LineData{{
		LineId: 1, LineVersion: 1, Text: "A❤️中", CellMeta: []byte{1, 0x82, 0x81},
		StyleSpans: []*pb.StyleSpan{{StartCol: 1, EndCol: 3, StyleId: 7, LinkId: 9}},
	}}
	if _, err := validateLineData(valid, 5); err != nil {
		t.Fatalf("valid UTF-8 compact line rejected: %v", err)
	}
	invalid := []struct {
		name string
		line *pb.LineData
		cols int
	}{
		{"zero-code-points", &pb.LineData{LineId: 1, LineVersion: 1, Text: "A", CellMeta: []byte{0}}, 5},
		{"metadata-too-short", &pb.LineData{LineId: 1, LineVersion: 1, Text: "A中", CellMeta: []byte{1}}, 5},
		{"wide-out-of-bounds", &pb.LineData{LineId: 1, LineVersion: 1, Text: "中", CellMeta: []byte{0x81}}, 1},
		{"mixed-runs", &pb.LineData{LineId: 1, LineVersion: 1, Text: "A", CellMeta: []byte{1}, Runs: []*pb.CellRun{{Col: 0, Cells: []*pb.Cell{{Text: "A", Width: 1}}}}}, 5},
	}
	for _, tc := range invalid {
		t.Run(tc.name, func(t *testing.T) {
			if _, err := validateLineData([]*pb.LineData{tc.line}, tc.cols); err == nil {
				t.Fatal("expected compact validation error")
			}
		})
	}
}

func TestValidateCompactLine_TextLimitAndMetadata(t *testing.T) {
	over64Bytes := strings.Repeat("界", 22)
	if err := validateCompactLine(over64Bytes, []byte{22}, nil, 1); err != nil {
		t.Fatalf("compact cell larger than 64B must be accepted: %v", err)
	}
	if err := validateCompactLine(string([]byte{0xff}), []byte{1}, nil, 1); err == nil {
		t.Fatal("invalid UTF-8 must be rejected")
	}
	if err := validateCompactLine("中A", []byte{1}, nil, 2); err == nil {
		t.Fatal("mismatched cell metadata must be rejected")
	}

	text := strings.Repeat("界", 127*87)
	meta := make([]byte, 87)
	for i := range meta {
		meta[i] = 127
	}
	if err := validateCompactLine(text, meta, nil, len(meta)); err == nil {
		t.Fatalf("compact text larger than %d bytes must be rejected", maxCompactLineTextBytes)
	}
}
