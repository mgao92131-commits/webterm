package logs

import (
	"fmt"
	"testing"
)

func BenchmarkLoggerAdd(b *testing.B) {
	for _, subs := range []int{0, 1, 10} {
		b.Run(fmt.Sprintf("subscribers=%d", subs), func(b *testing.B) {
			logger := New(DefaultCapacity)
			for i := 0; i < subs; i++ {
				_, _ = logger.Subscribe(64)
			}
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				logger.Add("info", "bench", "hello world")
			}
		})
	}
}

func BenchmarkLoggerRecent(b *testing.B) {
	logger := New(DefaultCapacity)
	for i := 0; i < DefaultCapacity; i++ {
		logger.Add("info", "bench", "hello world")
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_ = logger.Recent(100)
	}
}

func BenchmarkLoggerSubscribeUnsubscribe(b *testing.B) {
	logger := New(DefaultCapacity)
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		ch, cancel := logger.Subscribe(64)
		cancel()
		_ = ch
	}
}
