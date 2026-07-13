package relay

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"webterm/go-core/internal/application"
	"webterm/go-core/internal/fileupload"
	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/session"
)

// waitStreamRemoved 轮询直到指定 stream 从代理表中移除。
func waitStreamRemoved(t *testing.T, p *HTTPProxy, streamID string) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		p.mu.Lock()
		_, still := p.streams[streamID]
		p.mu.Unlock()
		if !still {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("stream %s not removed in time", streamID)
}

// 上传完整链路：HTTPHeaders -> HTTPChunk x N -> 空 FIN -> RouteHTTPv2 -> fileupload。
// 64 个 64 KiB 分块（4 MiB）远超累积路径 1 MiB 上限与队列容量 8：
// 若走旧的 append 累积逻辑会 413；若 pump 不排空队列则 DeliverChunk 永久阻塞。
// 断言：分块全部排空、无死锁、落盘内容正确、响应为正常 HTTP 200（非 StreamError）。
func TestUploadRequestDrainsBackloggedChunks(t *testing.T) {
	cwd := t.TempDir()
	manager, terminal := newUploadSession(t, cwd)
	router := application.NewSessionRouter(manager)
	router.SetFileUploadService(&fileupload.Service{Sessions: manager})
	w := &capWriter{}
	p := NewHTTPProxy(router, w)

	chunk := make([]byte, 64*1024)
	for i := range chunk {
		chunk[i] = byte(i)
	}
	total := 64 * len(chunk)
	meta, _ := json.Marshal(relaycore.HTTPRequestMeta{
		Method: http.MethodPost,
		Path:   "/api/sessions/" + terminal.ID() + "/upload",
		Headers: map[string]string{
			"X-File-Name":    "big.bin",
			"Content-Length": strconv.Itoa(total),
		},
	})
	p.HandleHTTPHeaders(context.Background(), nil, relaycore.NewFrame(relaycore.FrameTypeHTTPHeaders, "up1", 0, meta))

	delivered := make(chan struct{})
	go func() {
		for i := 0; i < 64; i++ {
			p.DeliverChunk(relaycore.NewFrame(relaycore.FrameTypeHTTPChunk, "up1", 0, chunk))
		}
		p.DeliverChunk(relaycore.NewFrame(relaycore.FrameTypeHTTPChunk, "up1", relaycore.FrameFlagFin, nil))
		close(delivered)
	}()

	select {
	case <-delivered:
	case <-time.After(3 * time.Second):
		t.Fatal("DeliverChunk blocked: pump 未排空队列（背压/丢弃逻辑失效）")
	}
	waitStreamRemoved(t, p, "up1")

	frames := w.forStream("up1")
	if len(frames) != 2 {
		t.Fatalf("frames = %v, want HTTPHeaders + FIN chunk", frames)
	}
	if frames[0].Type != relaycore.FrameTypeHTTPHeaders {
		t.Fatalf("frame[0] type = %v, want HTTPHeaders（业务响应不能变成 StreamError）", frames[0].Type)
	}
	var respMeta relaycore.HTTPResponseMeta
	if err := json.Unmarshal(frames[0].Payload, &respMeta); err != nil {
		t.Fatalf("unmarshal response meta: %v", err)
	}
	if respMeta.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", respMeta.StatusCode)
	}
	var result fileupload.Result
	if err := json.Unmarshal(frames[1].Payload, &result); err != nil {
		t.Fatalf("unmarshal result: %v", err)
	}
	if result.FileName != "big.bin" || result.Size != int64(total) {
		t.Fatalf("result = %+v, want big.bin size=%d", result, total)
	}
	got, err := os.ReadFile(result.AbsolutePath)
	if err != nil {
		t.Fatalf("ReadFile: %v", err)
	}
	want := make([]byte, total)
	for i := 0; i < 64; i++ {
		copy(want[i*len(chunk):], chunk)
	}
	if !bytes.Equal(got, want) {
		t.Fatal("published file content mismatch")
	}
	if n := countUploadTempFiles(t, filepath.Join(cwd, "WebTermUploads")); n != 0 {
		t.Fatalf("leftover temp files = %d, want 0", n)
	}
}

