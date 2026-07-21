package agentrouter

import (
	"encoding/json"
	"net/http"
	"strings"
	"testing"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
)

func newTestApp(t *testing.T) *app.App {
	t.Helper()
	application := app.New(config.Config{}, "test")
	t.Cleanup(application.Shutdown)
	return application
}

// TestNewReturnsUsableRouter 基础装配：router 非空且能处理 session CRUD。
func TestNewReturnsUsableRouter(t *testing.T) {
	router := New(newTestApp(t), "direct")
	if router == nil {
		t.Fatal("New returned nil router")
	}
	status, body, err := router.RouteHTTP(http.MethodGet, "/api/sessions", nil)
	if err != nil {
		t.Fatalf("RouteHTTP GET /api/sessions: %v", err)
	}
	if status != http.StatusOK {
		t.Fatalf("GET /api/sessions status = %d, body=%s", status, body)
	}
	var sessions []any
	if err := json.Unmarshal(body, &sessions); err != nil {
		t.Fatalf("GET /api/sessions body is not a JSON array: %v", err)
	}
}

// TestNewWiresUploadService 上传服务已注入：对不存在的 session 上传应返回 404
// （服务被调用），而不是 503（服务缺失）。
func TestNewWiresUploadService(t *testing.T) {
	router := New(newTestApp(t), "direct")
	header := http.Header{"X-File-Name": []string{"a.txt"}}
	result, err := router.RouteHTTPv2(http.MethodPost, "/api/sessions/no-such-session/upload", header, strings.NewReader("x"))
	if err != nil {
		t.Fatalf("RouteHTTPv2 upload: %v", err)
	}
	if result.StatusCode == http.StatusServiceUnavailable {
		t.Fatalf("upload returned 503; FileUploadService was not injected")
	}
	if result.StatusCode != http.StatusNotFound {
		t.Fatalf("upload to unknown session status = %d, want 404", result.StatusCode)
	}
}

// TestNewWiresFileSendService 文件发送服务已注入：未知 transferId 不应返回 503。
func TestNewWiresFileSendService(t *testing.T) {
	router := New(newTestApp(t), "direct")
	result, err := router.RouteHTTPv2(http.MethodGet, "/api/file-send/no-such-transfer", http.Header{}, nil)
	if err != nil {
		t.Fatalf("RouteHTTPv2 file-send: %v", err)
	}
	if result.StatusCode == http.StatusServiceUnavailable {
		t.Fatalf("file-send returned 503; FileSendService was not injected")
	}
}
