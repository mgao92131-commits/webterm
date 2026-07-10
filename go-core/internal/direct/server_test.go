package direct

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/mux"
	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/session"
	"webterm/go-core/internal/testutil"
)

func TestDirectLoginAndSessionCRUD(t *testing.T) {
	cfg := config.Config{
		Mode:   config.ModeDirect,
		Direct: config.DirectConfig{Addr: "127.0.0.1:0", User: "admin", Password: "pw"},
	}
	application := app.New(cfg, "test")
	server := New(cfg.Direct, application)

	loginBody := bytes.NewBufferString(`{"username":"admin","password":"pw"}`)
	loginResponse := httptest.NewRecorder()
	server.route(loginResponse, httptest.NewRequest(http.MethodPost, "/api/login", loginBody))
	if loginResponse.Code != http.StatusOK {
		t.Fatalf("login status = %d, want 200 body=%s", loginResponse.Code, loginResponse.Body.String())
	}
	cookies := loginResponse.Result().Cookies()
	if len(cookies) == 0 {
		t.Fatalf("login did not set cookies")
	}

	createBody := bytes.NewBufferString(`{"name":"work","cwd":"/tmp"}`)
	createRequest := httptest.NewRequest(http.MethodPost, "/api/sessions", createBody)
	createRequest.AddCookie(cookies[0])
	createResponse := httptest.NewRecorder()
	server.route(createResponse, createRequest)
	if createResponse.Code != http.StatusCreated {
		t.Fatalf("create status = %d, want 201 body=%s", createResponse.Code, createResponse.Body.String())
	}
	var created session.Info
	if err := json.Unmarshal(createResponse.Body.Bytes(), &created); err != nil {
		t.Fatalf("decode create body: %v", err)
	}
	if created.ID != "s1" {
		t.Fatalf("created ID = %q, want s1", created.ID)
	}

	renameBody := bytes.NewBufferString(`{"name":"renamed"}`)
	renameRequest := httptest.NewRequest(http.MethodPatch, "/api/sessions/s1", renameBody)
	renameRequest.AddCookie(cookies[0])
	renameResponse := httptest.NewRecorder()
	server.route(renameResponse, renameRequest)
	if renameResponse.Code != http.StatusOK {
		t.Fatalf("rename status = %d, want 200 body=%s", renameResponse.Code, renameResponse.Body.String())
	}

	deleteRequest := httptest.NewRequest(http.MethodDelete, "/api/sessions/s1", nil)
	deleteRequest.AddCookie(cookies[0])
	deleteResponse := httptest.NewRecorder()
	server.route(deleteResponse, deleteRequest)
	if deleteResponse.Code != http.StatusNoContent {
		t.Fatalf("delete status = %d, want 204 body=%s", deleteResponse.Code, deleteResponse.Body.String())
	}
}

func TestDirectAPIRequiresAuth(t *testing.T) {
	cfg := config.Config{
		Mode:   config.ModeDirect,
		Direct: config.DirectConfig{Addr: "127.0.0.1:0", User: "admin", Password: "pw"},
	}
	application := app.New(cfg, "test")
	server := New(cfg.Direct, application)

	response := httptest.NewRecorder()
	server.route(response, httptest.NewRequest(http.MethodGet, "/api/sessions", nil))
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", response.Code)
	}
}

