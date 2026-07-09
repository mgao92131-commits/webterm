package session

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"webterm/go-core/internal/protocol"
)

func TestManagerCreateListRenameClose(t *testing.T) {
	manager := NewManager()
	first, err := manager.Create("work", "/tmp")
	if err != nil {
		t.Fatalf("Create returned error: %v", err)
	}
	defer first.Close()
	second, err := manager.Create("", "/var")
	if err != nil {
		t.Fatalf("Create returned error: %v", err)
	}
	defer second.Close()

	if first.Info().ID != "s1" || second.Info().ID != "s2" {
		t.Fatalf("session IDs = %q %q, want s1 s2", first.Info().ID, second.Info().ID)
	}
	if manager.Count() != 2 {
		t.Fatalf("Count = %d, want 2", manager.Count())
	}

	if _, ok := manager.Rename("s1", "renamed"); !ok {
		t.Fatalf("Rename returned false")
	}
	got, ok := manager.Get("s1")
	if !ok {
		t.Fatalf("Get returned false")
	}
	if got.Info().DisplayTitle != "renamed - Terminal" {
		t.Fatalf("DisplayTitle = %q", got.Info().DisplayTitle)
	}

	if !manager.Close("s1") {
		t.Fatalf("Close returned false")
	}
	if manager.Count() != 1 {
		t.Fatalf("Count after close = %d, want 1", manager.Count())
	}
}

func TestManagerBroadcastsSessionLifecycle(t *testing.T) {
	manager := NewManager(TerminalDefaults{Command: "/bin/sh", CWD: "."})
	sink := &recordingSink{}
	manager.AttachManagerSink(sink)
	defer manager.RemoveManagerSink(sink)

	if len(sink.messages) != 1 || sink.messages[0].Type != "sessions" {
		t.Fatalf("initial messages = %#v", sink.messages)
	}

	terminal, err := manager.Create("work", ".")
	if err != nil {
		t.Fatalf("Create returned error: %v", err)
	}
	defer terminal.Close()
	if last := sink.last(); last.Type != "session" {
		t.Fatalf("create broadcast = %#v", last)
	}

	if _, ok := manager.Rename("s1", "renamed"); !ok {
		t.Fatalf("Rename returned false")
	}
	if last := sink.last(); last.Type != "session" {
		t.Fatalf("rename broadcast = %#v", last)
	}

	if !manager.Close("s1") {
		t.Fatalf("Close returned false")
	}
	if last := sink.last(); last.Type != "session-closed" || last.ID != "s1" {
		t.Fatalf("close broadcast = %#v", last)
	}
}

type recordingSink struct {
	mu       sync.Mutex
	messages []ManagerMessage
}

func (sink *recordingSink) SendManagerMessage(message ManagerMessage) bool {
	sink.mu.Lock()
	defer sink.mu.Unlock()
	sink.messages = append(sink.messages, message)
	return true
}

func (sink *recordingSink) last() ManagerMessage {
	sink.mu.Lock()
	defer sink.mu.Unlock()
	if len(sink.messages) == 0 {
		return ManagerMessage{}
	}
	return sink.messages[len(sink.messages)-1]
}

func (sink *recordingSink) all() []ManagerMessage {
	sink.mu.Lock()
	defer sink.mu.Unlock()
	out := make([]ManagerMessage, len(sink.messages))
	copy(out, sink.messages)
	return out
}

