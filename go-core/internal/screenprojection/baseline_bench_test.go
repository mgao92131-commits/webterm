package screenprojection

import (
	"fmt"
	"strings"
	"testing"

	"webterm/go-core/internal/terminalengine"
)

// baseline_bench_test.go 是远程终端主链路性能基线（阶段 1，见
// docs/go-android-terminal-performance-optimization-plan.md §5.1）的
// 投影/导出部分。覆盖：
//   - ExportState 全量导出在各场景/尺寸下的成本（BenchmarkExportStateBaseline）
//   - 活动屏幕导出 vs 历史窗口导出的分配差异（BenchmarkExportSplitBaseline）
//   - 单客户端 FrameForState diff 在 1/2/4 客户端下的成本（BenchmarkFrameDeriverDiffBaseline）
//   - export-once + N 客户端 diff 扇出的端到端成本（BenchmarkProjectorExportFanoutBaseline，
//     与既有 BenchmarkProjectorExportOnceFanout 互补：1/2/4 客户端 × 三种尺寸）
//
// 负载生成器与 headlessterm/baseline_bench_test.go 中的实现一致（两个包分属
// 不同 Go module，无法共享测试代码，故作此重复）；全部纯算法生成、无随机源。

// baselineFrameSink 防止编译器把被测帧优化掉。
var baselineFrameSink terminalengine.ScreenFrame

type projBaselineSize struct {
	name string
	rows int
	cols int
}

var projBaselineSizes = []projBaselineSize{
	{"80x24", 24, 80},
	{"120x40", 40, 120},
	{"200x50", 50, 200},
}

const projBaselineScrollbackLines = 10000

const projBaselineAlphabet = "abcdefghijklmnopqrstuvwxyz0123456789 .,;:/-_=+ABCDEFGHIJKLMNOPQRSTUVWXYZ"

func projBaselineChar(i int) byte {
	return projBaselineAlphabet[i%len(projBaselineAlphabet)]
}

func genProjASCIILine(sb *strings.Builder, lineIdx, width int) {
	for c := 0; c < width; c++ {
		sb.WriteByte(projBaselineChar(lineIdx*31 + c*7))
	}
}

func genProjASCIIChunk(cols, lines int) []byte {
	var sb strings.Builder
	for i := 0; i < lines; i++ {
		genProjASCIILine(&sb, i, cols-1)
		sb.WriteString("\r\n")
	}
	return []byte(sb.String())
}

func genProjSGRChunk(cols, lines int) []byte {
	var sb strings.Builder
	wordsPerLine := cols / 8
	if wordsPerLine < 4 {
		wordsPerLine = 4
	}
	for l := 0; l < lines; l++ {
		for w := 0; w < wordsPerLine; w++ {
			fg := (l*3 + w*5) % 8
			bg := (l + w*7) % 8
			attr := (l + w) % 4
			fmt.Fprintf(&sb, "\x1b[%d;3%d;4%dm", attr, fg, bg)
			for c := 0; c < 5; c++ {
				sb.WriteByte(projBaselineChar(l*17 + w*11 + c))
			}
			sb.WriteByte(' ')
		}
		sb.WriteString("\x1b[0m\r\n")
	}
	return []byte(sb.String())
}

func genProjCursorFrame(rows, cols int) []byte {
	var sb strings.Builder
	sb.WriteString("\x1b[H\x1b[J")
	for r := 0; r < rows; r += 3 {
		fmt.Fprintf(&sb, "\x1b[%d;1H", r+1)
		text := cols / 4
		for c := 0; c < text; c++ {
			sb.WriteByte(projBaselineChar(r*13 + c*3))
		}
		sb.WriteString("\x1b[K")
	}
	fmt.Fprintf(&sb, "\x1b[%d;%dH", rows/2+1, cols/2+1)
	sb.WriteByte('*')
	return []byte(sb.String())
}

func genProjTUIStyledLine(sb *strings.Builder, r, cols int) {
	segments := 4
	segW := cols / segments
	for s := 0; s < segments; s++ {
		w := segW
		if s == segments-1 {
			w = cols - segW*(segments-1)
		}
		if r == 0 {
			fmt.Fprintf(sb, "\x1b[7;38;5;%dm", (r+s*5)%256)
		} else {
			fmt.Fprintf(sb, "\x1b[38;5;%dm", (r*3+s*7)%256)
		}
		for c := 0; c < w; c++ {
			sb.WriteByte(projBaselineChar(r*19 + s*23 + c*5))
		}
	}
	sb.WriteString("\x1b[0m")
}

