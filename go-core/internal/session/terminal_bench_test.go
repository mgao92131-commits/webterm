package session

import (
	"fmt"
	"strings"
	"testing"
	"time"
)

func newBenchTerminal(rows, cols int) *TerminalSession {
	return &TerminalSession{
		id:        "s1",
		instance:  "i1",
		name:      "bench",
		status:    StatusRunning,
		cols:      cols,
		rows:      rows,
		createdAt: time.Now().UTC(),
		activeAt:  time.Now().UTC(),
		ring:      NewEventRing(0, 0),
		screen:    NewScreenState(rows, cols, nil, nil),
		clients:   make(map[*Client]struct{}),
	}
}

func BenchmarkTerminalPushOutput(b *testing.B) {
	payload := []byte(strings.Repeat("a", 1024))
	term := newBenchTerminal(30, 100)

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		term.PushOutput(payload)
	}
}

func BenchmarkTerminalStateBytes(b *testing.B) {
	term := newBenchTerminal(30, 100)
	payload := []byte(strings.Repeat("x", 100))
	for i := 0; i < 1000; i++ {
		term.PushOutput(payload)
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_ = term.StateBytes()
	}
}

func BenchmarkTerminalReplayAfter(b *testing.B) {
	term := newBenchTerminal(30, 100)
	payload := []byte(strings.Repeat("x", 100))
	for i := 0; i < 10000; i++ {
		term.PushOutput(payload)
	}
	mid := term.LatestSeq() / 2

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_ = term.ReplayAfter(mid)
	}
}

func BenchmarkTerminalBroadcastOutput(b *testing.B) {
	for _, clients := range []int{1, 10, 50} {
		b.Run(fmt.Sprintf("clients=%d", clients), func(b *testing.B) {
			term := newBenchTerminal(30, 100)
			for i := 0; i < clients; i++ {
				term.clients[&Client{}] = struct{}{}
			}
			payload := []byte("hello")

			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				frame := term.PushOutput(payload)
				term.broadcastOutput(frame)
			}
		})
	}
}

func BenchmarkTerminalInfo(b *testing.B) {
	term := newBenchTerminal(30, 100)
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_ = term.Info()
	}
}
