package application

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
	"time"

	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/session"
)

// recordingSocket 是一个 fake Socket，用于捕获 Client 发送的下行消息，也可注入上行消息。
type recordingSocket struct {
	mu       sync.Mutex
	messages []recordedMessage
	incoming chan recordedMessage
	closed   bool
}

type recordedMessage struct {
	msgType session.MessageType
	data    []byte
}

func newRecordingSocket() *recordingSocket {
	return &recordingSocket{incoming: make(chan recordedMessage, 8)}
}

func (s *recordingSocket) Read(ctx context.Context) (session.MessageType, []byte, error) {
	select {
	case <-ctx.Done():
		return 0, nil, ctx.Err()
	case m := <-s.incoming:
		return m.msgType, m.data, nil
	}
}

func (s *recordingSocket) Write(ctx context.Context, msgType session.MessageType, data []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return nil
	}
	s.messages = append(s.messages, recordedMessage{msgType: msgType, data: append([]byte(nil), data...)})
	return nil
}

func (s *recordingSocket) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.closed = true
	return nil
}

func (s *recordingSocket) inject(msgType session.MessageType, data []byte) {
	s.incoming <- recordedMessage{msgType: msgType, data: append([]byte(nil), data...)}
}

func (s *recordingSocket) binaryMessages() [][]byte {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out [][]byte
	for _, m := range s.messages {
		if m.msgType == session.MessageBinary {
			out = append(out, m.data)
		}
	}
	return out
}

func findDownloadHook(messages [][]byte) (downloadID string, fileName string, fileSize int64, ok bool) {
	for _, data := range messages {
		if len(data) == 0 || data[0] != protocol.MsgHook {
			continue
		}
		var ev protocol.HookEvent
		if err := protocol.DecodeJSONPayload(data[1:], &ev); err != nil {
			continue
		}
		if ev.Type == "download" {
			return ev.DownloadID, ev.FilePath, ev.TotalBytes, true
		}
	}
	return "", "", 0, false
}

