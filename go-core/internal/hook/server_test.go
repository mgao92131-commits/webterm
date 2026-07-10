package hook

import (
	"context"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
	"time"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/agentnotify"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/filesend"
	"webterm/go-core/internal/protocol"
)

type recordingSender struct {
	mu   sync.Mutex
	msgs []map[string]any
}

func (r *recordingSender) SendControl(_ context.Context, msg map[string]any) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.msgs = append(r.msgs, msg)
	return nil
}

func (r *recordingSender) last() map[string]any {
	r.mu.Lock()
	defer r.mu.Unlock()
	if len(r.msgs) == 0 {
		return nil
	}
	return r.msgs[len(r.msgs)-1]
}

func TestServerDispatchResolvesPID(t *testing.T) {
	tmpHome := t.TempDir()
	t.Setenv("HOME", tmpHome)

	// macOS Unix socket 路径长度受限，使用短路径
	socketPath := filepath.Join("/tmp", "webterm-hook-test-"+strconv.Itoa(os.Getpid())+".sock")
	cfg := config.Load(config.Options{Mode: config.ModeDirect})
	cfg.Shell.Command = "/bin/sh"
	application := app.New(cfg, "test")

	server := New(socketPath, application)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go func() {
		_ = server.ListenAndServe(ctx)
	}()

	// 等待 socket 可连接
	var conn net.Conn
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		var err error
		conn, err = net.Dial("unix", socketPath)
		if err == nil {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if conn == nil {
		t.Fatalf("socket not available")
	}
	defer conn.Close()

	terminal, err := application.Sessions().Create("work", ".")
	if err != nil {
		t.Fatalf("create session: %v", err)
	}
	defer terminal.Close()

	ev := protocol.HookEvent{
		Type:    "notify",
		PID:     terminal.ShellPID(),
		Level:   "running",
		Message: "pid resolved",
		Source:  "test",
	}
	data, _ := json.Marshal(ev)
	if _, err := conn.Write(append(data, '\n')); err != nil {
		t.Fatalf("write event: %v", err)
	}

	// 等待通知写入 session
	deadline = time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		info := terminal.Info()
		if info.Notification != nil && info.Notification.Message == "pid resolved" {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("notification not applied to session")
}

func startHookServer(t *testing.T) (*app.App, string, context.CancelFunc) {
	t.Helper()
	tmpHome := t.TempDir()
	t.Setenv("HOME", tmpHome)
	socketPath := filepath.Join("/tmp", "webterm-hook-send-"+strconv.Itoa(os.Getpid())+"-"+strconv.FormatInt(time.Now().UnixNano(), 10)+".sock")
	cfg := config.Load(config.Options{Mode: config.ModeDirect})
	cfg.Shell.Command = "/bin/sh"
	application := app.New(cfg, "test")
	server := New(socketPath, application)
	ctx, cancel := context.WithCancel(context.Background())
	go func() { _ = server.ListenAndServe(ctx) }()

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		c, err := net.Dial("unix", socketPath)
		if err == nil {
			_ = c.Close()
			return application, socketPath, cancel
		}
		time.Sleep(10 * time.Millisecond)
	}
	cancel()
	t.Fatal("socket not available")
	return nil, "", nil
}

func runSendCommand(t *testing.T, socketPath string, cmd protocol.CLICommand) []protocol.CLIResponse {
	t.Helper()
	conn, err := net.Dial("unix", socketPath)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close()
	data, _ := json.Marshal(cmd)
	if _, err := conn.Write(append(data, '\n')); err != nil {
		t.Fatalf("write command: %v", err)
	}
	var responses []protocol.CLIResponse
	dec := json.NewDecoder(conn)
	for {
		_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
		var resp protocol.CLIResponse
		if err := dec.Decode(&resp); err != nil {
			break
		}
		responses = append(responses, resp)
		if filesend.Status(resp.Status).IsTerminal() {
			break
		}
	}
	return responses
}

func TestSendCommandWithoutSenderFails(t *testing.T) {
	application, socketPath, cancel := startHookServer(t)
	defer cancel()

	terminal, err := application.Sessions().Create("work", ".")
	if err != nil {
		t.Fatalf("create session: %v", err)
	}
	defer terminal.Close()

	dir := t.TempDir()
	path := filepath.Join(dir, "a.txt")
	if err := os.WriteFile(path, []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}

	responses := runSendCommand(t, socketPath, protocol.CLICommand{
		Kind:      "command",
		Type:      "send",
		SessionID: terminal.ID(),
		FilePath:  path,
		Timestamp: time.Now().Unix(),
	})
	if len(responses) == 0 {
		t.Fatal("no responses")
	}
	last := responses[len(responses)-1]
	if last.Status != string(filesend.StatusFailed) {
		t.Fatalf("last status = %q, want failed", last.Status)
	}
	if last.Error != "device_not_connected" {
		t.Fatalf("error = %q, want device_not_connected", last.Error)
	}
}

func TestSendCommandOfferAndSaved(t *testing.T) {
	application, socketPath, cancel := startHookServer(t)
	defer cancel()

	sender := &recordingSender{}
	application.FileSendService().RegisterSender("dev-1", sender)

	terminal, err := application.Sessions().Create("work", ".")
	if err != nil {
		t.Fatalf("create session: %v", err)
	}
	defer terminal.Close()

	dir := t.TempDir()
	path := filepath.Join(dir, "b.txt")
	if err := os.WriteFile(path, []byte("payload"), 0o644); err != nil {
		t.Fatal(err)
	}

	// 在一个 goroutine 里模拟 Android：先等 offer，再回 accepted/saved。
	done := make(chan []protocol.CLIResponse, 1)
	go func() {
		done <- runSendCommand(t, socketPath, protocol.CLICommand{
			Kind:      "command",
			Type:      "send",
			SessionID: terminal.ID(),
			FilePath:  path,
			Timestamp: time.Now().Unix(),
		})
	}()

	// 等待 offer 被记录
	var offer map[string]any
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if m := sender.last(); m != nil {
			offer = m
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if offer == nil {
		t.Fatal("offer not delivered to sender")
	}
	transferID, _ := offer["transfer_id"].(string)
	if transferID == "" {
		t.Fatal("offer missing transfer_id")
	}
	if offer["transfer_token"] == "" || offer["transfer_token"] == nil {
		t.Fatal("offer must carry transfer_token")
	}

	svc := application.FileSendService()
	svc.HandleControl(map[string]any{"type": filesend.TypeAccepted, "transfer_id": transferID})
	svc.HandleControl(map[string]any{"type": filesend.TypeSaved, "transfer_id": transferID})

	var responses []protocol.CLIResponse
	select {
	case responses = <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("send command did not finish")
	}

	statuses := make([]string, 0, len(responses))
	for _, r := range responses {
		statuses = append(statuses, r.Status)
	}
	if statuses[len(statuses)-1] != string(filesend.StatusSaved) {
		t.Fatalf("last status = %q, want saved; all=%v", statuses[len(statuses)-1], statuses)
	}
}

func TestDispatchHookEventSendsAgentNotification(t *testing.T) {
	application, socketPath, cancel := startHookServer(t)
	defer cancel()

	sender := &recordingSender{}
	application.FileSendService().RegisterSender("dev-1", sender)

	terminal, err := application.Sessions().Create("work", ".")
	if err != nil {
		t.Fatalf("create session: %v", err)
	}
	defer terminal.Close()

	conn, err := net.Dial("unix", socketPath)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close()

	ev := protocol.HookEvent{
		Type:      "notify",
		SessionID: terminal.ID(),
		Level:     "error",
		Message:   "agent failed",
		Source:    "claude-code",
	}
	data, _ := json.Marshal(ev)
	if _, err := conn.Write(append(data, '\n')); err != nil {
		t.Fatalf("write event: %v", err)
	}

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		msg := sender.last()
		if msg != nil && msg["type"] == agentnotify.TypeAgentNotification {
			if msg["session_id"] != terminal.ID() {
				t.Fatalf("session_id=%v", msg["session_id"])
			}
			if msg["level"] != "error" || msg["message"] != "agent failed" {
				t.Fatalf("msg=%v", msg)
			}
			eid, _ := msg["event_id"].(string)
			if len(eid) < 8 || eid[:3] != "ev_" {
				t.Fatalf("event_id=%q", eid)
			}
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("agent_notification not dispatched")
}
