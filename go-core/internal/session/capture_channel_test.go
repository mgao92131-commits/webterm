//go:build webterm_capture

package session

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"testing"

	"webterm/go-core/internal/screenprojection"
	"webterm/go-core/internal/terminalcapture"
	"webterm/go-core/internal/terminalengine"
)

// failingSink 模拟物理写失败，其错误文本故意包含“敏感”内容，验证捕获绝不记录它。
type failingSink struct{}

func (failingSink) WriteFrame(context.Context, []byte, bool) error {
	return errors.New("write failed: secret-token /etc/passwd 10.0.0.1")
}

// 要求 9：写失败时捕获记录使用稳定枚举 failureKind，绝不包含原始错误文本。
func TestCaptureWireFailureHasNoRawErrorText(t *testing.T) {
	coord := terminalcapture.NewCoordinator()
	if err := coord.StartCapture(terminalcapture.Identity{
		CaptureID: "cap", SessionID: "s", ClientInstanceID: "cl", TerminalInstanceID: "inst",
	}, terminalcapture.DefaultLimits()); err != nil {
		t.Fatalf("StartCapture: %v", err)
	}

	client := newOwnedTerminalChannelRuntime(nil, failingSink{}, "")
	client.captureSink = coord
	client.terminalInstanceID = "inst"
	client.screenClientID = "sc"
	client.clientInstanceID = "cl"

	payload := []byte("wire-payload")
	handle := client.recordWireFrame("patch", 5, 4, payload)
	ok := client.writeScreenMessage(context.Background(), outboundMessage{binary: payload, kind: "patch"}, handle)
	if ok {
		t.Fatal("writeScreenMessage should fail with failingSink")
	}

	rings, found := coord.FinishCapture("cap", "inst")
	if !found || len(rings.Wire) != 1 {
		t.Fatalf("wire records = %d (found=%v)", len(rings.Wire), found)
	}
	rec := rings.Wire[0]
	if rec.FailureKind != terminalcapture.FailureWriteFailed {
		t.Fatalf("failureKind = %q, want stable enum %q", rec.FailureKind, terminalcapture.FailureWriteFailed)
	}
	// 原始错误文本的任何片段都不得进入捕获记录。
	for _, secret := range []string{"secret-token", "/etc/passwd", "10.0.0.1", "write failed:"} {
		if strings.Contains(rec.FailureKind, secret) {
			t.Fatalf("failureKind leaked raw error text %q", secret)
		}
	}

	// 经 BuildAgentPayload 导出后，wire 索引的 writeSucceeded 应序列化为稳定值 "false"。
	barrier := terminalcapture.BarrierState{Available: true, AgentRevision: 5, TerminalInstanceID: "inst",
		Canonical: terminalengine.ScreenFrame{Seq: 5, Rows: 1, Cols: 1}}
	ap := terminalcapture.BuildAgentPayload(barrier, rings, 0, 0, terminalcapture.AgentInfo{}, 0)
	for _, f := range ap.Files {
		if f.Path == "agent/wire/index.json" {
			if !strings.Contains(string(f.Data), `"writeSucceeded": "false"`) {
				t.Fatalf("wire index writeSucceeded not 'false':\n%s", f.Data)
			}
			if strings.Contains(string(f.Data), "secret-token") || strings.Contains(string(f.Data), "/etc/passwd") {
				t.Fatal("wire index leaked raw error text")
			}
		}
	}
}

// 要求 6：捕获派生帧（旁路存储）不推进 FrameDeriver baseline——
// 对同一状态再次派生仍返回空 patch（Kind=0），证明 baseline 只被真实写出推进一次。
func TestCaptureDoesNotAdvanceDeriverBaseline(t *testing.T) {
	coord := terminalcapture.NewCoordinator()
	_ = coord.StartCapture(terminalcapture.Identity{
		CaptureID: "cap", ClientInstanceID: "cl", TerminalInstanceID: "inst",
	}, terminalcapture.DefaultLimits())

	state := terminalengine.ScreenFrame{
		Version: 1, InstanceID: "inst", Epoch: 1, Seq: 7, Rows: 1, Cols: 4,
		Screen: []terminalengine.Line{{ID: 1, Version: 1, Runs: []terminalengine.CellRun{{Col: 0, Cells: []terminalengine.Cell{{Text: "abcd", Width: 1}}}}}},
	}

	var deriver screenprojection.FrameDeriver
	frame1 := deriver.FrameForState(state) // 首帧 → snapshot，baseline=state
	if frame1.Kind != terminalengine.FrameSnapshot {
		t.Fatalf("first frame kind = %v, want snapshot", frame1.Kind)
	}

	// 捕获点 C 的等价动作：旁路记录该帧（绝不回写 deriver）。
	coord.RecordDerived("inst", terminalcapture.DerivedRecord{
		ClientInstanceID: "cl", ScreenClientID: "sc", Frame: frame1,
	})

	// 对完全相同的状态再次派生：baseline 已等于 state，应无可观察变化 → Kind=0。
	frame2 := deriver.FrameForState(state)
	if frame2.Kind != 0 {
		t.Fatalf("second derive of same state kind=%v, want 0 (capture must not advance baseline)", frame2.Kind)
	}

	rings, _ := coord.FinishCapture("cap", "inst")
	if len(rings.Derived) != 1 {
		t.Fatalf("derived records = %d, want 1", len(rings.Derived))
	}
}

// captureWriteSink 记录 handler 写出的每一帧，供协议测试解析。
type captureWriteSink struct {
	mu     sync.Mutex
	frames [][]byte
}

