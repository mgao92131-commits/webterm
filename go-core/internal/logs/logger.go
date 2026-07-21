package logs

import (
	"os"
	"sync"
	"sync/atomic"
	"time"
)

const DefaultCapacity = 1000

type Entry struct {
	// RunID 标识产生该条目的 Agent 运行。每次进程启动 runID 不同，因此不同
	// 运行即使 Seq 相同也不会被诊断导出误判为重复。旧版本写出的条目没有该
	// 字段（反序列化为空串），导出器按内容指纹兼容处理。
	RunID   string         `json:"runId,omitempty"`
	Seq     uint64         `json:"seq"`
	Time    time.Time      `json:"time"`
	Level   string         `json:"level"`
	Source  string         `json:"source"`
	Event   string         `json:"event,omitempty"`
	Fields  map[string]any `json:"fields,omitempty"`
	Message string         `json:"message,omitempty"`
}

type Logger struct {
	mu          sync.Mutex
	runID       string
	nextSeq     uint64
	capacity    int
	entries     []Entry
	subscribers map[chan Entry]struct{}
	sink        *FileSink
	limiter     *RateLimiter
	droppedLogs atomic.Uint64
}

// New 创建不带运行标识的 Logger（RunID 为空），供测试或无 App 场景使用。
func New(capacity int) *Logger {
	return NewWithRunID(capacity, "")
}

// NewWithRunID 创建携带本次 Agent 运行标识的 Logger；写入的每条 Entry 自动
// 带上 runID，使诊断导出能区分不同运行、避免重启后 Seq 复位造成的去重冲突。
func NewWithRunID(capacity int, runID string) *Logger {
	if capacity <= 0 {
		capacity = DefaultCapacity
	}
	return &Logger{
		runID:       runID,
		nextSeq:     1,
		capacity:    capacity,
		subscribers: make(map[chan Entry]struct{}),
		limiter:     NewRateLimiter(DefaultRateLimitWindow, nil),
	}
}

// SetSink 安装本地 JSONL 落盘；nil 表示只保留内存 Ring。
func (logger *Logger) SetSink(sink *FileSink) {
	logger.mu.Lock()
	logger.sink = sink
	logger.mu.Unlock()
}

// SetRateLimiter 替换事件限流器；nil 表示关闭限流（测试用）。
func (logger *Logger) SetRateLimiter(limiter *RateLimiter) {
	logger.mu.Lock()
	logger.limiter = limiter
	logger.mu.Unlock()
}

// SubscriberDropped 返回因订阅者阻塞而被丢弃的事件总数。
func (logger *Logger) SubscriberDropped() uint64 {
	return logger.droppedLogs.Load()
}

// Event 写入一条结构化事件；限流窗口内的重复事件只累计不写入。
// 返回值 Seq==0 表示本次被限流抑制。
func (logger *Logger) Event(level string, source string, event string, fields map[string]any) Entry {
	logger.mu.Lock()
	limiter := logger.limiter
	logger.mu.Unlock()
	if limiter != nil {
		decision := limiter.Check(source, event, fields)
		if decision.summary != nil {
			s := decision.summary
			logger.add("info", s.source, "event_suppressed", map[string]any{
				"originalEvent":   s.event,
				"suppressedCount": s.suppressed,
				"windowMs":        s.windowMs,
			}, "")
		}
		if !decision.allowed {
			return Entry{}
		}
	}
	return logger.add(level, source, event, fields, "")
}

// Message 兼容旧代码的自由文本入口；新代码统一使用 Event。
func (logger *Logger) Message(level string, source string, message string) Entry {
	return logger.Add(level, source, message)
}

func (logger *Logger) Add(level string, source string, message string) Entry {
	return logger.add(level, source, "", nil, message)
}

func (logger *Logger) add(level string, source string, event string,
	fields map[string]any, message string) Entry {
	logger.mu.Lock()
	entry := Entry{
		RunID:   logger.runID,
		Seq:     logger.nextSeq,
		Time:    time.Now().UTC(),
		Level:   normalize(level, "info"),
		Source:  normalize(source, "core"),
		Event:   event,
		Fields:  fields,
		Message: message,
	}
	logger.nextSeq++
	logger.entries = append(logger.entries, entry)
	if len(logger.entries) > logger.capacity {
		copy(logger.entries, logger.entries[len(logger.entries)-logger.capacity:])
		logger.entries = logger.entries[:logger.capacity]
	}
	subscribers := make([]chan Entry, 0, len(logger.subscribers))
	for subscriber := range logger.subscribers {
		subscribers = append(subscribers, subscriber)
	}
	sink := logger.sink
	logger.mu.Unlock()

	// 同步落盘：事件频率低，省去异步队列；崩溃前的信息更容易真正写入。
	if sink != nil {
		if err := sink.Write(entry); err != nil {
			_, _ = os.Stderr.WriteString("webterm log sink: " + err.Error() + "\n")
		}
	}
	for _, subscriber := range subscribers {
		select {
		case subscriber <- entry:
		default:
			logger.droppedLogs.Add(1)
		}
	}
	return entry
}

func (logger *Logger) Recent(limit int) []Entry {
	logger.mu.Lock()
	defer logger.mu.Unlock()
	if limit <= 0 || limit > len(logger.entries) {
		limit = len(logger.entries)
	}
	start := len(logger.entries) - limit
	out := make([]Entry, limit)
	copy(out, logger.entries[start:])
	return out
}

func (logger *Logger) Subscribe(buffer int) (<-chan Entry, func()) {
	if buffer <= 0 {
		buffer = 64
	}
	ch := make(chan Entry, buffer)
	logger.mu.Lock()
	logger.subscribers[ch] = struct{}{}
	logger.mu.Unlock()
	cancel := func() {
		logger.mu.Lock()
		if _, ok := logger.subscribers[ch]; ok {
			delete(logger.subscribers, ch)
			close(ch)
		}
		logger.mu.Unlock()
	}
	return ch, cancel
}

func normalize(value string, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}
