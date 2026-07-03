package eventring

import (
	"fmt"
	"testing"
)

func BenchmarkRingPush(b *testing.B) {
	payload := make([]byte, 1024)
	for i := range payload {
		payload[i] = byte('a' + i%26)
	}

	b.Run("small", func(b *testing.B) {
		ring := New(0, 0)
		b.ResetTimer()
		for i := 0; i < b.N; i++ {
			ring.Push(payload[:64])
		}
	})

	b.Run("1KiB", func(b *testing.B) {
		ring := New(0, 0)
		b.ResetTimer()
		for i := 0; i < b.N; i++ {
			ring.Push(payload)
		}
	})

	b.Run("saturate", func(b *testing.B) {
		// 默认 5 MiB 上限，持续写入 1 KiB 帧会触发 trim + compact
		ring := New(0, 0)
		for i := 0; i < DefaultMaxBytes/len(payload)*2; i++ {
			ring.Push(payload)
		}
		b.ResetTimer()
		for i := 0; i < b.N; i++ {
			ring.Push(payload)
		}
	})
}

func BenchmarkRingAfter(b *testing.B) {
	ring := New(0, 0)
	payload := make([]byte, 256)
	for i := 0; i < 10000; i++ {
		ring.Push(payload)
	}
	mid := ring.LatestSeq() / 2

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_ = ring.After(mid)
	}
}

func BenchmarkRingCanReplayFrom(b *testing.B) {
	ring := New(0, 0)
	payload := make([]byte, 256)
	for i := 0; i < 10000; i++ {
		ring.Push(payload)
	}
	mid := ring.LatestSeq() / 2

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_ = ring.CanReplayFrom(mid)
	}
}

func BenchmarkRingReplayAll(b *testing.B) {
	ring := New(0, 0)
	payload := make([]byte, 256)
	for i := 0; i < 10000; i++ {
		ring.Push(payload)
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_ = ring.After(0)
	}
}

func BenchmarkRingTrim(b *testing.B) {
	for _, frames := range []int{1000, 10000, 20000} {
		b.Run(fmt.Sprintf("frames=%d", frames), func(b *testing.B) {
			ring := New(frames, 0)
			payload := make([]byte, 256)
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				ring.Push(payload)
			}
		})
	}
}
