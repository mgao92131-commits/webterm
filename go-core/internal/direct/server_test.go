package direct

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
)

// newTestServer 构建一个 Direct Server 与对应的 httptest.Server。返回的 cwd
// 是 Agent 的默认 shell 目录（上传落盘根目录）。
func newTestServer(t *testing.T) (*Server, *httptest.Server, string) {
	t.Helper()
	cwd := t.TempDir()
	cfg := config.Config{}
	cfg.Shell.CWD = cwd
	cfg.Upload.MaxBytes = 1 << 20
	application := app.New(cfg, "test")
	t.Cleanup(application.Shutdown)
	server := New(config.DirectConfig{Addr: "127.0.0.1:0", Username: "admin", Password: "pw"}, application)
	ts := httptest.NewServer(server.routes())
	t.Cleanup(ts.Close)
	return server, ts, cwd
}

func wsURL(ts *httptest.Server, path string) string {
	return "ws" + strings.TrimPrefix(ts.URL, "http") + path
}

// loginCookie 登录并返回可放入 Cookie 头的 "webterm_token=..." 字符串。
func loginCookie(t *testing.T, baseURL, username, password string) string {
	t.Helper()
	body, _ := json.Marshal(map[string]string{"username": username, "password": password})
	resp, err := http.Post(baseURL+"/api/auth/login", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatalf("login request: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("login status = %d, want 200", resp.StatusCode)
	}
	for _, cookie := range resp.Cookies() {
		if cookie.Name == AuthCookieName {
			return AuthCookieName + "=" + cookie.Value
		}
	}
	t.Fatal("login did not set an auth cookie")
	return ""
}

func doRequest(t *testing.T, method, url, cookie string, body io.Reader) *http.Response {
	t.Helper()
	req, err := http.NewRequest(method, url, body)
	if err != nil {
		t.Fatalf("NewRequest: %v", err)
	}
	if cookie != "" {
		req.Header.Set("Cookie", cookie)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("%s %s: %v", method, url, err)
	}
	return resp
}

func TestDirectLoginSuccess(t *testing.T) {
	_, ts, _ := newTestServer(t)
	body, _ := json.Marshal(map[string]string{"username": "admin", "password": "pw"})
	resp, err := http.Post(ts.URL+"/api/auth/login", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatalf("login: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("login status = %d, want 200", resp.StatusCode)
	}
	var cookies []*http.Cookie
	for _, cookie := range resp.Cookies() {
		if cookie.Name == AuthCookieName {
			cookies = append(cookies, cookie)
		}
	}
	if len(cookies) != 1 {
		t.Fatalf("expected exactly one %s cookie, got %d", AuthCookieName, len(cookies))
	}
	if !cookies[0].HttpOnly {
		t.Fatal("auth cookie must be HttpOnly")
	}
}

func TestDirectLoginWrongPassword(t *testing.T) {
	_, ts, _ := newTestServer(t)
	body, _ := json.Marshal(map[string]string{"username": "admin", "password": "nope"})
	resp, err := http.Post(ts.URL+"/api/auth/login", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatalf("login: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("wrong-password login status = %d, want 401", resp.StatusCode)
	}
	for _, cookie := range resp.Cookies() {
		if cookie.Name == AuthCookieName && cookie.Value != "" {
			t.Fatal("failed login must not issue an auth cookie")
		}
	}
}

func TestDirectRefresh(t *testing.T) {
	_, ts, _ := newTestServer(t)
	oldCookie := loginCookie(t, ts.URL, "admin", "pw")

	// 用旧 Cookie 刷新，应拿到新 Cookie。
	resp := doRequest(t, http.MethodPost, ts.URL+"/api/auth/refresh", oldCookie, strings.NewReader(""))
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("refresh status = %d, want 200", resp.StatusCode)
	}
	var newCookie string
	for _, cookie := range resp.Cookies() {
		if cookie.Name == AuthCookieName {
			newCookie = AuthCookieName + "=" + cookie.Value
		}
	}
	if newCookie == "" {
		t.Fatal("refresh did not rotate a new cookie")
	}
	if newCookie == oldCookie {
		t.Fatal("refresh should rotate to a different token")
	}

	// 旧 Token 已失效。
	meOld := doRequest(t, http.MethodGet, ts.URL+"/api/auth/me", oldCookie, nil)
	meOld.Body.Close()
	if meOld.StatusCode != http.StatusUnauthorized {
		t.Fatalf("me with old cookie status = %d, want 401", meOld.StatusCode)
	}
	// 新 Token 有效。
	meNew := doRequest(t, http.MethodGet, ts.URL+"/api/auth/me", newCookie, nil)
	meNew.Body.Close()
	if meNew.StatusCode != http.StatusOK {
		t.Fatalf("me with new cookie status = %d, want 200", meNew.StatusCode)
	}
}

// TestExpiredCookieRejectedByServer 推进 Authenticator 时钟使 Token 过期后，
// 受保护接口返回 401。
func TestExpiredCookieRejectedByServer(t *testing.T) {
	server, ts, _ := newTestServer(t)
	cookie := loginCookie(t, ts.URL, "admin", "pw")

	server.auth.now = func() time.Time { return time.Now().Add(2 * defaultTokenTTL) }
	resp := doRequest(t, http.MethodGet, ts.URL+"/api/auth/me", cookie, nil)
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("me with expired token status = %d, want 401", resp.StatusCode)
	}
}

func TestUnauthenticatedSessionRequestRejected(t *testing.T) {
	_, ts, _ := newTestServer(t)
	resp := doRequest(t, http.MethodGet, ts.URL+"/api/sessions", "", nil)
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unauthenticated /api/sessions status = %d, want 401", resp.StatusCode)
	}
}

