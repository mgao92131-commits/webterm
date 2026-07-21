package logs

import (
	"fmt"
	"testing"
	"time"
)

// TestRateLimiterBoundsStatesMemory 验证大量不同 key 不会让状态表无界增长。
func TestRateLimiterBoundsStatesMemory(t *testing.T) {
	now := time.Unix(1_000_000, 0)
	limiter := NewRateLimiter(time.Second, func() time.Time { return now })
	limiter.maxStates = 16

	for i := 0; i < 200; i++ {
		limiter.Check("src", "evt", map[string]any{"sessionId": fmt.Sprintf("s%d", i)})
	}

	limiter.mu.Lock()
	n := len(limiter.states)
	limiter.mu.Unlock()
	if n > limiter.maxStates+1 {
		t.Errorf("states not bounded: %d entries (maxStates=%d)", n, limiter.maxStates)
	}
}

// TestRateLimiterEvictsExpiredFirst 验证超限时优先回收过期窗口。
func TestRateLimiterEvictsExpiredFirst(t *testing.T) {
	now := time.Unix(1_000_000, 0)
	clock := now
	limiter := NewRateLimiter(time.Second, func() time.Time { return clock })
	limiter.maxStates = 8

	for i := 0; i < 8; i++ {
		limiter.Check("src", "evt", map[string]any{"sessionId": fmt.Sprintf("old%d", i)})
	}
	// 推进时钟超过窗口，使旧状态过期；再加入新状态触发回收。
	clock = now.Add(2 * time.Second)
	for i := 0; i < 5; i++ {
		limiter.Check("src", "evt", map[string]any{"sessionId": fmt.Sprintf("new%d", i)})
	}

	limiter.mu.Lock()
	n := len(limiter.states)
	limiter.mu.Unlock()
	if n > limiter.maxStates+1 {
		t.Errorf("states not bounded after expiry eviction: %d", n)
	}
}