func TestManagerTitleBroadcast(t *testing.T) {
	manager := NewManager(TerminalDefaults{Command: "/bin/sh", CWD: "."})
	sink := &recordingSink{}
	manager.AttachManagerSink(sink)
	defer manager.RemoveManagerSink(sink)

	// 创建真实的 shell terminal
	terminal, err := manager.Create("work", ".")
	if err != nil {
		t.Fatalf("Create returned error: %v", err)
	}
	defer terminal.Close()

	// 往 stdin 写入命令，让 shell 执行并向 stdout 输出 OSC 标题更新转义序列
	// 使用 printf 格式化八进制 \033 (ESC) 和 \007 (BEL)
	cmdStr := "printf '\\033]0;IntegratedTitle\\007\\n'\r"
	if err := terminal.WriteInput([]byte(cmdStr)); err != nil {
		t.Fatalf("WriteInput returned error: %v", err)
	}

	// 轮询等待更新广播到达 sink
	deadline := time.Now().Add(3 * time.Second)
	found := false
	for time.Now().Before(deadline) {
		for _, msg := range sink.all() {
			if msg.Type == "session" {
				if info, ok := msg.Data.(Info); ok && info.TermTitle == "IntegratedTitle" {
					found = true
					break
				}
			}
		}
		if found {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}

	if !found {
		t.Fatalf("expected to receive session broadcast with TermTitle=%q, but did not. messages=%#v", "IntegratedTitle", sink.all())
	}
}

func TestManagerSessionMapsAndPIDResolution(t *testing.T) {
	manager := NewManager(TerminalDefaults{Command: "/bin/sh", CWD: "."})
	terminal, err := manager.Create("work", ".")
	if err != nil {
		t.Fatalf("Create returned error: %v", err)
	}
	defer terminal.Close()

	shellPID := terminal.ShellPID()
	if shellPID <= 0 {
		t.Fatalf("shell pid <= 0: %d", shellPID)
	}

	// 1. 直接通过 shell PID 解析到 session
	sid, err := manager.ResolveSessionForPID(shellPID)
	if err != nil {
		t.Fatalf("ResolveSessionForPID(shellPID) error: %v", err)
	}
	if sid != terminal.ID() {
		t.Fatalf("ResolveSessionForPID(shellPID) = %q, want %q", sid, terminal.ID())
	}

	// 2. 通过子进程 PID 沿父进程链解析到 session
	tmpDir := t.TempDir()
	pidFile := filepath.Join(tmpDir, "child.pid")
	cmd := fmt.Sprintf("(sleep 3 & echo $! > %s)\r", pidFile)
	if err := terminal.WriteInput([]byte(cmd)); err != nil {
		t.Fatalf("WriteInput returned error: %v", err)
	}

	var childPID int
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		data, err := os.ReadFile(pidFile)
		if err == nil {
			pid, _ := strconv.Atoi(strings.TrimSpace(string(data)))
			if pid > 0 {
				childPID = pid
				break
			}
		}
		time.Sleep(50 * time.Millisecond)
	}
	if childPID <= 0 {
		t.Fatalf("failed to capture child pid")
	}

	sid, err = manager.ResolveSessionForPID(childPID)
	if err != nil {
		t.Fatalf("ResolveSessionForPID(childPID) error: %v", err)
	}
	if sid != terminal.ID() {
		t.Fatalf("ResolveSessionForPID(childPID) = %q, want %q", sid, terminal.ID())
	}

	// 3. 关闭 session 后映射被清理
	if !manager.Close(terminal.ID()) {
		t.Fatalf("Close returned false")
	}
	if _, err := manager.ResolveSessionForPID(shellPID); err == nil {
		t.Fatalf("expected error after closing session, got nil")
	}
}

func TestDownloadTaskConsumeAndPeek(t *testing.T) {
	manager := NewManager()

	task := &DownloadTask{
		ID:        "d_test",
		SessionID: "s1",
		Path:      "/tmp/test.txt",
		FileName:  "test.txt",
		Size:      1234,
		StateChan: make(chan protocol.CLIResponse, 4),
		CreatedAt: time.Now(),
		ExpiresAt: time.Now().Add(10 * time.Minute),
	}
	manager.AddDownloadTask("s1", task)

	// Peek 可以重复读到任务
	peek1, ok := manager.PeekDownloadTask("d_test")
	if !ok || peek1.ID != "d_test" {
		t.Fatalf("first Peek failed")
	}
	peek2, ok := manager.PeekDownloadTask("d_test")
	if !ok || peek2.ID != "d_test" {
		t.Fatalf("second Peek failed")
	}

	// 首次 Get 消费成功，任务仍保留（供 Peek 查找进度）
	got, ok := manager.GetDownloadTask("d_test")
	if !ok || got.ID != "d_test" || !got.consumed {
		t.Fatalf("first Get should consume task")
	}

	// 再次 Get 应失败（一次性消费）
	if _, ok := manager.GetDownloadTask("d_test"); ok {
		t.Fatalf("second Get should fail")
	}

	// Peek 仍能查到任务，直到 RemoveDownloadTask 删除
	if _, ok := manager.PeekDownloadTask("d_test"); !ok {
		t.Fatalf("Peek after consume should still find task")
	}

	manager.RemoveDownloadTask("d_test")
	if _, ok := manager.PeekDownloadTask("d_test"); ok {
		t.Fatalf("Peek after remove should fail")
	}
}

func TestDownloadTaskExpires(t *testing.T) {
	manager := NewManager()

	task := &DownloadTask{
		ID:        "d_expired",
		SessionID: "s1",
		Path:      "/tmp/test.txt",
		FileName:  "test.txt",
		Size:      100,
		StateChan: make(chan protocol.CLIResponse, 1),
		CreatedAt: time.Now().Add(-20 * time.Minute),
		ExpiresAt: time.Now().Add(-10 * time.Minute),
	}
	manager.AddDownloadTask("s1", task)

	if _, ok := manager.GetDownloadTask("d_expired"); ok {
		t.Fatalf("expired task should not be returned")
	}
	if _, ok := manager.PeekDownloadTask("d_expired"); ok {
		t.Fatalf("expired task should not be peeked")
	}
}