func TestDirectServesStaticWebRoot(t *testing.T) {
	webRoot := t.TempDir()
	if err := os.MkdirAll(filepath.Join(webRoot, "assets"), 0o755); err != nil {
		t.Fatalf("MkdirAll: %v", err)
	}
	if err := os.WriteFile(filepath.Join(webRoot, "index.html"), []byte("<!doctype html><div id=\"app\"></div>"), 0o644); err != nil {
		t.Fatalf("WriteFile index: %v", err)
	}
	if err := os.WriteFile(filepath.Join(webRoot, "assets", "app.js"), []byte("console.log('ok')"), 0o644); err != nil {
		t.Fatalf("WriteFile asset: %v", err)
	}

	cfg := config.Config{
		Mode:   config.ModeDirect,
		Direct: config.DirectConfig{Addr: "127.0.0.1:0", User: "admin", Password: "pw", WebRoot: webRoot},
	}
	application := app.New(cfg, "test")
	server := New(cfg.Direct, application)

	indexResponse := httptest.NewRecorder()
	server.route(indexResponse, httptest.NewRequest(http.MethodGet, "/", nil))
	if indexResponse.Code != http.StatusOK || !strings.Contains(indexResponse.Body.String(), `id="app"`) {
		t.Fatalf("index response code=%d body=%s", indexResponse.Code, indexResponse.Body.String())
	}

	assetResponse := httptest.NewRecorder()
	server.route(assetResponse, httptest.NewRequest(http.MethodGet, "/assets/app.js", nil))
	if assetResponse.Code != http.StatusOK || !strings.Contains(assetResponse.Body.String(), "console.log") {
		t.Fatalf("asset response code=%d body=%s", assetResponse.Code, assetResponse.Body.String())
	}

	fallbackResponse := httptest.NewRecorder()
	server.route(fallbackResponse, httptest.NewRequest(http.MethodGet, "/terminal/s1", nil))
	if fallbackResponse.Code != http.StatusOK || !strings.Contains(fallbackResponse.Body.String(), `id="app"`) {
		t.Fatalf("fallback response code=%d body=%s", fallbackResponse.Code, fallbackResponse.Body.String())
	}
}

func TestDirectAPIDoesNotFallBackToStatic(t *testing.T) {
	webRoot := t.TempDir()
	if err := os.WriteFile(filepath.Join(webRoot, "index.html"), []byte("index"), 0o644); err != nil {
		t.Fatalf("WriteFile index: %v", err)
	}
	cfg := config.Config{
		Mode:   config.ModeDirect,
		Direct: config.DirectConfig{Addr: "127.0.0.1:0", User: "admin", Password: "pw", WebRoot: webRoot},
	}
	application := app.New(cfg, "test")
	server := New(cfg.Direct, application)

	response := httptest.NewRecorder()
	server.route(response, httptest.NewRequest(http.MethodGet, "/api/does-not-exist", nil))
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401; body=%s", response.Code, response.Body.String())
	}
}

func TestResolveStaticRootFindsParentWebDirectory(t *testing.T) {
	root := t.TempDir()
	webRoot := filepath.Join(root, "web")
	nested := filepath.Join(root, "go-core", "cmd", "webterm-agent")
	if err := os.MkdirAll(webRoot, 0o755); err != nil {
		t.Fatalf("MkdirAll web: %v", err)
	}
	if err := os.MkdirAll(nested, 0o755); err != nil {
		t.Fatalf("MkdirAll nested: %v", err)
	}
	if err := os.WriteFile(filepath.Join(webRoot, "index.html"), []byte("index"), 0o644); err != nil {
		t.Fatalf("WriteFile index: %v", err)
	}
	oldWD, err := os.Getwd()
	if err != nil {
		t.Fatalf("Getwd: %v", err)
	}
	defer os.Chdir(oldWD)
	if err := os.Chdir(nested); err != nil {
		t.Fatalf("Chdir: %v", err)
	}
	got, err := filepath.EvalSymlinks(resolveStaticRoot(""))
	if err != nil {
		t.Fatalf("EvalSymlinks got: %v", err)
	}
	want, err := filepath.EvalSymlinks(webRoot)
	if err != nil {
		t.Fatalf("EvalSymlinks want: %v", err)
	}
	if got != want {
		t.Fatalf("resolveStaticRoot = %q, want %q", got, want)
	}
}

func TestResolveStaticRootUsesConfiguredPath(t *testing.T) {
	if got := resolveStaticRoot("/tmp/custom-web"); got != "/tmp/custom-web" {
		t.Fatalf("resolveStaticRoot configured = %q", got)
	}
}

