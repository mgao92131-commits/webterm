package logs

import (
	"fmt"
	"sync"
	"time"
)

// DefaultRateLimitWindow 是事件限流窗口（方案 §10：5 秒）。
const DefaultRateLimitWindow = 5 * time.Second

// noRateLimitEvents 中的事件永不限流（方案 §10 名单）。
var noRateLimitEvents = map[string]struct{}{
	"agent_start":             {},
	"agent_stop":              {},
	"terminal_process_exited": {},
	"screen_encode_failed":    {},
	"screen_writer_failed":    {},
	"input_write_uncertain":   {},
}

type rateLimitState struct {
	windowStart time.Time
	suppressed  int
}

type suppressedSummary struct {
	source     string
	event      string
	suppressed int
	windowMs   int64
}

type rateLimitDecision struct {
	allowed bool
	summary *suppressedSummary
}

// RateLimiter 按 source+event+sessionId+reason 对事件限流：
// 窗口内第一条立即放行，重复事件只累计，窗口结束后下一条放行时
// 先吐出一条 event_suppressed 汇总。
type RateLimiter struct {
	mu     sync.Mutex
	window time.Duration
	now    func() time.Time
	states map[string]*rateLimitState
}

// NewRateLimiter 创建限流器；now 为 nil 时使用真实时钟（测试可注入假时钟）。
func NewRateLimiter(window time.Duration, now func() time.Time) *RateLimiter {
	if window <= 0 {
		window = DefaultRateLimitWindow
	}
	if now == nil {
		now = time.Now
	}
	return &RateLimiter{window: window, now: now, states: make(map[string]*rateLimitState)}
}

// Check 判断事件是否放行；若上一窗口有被抑制事件，返回待写出的汇总。
func (limiter *RateLimiter) Check(source string, event string, fields map[string]any) rateLimitDecision {
	if _, exempt := noRateLimitEvents[event]; exempt {
		return rateLimitDecision{allowed: true}
	}
	key := source + "\x00" + event + "\x00" + fieldString(fields, "sessionId") +
		"\x00" + fieldString(fields, "reason")

	limiter.mu.Lock()
	defer limiter.mu.Unlock()
	now := limiter.now()
	state, exists := limiter.states[key]
	if !exists || now.Sub(state.windowStart) >= limiter.window {
		decision := rateLimitDecision{allowed: true}
		if exists && state.suppressed > 0 {
			decision.summary = &suppressedSummary{
				source:     source,
				event:      event,
				suppressed: state.suppressed,
				windowMs:   limiter.window.Milliseconds(),
			}
		}
		limiter.states[key] = &rateLimitState{windowStart: now}
		return decision
	}
	state.suppressed++
	return rateLimitDecision{allowed: false}
}

// SuppressedForTest 返回 key 当前窗口内被抑制的计数（测试用）。
func (limiter *RateLimiter) SuppressedForTest(source string, event string, fields map[string]any) int {
	key := source + "\x00" + event + "\x00" + fieldString(fields, "sessionId") +
		"\x00" + fieldString(fields, "reason")
	limiter.mu.Lock()
	defer limiter.mu.Unlock()
	if state, ok := limiter.states[key]; ok {
		return state.suppressed
	}
	return 0
}

func fieldString(fields map[string]any, name string) string {
	if fields == nil {
		return ""
	}
	value, ok := fields[name]
	if !ok || value == nil {
		return ""
	}
	return fmt.Sprint(value)
}
