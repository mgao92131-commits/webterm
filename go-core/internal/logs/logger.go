package logs

import (
	"sync"
	"time"
)

const DefaultCapacity = 500

type Entry struct {
	Seq     uint64    `json:"seq"`
	Time    time.Time `json:"time"`
	Level   string    `json:"level"`
	Source  string    `json:"source"`
	Message string    `json:"message"`
}

type Logger struct {
	mu          sync.Mutex
	nextSeq     uint64
	capacity    int
	entries     []Entry
	subscribers map[chan Entry]struct{}
}

func New(capacity int) *Logger {
	if capacity <= 0 {
		capacity = DefaultCapacity
	}
	return &Logger{
		nextSeq:     1,
		capacity:    capacity,
		subscribers: make(map[chan Entry]struct{}),
	}
}

func (logger *Logger) Add(level string, source string, message string) Entry {
	logger.mu.Lock()
	entry := Entry{
		Seq:     logger.nextSeq,
		Time:    time.Now().UTC(),
		Level:   normalize(level, "info"),
		Source:  normalize(source, "core"),
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
	logger.mu.Unlock()

	for _, subscriber := range subscribers {
		select {
		case subscriber <- entry:
		default:
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