// 业务错误（session 不存在 -> 404 SESSION_NOT_FOUND）必须作为正常 HTTP
// 响应帧返回，不能转成 StreamError（否则 Android 只看到 502）。
func TestUploadBusinessErrorIsHTTPResponse(t *testing.T) {
	cwd := t.TempDir()
	manager, _ := newUploadSession(t, cwd)
	router := application.NewSessionRouter(manager)
	router.SetFileUploadService(&fileupload.Service{Sessions: manager})
	w := &capWriter{}
	p := NewHTTPProxy(router, w)

	meta, _ := json.Marshal(relaycore.HTTPRequestMeta{
		Method:  http.MethodPost,
		Path:    "/api/sessions/no-such-session/upload",
		Headers: map[string]string{"X-File-Name": "a.txt"},
	})
	p.HandleHTTPHeaders(context.Background(), nil, relaycore.NewFrame(relaycore.FrameTypeHTTPHeaders, "up-err", 0, meta))
	p.DeliverChunk(relaycore.NewFrame(relaycore.FrameTypeHTTPChunk, "up-err", relaycore.FrameFlagFin, nil))
	waitStreamRemoved(t, p, "up-err")

	frames := w.forStream("up-err")
	if len(frames) != 2 {
		t.Fatalf("frames = %v, want HTTPHeaders + FIN chunk", frames)
	}
	if frames[0].Type == relaycore.FrameTypeStreamError {
		t.Fatal("business error was converted to StreamError")
	}
	var respMeta relaycore.HTTPResponseMeta
	if err := json.Unmarshal(frames[0].Payload, &respMeta); err != nil {
		t.Fatalf("unmarshal response meta: %v", err)
	}
	if respMeta.StatusCode != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", respMeta.StatusCode)
	}
	if !bytes.Contains(frames[1].Payload, []byte("SESSION_NOT_FOUND")) {
		t.Fatalf("body = %q, want SESSION_NOT_FOUND json", frames[1].Payload)
	}
}

// CloseStream（Android 断开 / Relay 发来 StreamClose）必须中止上传 stream：
// pump 收到 done 后 CloseWithError，不死锁、不 panic。
func TestUploadStreamCloseAbortsRequest(t *testing.T) {
	manager := session.NewManager(session.TerminalDefaults{})
	p := NewHTTPProxy(application.NewSessionRouter(manager), &capWriter{})

	meta, _ := json.Marshal(relaycore.HTTPRequestMeta{
		Method: http.MethodPost,
		Path:   "/api/sessions/s1/upload",
	})
	p.HandleHTTPHeaders(context.Background(), nil, relaycore.NewFrame(relaycore.FrameTypeHTTPHeaders, "up2", 0, meta))
	p.DeliverChunk(relaycore.NewFrame(relaycore.FrameTypeHTTPChunk, "up2", 0, []byte("partial")))

	p.CloseStream("up2")
	waitStreamRemoved(t, p, "up2")
}

// newUploadSession 在指定目录创建一个真实 session（/bin/sh），与 fileupload 包
// 既有测试一致。用于验证 io.Pipe 与 fileupload.Service 的真实交互。
func newUploadSession(t *testing.T, cwd string) (*session.Manager, *session.TerminalSession) {
	t.Helper()
	manager := session.NewManager(session.TerminalDefaults{Command: "/bin/sh"})
	terminal, err := manager.Create(cwd)
	if err != nil {
		t.Fatalf("Create session: %v", err)
	}
	t.Cleanup(func() { terminal.Close() })
	return manager, terminal
}

// countUploadTempFiles 统计上传目录中残留的隐藏临时文件数。
func countUploadTempFiles(t *testing.T, targetDir string) int {
	t.Helper()
	entries, err := os.ReadDir(targetDir)
	if err != nil {
		if os.IsNotExist(err) {
			return 0
		}
		t.Fatalf("ReadDir(%s): %v", targetDir, err)
	}
	count := 0
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name(), ".webterm-upload-") {
			count++
		}
	}
	return count
}

