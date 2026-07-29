package mux

import (
	"context"
	"sync"
)

// channelLifecycle 是 logical channel 到 PhysicalWriter 之间的关闭栅栏。
//
// accepted 只统计已经被 PhysicalWriter 接受、但尚未完成（写出、失败或因关闭
// 被跳过）的请求。Abort 先把 accepting 置为 false，再等待 accepted 归零；
// 因此关闭 ACK 发出后，不会再有属于旧 lifecycle 的 socket write。
type channelLifecycle struct {
	mu            sync.Mutex
	accepting     bool
	accepted      int
	drained       chan struct{}
	drainedClosed bool
}

func newChannelLifecycle() *channelLifecycle {
	return &channelLifecycle{
		accepting: true,
		drained:   make(chan struct{}),
	}
}

func (lifecycle *channelLifecycle) tryAcquire() bool {
	if lifecycle == nil {
		return false
	}
	lifecycle.mu.Lock()
	defer lifecycle.mu.Unlock()
	if !lifecycle.accepting {
		return false
	}
	lifecycle.accepted++
	return true
}

func (lifecycle *channelLifecycle) release() {
	if lifecycle == nil {
		return
	}
	lifecycle.mu.Lock()
	if lifecycle.accepted > 0 {
		lifecycle.accepted--
	}
	lifecycle.closeDrainedIfReadyLocked()
	lifecycle.mu.Unlock()
}

func (lifecycle *channelLifecycle) invalidate() {
	if lifecycle == nil {
		return
	}
	lifecycle.mu.Lock()
	lifecycle.accepting = false
	lifecycle.closeDrainedIfReadyLocked()
	lifecycle.mu.Unlock()
}

func (lifecycle *channelLifecycle) isOpen() bool {
	if lifecycle == nil {
		return false
	}
	lifecycle.mu.Lock()
	defer lifecycle.mu.Unlock()
	return lifecycle.accepting
}

func (lifecycle *channelLifecycle) wait(ctx context.Context) error {
	if lifecycle == nil {
		return nil
	}
	select {
	case <-lifecycle.drained:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (lifecycle *channelLifecycle) closeDrainedIfReadyLocked() {
	if lifecycle.accepting || lifecycle.accepted != 0 || lifecycle.drainedClosed {
		return
	}
	lifecycle.drainedClosed = true
	close(lifecycle.drained)
}
