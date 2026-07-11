package screenprojection

import (
	"time"

	"webterm/go-core/internal/terminalengine"
)

// Coalescer 合并高频 patch，并在必要时用 snapshot 替代。
type Coalescer struct {
	pending      terminalengine.ScreenFrame
	hasPending   bool
	lastFlush    time.Time
	flushWindow  time.Duration
	snapshotSize int
}

// NewCoalescer 创建 patch 合并器。
func NewCoalescer() *Coalescer {
	return &Coalescer{
		flushWindow: 16 * time.Millisecond,
	}
}

// Accept 接收一帧，决定是否需要立即发送或继续合并。
// 返回的 frame 是非零值时表示应立即发送。
func (c *Coalescer) Accept(frame terminalengine.ScreenFrame) (terminalengine.ScreenFrame, bool) {
	now := time.Now()
	if !c.hasPending {
		c.pending = frame
		c.hasPending = true
		c.lastFlush = now
		return terminalengine.ScreenFrame{}, false
	}

	// 如果前一帧是 snapshot 或当前帧是 snapshot，立即刷新前一帧。
	if c.pending.BaseRevision == 0 || frame.BaseRevision == 0 {
		out := c.pending
		c.pending = frame
		c.lastFlush = now
		return out, true
	}

	// 合并 patch：简单起见，用新 patch 直接替换旧 patch。
	// 生产实现应合并 changed rows 和 historyAppend。
	c.pending = frame
	if now.Sub(c.lastFlush) >= c.flushWindow {
		out := c.pending
		c.hasPending = false
		c.lastFlush = now
		return out, true
	}

	return terminalengine.ScreenFrame{}, false
}

// Flush 强制刷新待发送帧。
func (c *Coalescer) Flush() (terminalengine.ScreenFrame, bool) {
	if !c.hasPending {
		return terminalengine.ScreenFrame{}, false
	}
	out := c.pending
	c.hasPending = false
	c.lastFlush = time.Now()
	return out, true
}

// ClientQueue 表示一个 client 的发送队列，带背压。
type ClientQueue struct {
	maxDepth int
	frames   []terminalengine.ScreenFrame
}

// NewClientQueue 创建 client 发送队列。
func NewClientQueue(maxDepth int) *ClientQueue {
	if maxDepth <= 0 {
		maxDepth = 64
	}
	return &ClientQueue{maxDepth: maxDepth}
}

// Enqueue 入队一帧；队列满时丢弃未发送 patch 并排队最新 snapshot。
// 如果 snapshot 也放不下，返回 true 表示应断开该 client。
func (q *ClientQueue) Enqueue(frame terminalengine.ScreenFrame) (drop bool) {
	if len(q.frames) >= q.maxDepth {
		// 丢弃所有未发送 patch，只保留 snapshot。
		var keep []terminalengine.ScreenFrame
		for _, f := range q.frames {
			if f.BaseRevision == 0 {
				keep = append(keep, f)
			}
		}
		q.frames = keep
	}

	if len(q.frames) >= q.maxDepth {
		return true // 连 snapshot 都堆积，建议断开
	}

	q.frames = append(q.frames, frame)
	return false
}

// Dequeue 出队一帧。
func (q *ClientQueue) Dequeue() (terminalengine.ScreenFrame, bool) {
	if len(q.frames) == 0 {
		return terminalengine.ScreenFrame{}, false
	}
	frame := q.frames[0]
	q.frames = q.frames[1:]
	return frame, true
}
