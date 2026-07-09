package hook

import (
	"context"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/protocol"
)

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