func TestUnauthenticatedMuxRejected(t *testing.T) {
	_, ts, _ := newTestServer(t)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	// 无 Cookie：requireAuth 在 Upgrade 前返回 401，握手失败。
	conn, _, err := websocket.Dial(ctx, wsURL(ts, "/ws/sessions"), &websocket.DialOptions{
		Subprotocols: []string{"webterm.mux.v1"},
	})
	if err == nil {
		conn.Close(websocket.StatusNormalClosure, "")
		t.Fatal("unauthenticated mux dial should fail")
	}
}

func TestWrongMuxSubprotocolRejected(t *testing.T) {
	_, ts, _ := newTestServer(t)
	cookie := loginCookie(t, ts.URL, "admin", "pw")
	header := http.Header{}
	header.Set("Cookie", cookie)

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	conn, _, err := websocket.Dial(ctx, wsURL(ts, "/ws/sessions"), &websocket.DialOptions{
		Subprotocols: []string{"wrong.proto"},
		HTTPHeader:   header,
	})
	if err != nil {
		// 服务端直接拒绝握手也可接受。
		return
	}
	defer conn.Close(websocket.StatusNormalClosure, "")
	if conn.Subprotocol() == "wrong.proto" {
		t.Fatal("server must not negotiate an unsupported subprotocol")
	}
	// 服务端应以 StatusPolicyViolation 关闭连接：读取应很快返回错误。
	readCtx, cancelRead := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancelRead()
	if _, _, readErr := conn.Read(readCtx); readErr == nil {
		t.Fatal("connection with wrong subprotocol should be closed by server")
	}
}

// TestDirectMuxAccepted 正确的子协议 + 有效 Cookie 应成功完成 WebSocket 握手并
// 协商出 webterm.mux.v1。
func TestDirectMuxAccepted(t *testing.T) {
	_, ts, _ := newTestServer(t)
	cookie := loginCookie(t, ts.URL, "admin", "pw")
	header := http.Header{}
	header.Set("Cookie", cookie)

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	conn, _, err := websocket.Dial(ctx, wsURL(ts, "/ws/sessions"), &websocket.DialOptions{
		Subprotocols: []string{"webterm.mux.v1"},
		HTTPHeader:   header,
	})
	if err != nil {
		t.Fatalf("authenticated mux dial failed: %v", err)
	}
	defer conn.Close(websocket.StatusNormalClosure, "")
	if conn.Subprotocol() != "webterm.mux.v1" {
		t.Fatalf("negotiated subprotocol = %q, want webterm.mux.v1", conn.Subprotocol())
	}
}

func TestDirectSessionList(t *testing.T) {
	_, ts, _ := newTestServer(t)
	cookie := loginCookie(t, ts.URL, "admin", "pw")
	resp := doRequest(t, http.MethodGet, ts.URL+"/api/sessions", cookie, nil)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("session list status = %d, want 200", resp.StatusCode)
	}
	data, _ := io.ReadAll(resp.Body)
	var sessions []any
	if err := json.Unmarshal(data, &sessions); err != nil {
		t.Fatalf("session list body not a JSON array: %v (%s)", err, data)
	}
}

