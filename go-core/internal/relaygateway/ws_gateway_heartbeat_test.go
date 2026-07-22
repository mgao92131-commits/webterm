package relaygateway

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/relayrouter"
	"webterm/go-core/internal/relaystore"
	"webterm/go-core/internal/testutil"
)

// newWSGatewayTestServer 构造带真实 store/registry/stream manager 的 WSGateway
// httptest 服务器；agent 侧用 captureSender（见 http_gateway_upload_test.go）
// 捕获发往 agent 的帧。返回的 cookie 用于客户端握手鉴权。
func newWSGatewayTestServer(t *testing.T) (*httptest.Server, *WSGateway, *captureSender, string) {
	t.Helper()
	testutil.SkipIfLoopbackListenUnavailable(t)

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

	streams := relayrouter.NewStreamManager()
	gateway := NewWSGateway(store, registry, streams)
	server := httptest.NewServer(gateway)
	t.Cleanup(server.Close)

	cookie := relaycore.AuthCookieName + "=" + token.Value
	return server, gateway, sender, cookie
}

// dialWSGateway 以 Android 客户端身份连接到 /ws/sessions。
func dialWSGateway(t *testing.T, server *httptest.Server, cookie string) *websocket.Conn {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	header := http.Header{}
	header.Set("Cookie", cookie)
	header.Set("x-device-id", "dev1")
	conn, _, err := websocket.Dial(ctx, wsURL(server.URL)+"/ws/sessions", &websocket.DialOptions{HTTPHeader: header})
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	return conn
}

func countStreamClose(sender *captureSender) int {
	count := 0
	for _, frame := range sender.snapshot() {
		if frame.Type == relaycore.FrameTypeStreamClose {
			count++
		}
	}
	return count
}

func waitStreamCloseCount(t *testing.T, sender *captureSender, want int) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if countStreamClose(sender) >= want {
			return
		}
		time.Sleep(2 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %d StreamClose frames, got %d", want, countStreamClose(sender))
}

// Ping 成功时 stream 保持：客户端持续读取并自动回应 Ping，
// 多个 heartbeat 周期内不产生 StreamClose。
func TestWSGatewayHeartbeatKeepsHealthyStreamAlive(t *testing.T) {
	server, gateway, sender, cookie := newWSGatewayTestServer(t)
	gateway.heartbeatInterval = 20 * time.Millisecond
	gateway.heartbeatTimeout = time.Second

	conn := dialWSGateway(t, server, cookie)
	defer conn.Close(websocket.StatusNormalClosure, "")

	readCtx, stopRead := context.WithCancel(context.Background())
	defer stopRead()
	go func() {
		for {
			if _, _, err := conn.Read(readCtx); err != nil {
				return
			}
		}
	}()

	// 跨越多个 heartbeat 周期，健康连接不应被清理。
	time.Sleep(150 * time.Millisecond)
	if n := countStreamClose(sender); n != 0 {
		t.Fatalf("healthy stream got %d StreamClose frames, want 0", n)
	}
}

// Ping 超时后触发清理，且只发送一次 StreamClose：
// 客户端不运行 Reader（半开连接），Ping 收不到 Pong。
func TestWSGatewayHeartbeatFailureTearsDownExactlyOnce(t *testing.T) {
	server, gateway, sender, cookie := newWSGatewayTestServer(t)
	gateway.heartbeatInterval = 20 * time.Millisecond
	gateway.heartbeatTimeout = 50 * time.Millisecond

	conn := dialWSGateway(t, server, cookie)
	defer conn.Close(websocket.StatusNormalClosure, "")
	// 故意不读取：服务端 Ping 永远收不到 Pong，heartbeat 将超时。

	waitStreamCloseCount(t, sender, 1)
	// 再观察一段时间，确认不会重复发送 StreamClose。
	time.Sleep(150 * time.Millisecond)
	if n := countStreamClose(sender); n != 1 {
		t.Fatalf("StreamClose count = %d, want exactly 1", n)
	}
}

// 已有读写错误仍能立即退出：heartbeat 间隔设为远大于观察窗口，
// 客户端立即断开，清理必须由读错误而非 heartbeat 触发。
func TestWSGatewayReadErrorTearsDownBeforeHeartbeat(t *testing.T) {
	server, gateway, sender, cookie := newWSGatewayTestServer(t)
	gateway.heartbeatInterval = 10 * time.Second
	gateway.heartbeatTimeout = time.Second

	start := time.Now()
	conn := dialWSGateway(t, server, cookie)
	// 客户端立即关闭连接 → clientToAgent 的 Read 立即失败。
	conn.Close(websocket.StatusGoingAway, "client gone")

	waitStreamCloseCount(t, sender, 1)
	if elapsed := time.Since(start); elapsed > 3*time.Second {
		t.Fatalf("teardown took %s, want fast read-error path (heartbeat is 10s)", elapsed)
	}
}

// heartbeat 退出不泄漏 goroutine：ctx 取消后 runHeartbeat 必须及时返回。
func TestWSGatewayRunHeartbeatReturnsOnContextCancel(t *testing.T) {
	server, gateway, _, cookie := newWSGatewayTestServer(t)
	gateway.heartbeatInterval = time.Hour // 不让 ticker 触发，仅测退出路径

	conn := dialWSGateway(t, server, cookie)
	defer conn.Close(websocket.StatusNormalClosure, "")

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- gateway.runHeartbeat(ctx, conn) }()

	cancel()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("runHeartbeat returned nil after cancel, want ctx error")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("runHeartbeat did not exit after ctx cancel (goroutine leak)")
	}
}

// heartbeat 被禁用（interval<=0）时不得提前返回 nil，否则会误触发 ServeHTTP 清理。
func TestWSGatewayRunHeartbeatDisabledWaitsForCancel(t *testing.T) {
	gateway := NewWSGateway(nil, nil, nil)
	gateway.heartbeatInterval = 0

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- gateway.runHeartbeat(ctx, nil) }()

	select {
	case <-done:
		t.Fatal("disabled heartbeat returned before ctx cancel; would tear down stream spuriously")
	case <-time.After(80 * time.Millisecond):
	}

	cancel()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("disabled heartbeat did not exit after ctx cancel")
	}
}
