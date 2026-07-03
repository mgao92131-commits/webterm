package emulator

import (
	"fmt"
	"strings"
	"testing"
)

func fillScreen(s *Screen, rows, cols int) {
	for i := 0; i < rows; i++ {
		line := strings.Repeat(fmt.Sprintf("%d", i%10), cols)
		_ = s.Write([]byte(line + "\r\n"))
	}
}

func BenchmarkScreenAnsiText(b *testing.B) {
	for _, size := range []struct{ rows, cols int }{
		{30, 100},
		{60, 200},
		{200, 500},
	} {
		b.Run(fmt.Sprintf("%dx%d", size.rows, size.cols), func(b *testing.B) {
			s := NewScreen(size.rows, size.cols, nil, nil)
			fillScreen(s, size.rows, size.cols)
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				_ = s.AnsiText()
			}
		})
	}
}

func BenchmarkScreenDirtyDelta(b *testing.B) {
	s := NewScreen(30, 100, nil, nil)
	fillScreen(s, 30, 100)

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		// 每次写一点新内容再取 delta，模拟真实流量
		_ = s.Write([]byte("x"))
		_ = s.DirtyDelta(uint64(i + 1))
	}
}

func BenchmarkScreenWrite(b *testing.B) {
	payload := []byte(strings.Repeat("hello world ", 100))
	for _, size := range []struct{ rows, cols int }{
		{30, 100},
		{60, 200},
	} {
		b.Run(fmt.Sprintf("%dx%d", size.rows, size.cols), func(b *testing.B) {
			s := NewScreen(size.rows, size.cols, nil, nil)
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				_ = s.Write(payload)
			}
		})
	}
}

func BenchmarkScreenResize(b *testing.B) {
	s := NewScreen(30, 100, nil, nil)
	fillScreen(s, 30, 100)

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		if i%2 == 0 {
			s.Resize(60, 200)
		} else {
			s.Resize(30, 100)
		}
	}
}