// createSession 通过 Direct HTTP 创建一个会话并返回其 ID。
func createSession(t *testing.T, baseURL, cookie string) string {
	t.Helper()
	resp := doRequest(t, http.MethodPost, baseURL+"/api/sessions", cookie, strings.NewReader(""))
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		body, _ := io.ReadAll(resp.Body)
		t.Fatalf("create session status = %d, want 201 (%s)", resp.StatusCode, body)
	}
	var info struct {
		ID string `json:"id"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&info); err != nil || info.ID == "" {
		t.Fatalf("create session returned no id: %v", err)
	}
	return info.ID
}

func TestDirectCreateAndDeleteSession(t *testing.T) {
	_, ts, _ := newTestServer(t)
	cookie := loginCookie(t, ts.URL, "admin", "pw")

	id := createSession(t, ts.URL, cookie)

	// 列表应包含新会话。
	listResp := doRequest(t, http.MethodGet, ts.URL+"/api/sessions", cookie, nil)
	listBody, _ := io.ReadAll(listResp.Body)
	listResp.Body.Close()
	if !strings.Contains(string(listBody), id) {
		t.Fatalf("session list does not contain %s: %s", id, listBody)
	}

	// 删除成功返回 204。
	delResp := doRequest(t, http.MethodDelete, ts.URL+"/api/sessions/"+id, cookie, nil)
	delResp.Body.Close()
	if delResp.StatusCode != http.StatusNoContent {
		t.Fatalf("delete session status = %d, want 204", delResp.StatusCode)
	}
	// 重复删除返回 404。
	delAgain := doRequest(t, http.MethodDelete, ts.URL+"/api/sessions/"+id, cookie, nil)
	delAgain.Body.Close()
	if delAgain.StatusCode != http.StatusNotFound {
		t.Fatalf("delete deleted session status = %d, want 404", delAgain.StatusCode)
	}
}

func TestDirectUpload(t *testing.T) {
	_, ts, cwd := newTestServer(t)
	cookie := loginCookie(t, ts.URL, "admin", "pw")
	id := createSession(t, ts.URL, cookie)
	defer func() {
		resp := doRequest(t, http.MethodDelete, ts.URL+"/api/sessions/"+id, cookie, nil)
		resp.Body.Close()
	}()

	content := []byte("hello direct upload")
	req, _ := http.NewRequest(http.MethodPost, ts.URL+"/api/sessions/"+id+"/upload", bytes.NewReader(content))
	req.Header.Set("Cookie", cookie)
	req.Header.Set("X-File-Name", "hello.txt")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("upload: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		t.Fatalf("upload status = %d, want 200 (%s)", resp.StatusCode, body)
	}
	var result struct {
		FileName     string `json:"fileName"`
		AbsolutePath string `json:"absolutePath"`
		Size         int64  `json:"size"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		t.Fatalf("decode upload result: %v", err)
	}
	if result.FileName != "hello.txt" || result.Size != int64(len(content)) {
		t.Fatalf("upload result = %#v", result)
	}
	if !strings.HasPrefix(result.AbsolutePath, cwd) {
		t.Fatalf("upload absolute path %q not under session cwd %q", result.AbsolutePath, cwd)
	}
	saved, err := os.ReadFile(result.AbsolutePath)
	if err != nil {
		t.Fatalf("read uploaded file: %v", err)
	}
	if !bytes.Equal(saved, content) {
		t.Fatalf("uploaded content = %q, want %q", saved, content)
	}
}

// TestDirectFileSendRouted 文件发送路由可达：未知 transferId 不应返回 503
// （503 意味着 FileSendService 未注入）。
func TestDirectFileSendRouted(t *testing.T) {
	_, ts, _ := newTestServer(t)
	cookie := loginCookie(t, ts.URL, "admin", "pw")
	resp := doRequest(t, http.MethodGet, ts.URL+"/api/file-send/no-such-transfer", cookie, nil)
	resp.Body.Close()
	if resp.StatusCode == http.StatusServiceUnavailable {
		t.Fatal("file-send returned 503; FileSendService was not wired")
	}
}

// TestDirectGracefulShutdown 验证取消 ctx 后服务在限定时间内优雅退出，且退出前
// 能正常处理登录请求。
func TestDirectGracefulShutdown(t *testing.T) {
	cfg := config.Config{}
	cfg.Shell.CWD = t.TempDir()
	cfg.Upload.MaxBytes = 1 << 20
	application := app.New(cfg, "test")
	t.Cleanup(application.Shutdown)
	server := New(config.DirectConfig{Addr: "127.0.0.1:0", Username: "admin", Password: "pw"}, application)

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	baseURL := "http://" + listener.Addr().String()

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- server.serve(ctx, listener) }()

	// 服务中：登录应成功。
	cookie := loginCookie(t, baseURL, "admin", "pw")
	if cookie == "" {
		t.Fatal("server not serving before shutdown")
	}

	cancel()
	select {
	case err := <-done:
		if err != nil && !errors.Is(err, context.Canceled) {
			t.Fatalf("serve returned unexpected error: %v", err)
		}
	case <-time.After(6 * time.Second):
		t.Fatal("graceful shutdown timed out")
	}
}
