package session

import (
	"sync"
	"testing"
	"time"
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
