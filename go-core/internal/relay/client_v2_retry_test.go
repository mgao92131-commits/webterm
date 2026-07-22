package relay

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/testutil"
)

// newRegisterErrorRelay 启动一个假的 Relay：接受 WebSocket、读取 agent.register，
// 然后按 mode 响应。每次连接计数一次（realtime plane 注册失败即返回，故每次
// runOnce 恰好一次连接）。
//
//	mode="error"：回写 {"type":"agent.error","code":code,"retryable":retryable} 后关闭。
//	mode="close"：不响应直接关闭连接（模拟连接被对端中断 → connection_closed）。
func newRegisterErrorRelay(t *testing.T, mode, code string, retryable bool, attempts *atomic.Int64) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		defer conn.Close(websocket.StatusNormalClosure, "")
		if _, _, err := conn.Read(r.Context()); err != nil {
			return
		}
		attempts.Add(1)
		if mode == "close" {
			_ = conn.Close(websocket.StatusGoingAway, "gone")
			return
		}
		data, _ := json.Marshal(map[string]any{
			"type":      v2AgentErrorMessage,
			"code":      code,
			"retryable": retryable,
		})
		_ = conn.Write(r.Context(), websocket.MessageText, data)
		_ = conn.Close(websocket.StatusPolicyViolation, "rejected")
	}))
}

func newRetryTestClient(t *testing.T, serverURL string) (*V2Client, *app.App) {
	t.Helper()
	cfg := config.Config{
		Relay: config.RelayConfig{URL: serverURL, Secret: "test-secret"},
		Shell: config.ShellConfig{Command: "/bin/sh", CWD: "."},
	}
	application := app.New(cfg, "test")
	t.Cleanup(application.Shutdown)
	return NewV2(cfg.Relay, application), application
}

func waitForAttempts(t *testing.T, attempts *atomic.Int64, want int64) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if attempts.Load() >= want {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %d attempts, got %d", want, attempts.Load())
}

// 不可重试的凭据错误：只注册一次，随后停止重试并保持阻塞（进程存活），
// 直到 ctx 取消才返回。
func TestV2ClientStopsRetryingInvalidCredential(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)
	var attempts atomic.Int64
	server := newRegisterErrorRelay(t, "error", "invalid_credential", false, &attempts)
	defer server.Close()
	client, _ := newRetryTestClient(t, server.URL)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- client.Run(ctx) }()

	waitForAttempts(t, &attempts, 1)
	// 等待超过首个 backoff（1s）：若仍在重试，此时应已出现第 2 次注册。
	time.Sleep(1500 * time.Millisecond)
	if got := attempts.Load(); got != 1 {
		t.Fatalf("register attempts = %d, want 1 (invalid credential must not retry)", got)
	}
	// Run 应仍阻塞（进程存活），未返回。
	select {
	case err := <-done:
		t.Fatalf("Run returned %v, want blocked until ctx cancel", err)
	default:
	}

	cancel()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("Run returned nil after cancel, want ctx error")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Run did not return after ctx cancel")
	}
}

// 服务端临时错误（server_busy，可重试）：按 backoff 持续重试。
func TestV2ClientRetriesServerBusy(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)
	var attempts atomic.Int64
	server := newRegisterErrorRelay(t, "error", "server_busy", true, &attempts)
	defer server.Close()
	client, _ := newRetryTestClient(t, server.URL)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = client.Run(ctx) }()

	waitForAttempts(t, &attempts, 2)
}

// 连接被对端中断（connection_closed，可重试）：按 backoff 持续重连。
func TestV2ClientRetriesConnectionClosed(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)
	var attempts atomic.Int64
	server := newRegisterErrorRelay(t, "close", "", false, &attempts)
	defer server.Close()
	client, _ := newRetryTestClient(t, server.URL)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = client.Run(ctx) }()

	waitForAttempts(t, &attempts, 2)
}

// 配置错误下进程保持存活：Relay 状态为 disconnected 且 lastErrorKind 保留为
// auth_rejected，Run 不返回（不触发 Supervisor 结束进程 / KeepAlive 重启风暴）。
func TestV2ClientKeepsProcessAliveInConfigError(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)
	var attempts atomic.Int64
	server := newRegisterErrorRelay(t, "error", "invalid_credential", false, &attempts)
	defer server.Close()
	client, application := newRetryTestClient(t, server.URL)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- client.Run(ctx) }()

	waitForAttempts(t, &attempts, 1)

	status := application.DiagnosticsRelayStatus()
	if status["connected"] != false {
		t.Errorf("relay connected = %v, want false", status["connected"])
	}
	if status["lastErrorKind"] != string(app.RelayErrorAuthRejected) {
		t.Errorf("lastErrorKind = %v, want %s", status["lastErrorKind"], app.RelayErrorAuthRejected)
	}
	select {
	case err := <-done:
		t.Fatalf("Run returned %v, want blocked (process stays alive)", err)
	default:
	}
}
