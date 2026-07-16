package session

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"sync"
	"time"

	"webterm/go-core/internal/protocol"
)

// DownloadTask 是 webterm download 命令创建的一次性下载任务。
type DownloadTask struct {
	ID        string
	SessionID string
	Path      string
	FileName  string
	Size      int64
	StateChan chan protocol.CLIResponse
	closeOnce sync.Once
	CreatedAt time.Time
	ExpiresAt time.Time
	consumed  bool
}

func (task *DownloadTask) Close() {
	if task != nil {
		task.closeOnce.Do(func() { close(task.StateChan) })
	}
}

func (task *DownloadTask) tryConsume() bool {
	if task.consumed {
		return false
	}
	task.consumed = true
	return true
}

// DownloadTaskRegistry 拥有下载任务的一次性消费、过期和关闭语义。
type DownloadTaskRegistry struct {
	mu    sync.Mutex
	tasks map[string]*DownloadTask
	now   func() time.Time
}

func NewDownloadTaskRegistry() *DownloadTaskRegistry {
	return &DownloadTaskRegistry{tasks: make(map[string]*DownloadTask), now: time.Now}
}

func (registry *DownloadTaskRegistry) Add(sessionID string, task *DownloadTask) {
	registry.mu.Lock()
	defer registry.mu.Unlock()
	task.SessionID = sessionID
	registry.tasks[task.ID] = task
}

func (registry *DownloadTaskRegistry) Consume(id string) (*DownloadTask, bool) {
	registry.mu.Lock()
	defer registry.mu.Unlock()
	task, ok := registry.lookupCurrentLocked(id)
	if !ok || !task.tryConsume() {
		return nil, false
	}
	return task, true
}

func (registry *DownloadTaskRegistry) Peek(id string) (*DownloadTask, bool) {
	registry.mu.Lock()
	defer registry.mu.Unlock()
	return registry.lookupCurrentLocked(id)
}

func (registry *DownloadTaskRegistry) Remove(id string) {
	registry.mu.Lock()
	defer registry.mu.Unlock()
	if task, ok := registry.tasks[id]; ok {
		task.Close()
		delete(registry.tasks, id)
	}
}

func (registry *DownloadTaskRegistry) lookupCurrentLocked(id string) (*DownloadTask, bool) {
	task, ok := registry.tasks[id]
	if !ok {
		return nil, false
	}
	if registry.now().After(task.ExpiresAt) {
		task.Close()
		delete(registry.tasks, id)
		return nil, false
	}
	return task, true
}

func generateDownloadID() string {
	var bytes [8]byte
	if _, err := rand.Read(bytes[:]); err != nil {
		return fmt.Sprintf("d_%d", time.Now().UnixNano())
	}
	return fmt.Sprintf("d_%d_%s", time.Now().UnixNano(), hex.EncodeToString(bytes[:]))
}