func TestDirectManagerWebSocketReceivesSessionUpdates(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)

	cfg := config.Config{
		Mode:   config.ModeDirect,
		Direct: config.DirectConfig{Addr: "127.0.0.1:0", User: "admin", Password: "pw"},
		Shell:  config.ShellConfig{Command: "/bin/sh", CWD: "."},
	}
	application := app.New(cfg, "test")
	directServer := New(cfg.Direct, application)
	httpServer := httptest.NewServer(http.HandlerFunc(directServer.route))
	defer httpServer.Close()

	jar, client := authenticatedClient(t, httpServer)
	defer client.CloseIdleConnections()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	headers := authHeaders(jar, httpServer.URL)
	conn, err := dialMux(ctx, httpServer.URL, headers)
	if err != nil {
		t.Fatalf("dial mux: %v", err)
	}
	defer conn.Close(websocket.StatusNormalClosure, "")

	if err := openMuxChannel(ctx, conn, "manager", "/ws/sessions", nil); err != nil {
		t.Fatalf("open manager channel: %v", err)
	}

	_, first, err := readMuxText(ctx, conn, "manager")
	if err != nil {
		t.Fatalf("read initial manager message: %v", err)
	}
	var initial session.ManagerMessage
	if err := json.Unmarshal(first, &initial); err != nil {
		t.Fatalf("decode initial: %v", err)
	}
	if initial.Type != "sessions" {
		t.Fatalf("initial type = %q, want sessions", initial.Type)
	}

	createBody := bytes.NewBufferString(`{"name":"manager-ws","cwd":"."}`)
	createRequest, err := http.NewRequest(http.MethodPost, httpServer.URL+"/api/sessions", createBody)
	if err != nil {
		t.Fatalf("create NewRequest: %v", err)
	}
	createRequest.Header.Set("Content-Type", "application/json")
	createResponse, err := client.Do(createRequest)
	if err != nil {
		t.Fatalf("create request: %v", err)
	}
	_ = createResponse.Body.Close()
	if createResponse.StatusCode != http.StatusCreated {
		t.Fatalf("create status = %d", createResponse.StatusCode)
	}

	_, update, err := readMuxText(ctx, conn, "manager")
	if err != nil {
		t.Fatalf("read session update: %v", err)
	}
	var message session.ManagerMessage
	if err := json.Unmarshal(update, &message); err != nil {
		t.Fatalf("decode update: %v", err)
	}
	if message.Type != "session" {
		t.Fatalf("update type = %q, want session", message.Type)
	}
}

func TestDirectMuxEndpointManagerChannel(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)

	cfg := config.Config{
		Mode:   config.ModeDirect,
		Direct: config.DirectConfig{Addr: "127.0.0.1:0", User: "admin", Password: "pw"},
		Shell:  config.ShellConfig{Command: "/bin/sh", CWD: "."},
	}
	application := app.New(cfg, "test")
	directServer := New(cfg.Direct, application)
	httpServer := httptest.NewServer(http.HandlerFunc(directServer.route))
	defer httpServer.Close()

	jar, err := cookiejar.New(nil)
	if err != nil {
		t.Fatalf("cookiejar.New: %v", err)
	}
	client := httpServer.Client()
	client.Jar = jar
	loginBody := bytes.NewBufferString(`{"username":"admin","password":"pw"}`)
	loginRequest, err := http.NewRequest(http.MethodPost, httpServer.URL+"/api/login", loginBody)
	if err != nil {
		t.Fatalf("NewRequest: %v", err)
	}
	loginRequest.Header.Set("Content-Type", "application/json")
	loginResponse, err := client.Do(loginRequest)
	if err != nil {
		t.Fatalf("login request: %v", err)
	}
	_ = loginResponse.Body.Close()
	if loginResponse.StatusCode != http.StatusOK {
		t.Fatalf("login status = %d", loginResponse.StatusCode)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	headers := http.Header{}
	for _, cookie := range jar.Cookies(loginRequest.URL) {
		headers.Add("Cookie", cookie.String())
	}
	wsURL := "ws" + httpServer.URL[len("http"):] + "/ws/sessions"
	conn, _, err := websocket.Dial(ctx, wsURL, &websocket.DialOptions{
		HTTPHeader:   headers,
		Subprotocols: []string{protocol.MuxSubprotocol},
	})
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close(websocket.StatusNormalClosure, "")
	if conn.Subprotocol() != protocol.MuxSubprotocol {
		t.Fatalf("subprotocol = %q, want %q", conn.Subprotocol(), protocol.MuxSubprotocol)
	}

	// 发 ws-connect 建 manager 通道。
	msgBytes, _ := json.Marshal(map[string]any{
		"type":               protocol.WSConnect,
		"tunnelConnectionId": "manager",
		"path":               "/ws/sessions",
		"protocols":          []string{},
	})
	wctx, wcancel := context.WithTimeout(ctx, 5*time.Second)
	defer wcancel()
	if err := conn.Write(wctx, websocket.MessageText, msgBytes); err != nil {
		t.Fatalf("write ws-connect: %v", err)
	}

	_, data, err := conn.Read(ctx)
	if err != nil {
		t.Fatalf("read ws-connected: %v", err)
	}
	var connected map[string]any
	if err := json.Unmarshal(data, &connected); err != nil {
		t.Fatalf("decode ws-connected: %v", err)
	}
	if connected["type"] != protocol.WSConnected {
		t.Fatalf("ws-connected = %#v", connected)
	}
	cancel()
}

