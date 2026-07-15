package relaygateway

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync"
	"testing"
	"time"

	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/relayrouter"
	"webterm/go-core/internal/relaystore"
)

// captureSender 记录 gateway 发往 agent 的所有帧。
// payload 会被复制，避免 gateway 复用 64 KiB 缓冲导致帧内容互相覆盖。
// backpress > 0 时前 N 次 SendFrame 返回 ErrBackpressure，模拟发送队列满。
type captureSender struct {
	mu        sync.Mutex
	frames    []relaycore.Frame
	backpress int
}

func (s *captureSender) SendFrame(_ context.Context, frame relaycore.Frame) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	// 只对 body 帧模拟队列满：HTTPHeaders 发送不走重试循环（与生产代码一致）。
	if s.backpress > 0 && frame.Type == relaycore.FrameTypeHTTPChunk {
		s.backpress--
		return relaycore.ErrBackpressure
	}
	frame.Payload = append([]byte(nil), frame.Payload...)
	s.frames = append(s.frames, frame)
	return nil
}

func (s *captureSender) snapshot() []relaycore.Frame {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]relaycore.Frame(nil), s.frames...)
}

// newUploadGateway 构造带真实 store/registry/stream manager 的 HTTPGateway，
// agent 侧用 captureSender 代替 WebSocket。
func newUploadGateway(t *testing.T) (*HTTPGateway, *relayrouter.StreamManager, *captureSender, *http.Cookie) {
	t.Helper()
	store := relaystore.NewMemoryStore()
	user, err := store.CreateUser("owner@example.com", "secret", "admin")
	if err != nil {
		t.Fatalf("CreateUser: %v", err)
	}
	token, err := store.IssueToken(user.ID, time.Hour)
	if err != nil {
		t.Fatalf("IssueToken: %v", err)
	}
	registry := relayrouter.NewRegistry()
	sender := &captureSender{}
	registry.RegisterAgentConnection(relaycore.DevicePresence{
		UserID:            user.ID,
		DeviceID:          "dev1",
		AgentConnectionID: "conn1",
		Online:            true,
	}, sender)
	if !registry.RegisterAgentDataConnection("dev1", sender) {
		t.Fatal("register bulk agent plane")
	}
	streams := relayrouter.NewStreamManager()
	cookie := &http.Cookie{Name: relaycore.AuthCookieName, Value: token.Value}
	return NewHTTPGateway(store, registry, streams), streams, sender, cookie
}

func newUploadRequest(t *testing.T, cookie *http.Cookie, body io.Reader) *http.Request {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/api/sessions/s1/upload", body)
	req.Header.Set("x-device-id", "dev1")
	req.AddCookie(cookie)
	return req
}