func TestDownloadEndToEnd(t *testing.T) {
	manager := session.NewManager(session.TerminalDefaults{Command: "/bin/sh", CWD: "/tmp"})
	terminal, err := manager.Create("work", t.TempDir())
	if err != nil {
		t.Fatalf("create session: %v", err)
	}
	defer terminal.Close()

	// 等待 shell PID 被记录，避免 HandleCLICommand 解析 session 失败。
	deadline := time.Now().Add(2 * time.Second)
	for terminal.ShellPID() <= 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}

	// 挂一个 fake Android client 并设为 ready，避免 hello 同步的竞态
	fakeSocket := newRecordingSocket()
	client := session.NewClient(fakeSocket, terminal, session.ClientModeBinary)
	terminal.Attach(client)
	client.SetReadyForTest()
	clientCtx, cancelClient := context.WithCancel(context.Background())
	defer cancelClient()
	go client.Run(clientCtx)

	// 准备一个测试文件
	wantContent := []byte("hello webterm download " + strconv.FormatInt(time.Now().UnixNano(), 10))
	testFile := filepath.Join(t.TempDir(), "test.txt")
	if err := os.WriteFile(testFile, wantContent, 0o644); err != nil {
		t.Fatalf("write test file: %v", err)
	}

	// 模拟 CLI 发起 download 命令
	cliConn, agentConn := net.Pipe()
	defer cliConn.Close()

	cmd := protocol.CLICommand{
		Kind:      "command",
		Type:      "download",
		SessionID: terminal.ID(),
		CWD:       terminal.Info().CWD,
		FilePath:  testFile,
		Timestamp: time.Now().Unix(),
	}

	done := make(chan error, 1)
	go func() {
		terminal.HandleCLICommand(agentConn, cmd)
		done <- nil
	}()

	// 1. 等待 Agent 向 Android 广播 download hook
	var downloadID, fileName string
	var fileSize int64
	deadline = time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if id, name, size, ok := findDownloadHook(fakeSocket.binaryMessages()); ok {
			downloadID = id
			fileName = name
			fileSize = size
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if downloadID == "" {
		t.Fatalf("download hook not broadcast to client")
	}
	if fileName != "test.txt" {
		t.Fatalf("fileName = %q, want test.txt", fileName)
	}
	if fileSize != int64(len(wantContent)) {
		t.Fatalf("fileSize = %d, want %d", fileSize, len(wantContent))
	}

	// 2. Android 回传进度（在文件读完之前），验证 CLI 能收到 progress 响应
	terminal.OnDownloadProgress(downloadID, int64(len(wantContent))/2, int64(len(wantContent)))
	terminal.OnDownloadProgress(downloadID, int64(len(wantContent)), int64(len(wantContent)))

	// 3. Android 请求 /api/fs/download 下载文件
	router := NewSessionRouter(manager)
	result, err := router.RouteHTTPv2("GET", "/api/fs/download?downloadId="+downloadID+"&sessionId="+terminal.ID(), nil)
	if err != nil {
		t.Fatalf("RouteHTTPv2 error: %v", err)
	}
	if result.StatusCode != 200 {
		t.Fatalf("status = %d, want 200", result.StatusCode)
	}
	gotContent, err := io.ReadAll(result.Body)
	if err != nil {
		t.Fatalf("read body: %v", err)
	}
	_ = result.Body.Close()
	if !bytes.Equal(gotContent, wantContent) {
		t.Fatalf("content mismatch: got %q, want %q", gotContent, wantContent)
	}

	// 4. 读取 CLI 响应流
	decoder := json.NewDecoder(cliConn)
	statuses := []string{}
	deadline = time.Now().Add(2 * time.Second)
readLoop:
	for time.Now().Before(deadline) {
		cliConn.SetReadDeadline(time.Now().Add(100 * time.Millisecond))
		var resp protocol.CLIResponse
		if err := decoder.Decode(&resp); err != nil {
			if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
				break
			}
			if err == io.EOF {
				break
			}
			t.Fatalf("decode cli response: %v", err)
		}
		statuses = append(statuses, resp.Status)
		switch resp.Status {
		case "complete", "failed":
			break readLoop
		}
	}

	if len(statuses) == 0 {
		t.Fatalf("no cli response received")
	}
	if statuses[0] != "preparing" {
		t.Fatalf("first status = %q, want preparing", statuses[0])
	}
	if statuses[len(statuses)-1] != "complete" {
		t.Fatalf("last status = %q, want complete; statuses=%v", statuses[len(statuses)-1], statuses)
	}

	hasProgress := false
	for _, s := range statuses {
		if s == "progress" {
			hasProgress = true
			break
		}
	}
	if !hasProgress {
		t.Fatalf("expected progress status in CLI responses, got %v", statuses)
	}

	// 5. 任务完成后再次请求应返回 410
	result2, err := router.RouteHTTPv2("GET", "/api/fs/download?downloadId="+downloadID+"&sessionId="+terminal.ID(), nil)
	if err != nil {
		t.Fatalf("second RouteHTTPv2 error: %v", err)
	}
	if result2.StatusCode != 410 {
		t.Fatalf("second status = %d, want 410", result2.StatusCode)
	}
}

func TestRouteHTTPv2FileSendStub(t *testing.T) {
	manager := session.NewManager(session.TerminalDefaults{
		Command: "/bin/sh",
		CWD:     ".",
	})
	router := NewSessionRouter(manager)
	result, err := router.RouteHTTPv2("GET", "/api/file-send/t_abc123", nil)
	if err != nil {
		t.Fatalf("RouteHTTPv2 error: %v", err)
	}
	if result.StatusCode != 501 {
		t.Fatalf("status = %d, want 501 for unimplemented file-send stub", result.StatusCode)
	}
}
