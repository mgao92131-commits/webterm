package relayapp

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/testutil"
)

// 端到端：Android -> Gateway -> Relay -> Agent 的上传请求流。
// 300 个 64 KiB 分块（约 19 MiB）+ 慢速 agent（每帧 2ms）使 relay 侧 256
// 发送队列被填满，从而覆盖 ErrBackpressure 等待重试路径：慢消费者必须
// 被背压拖慢，而不是丢帧或 502。
func TestAppStreamsUploadRequestBodyToAgent(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)

	app := NewInMemory(Config{})
	user, err := app.Store().CreateUser("owner@example.com", "secret", "admin")
	if err != nil {
		t.Fatalf("CreateUser returned error: %v", err)
	}
	device, credential, err := app.Store().CreateDevice(user.ID, "Agent Mac")
	if err != nil {
		t.Fatalf("CreateDevice returned error: %v", err)
	}
	token, err := app.Store().IssueToken(user.ID, time.Hour)
	if err != nil {
		t.Fatalf("IssueToken returned error: %v", err)
	}

	server := httptest.NewServer(app.Handler())
	defer server.Close()

	body := make([]byte, 300*64*1024)
	for i := range body {
		body[i] = byte(i)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	agentDone := make(chan error, 1)
	agentReady := make(chan struct{})
	go func() {
		agentDone <- runUploadCollectAgent(ctx, server.URL, credential, body, agentReady)
	}()
	<-agentReady

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, server.URL+"/api/sessions/s1/upload", bytes.NewReader(body))
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	req.AddCookie(&http.Cookie{Name: relaycore.AuthCookieName, Value: token.Value})
	req.Header.Set("x-device-id", device.ID)
	req.Header.Set("X-File-Name", "big.bin")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("Do returned error: %v", err)
	}
	defer res.Body.Close()
	respBody, _ := io.ReadAll(res.Body)
	if res.StatusCode != http.StatusOK {
		t.Fatalf("status = %d body=%s, want 200（慢速 agent 不应导致 502）", res.StatusCode, respBody)
	}
	if err := <-agentDone; err != nil {
		t.Fatalf("agent failed: %v", err)
	}
	if len(app.Streams().Snapshot()) != 0 {
		t.Fatalf("streams still active: %#v", app.Streams().Snapshot())
	}
}

// runUploadCollectAgent 扮演 agent：校验 HTTPHeaders 元数据（含显式 Content-Length），
// 按 64 KiB 分块收完整个 body（每帧故意 sleep 2ms 模拟慢消费者），
// 校验内容逐字节一致与空 FIN 语义，最后返回正常 HTTP 200 响应帧。
func runUploadCollectAgent(ctx context.Context, serverURL, credential string, want []byte, ready chan<- struct{}) error {
	realtime, err := dialRegisteredAgentPlane(ctx, serverURL, credential, "realtime")
	if err != nil {
		return err
	}
	defer realtime.Close(websocket.StatusNormalClosure, "")
	conn, err := dialRegisteredAgentPlane(ctx, serverURL, credential, "bulk")
	if err != nil {
		return err
	}
	defer conn.Close(websocket.StatusNormalClosure, "")
	conn.SetReadLimit(8 << 20)
	close(ready)

	frame, err := readRelayFrame(ctx, conn)
	if err != nil {
		return err
	}
	if frame.Type != relaycore.FrameTypeHTTPHeaders {
		return errUnexpected("first frame type", frame.Type, relaycore.FrameTypeHTTPHeaders)
	}
	var meta relaycore.HTTPRequestMeta
	if err := json.Unmarshal(frame.Payload, &meta); err != nil {
		return err
	}
	if meta.Method != http.MethodPost || meta.Path != "/api/sessions/s1/upload" {
		return errUnexpected("request meta", meta.Method+" "+meta.Path, "POST /api/sessions/s1/upload")
	}
	if got := meta.Headers["Content-Length"]; got != strconv.Itoa(len(want)) {
		return errUnexpected("Content-Length header", got, strconv.Itoa(len(want)))
	}

	offset := 0
	chunks := 0
	for {
		// 慢消费者：拖慢 relay 发送队列排水，触发背压。
		time.Sleep(2 * time.Millisecond)
		chunk, err := readRelayFrame(ctx, conn)
		if err != nil {
			return err
		}
		if chunk.Type != relaycore.FrameTypeHTTPChunk {
			return errUnexpected("frame type", chunk.Type, relaycore.FrameTypeHTTPChunk)
		}
		if chunk.StreamID != frame.StreamID {
			return errUnexpected("chunk stream id", chunk.StreamID, frame.StreamID)
		}
		if len(chunk.Payload) > 0 {
			if offset+len(chunk.Payload) > len(want) {
				return errUnexpected("received bytes", offset+len(chunk.Payload), len(want))
			}
			if !bytes.Equal(chunk.Payload, want[offset:offset+len(chunk.Payload)]) {
				return errUnexpected("chunk payload at offset", offset, "matching body")
			}
			offset += len(chunk.Payload)
			chunks++
			if chunk.Flags.Has(relaycore.FrameFlagFin) {
				return errUnexpected("FIN on data chunk", true, false)
			}
			continue
		}
		// 空 payload：必须是 FIN，且此前已收完整个 body。
		if !chunk.Flags.Has(relaycore.FrameFlagFin) {
			return errUnexpected("empty chunk without FIN", chunk.Flags, relaycore.FrameFlagFin)
		}
		if offset != len(want) {
			return errUnexpected("FIN after bytes", offset, len(want))
		}
		break
	}
	if chunks < 2 {
		return errUnexpected("chunk count", chunks, ">= 2（应流式分块而非单帧）")
	}

	responseMeta, _ := json.Marshal(relaycore.HTTPResponseMeta{
		StatusCode: http.StatusOK,
		Headers:    map[string]string{"content-type": "application/json"},
	})
	if err := writeRelayFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeHTTPHeaders, frame.StreamID, 0, responseMeta)); err != nil {
		return err
	}
	return writeRelayFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeHTTPChunk, frame.StreamID, relaycore.FrameFlagFin, []byte(`{"fileName":"big.bin"}`)))
}
