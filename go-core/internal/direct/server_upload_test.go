package direct

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"testing"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/session"
)

// newUploadTestServer 创建登录好的 direct server 与一个 cwd 为临时目录的 session。
func newUploadTestServer(t *testing.T, cwd string) (*Server, []*http.Cookie, string) {
	t.Helper()
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
		t.Fatalf("login status = %d", loginResponse.Code)
	}
	cookies := loginResponse.Result().Cookies()

	createBody := bytes.NewBufferString(`{"name":"upload","cwd":` + strconv.Quote(cwd) + `}`)
	createRequest := httptest.NewRequest(http.MethodPost, "/api/sessions", createBody)
	for _, cookie := range cookies {
		createRequest.AddCookie(cookie)
	}
	createResponse := httptest.NewRecorder()
	server.route(createResponse, createRequest)
	if createResponse.Code != http.StatusCreated {
		t.Fatalf("create status = %d body=%s", createResponse.Code, createResponse.Body.String())
	}
	var created session.Info
	if err := json.Unmarshal(createResponse.Body.Bytes(), &created); err != nil {
		t.Fatalf("decode create body: %v", err)
	}
	t.Cleanup(func() { application.Sessions().Close(created.ID) })
	return server, cookies, created.ID
}

func doUpload(t *testing.T, server *Server, cookies []*http.Cookie, sessionID, fileName string, size int64, body []byte) *httptest.ResponseRecorder {
	t.Helper()
	request := httptest.NewRequest(http.MethodPost, "/api/sessions/"+sessionID+"/upload", bytes.NewReader(body))
	request.Header.Set("Content-Type", "application/octet-stream")
	if fileName != "" {
		request.Header.Set("X-File-Name", fileName)
	}
	if size >= 0 {
		request.Header.Set("X-File-Size", strconv.FormatInt(size, 10))
	}
	for _, cookie := range cookies {
		request.AddCookie(cookie)
	}
	response := httptest.NewRecorder()
	server.route(response, request)
	return response
}

func TestDirectUploadSuccess(t *testing.T) {
	cwd := t.TempDir()
	server, cookies, sessionID := newUploadTestServer(t, cwd)

	payload := []byte("direct upload payload 0123456789")
	response := doUpload(t, server, cookies, sessionID, "demo.zip", int64(len(payload)), payload)
	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 body=%s", response.Code, response.Body.String())
	}
	var result struct {
		FileName     string `json:"fileName"`
		RelativePath string `json:"relativePath"`
		AbsolutePath string `json:"absolutePath"`
		Size         int64  `json:"size"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
		t.Fatalf("decode result: %v", err)
	}
	if result.FileName != "demo.zip" || result.RelativePath != "WebTermUploads/demo.zip" {
		t.Fatalf("result = %+v", result)
	}
	if result.Size != int64(len(payload)) {
		t.Fatalf("size = %d, want %d", result.Size, len(payload))
	}
	got, err := os.ReadFile(filepath.Join(cwd, "WebTermUploads", "demo.zip"))
	if err != nil {
		t.Fatalf("read uploaded file: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatal("uploaded file content mismatch")
	}
}

// TestDirectUploadLargeBodyBypassesJSONLimit 验证 >1 MiB 的上传不会落入
// readRequestBody() 的 1 MiB JSON 路径而被静默截断。
func TestDirectUploadLargeBodyBypassesJSONLimit(t *testing.T) {
	cwd := t.TempDir()
	server, cookies, sessionID := newUploadTestServer(t, cwd)

	payload := bytes.Repeat([]byte("0123456789abcdef"), (2<<20)/16) // 2 MiB
	response := doUpload(t, server, cookies, sessionID, "large.bin", int64(len(payload)), payload)
	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 body=%s", response.Code, response.Body.String())
	}
	var result struct {
		Size int64 `json:"size"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
		t.Fatalf("decode result: %v", err)
	}
	if result.Size != int64(len(payload)) {
		t.Fatalf("size = %d, want %d (body must not be truncated at 1 MiB)", result.Size, len(payload))
	}
	got, err := os.ReadFile(filepath.Join(cwd, "WebTermUploads", "large.bin"))
	if err != nil {
		t.Fatalf("read uploaded file: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatal("uploaded file content mismatch")
	}
}

func TestDirectUploadMissingFileNameReturnsStandardJSONError(t *testing.T) {
	cwd := t.TempDir()
	server, cookies, sessionID := newUploadTestServer(t, cwd)

	response := doUpload(t, server, cookies, sessionID, "", 4, []byte("data"))
	if response.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", response.Code)
	}
	var body struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode error body %q: %v", response.Body.String(), err)
	}
	if body.Code != "INVALID_FILE_NAME" || body.Message == "" {
		t.Fatalf("error body = %+v, want code=INVALID_FILE_NAME with message", body)
	}
}

func TestDirectUploadRequiresAuth(t *testing.T) {
	cfg := config.Config{
		Mode:   config.ModeDirect,
		Direct: config.DirectConfig{Addr: "127.0.0.1:0", User: "admin", Password: "pw"},
	}
	application := app.New(cfg, "test")
	server := New(cfg.Direct, application)

	request := httptest.NewRequest(http.MethodPost, "/api/sessions/s1/upload", bytes.NewReader([]byte("data")))
	request.Header.Set("X-File-Name", "demo.zip")
	response := httptest.NewRecorder()
	server.route(response, request)
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", response.Code)
	}
}