// waitFrames 轮询 captureSender，直到抓到至少 n 帧或超时。
func waitFrames(t *testing.T, sender *captureSender, n int) []relaycore.Frame {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		frames := sender.snapshot()
		if len(frames) >= n {
			return frames
		}
		time.Sleep(2 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %d frames, got %d", n, len(sender.snapshot()))
	return nil
}

// pushAgentResponse 模拟 agent 返回的 HTTP 响应帧。
func pushAgentResponse(streams *relayrouter.StreamManager, streamID string, status int, body string) {
	meta, _ := json.Marshal(relaycore.HTTPResponseMeta{
		StatusCode: status,
		Headers:    map[string]string{"content-type": "application/json; charset=utf-8"},
	})
	streams.HandleAgentFrame(relaycore.NewFrame(relaycore.FrameTypeHTTPHeaders, streamID, 0, meta))
	streams.HandleAgentFrame(relaycore.NewFrame(relaycore.FrameTypeHTTPChunk, streamID, relaycore.FrameFlagFin, []byte(body)))
}

// 完整链路：HTTPHeaders -> 64 KiB HTTPChunk x N -> 空 FIN chunk，且 Content-Length
// 显式来自 r.ContentLength（计划 5.1）。
func TestHTTPGatewayStreamsUploadBodyInFixedChunks(t *testing.T) {
	gateway, streams, sender, cookie := newUploadGateway(t)
	body := make([]byte, 2*httpGatewayChunkSize+1)
	for i := range body {
		body[i] = byte(i)
	}
	rec := httptest.NewRecorder()
	done := make(chan struct{})
	go func() {
		gateway.ServeHTTP(rec, newUploadRequest(t, cookie, bytes.NewReader(body)))
		close(done)
	}()

	frames := waitFrames(t, sender, 4)
	if frames[0].Type != relaycore.FrameTypeHTTPHeaders {
		t.Fatalf("frame[0] type = %v, want HTTPHeaders", frames[0].Type)
	}
	var meta relaycore.HTTPRequestMeta
	if err := json.Unmarshal(frames[0].Payload, &meta); err != nil {
		t.Fatalf("unmarshal meta: %v", err)
	}
	if meta.Method != http.MethodPost || meta.Path != "/api/sessions/s1/upload" {
		t.Fatalf("meta = %s %s, want POST /api/sessions/s1/upload", meta.Method, meta.Path)
	}
	if got := meta.Headers["Content-Length"]; got != strconv.Itoa(len(body)) {
		t.Fatalf("Content-Length = %q, want %d（必须来自 r.ContentLength）", got, len(body))
	}
	streamID := frames[0].StreamID

	// 等 FIN 发出后再断言全部分块。
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		frames = sender.snapshot()
		last := frames[len(frames)-1]
		if last.Type == relaycore.FrameTypeHTTPChunk && last.Flags.Has(relaycore.FrameFlagFin) {
			break
		}
		time.Sleep(2 * time.Millisecond)
	}
	if len(frames) != 5 {
		t.Fatalf("frame count = %d, want 5 (headers + 3 chunks + FIN)", len(frames))
	}
	wantSizes := []int{httpGatewayChunkSize, httpGatewayChunkSize, 1}
	for i, want := range wantSizes {
		chunk := frames[i+1]
		if chunk.Type != relaycore.FrameTypeHTTPChunk || chunk.Flags.Has(relaycore.FrameFlagFin) {
			t.Fatalf("chunk %d type/flags wrong: type=%v flags=%v", i, chunk.Type, chunk.Flags)
		}
		if len(chunk.Payload) != want {
			t.Fatalf("chunk %d size = %d, want %d", i, len(chunk.Payload), want)
		}
		if !bytes.Equal(chunk.Payload, body[i*httpGatewayChunkSize:i*httpGatewayChunkSize+want]) {
			t.Fatalf("chunk %d payload mismatch", i)
		}
	}
	fin := frames[4]
	if fin.Type != relaycore.FrameTypeHTTPChunk || !fin.Flags.Has(relaycore.FrameFlagFin) || len(fin.Payload) != 0 {
		t.Fatalf("FIN frame wrong: type=%v flags=%v payload=%d bytes", fin.Type, fin.Flags, len(fin.Payload))
	}

	pushAgentResponse(streams, streamID, http.StatusOK, `{"fileName":"demo.zip"}`)
	<-done
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 (body=%q)", rec.Code, rec.Body.String())
	}
	if rec.Body.String() != `{"fileName":"demo.zip"}` {
		t.Fatalf("response body = %q", rec.Body.String())
	}
}

// 上传路由不设总超时：gateway.timeout 远小于响应延迟时也不应 504。
func TestHTTPGatewayUploadNotKilledByTotalTimeout(t *testing.T) {
	gateway, streams, sender, cookie := newUploadGateway(t)
	gateway.timeout = 30 * time.Millisecond
	rec := httptest.NewRecorder()
	done := make(chan struct{})
	go func() {
		gateway.ServeHTTP(rec, newUploadRequest(t, cookie, bytes.NewReader([]byte("x"))))
		close(done)
	}()

	frames := waitFrames(t, sender, 2) // headers + FIN
	time.Sleep(100 * time.Millisecond) // 远超 30ms 总超时
	pushAgentResponse(streams, frames[0].StreamID, http.StatusOK, `{"ok":true}`)
	<-done
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200：长流上传不应被短总超时误杀", rec.Code)
	}
}

// 业务错误（如 403 UPLOAD_DIRECTORY_NOT_WRITABLE）必须按正常 HTTP 响应返回，
// 不能变成 502（计划 5.2）。
func TestHTTPGatewayBusinessErrorIsHTTPResponse(t *testing.T) {
	gateway, streams, sender, cookie := newUploadGateway(t)
	rec := httptest.NewRecorder()
	done := make(chan struct{})
	go func() {
		gateway.ServeHTTP(rec, newUploadRequest(t, cookie, bytes.NewReader([]byte("x"))))
		close(done)
	}()

	frames := waitFrames(t, sender, 2)
	pushAgentResponse(streams, frames[0].StreamID, http.StatusForbidden,
		`{"code":"UPLOAD_DIRECTORY_NOT_WRITABLE","message":"当前终端目录没有写入权限"}`)
	<-done
	if rec.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want 403：业务错误不能转成 502", rec.Code)
	}
	if !bytes.Contains(rec.Body.Bytes(), []byte("UPLOAD_DIRECTORY_NOT_WRITABLE")) {
		t.Fatalf("body = %q, want business error json", rec.Body.String())
	}
}

