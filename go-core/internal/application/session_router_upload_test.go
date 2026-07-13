package application

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"webterm/go-core/internal/fileupload"
	"webterm/go-core/internal/session"
)

// newUploadRouter 创建一个带真实 session（/bin/sh）与上传服务的路由器。
func newUploadRouter(t *testing.T, cwd string, maxSize int64) (*SessionRouter, string) {
	t.Helper()
	manager := session.NewManager(session.TerminalDefaults{Command: "/bin/sh"})
	terminal, err := manager.Create(cwd)
	if err != nil {
		t.Fatalf("Create session: %v", err)
	}
	t.Cleanup(func() { terminal.Close() })
	router := NewSessionRouter(manager)
	router.SetFileUploadService(&fileupload.Service{
		Sessions:      manager,
		MaxUploadSize: maxSize,
	})
	return router, terminal.ID()
}

func uploadHeaders(fileName string, size int64) http.Header {
	header := http.Header{}
	if fileName != "" {
		header.Set("X-File-Name", fileName)
	}
	if size >= 0 {
		header.Set("X-File-Size", strconv.FormatInt(size, 10))
	}
	return header
}

func decodeUploadError(t *testing.T, data []byte) (string, string) {
	t.Helper()
	var body struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	}
	if err := json.Unmarshal(data, &body); err != nil {
		t.Fatalf("decode error body %q: %v", data, err)
	}
	if body.Code == "" || body.Message == "" {
		t.Fatalf("error body must have non-empty code and message: %q", data)
	}
	return body.Code, body.Message
}

func TestRouteHTTPv2UploadSuccess(t *testing.T) {
	cwd := t.TempDir()
	router, sessionID := newUploadRouter(t, cwd, 0)

	payload := []byte("hello upload route 0123456789")
	result, err := router.RouteHTTPv2(http.MethodPost, "/api/sessions/"+sessionID+"/upload",
		uploadHeaders("demo.zip", int64(len(payload))), bytes.NewReader(payload))
	if err != nil {
		t.Fatalf("RouteHTTPv2: %v", err)
	}
	if result.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200, body=%s", result.StatusCode, result.Data)
	}
	var res fileupload.Result
	if err := json.Unmarshal(result.Data, &res); err != nil {
		t.Fatalf("decode result: %v", err)
	}
	if res.FileName != "demo.zip" || res.RelativePath != "WebTermUploads/demo.zip" {
		t.Fatalf("result = %+v", res)
	}
	if res.Size != int64(len(payload)) {
		t.Fatalf("size = %d, want %d", res.Size, len(payload))
	}
	got, err := os.ReadFile(filepath.Join(cwd, "WebTermUploads", "demo.zip"))
	if err != nil {
		t.Fatalf("read uploaded file: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatal("uploaded file content mismatch")
	}
}

func TestRouteHTTPv2UploadMissingFileName(t *testing.T) {
	router, sessionID := newUploadRouter(t, t.TempDir(), 0)
	result, err := router.RouteHTTPv2(http.MethodPost, "/api/sessions/"+sessionID+"/upload",
		uploadHeaders("", 4), bytes.NewReader([]byte("data")))
	if err != nil {
		t.Fatalf("RouteHTTPv2: %v", err)
	}
	if result.StatusCode != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", result.StatusCode)
	}
	code, _ := decodeUploadError(t, result.Data)
	if code != string(fileupload.CodeInvalidFileName) {
		t.Fatalf("code = %q, want INVALID_FILE_NAME", code)
	}
}

func TestRouteHTTPv2UploadInvalidFileSizeHeader(t *testing.T) {
	router, sessionID := newUploadRouter(t, t.TempDir(), 0)
	header := uploadHeaders("demo.zip", -1)
	header.Set("X-File-Size", "not-a-number")
	result, err := router.RouteHTTPv2(http.MethodPost, "/api/sessions/"+sessionID+"/upload",
		header, bytes.NewReader([]byte("data")))
	if err != nil {
		t.Fatalf("RouteHTTPv2: %v", err)
	}
	if result.StatusCode != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", result.StatusCode)
	}
	code, _ := decodeUploadError(t, result.Data)
	if code != string(fileupload.CodeSizeMismatch) {
		t.Fatalf("code = %q, want SIZE_MISMATCH", code)
	}
}

func TestRouteHTTPv2UploadTooLarge(t *testing.T) {
	router, sessionID := newUploadRouter(t, t.TempDir(), 16)
	payload := []byte("this payload is longer than sixteen bytes")
	result, err := router.RouteHTTPv2(http.MethodPost, "/api/sessions/"+sessionID+"/upload",
		uploadHeaders("big.bin", int64(len(payload))), bytes.NewReader(payload))
	if err != nil {
		t.Fatalf("RouteHTTPv2: %v", err)
	}
	if result.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("status = %d, want 413", result.StatusCode)
	}
	code, _ := decodeUploadError(t, result.Data)
	if code != string(fileupload.CodeFileTooLarge) {
		t.Fatalf("code = %q, want FILE_TOO_LARGE", code)
	}
}

func TestRouteHTTPv2UploadSessionNotFound(t *testing.T) {
	router, _ := newUploadRouter(t, t.TempDir(), 0)
	result, err := router.RouteHTTPv2(http.MethodPost, "/api/sessions/s999/upload",
		uploadHeaders("demo.zip", 4), bytes.NewReader([]byte("data")))
	if err != nil {
		t.Fatalf("RouteHTTPv2: %v", err)
	}
	if result.StatusCode != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", result.StatusCode)
	}
	code, message := decodeUploadError(t, result.Data)
	if code != string(fileupload.CodeSessionNotFound) {
		t.Fatalf("code = %q, want SESSION_NOT_FOUND", code)
	}
	if !strings.Contains(message, "session") {
		t.Fatalf("message = %q, want Chinese message from fileupload error", message)
	}
}