func TestDirectMuxTerminalChannel(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)

	cfg := config.Config{
		Mode:   config.ModeDirect,
		Direct: config.DirectConfig{Addr: "127.0.0.1:0", User: "admin", Password: "pw"},
		Shell:  config.ShellConfig{Command: "/bin/sh", CWD: "."},
	}
	application := app.New(cfg, "test")
	directServer := New(cfg.Direct, application)
	httpServer := httptest.NewServer(http.HandlerFunc(directServer.route))
	defer httpServer.Close()

	jar, client := authenticatedClient(t, httpServer)
	defer client.CloseIdleConnections()

	sessionID, err := createSession(client, httpServer.URL, "mux-term", ".")
	if err != nil {
		t.Fatalf("create session: %v", err)
	}
	defer application.Sessions().Close(sessionID)

	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	conn, err := dialMux(ctx, httpServer.URL, authHeaders(jar, httpServer.URL))
	if err != nil {
		t.Fatalf("dial mux: %v", err)
	}
	defer conn.Close(websocket.StatusNormalClosure, "")

	if err := openMuxChannel(ctx, conn, "term1", "/ws/sessions/"+sessionID, []string{protocol.ScreenSubprotocol}); err != nil {
		t.Fatalf("open terminal channel: %v", err)
	}

	hello, _ := json.Marshal(map[string]any{"type": "hello", "cols": 100, "rows": 30, "lastSeq": 0})
	if err := writeMuxText(ctx, conn, "term1", hello); err != nil {
		t.Fatalf("write hello: %v", err)
	}

	var sawScreenState, sawInfo bool
	for i := 0; i < 2; i++ {
		_, data, err := readMuxText(ctx, conn, "term1")
		if err != nil {
			t.Fatalf("read terminal message: %v", err)
		}
		switch {
		case strings.Contains(string(data), `"type":"screen-state"`):
			sawScreenState = true
		case strings.Contains(string(data), `"type":"info"`):
			sawInfo = true
		default:
			t.Fatalf("unexpected message: %s", data)
		}
	}
	if !sawScreenState {
		t.Fatalf("did not receive screen-state")
	}
	if !sawInfo {
		t.Fatalf("did not receive info")
	}
}

func authenticatedClient(t *testing.T, httpServer *httptest.Server) (*cookiejar.Jar, *http.Client) {
	t.Helper()
	jar, err := cookiejar.New(nil)
	if err != nil {
		t.Fatalf("cookiejar.New: %v", err)
	}
	client := httpServer.Client()
	client.Jar = jar
	loginBody := bytes.NewBufferString(`{"username":"admin","password":"pw"}`)
	loginResponse, err := client.Post(httpServer.URL+"/api/login", "application/json", loginBody)
	if err != nil {
		t.Fatalf("login request: %v", err)
	}
	_ = loginResponse.Body.Close()
	if loginResponse.StatusCode != http.StatusOK {
		t.Fatalf("login status = %d", loginResponse.StatusCode)
	}
	return jar, client
}

func authHeaders(jar *cookiejar.Jar, serverURL string) http.Header {
	parsed, err := url.Parse(serverURL)
	if err != nil {
		return http.Header{}
	}
	headers := http.Header{}
	for _, cookie := range jar.Cookies(parsed) {
		headers.Add("Cookie", cookie.String())
	}
	return headers
}

func createSession(client *http.Client, serverURL, name, cwd string) (string, error) {
	body := bytes.NewBufferString(fmt.Sprintf(`{"name":%q,"cwd":%q}`, name, cwd))
	req, err := http.NewRequest(http.MethodPost, serverURL+"/api/sessions", body)
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")
	res, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusCreated {
		return "", fmt.Errorf("create status %d", res.StatusCode)
	}
	var info session.Info
	if err := json.NewDecoder(res.Body).Decode(&info); err != nil {
		return "", err
	}
	return info.ID, nil
}