func (s *captureWriteSink) WriteFrame(_ context.Context, payload []byte, _ bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.frames = append(s.frames, append([]byte(nil), payload...))
	return nil
}

func (s *captureWriteSink) snapshot() [][]byte {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([][]byte, len(s.frames))
	copy(out, s.frames)
	return out
}

// 要求（阶段 3 通信）：capture 通道 start→finish 协议端到端——result 头 + 分块 blob
// 可被重组为完整文件，且每个文件 SHA-256 与索引一致；finish 后捕获停止。
func TestCaptureChannelProtocolEndToEnd(t *testing.T) {
	coord := terminalcapture.NewCoordinator()
	terminalcapture.Install(coord)
	defer terminalcapture.Install(nil)

	sink := &captureWriteSink{}
	handler := NewCaptureChannelHandler(nil, sink)

	mustJSON := func(v any) []byte {
		data, err := json.Marshal(v)
		if err != nil {
			t.Fatalf("marshal request: %v", err)
		}
		return data
	}

	// start
	handler.HandleFrame(mustJSON(captureRequest{
		Op: "start", CaptureID: "cap1", SessionID: "s1",
		ClientInstanceID: "cl", TerminalInstanceID: "inst", LayoutEpoch: 1,
	}), true)
	frames := sink.snapshot()
	if len(frames) != 1 {
		t.Fatalf("after start frames=%d, want 1", len(frames))
	}
	var ack captureAck
	if err := json.Unmarshal(frames[0], &ack); err != nil {
		t.Fatalf("unmarshal ack: %v", err)
	}
	if !ack.OK || !ack.Supported {
		t.Fatalf("start ack = %+v, want ok+supported", ack)
	}

	// 记录若干旁路数据（模拟热路径捕获）。
	coord.RecordPTY("inst", terminalcapture.PTYRecord{EventSeq: 1, Data: []byte("pty-body-中文")})
	coord.RecordWire("inst", terminalcapture.WireRecord{Kind: "patch", ScreenRevision: 3, BaseRevision: 2, Payload: []byte{1, 2, 3, 4, 5}})

	// finish
	handler.HandleFrame(mustJSON(captureRequest{
		Op: "finish", CaptureID: "cap1", TerminalInstanceID: "inst",
		AndroidModelRevision: 3, AndroidRenderedRevision: 2,
	}), true)

	frames = sink.snapshot()
	if len(frames) < 2 {
		t.Fatalf("after finish frames=%d, want >=2", len(frames))
	}
	// frames[1] 是 result 头（frames[0] 是 start ack）。
	var result captureResult
	if err := json.Unmarshal(frames[1], &result); err != nil {
		t.Fatalf("unmarshal result: %v", err)
	}
	if !result.OK {
		t.Fatalf("result not ok: %+v", result)
	}
	if result.Meta.AndroidModelRevision != 3 || result.Meta.AndroidRenderedRevision != 2 {
		t.Fatalf("android revisions not echoed: %+v", result.Meta)
	}
	if len(result.Files) == 0 {
		t.Fatal("result has no file index")
	}

	// 重组 blob → 按 path 拼接，校验 SHA-256。
	assembled := map[string][]byte{}
	for _, raw := range frames[2:] {
		var blob captureBlob
		if err := json.Unmarshal(raw, &blob); err != nil {
			t.Fatalf("unmarshal blob: %v", err)
		}
		if blob.Op != "blob" {
			t.Fatalf("unexpected op %q after result", blob.Op)
		}
		assembled[blob.Path] = append(assembled[blob.Path], blob.Data...)
	}

	for _, info := range result.Files {
		data, ok := assembled[info.Path]
		if !ok {
			t.Fatalf("file %s missing from blobs", info.Path)
		}
		if len(data) != info.Length {
			t.Fatalf("file %s length=%d, index says %d", info.Path, len(data), info.Length)
		}
		sum := sha256.Sum256(data)
		if hex.EncodeToString(sum[:]) != info.SHA256 {
			t.Fatalf("file %s sha mismatch", info.Path)
		}
	}
	// 必须包含关键文件。
	for _, want := range []string{"agent/capture-meta.json", "agent/canonical-state.json", "agent/pty.bin", "agent/pty-index.json", "agent/wire/index.json"} {
		if _, ok := assembled[want]; !ok {
			t.Fatalf("expected file %s not produced", want)
		}
	}
	// PTY 正文完整保留（含宽字符）。
	if !strings.Contains(string(assembled["agent/pty.bin"]), "pty-body-中文") {
		t.Fatal("pty.bin lost body content")
	}
	// finish 后捕获停止。
	if _, active := coord.Active(); active {
		t.Fatal("capture must stop after finish")
	}
}

// cancel 操作释放并应答。
func TestCaptureChannelCancel(t *testing.T) {
	coord := terminalcapture.NewCoordinator()
	terminalcapture.Install(coord)
	defer terminalcapture.Install(nil)

	sink := &captureWriteSink{}
	handler := NewCaptureChannelHandler(nil, sink)
	start, _ := json.Marshal(captureRequest{Op: "start", CaptureID: "capX", TerminalInstanceID: "inst", ClientInstanceID: "cl"})
	handler.HandleFrame(start, true)
	if _, active := coord.Active(); !active {
		t.Fatal("expected active capture after start")
	}
	cancel, _ := json.Marshal(captureRequest{Op: "cancel", CaptureID: "capX"})
	handler.HandleFrame(cancel, true)
	if _, active := coord.Active(); active {
		t.Fatal("capture must be cancelled")
	}
}
