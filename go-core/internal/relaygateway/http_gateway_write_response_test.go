package relaygateway

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/relayrouter"
)

func newTestHandle(t *testing.T, timeout time.Duration) (*relayrouter.StreamManager, relayrouter.StreamHandle) {
	t.Helper()
	mgr := relayrouter.NewStreamManagerForTest(nil, 4)
	handle := mgr.CreateStream(relaycore.StreamKindHTTP, relaycore.StreamRoute{Path: "/api/file-send/t1"}, "u", "d", "a", timeout)
	if !mgr.Open(handle.ID) {
		t.Fatal("open stream failed")
	}
	return mgr, handle
}

func pushResponse(t *testing.T, mgr *relayrouter.StreamManager, handle relayrouter.StreamHandle, delay time.Duration, body string) {
	t.Helper()
	go func() {
		time.Sleep(delay)
		meta, _ := json.Marshal(relaycore.HTTPResponseMeta{StatusCode: http.StatusOK})
		mgr.HandleAgentFrame(relaycore.NewFrame(relaycore.FrameTypeHTTPHeaders, handle.ID, 0, meta))
		mgr.HandleAgentFrame(relaycore.NewFrame(relaycore.FrameTypeHTTPChunk, handle.ID, relaycore.FrameFlagFin, []byte(body)))
	}()
}

// 文件流（streamTimeout=0）即使首字节延迟超过普通 30s 总超时的尺度，也不应被 504。
func TestWriteResponseNoTimeoutForFileStream(t *testing.T) {
	mgr, handle := newTestHandle(t, 0)
	pushResponse(t, mgr, handle, 40*time.Millisecond, "hello-file")

	rec := httptest.NewRecorder()
	(&HTTPGateway{}).writeResponse(rec, context.Background(), handle, 0)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d (body=%q)", rec.Code, rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), "hello-file") {
		t.Fatalf("expected streamed body, got %q", rec.Body.String())
	}
}

// 普通请求保留总超时：首字节迟迟不到应 504。
func TestWriteResponseHonorsTotalTimeout(t *testing.T) {
	mgr, handle := newTestHandle(t, 20*time.Millisecond)
	pushResponse(t, mgr, handle, 60*time.Millisecond, "too-late")

	rec := httptest.NewRecorder()
	(&HTTPGateway{}).writeResponse(rec, context.Background(), handle, 20*time.Millisecond)

	if rec.Code != http.StatusGatewayTimeout {
		t.Fatalf("expected 504, got %d", rec.Code)
	}
}