// FIN 语义：pump 在 FIN 时 pw.Close()，PipeReader 读到 EOF，
// fileupload.Service 完成大小校验并发布最终文件（空 FIN 不等于成功，
// 但 EOF + 大小一致才是成功——这里验证正常路径）。
func TestUploadPipeFinProducesEOFAndPublishesFile(t *testing.T) {
	cwd := t.TempDir()
	manager, terminal := newUploadSession(t, cwd)
	svc := &fileupload.Service{Sessions: manager}

	data := make([]byte, 150000)
	for i := range data {
		data[i] = byte(i)
	}
	pr, pw := io.Pipe()
	type outcome struct {
		result *fileupload.Result
		err    error
	}
	done := make(chan outcome, 1)
	go func() {
		result, err := svc.Upload(context.Background(), fileupload.Request{
			SessionID:    terminal.ID(),
			FileName:     "demo.zip",
			DeclaredSize: int64(len(data)),
			Body:         pr,
		})
		done <- outcome{result, err}
	}()

	// 模拟 HTTPProxy pump：64 KiB 分块写入，FIN 时 Close 写端。
	for i := 0; i < len(data); i += 64 * 1024 {
		end := i + 64*1024
		if end > len(data) {
			end = len(data)
		}
		if _, err := pw.Write(data[i:end]); err != nil {
			t.Fatalf("pipe write: %v", err)
		}
	}
	if err := pw.Close(); err != nil {
		t.Fatalf("pipe close (FIN): %v", err)
	}

	out := <-done
	if out.err != nil {
		t.Fatalf("Upload: %v", out.err)
	}
	if out.result.Size != int64(len(data)) {
		t.Fatalf("Size = %d, want %d", out.result.Size, len(data))
	}
	got, err := os.ReadFile(out.result.AbsolutePath)
	if err != nil {
		t.Fatalf("ReadFile: %v", err)
	}
	if !bytes.Equal(got, data) {
		t.Fatal("published file content mismatch")
	}
	if n := countUploadTempFiles(t, filepath.Join(cwd, "WebTermUploads")); n != 0 {
		t.Fatalf("leftover temp files = %d, want 0", n)
	}
}

// 断线语义：pump 在 StreamClose/Relay 断开时 CloseWithError，
// PipeReader 读到错误，fileupload.Service 中止并删除临时文件。
func TestUploadPipeErrorInterruptsAndCleansTemp(t *testing.T) {
	cwd := t.TempDir()
	manager, terminal := newUploadSession(t, cwd)
	svc := &fileupload.Service{Sessions: manager}

	pr, pw := io.Pipe()
	done := make(chan error, 1)
	go func() {
		_, err := svc.Upload(context.Background(), fileupload.Request{
			SessionID:    terminal.ID(),
			FileName:     "partial.bin",
			DeclaredSize: -1,
			Body:         pr,
		})
		done <- err
	}()

	if _, err := pw.Write([]byte("partial data")); err != nil {
		t.Fatalf("pipe write: %v", err)
	}
	_ = pw.CloseWithError(errors.New("relay stream closed"))

	err := <-done
	if err == nil {
		t.Fatal("Upload succeeded unexpectedly after pipe error")
	}
	if code := fileupload.CodeOf(err); code != fileupload.CodeTransferInterrupted {
		t.Fatalf("code = %s, want TRANSFER_INTERRUPTED", code)
	}
	if n := countUploadTempFiles(t, filepath.Join(cwd, "WebTermUploads")); n != 0 {
		t.Fatalf("leftover temp files after interrupt = %d, want 0", n)
	}
}

// 取消语义：context 取消时 fileupload.Service 主动关闭 Body（PipeReader），
// 读端报错 -> 中止 -> 删除临时文件。
func TestUploadContextCancelCleansTemp(t *testing.T) {
	cwd := t.TempDir()
	manager, terminal := newUploadSession(t, cwd)
	svc := &fileupload.Service{Sessions: manager}

	ctx, cancel := context.WithCancel(context.Background())
	pr, pw := io.Pipe()
	done := make(chan error, 1)
	go func() {
		_, err := svc.Upload(ctx, fileupload.Request{
			SessionID:    terminal.ID(),
			FileName:     "cancel.bin",
			DeclaredSize: -1,
			Body:         pr,
		})
		done <- err
	}()

	if _, err := pw.Write([]byte("partial data")); err != nil {
		t.Fatalf("pipe write: %v", err)
	}
	cancel()

	err := <-done
	if err == nil {
		t.Fatal("Upload succeeded unexpectedly after context cancel")
	}
	if code := fileupload.CodeOf(err); code != fileupload.CodeTransferInterrupted {
		t.Fatalf("code = %s, want TRANSFER_INTERRUPTED", code)
	}
	if n := countUploadTempFiles(t, filepath.Join(cwd, "WebTermUploads")); n != 0 {
		t.Fatalf("leftover temp files after cancel = %d, want 0", n)
	}
}
