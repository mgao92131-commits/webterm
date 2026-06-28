package direct

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
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

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	headers := http.Header{}
	for _, cookie := range jar.Cookies(loginRequest.URL) {
		headers.Add("Cookie", cookie.String())
	}
	wsURL := "ws" + httpServer.URL[len("http"):] + "/ws/sessions"
	conn, _, err := websocket.Dial(ctx, wsURL, &websocket.DialOptions{HTTPHeader: headers})
	if err != nil {
		t.Fatalf("websocket dial: %v", err)
	}
	defer conn.Close(websocket.StatusNormalClosure, "")

	_, first, err := conn.Read(ctx)
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

	_, update, err := conn.Read(ctx)
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

func TestDirectTerminalWebSocketAcceptsScreenProtocol(t *testing.T) {
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

	createBody := bytes.NewBufferString(`{"name":"screen-ws","cwd":"."}`)
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
	defer application.Sessions().Close("s1")

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	headers := http.Header{}
	for _, cookie := range jar.Cookies(loginRequest.URL) {
		headers.Add("Cookie", cookie.String())
	}
	wsURL := "ws" + httpServer.URL[len("http"):] + "/ws/sessions/s1"
	conn, _, err := websocket.Dial(ctx, wsURL, &websocket.DialOptions{
		HTTPHeader:   headers,
		Subprotocols: []string{protocol.ScreenSubprotocol},
	})
	if err != nil {
		t.Fatalf("websocket dial: %v", err)
	}
	defer conn.Close(websocket.StatusNormalClosure, "")
	if conn.Subprotocol() != protocol.ScreenSubprotocol {
		t.Fatalf("subprotocol = %q, want %q", conn.Subprotocol(), protocol.ScreenSubprotocol)
	}
}