func dialMux(ctx context.Context, serverURL string, headers http.Header) (*websocket.Conn, error) {
	wsURL := "ws" + serverURL[len("http"):] + "/ws/sessions"
	conn, _, err := websocket.Dial(ctx, wsURL, &websocket.DialOptions{
		HTTPHeader:   headers,
		Subprotocols: []string{protocol.MuxSubprotocol},
	})
	return conn, err
}

func openMuxChannel(ctx context.Context, conn *websocket.Conn, id, path string, protocols []string) error {
	msgBytes, _ := json.Marshal(map[string]any{
		"type":               protocol.WSConnect,
		"tunnelConnectionId": id,
		"path":               path,
		"protocols":          protocols,
	})
	if err := conn.Write(ctx, websocket.MessageText, msgBytes); err != nil {
		return err
	}
	_, data, err := conn.Read(ctx)
	if err != nil {
		return err
	}
	var connected map[string]any
	if err := json.Unmarshal(data, &connected); err != nil {
		return err
	}
	if connected["type"] != protocol.WSConnected {
		return fmt.Errorf("expected ws-connected, got %#v", connected)
	}
	return nil
}

func readMuxText(ctx context.Context, conn *websocket.Conn, id string) (websocket.MessageType, []byte, error) {
	for {
		mt, data, err := conn.Read(ctx)
		if err != nil {
			return 0, nil, err
		}
		if mt != websocket.MessageBinary {
			continue
		}
		frame, err := protocol.DecodeTunnelFrame(data)
		if err != nil {
			continue
		}
		if frame.MsgType != protocol.MsgTypeWSData || frame.ID != id || frame.ExtraByte != protocol.WSDataText {
			continue
		}
		return mt, frame.Payload, nil
	}
}

func writeMuxText(ctx context.Context, conn *websocket.Conn, id string, payload []byte) error {
	frame, err := protocol.EncodeTunnelFrame(protocol.MsgTypeWSData, id, protocol.WSDataText, payload)
	if err != nil {
		return err
	}
	return conn.Write(ctx, websocket.MessageBinary, frame)
}

type noopSocket struct{}

func (noopSocket) Read(ctx context.Context) (session.MessageType, []byte, error) {
	<-ctx.Done()
	return 0, nil, ctx.Err()
}
func (noopSocket) Write(context.Context, session.MessageType, []byte) error { return nil }
func (noopSocket) Close() error                                             { return nil }

func TestBindDeviceSenderRegistersAndUnregisters(t *testing.T) {
	cfg := config.Config{Mode: config.ModeDirect, Direct: config.DirectConfig{Addr: "127.0.0.1:0"}}
	application := app.New(cfg, "test")
	server := New(cfg.Direct, application)
	svc := application.FileSendService()

	sess := mux.Serve(noopSocket{}, &mux.ServeOpts{})

	if svc.HasSender("dev-1") {
		t.Fatal("sender should not be registered before bind")
	}
	unbind := server.bindDeviceSender("dev-1", sess)
	if !svc.HasSender("dev-1") {
		t.Fatal("sender should be registered after bind")
	}
	unbind()
	if svc.HasSender("dev-1") {
		t.Fatal("sender should be unregistered after unbind")
	}
}

func TestBindDeviceSenderEmptyDeviceUsesPlaceholder(t *testing.T) {
	cfg := config.Config{Mode: config.ModeDirect, Direct: config.DirectConfig{Addr: "127.0.0.1:0"}}
	application := app.New(cfg, "test")
	server := New(cfg.Direct, application)
	svc := application.FileSendService()

	sess := mux.Serve(noopSocket{}, &mux.ServeOpts{})
	unbind := server.bindDeviceSender("", sess)
	defer unbind()
	if !svc.HasSender("direct") {
		t.Fatal("empty deviceID should register under placeholder key 'direct'")
	}
}

func TestBindDeviceSenderNilSessionIsNoOp(t *testing.T) {
	cfg := config.Config{Mode: config.ModeDirect, Direct: config.DirectConfig{Addr: "127.0.0.1:0"}}
	application := app.New(cfg, "test")
	server := New(cfg.Direct, application)
	unbind := server.bindDeviceSender("dev-1", nil)
	unbind() // 不应 panic
}