func genProjTUIFrame(rows, cols int) []byte {
	var sb strings.Builder
	sb.WriteString("\x1b[H")
	for r := rows - 1; r >= 0; r-- {
		genProjTUIStyledLine(&sb, r, cols)
		if r > 0 {
			sb.WriteString("\r\n")
		}
	}
	sb.WriteString("\r\n") // 底行换行 → 每帧滚出一行进历史
	return []byte(sb.String())
}

func genProjScrollChunk(cols, lines int) []byte {
	var sb strings.Builder
	for i := 0; i < lines; i++ {
		fmt.Fprintf(&sb, "\x1b[90m[%04d]\x1b[0m ", i%10000)
		genProjASCIILine(&sb, i, cols-12)
		sb.WriteString("\r\n")
	}
	return []byte(sb.String())
}

// projScenario 是一个可重复的投影负载：payload 为单次迭代写入的字节，
// warmups 为达到稳态（屏幕填满、历史窗口满 300 行）所需的预热写入次数。
type projScenario struct {
	name    string
	payload func(rows, cols int) []byte
	warmups int
}

func projScenarios() []projScenario {
	return []projScenario{
		{"ascii", func(rows, cols int) []byte { return genProjASCIIChunk(cols, 32) }, 20},
		{"sgr", func(rows, cols int) []byte { return genProjSGRChunk(cols, 32) }, 20},
		{"cursor", genProjCursorFrame, 20},
		{"tui", genProjTUIFrame, 320},
		{"scroll", func(rows, cols int) []byte { return genProjScrollChunk(cols, 16) }, 320},
	}
}

// newProjBaselineRig 构造 engine + tracked scrollback + projector 并完成预热。
func newProjBaselineRig(b *testing.B, rows, cols int, sc projScenario) (*terminalengine.Engine, *Projector, []byte) {
	b.Helper()
	scrollback := terminalengine.NewTrackedScrollback(projBaselineScrollbackLines, nil)
	engine := terminalengine.NewEngine(rows, cols, scrollback)
	projector := NewProjector(engine, scrollback, "baseline", "instance")
	payload := sc.payload(rows, cols)
	for i := 0; i < sc.warmups; i++ {
		if err := engine.Write(payload); err != nil {
			b.Fatal(err)
		}
	}
	return engine, projector, payload
}

// BenchmarkExportStateBaseline 单独测量 ExportState（exportSnapshot +
// exportHistoryWindow 全量导出）的耗时与分配，不含 Write 与 diff。
func BenchmarkExportStateBaseline(b *testing.B) {
	for _, sc := range projScenarios() {
		for _, sz := range projBaselineSizes {
			b.Run(sc.name+"/"+sz.name, func(b *testing.B) {
				engine, projector, payload := newProjBaselineRig(b, sz.rows, sz.cols, sc)
				b.ReportAllocs()
				b.ResetTimer()
				for i := 0; i < b.N; i++ {
					b.StopTimer()
					if err := engine.Write(payload); err != nil {
						b.Fatal(err)
					}
					b.StartTimer()
					baselineFrameSink = projector.ExportState(0, uint64(i+1))
				}
			})
		}
	}
}

// BenchmarkExportSplitBaseline 拆分"活动屏幕导出"与"历史窗口导出"的分配量：
// screen-only 用 CUP 局部重绘负载，历史恒为空；history-heavy 先把 scrollback
// 填满（>=300 行窗口）并持续滚动，每次导出都要重导出 300 行历史。
// 两者 allocs/op、B/op 之差即历史窗口导出的边际成本。
func BenchmarkExportSplitBaseline(b *testing.B) {
	cases := []struct {
		name string
		sc   projScenario
	}{
		{"screen-only", projScenario{"cursor", genProjCursorFrame, 20}},
		{"history-heavy", projScenario{"scroll", func(rows, cols int) []byte { return genProjScrollChunk(cols, 16) }, 320}},
	}
	for _, tc := range cases {
		for _, sz := range projBaselineSizes {
			b.Run(tc.name+"/"+sz.name, func(b *testing.B) {
				engine, projector, payload := newProjBaselineRig(b, sz.rows, sz.cols, tc.sc)
				b.ReportAllocs()
				b.ResetTimer()
				for i := 0; i < b.N; i++ {
					b.StopTimer()
					if err := engine.Write(payload); err != nil {
						b.Fatal(err)
					}
					b.StartTimer()
					baselineFrameSink = projector.ExportState(0, uint64(i+1))
				}
			})
		}
	}
}

