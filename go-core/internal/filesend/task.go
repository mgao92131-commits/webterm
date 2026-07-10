package filesend

import (
	"sync"
	"time"

	"webterm/go-core/internal/protocol"
)

// Task 是一次 webterm send 任务的完整生命周期。
type Task struct {
	ID        string
	Token     string
	SessionID string
	DeviceID  string
	Path      string
	FileName  string
	Size      int64
	SHA256    string

	Status    Status
	BytesSent int64
	Error     string

	StateChan chan protocol.CLIResponse

	CreatedAt time.Time
	ExpiresAt time.Time

	mu        sync.RWMutex
	closeOnce sync.Once
}

// Snapshot 返回任务当前状态的只读副本。
func (t *Task) Snapshot() (Status, int64, string) {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.Status, t.BytesSent, t.Error
}

// Expired 报告任务是否已过有效期。
func (t *Task) Expired(now time.Time) bool {
	return !t.ExpiresAt.IsZero() && now.After(t.ExpiresAt)
}

// Close 关闭 StateChan，保证只关闭一次。
func (t *Task) Close() {
	if t == nil {
		return
	}
	t.closeOnce.Do(func() { close(t.StateChan) })
}

// transition 在持有调用方锁的情况下做状态迁移，返回是否成功。
// 终态任务不再接受任何非终态迁移；cancel 在 saving 之后被忽略。
func (t *Task) transition(next Status) bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.Status.IsTerminal() {
		return false
	}
	// cancel 在 saving 之后被忽略（平台即将完成 rename，无法安全中止）。
	if next == StatusCancelled && (t.Status == StatusSaving) {
		return false
	}
	t.Status = next
	return true
}

// SetStatus 尝试迁移状态，返回是否成功。
func (t *Task) SetStatus(next Status) bool {
	return t.transition(next)
}

// SetFailed 迁移到 failed 并记录错误原因。
func (t *Task) SetFailed(reason string) bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.Status.IsTerminal() {
		return false
	}
	t.Status = StatusFailed
	t.Error = reason
	return true
}

// SetBytesSent 更新已发送字节数。
func (t *Task) SetBytesSent(n int64) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if n > t.BytesSent {
		t.BytesSent = n
	}
}