// Agent 在读取完整 body 前返回业务错误时，Relay 必须立即关闭客户端请求体，
// 而不是继续把剩余的大文件分块发送到 Agent。
func TestHTTPGatewayStopsUploadWhenAgentRepliesEarly(t *testing.T) {
	gateway, streams, sender, cookie := newUploadGateway(t)
	reader, writer := io.Pipe()
	defer writer.Close()
	rec := httptest.NewRecorder()
	done := make(chan struct{})
	go func() {
		gateway.ServeHTTP(rec, newUploadRequest(t, cookie, reader))
		close(done)
	}()

	frames := waitFrames(t, sender, 1)
	streamID := frames[0].StreamID
	pushAgentResponse(streams, streamID, http.StatusNotFound,
		`{"code":"SESSION_NOT_FOUND","message":"session 不存在"}`)

	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("Agent 提前响应后 gateway 未关闭仍在阻塞的上传 body")
	}
	if rec.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rec.Code)
	}
	frames = sender.snapshot()
	foundClose := false
	for _, frame := range frames {
		if frame.StreamID == streamID && frame.Type == relaycore.FrameTypeStreamClose {
			foundClose = true
			break
		}
	}
	if !foundClose {
		t.Fatal("Agent 提前响应后未发送 StreamClose")
	}
}

// 非上传路由保持 1 MiB 快速拒绝：不创建 stream、不向 agent 发任何帧。
func TestHTTPGatewayRejectsOversizedNonUploadBody(t *testing.T) {
	gateway, streams, sender, cookie := newUploadGateway(t)
	req := httptest.NewRequest(http.MethodPost, "/api/sessions", bytes.NewReader(make([]byte, httpGatewayMaxBodyBytes+1)))
	req.Header.Set("x-device-id", "dev1")
	req.AddCookie(cookie)
	rec := httptest.NewRecorder()
	gateway.ServeHTTP(rec, req)

	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("status = %d, want 413", rec.Code)
	}
	if got := len(sender.snapshot()); got != 0 {
		t.Fatalf("sender got %d frames, want 0", got)
	}
	if got := len(streams.Snapshot()); got != 0 {
		t.Fatalf("streams = %d, want 0", got)
	}
}

// 客户端取消/断开（body 读到一半出错）时，gateway 必须向 agent 发 StreamClose，
// 让 agent 侧 io.Pipe 读端立即报错、fileupload.Service 清理临时文件。
func TestHTTPGatewaySendsStreamCloseOnClientCancel(t *testing.T) {
	gateway, streams, sender, cookie := newUploadGateway(t)
	ctx, cancel := context.WithCancel(context.Background())
	body := &cancelableBody{ctx: ctx, first: []byte("partial")}
	req := newUploadRequest(t, cookie, nil).WithContext(ctx)
	req.Body = body
	rec := httptest.NewRecorder()
	done := make(chan struct{})
	go func() {
		gateway.ServeHTTP(rec, req)
		close(done)
	}()

	waitFrames(t, sender, 2) // headers + 第一块数据
	cancel()
	<-done

	frames := sender.snapshot()
	last := frames[len(frames)-1]
	if last.Type != relaycore.FrameTypeStreamClose {
		t.Fatalf("last frame type = %v, want StreamClose（取消必须通知 agent 中止）", last.Type)
	}
	if got := len(streams.Snapshot()); got != 0 {
		t.Fatalf("streams = %d, want 0（stream 应被关闭）", got)
	}
}

// 发送队列满（ErrBackpressure）时应等待重试而不是直接 502，
// 慢速 agent 不会误杀上传。
func TestHTTPGatewayRetriesOnBackpressure(t *testing.T) {
	gateway, streams, sender, cookie := newUploadGateway(t)
	sender.backpress = 5
	rec := httptest.NewRecorder()
	done := make(chan struct{})
	go func() {
		gateway.ServeHTTP(rec, newUploadRequest(t, cookie, bytes.NewReader([]byte("payload"))))
		close(done)
	}()

	frames := waitFrames(t, sender, 3) // headers + data chunk + FIN
	pushAgentResponse(streams, frames[0].StreamID, http.StatusOK, `{"ok":true}`)
	<-done
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200：背压应等待重试而不是 502", rec.Code)
	}
	if got := frames[1].Payload; !bytes.Equal(got, []byte("payload")) {
		t.Fatalf("chunk payload = %q, want payload", got)
	}
}

// cancelableBody 第一口返回数据，之后阻塞直到 context 取消，模拟客户端上传中断。
type cancelableBody struct {
	ctx   context.Context
	first []byte
	sent  bool
}

func (b *cancelableBody) Read(p []byte) (int, error) {
	if !b.sent {
		b.sent = true
		return copy(p, b.first), nil
	}
	<-b.ctx.Done()
	return 0, b.ctx.Err()
}

func (b *cancelableBody) Close() error { return nil }
