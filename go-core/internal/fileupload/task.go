package fileupload

import (
	"sync"
	"time"
)

// Task 是一次活跃上传任务的运行时记录。
//
// 与 filesend.Task 不同，上传没有 transfer token/offer/Android ack 控制面：
// HTTP 响应成功就是 Agent 落盘成功的权威结论。Task 只用于两件事：
//  1. 登记在 Service 的活跃任务表中，保证同一 session 同时仅一个活跃上传；
//  2. 记录临时文件路径与已写字节数，便于诊断；所有失败路径由 Service.Upload
//     统一删除临时文件。
type Task struct {
	SessionID string
	FileName  string
	TempPath  string
	StartedAt time.Time

	mu           sync.RWMutex
	bytesWritten int64
}

func newTask(sessionID, fileName string) *Task {
	return &Task{
		SessionID: sessionID,
		FileName:  fileName,
		StartedAt: time.Now().UTC(),
	}
}

// SetBytesWritten 更新已写入临时文件的字节数。
func (t *Task) SetBytesWritten(n int64) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if n > t.bytesWritten {
		t.bytesWritten = n
	}
}

// BytesWritten 返回已写入临时文件的字节数。
func (t *Task) BytesWritten() int64 {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.bytesWritten
}
