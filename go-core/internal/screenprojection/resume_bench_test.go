package screenprojection

import (
	"fmt"
	"testing"

	"webterm/go-core/internal/terminalengine"
)

// BenchmarkDeriveResumeFrame 测量 resume 推导在不同变化行比例下的成本
// （100 行 × 80 列屏幕，百分比对应变化行数）。变化行包含光标移动带出的
// 光标行（±1 行）。100% 命中 §6.1 成本降级直接返回 snapshot 信号，不构帧。
// 与 §6.1 第 4 条配合，用于校准阈值；跑法：go test -run=^$ -bench . -benchmem
func BenchmarkDeriveResumeFrame(b *testing.B) {
	for _, pct := range []int{0, 1, 10, 60, 100} {
		b.Run(fmt.Sprintf("rows_changed=%d%%", pct), func(b *testing.B) {
			sb := terminalengine.NewTrackedScrollback(10000, nil)
			engine := terminalengine.NewEngine(100, 80, sb)
			p := NewProjector(engine, sb, "benchmark", "instance")
			if err := engine.Write([]byte("baseline")); err != nil {
				b.Fatal(err)
			}
			p.ExportState(0, 1)

			for r := 0; r < pct; r++ {
				if err := engine.Write([]byte(fmt.Sprintf("\x1b[%d;1Hchanged-row-%03d", r+1, r))); err != nil {
					b.Fatal(err)
				}
			}
			state := p.ExportState(0, 2)

			b.ReportAllocs()
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				d := p.DeriveResumeFrame(state, 1)
				if d.Outcome == 0 {
					b.Fatal("empty derivation outcome")
				}
			}
		})
	}
}