// BenchmarkProjectorSingleDirtyRow isolates the steady-state row-level dirty path. The historical
// "cursor" scenario clears and sparsely redraws the whole screen, so it cannot validate the
// single-row allocation target.
func BenchmarkProjectorSingleDirtyRow(b *testing.B) {
	for _, sz := range projBaselineSizes {
		b.Run(sz.name, func(b *testing.B) {
			scrollback := terminalengine.NewTrackedScrollback(projBaselineScrollbackLines, nil)
			engine := terminalengine.NewEngine(sz.rows, sz.cols, scrollback)
			projector := NewProjector(engine, scrollback, "baseline", "instance")
			if err := engine.Write(genProjASCIIChunk(sz.cols, sz.rows)); err != nil {
				b.Fatal(err)
			}
			baselineFrameSink = projector.ExportState(0, 1)
			payload := []byte(fmt.Sprintf("\x1b[%d;%dHX", sz.rows/2+1, sz.cols/2+1))

			b.ReportAllocs()
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				b.StopTimer()
				if err := engine.Write(payload); err != nil {
					b.Fatal(err)
				}
				b.StartTimer()
				baselineFrameSink = projector.ExportState(0, uint64(i+2))
			}
		})
	}
}

// BenchmarkFrameDeriverDiffBaseline 单独测量每客户端 FrameForState diff
// （linesEqual 全屏逐行比较 + history append 计算），不含 Write 与导出。
// clients 维度模拟 1/2/4 个 screen client 各自的基线推导。
//
// 计时循环在预生成的状态环上纯跑 diff：若每次迭代现做 Write+ExportState，
// 未计时部分是计时部分的上百倍，b.N 校准会把墙钟时间放大到不可接受。
// 环上相邻状态来自连续的真实输出，diff 形态与在线路径一致；环回绕那一次
// （63→0）历史窗口为反向，仅 1/64 样本，不影响基线结论。
func BenchmarkFrameDeriverDiffBaseline(b *testing.B) {
	const stateRing = 64
	diffScenarios := []projScenario{
		{"ascii", func(rows, cols int) []byte { return genProjASCIIChunk(cols, 32) }, 20},
		{"tui", genProjTUIFrame, 320},
	}
	for _, sc := range diffScenarios {
		for _, sz := range projBaselineSizes {
			for _, clients := range []int{1, 2, 4} {
				b.Run(fmt.Sprintf("%s/%s/clients=%d", sc.name, sz.name, clients), func(b *testing.B) {
					engine, projector, payload := newProjBaselineRig(b, sz.rows, sz.cols, sc)
					states := make([]terminalengine.ScreenFrame, stateRing)
					for i := range states {
						if err := engine.Write(payload); err != nil {
							b.Fatal(err)
						}
						states[i] = projector.ExportState(0, uint64(i+1))
					}
					derivers := make([]FrameDeriver, clients)
					for c := 0; c < clients; c++ {
						baselineFrameSink = derivers[c].FrameForState(states[0])
					}
					b.ReportAllocs()
					b.ResetTimer()
					for i := 0; i < b.N; i++ {
						state := states[(i+1)%stateRing]
						for c := 0; c < clients; c++ {
							baselineFrameSink = derivers[c].FrameForState(state)
						}
					}
				})
			}
		}
	}
}

// BenchmarkProjectorExportFanoutBaseline 测量"每次修订导出一次 + 每客户端
// diff"的完整扇出成本（Write + ExportState + N×FrameForState 全部计时），
// 客户端数取计划 §5.1 要求的 1/2/4。tui 场景下全屏变化触发 snapshot 路径，
// 是扇出成本的最坏形态。
func BenchmarkProjectorExportFanoutBaseline(b *testing.B) {
	sc := projScenario{"tui", genProjTUIFrame, 320}
	for _, sz := range projBaselineSizes {
		for _, clients := range []int{1, 2, 4} {
			b.Run(fmt.Sprintf("%s/%s/clients=%d", sc.name, sz.name, clients), func(b *testing.B) {
				engine, projector, payload := newProjBaselineRig(b, sz.rows, sz.cols, sc)
				derivers := make([]FrameDeriver, clients)
				state := projector.ExportState(0, 1)
				for c := 0; c < clients; c++ {
					baselineFrameSink = derivers[c].FrameForState(state)
				}
				b.ReportAllocs()
				b.ResetTimer()
				for i := 0; i < b.N; i++ {
					if err := engine.Write(payload); err != nil {
						b.Fatal(err)
					}
					state = projector.ExportState(0, uint64(i+2))
					for c := 0; c < clients; c++ {
						baselineFrameSink = derivers[c].FrameForState(state)
					}
				}
			})
		}
	}
}