// TestRouteHTTPv2UploadLargeBodyNotTruncated 验证 >1 MiB 的 body 完整落盘，
// 即上传路由没有落入通用 JSON 路径的 1 MiB 截断。
func TestRouteHTTPv2UploadLargeBodyNotTruncated(t *testing.T) {
	cwd := t.TempDir()
	router, sessionID := newUploadRouter(t, cwd, 0)

	payload := bytes.Repeat([]byte("0123456789abcdef"), (2<<20)/16) // 2 MiB
	result, err := router.RouteHTTPv2(http.MethodPost, "/api/sessions/"+sessionID+"/upload",
		uploadHeaders("large.bin", int64(len(payload))), bytes.NewReader(payload))
	if err != nil {
		t.Fatalf("RouteHTTPv2: %v", err)
	}
	if result.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200, body=%s", result.StatusCode, result.Data)
	}
	var res fileupload.Result
	if err := json.Unmarshal(result.Data, &res); err != nil {
		t.Fatalf("decode result: %v", err)
	}
	if res.Size != int64(len(payload)) {
		t.Fatalf("size = %d, want %d (body must not be truncated)", res.Size, len(payload))
	}
	got, err := os.ReadFile(filepath.Join(cwd, "WebTermUploads", "large.bin"))
	if err != nil {
		t.Fatalf("read uploaded file: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatal("uploaded file content mismatch")
	}
}

// TestRouteHTTPv2UploadSizeMismatchConflict 验证 X-File-Size 与实际 body 不符时
// 以实际字节数为判据，返回 SIZE_MISMATCH 且不落盘。
func TestRouteHTTPv2UploadSizeMismatchConflict(t *testing.T) {
	cwd := t.TempDir()
	router, sessionID := newUploadRouter(t, cwd, 0)
	result, err := router.RouteHTTPv2(http.MethodPost, "/api/sessions/"+sessionID+"/upload",
		uploadHeaders("demo.zip", 100), bytes.NewReader([]byte("short")))
	if err != nil {
		t.Fatalf("RouteHTTPv2: %v", err)
	}
	if result.StatusCode != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", result.StatusCode)
	}
	code, _ := decodeUploadError(t, result.Data)
	if code != string(fileupload.CodeSizeMismatch) {
		t.Fatalf("code = %q, want SIZE_MISMATCH", code)
	}
	if _, statErr := os.Stat(filepath.Join(cwd, "WebTermUploads", "demo.zip")); !os.IsNotExist(statErr) {
		t.Fatal("size-mismatched upload must not leave a final file")
	}
}

// TestRouteHTTPv2UploadBase64FileName 验证 Android 端 X-File-Name-B64
// （OkHttp 拒绝非 ASCII header 值）被正确解码落盘。
func TestRouteHTTPv2UploadBase64FileName(t *testing.T) {
	cwd := t.TempDir()
	router, sessionID := newUploadRouter(t, cwd, 0)

	payload := []byte("b64 filename payload")
	header := uploadHeaders("", int64(len(payload)))
	header.Set("X-File-Name-B64", base64.URLEncoding.EncodeToString([]byte("中文 文件.zip")))
	result, err := router.RouteHTTPv2(http.MethodPost, "/api/sessions/"+sessionID+"/upload",
		header, bytes.NewReader(payload))
	if err != nil {
		t.Fatalf("RouteHTTPv2: %v", err)
	}
	if result.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200, body=%s", result.StatusCode, result.Data)
	}
	var res fileupload.Result
	if err := json.Unmarshal(result.Data, &res); err != nil {
		t.Fatalf("decode result: %v", err)
	}
	if res.FileName != "中文 文件.zip" {
		t.Fatalf("fileName = %q, want decoded B64 name", res.FileName)
	}
	if _, statErr := os.Stat(filepath.Join(cwd, "WebTermUploads", "中文 文件.zip")); statErr != nil {
		t.Fatalf("uploaded file missing: %v", statErr)
	}

	// 非法 Base64 必须返回 INVALID_FILE_NAME。
	bad := uploadHeaders("", 4)
	bad.Set("X-File-Name-B64", "%%%not-base64%%%")
	badResult, err := router.RouteHTTPv2(http.MethodPost, "/api/sessions/"+sessionID+"/upload",
		bad, bytes.NewReader([]byte("data")))
	if err != nil {
		t.Fatalf("RouteHTTPv2: %v", err)
	}
	if badResult.StatusCode != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", badResult.StatusCode)
	}
	code, _ := decodeUploadError(t, badResult.Data)
	if code != string(fileupload.CodeInvalidFileName) {
		t.Fatalf("code = %q, want INVALID_FILE_NAME", code)
	}
}

func TestRouteHTTPv2UploadServiceUnavailable(t *testing.T) {
	manager := session.NewManager(session.TerminalDefaults{Command: "/bin/sh"})
	router := NewSessionRouter(manager)
	// 未注入 FileUploadService 时返回 503，与 filesend 未接线行为一致。
	result, err := router.RouteHTTPv2(http.MethodPost, "/api/sessions/s1/upload",
		uploadHeaders("demo.zip", 4), bytes.NewReader([]byte("data")))
	if err != nil {
		t.Fatalf("RouteHTTPv2: %v", err)
	}
	if result.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503", result.StatusCode)
	}
}
