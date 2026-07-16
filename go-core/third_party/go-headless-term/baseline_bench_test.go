package headlessterm

import (
	"fmt"
	"strings"
	"testing"
)

// baseline_bench_test.go 是远程终端主链路性能基线（阶段 1，见
// docs/go-android-terminal-performance-optimization-plan.md §5.1）的
// Terminal.Write 热路径部分。所有负载纯算法生成、无随机源，保证可重复。
//
// 场景对应计划 §5.1：
//   - ascii  连续普通 ASCII 输出（cat 大文件风格）
//   - sgr    大量 ANSI 颜色/样式切换（每个词换 SGR）
//   - cursor 光标移动 + 擦除 + 局部重绘（CUP/ED/EL 混合）
//   - tui    全屏 TUI 整屏重绘 + 每帧一行 scrollback
//   - scroll 持续产生 scrollback（每帧滚出多行进历史）
//
// 每个场景在 80x24 / 120x40 / 200x50 三种尺寸下运行。
// 终端配置贴近生产：主屏带 10000 行内存 scrollback（与 TrackedScrollback
// 容量一致），因此滚动场景包含历史行 push 成本。

type baselineSize struct {
	name string
	rows int
	cols int
}

var baselineSizes = []baselineSize{
	{"80x24", 24, 80},
	{"120x40", 40, 120},
	{"200x50", 50, 200},
}

const baselineScrollbackLines = 10000

// baselineAlphabet 是可打印 ASCII 字符表，负载内容按位置取模生成，确定性强。
const baselineAlphabet = "abcdefghijklmnopqrstuvwxyz0123456789 .,;:/-_=+ABCDEFGHIJKLMNOPQRSTUVWXYZ"

func baselineChar(i int) byte {
	return baselineAlphabet[i%len(baselineAlphabet)]
}

// genASCIILine 生成一行 cols-1 个字符的确定性纯文本（不含行尾）。
func genASCIILine(sb *strings.Builder, lineIdx, width int) {
	for c := 0; c < width; c++ {
		sb.WriteByte(baselineChar(lineIdx*31 + c*7))
	}
}

// genASCIIChunk 生成 lines 行纯文本（\r\n 结尾），模拟 cat 大文件的一个读块。
func genASCIIChunk(cols, lines int) []byte {
	var sb strings.Builder
	for i := 0; i < lines; i++ {
		genASCIILine(&sb, i, cols-1)
		sb.WriteString("\r\n")
	}
	return []byte(sb.String())
}

// genSGRChunk 生成每词切换 SGR 前景色/背景色/属性的文本块。
// 颜色在有限集合内循环，style 组合有界（贴近真实高亮输出，字典不爆炸）。
func genSGRChunk(cols, lines int) []byte {
	var sb strings.Builder
	wordsPerLine := cols / 8
	if wordsPerLine < 4 {
		wordsPerLine = 4
	}
	for l := 0; l < lines; l++ {
		for w := 0; w < wordsPerLine; w++ {
			fg := (l*3 + w*5) % 8
			bg := (l + w*7) % 8
			attr := (l + w) % 4 // 0=normal 1=bold 2=dim 4=underline
			fmt.Fprintf(&sb, "\x1b[%d;3%d;4%dm", attr, fg, bg)
			for c := 0; c < 5; c++ {
				sb.WriteByte(baselineChar(l*17 + w*11 + c))
			}
			sb.WriteByte(' ')
		}
		sb.WriteString("\x1b[0m\r\n")
	}
	return []byte(sb.String())
}

// genCursorFrame 生成一帧 CUP/ED/EL 混合的局部重绘：
// 回 home 后擦除下方，再每隔三行用 CUP 定位写短文本并 EL 擦到行尾。
func genCursorFrame(rows, cols int) []byte {
	var sb strings.Builder
	sb.WriteString("\x1b[H\x1b[J")
	for r := 0; r < rows; r += 3 {
		fmt.Fprintf(&sb, "\x1b[%d;1H", r+1)
		text := cols / 4
		for c := 0; c < text; c++ {
			sb.WriteByte(baselineChar(r*13 + c*3))
		}
		sb.WriteString("\x1b[K")
	}
	fmt.Fprintf(&sb, "\x1b[%d;%dH", rows/2+1, cols/2+1)
	sb.WriteByte('*')
	return []byte(sb.String())
}

// genTUIStyledLine 生成一整行（cols 宽）带多段 SGR 的 TUI 内容；
// 末行是反色状态栏，模拟 Claude Code 风格全屏画面。
func genTUIStyledLine(sb *strings.Builder, r, cols int) {
	segments := 4
	segW := cols / segments
	for s := 0; s < segments; s++ {
		w := segW
		if s == segments-1 {
			w = cols - segW*(segments-1)
		}
		if r == 0 { // 末行（先写到底部的状态栏）使用反色
			fmt.Fprintf(sb, "\x1b[7;38;5;%dm", (r+s*5)%256)
		} else {
			fmt.Fprintf(sb, "\x1b[38;5;%dm", (r*3+s*7)%256)
		}
		for c := 0; c < w; c++ {
			sb.WriteByte(baselineChar(r*19 + s*23 + c*5))
		}
	}
	sb.WriteString("\x1b[0m")
}

// genTUIFrame 生成一帧全屏 TUI 重绘：CUP 回 home，逐行全宽重绘，
// 结尾 \r\n 在底行换行使每帧恰好滚出一行进入 scrollback（少量历史）。
func genTUIFrame(rows, cols int) []byte {
	var sb strings.Builder
	sb.WriteString("\x1b[H")
	for r := rows - 1; r >= 0; r-- {
		genTUIStyledLine(&sb, r, cols)
		if r > 0 {
			sb.WriteString("\r\n")
		}
	}
	sb.WriteString("\r\n") // 底行换行 → 滚出一行进历史
	return []byte(sb.String())
}

// genScrollChunk 生成持续滚动的文本块：每行末尾换行，稳态下每行都进入历史。
func genScrollChunk(cols, lines int) []byte {
	var sb strings.Builder
	for i := 0; i < lines; i++ {
		fmt.Fprintf(&sb, "\x1b[90m[%04d]\x1b[0m ", i%10000)
		genASCIILine(&sb, i, cols-12)
		sb.WriteString("\r\n")
	}
	return []byte(sb.String())
}

// BenchmarkTerminalWriteBaseline 测量 Terminal.Write（ANSI 解析 + 网格写入）
// 在各场景/尺寸下的吞吐与分配。b.SetBytes 以负载字节数报 MB/s。
func BenchmarkTerminalWriteBaseline(b *testing.B) {
	scenarios := []struct {
		name    string
		payload func(rows, cols int) []byte
	}{
		{"ascii", func(rows, cols int) []byte { return genASCIIChunk(cols, 32) }},
		{"sgr", func(rows, cols int) []byte { return genSGRChunk(cols, 32) }},
		{"cursor", genCursorFrame},
		{"tui", genTUIFrame},
		{"scroll", func(rows, cols int) []byte { return genScrollChunk(cols, 16) }},
	}
	for _, sc := range scenarios {
		for _, sz := range baselineSizes {
			b.Run(sc.name+"/"+sz.name, func(b *testing.B) {
				payload := sc.payload(sz.rows, sz.cols)
				term := New(
					WithSize(sz.rows, sz.cols),
					WithScrollback(NewMemoryScrollback(baselineScrollbackLines)),
				)
				// 预热：填满屏幕与部分历史，使每次迭代处于稳态。
				warmup := sc.payload(sz.rows, sz.cols)
				for i := 0; i < 20; i++ {
					if _, err := term.Write(warmup); err != nil {
						b.Fatal(err)
					}
				}
				b.SetBytes(int64(len(payload)))
				b.ReportAllocs()
				b.ResetTimer()
				for i := 0; i < b.N; i++ {
					if _, err := term.Write(payload); err != nil {
						b.Fatal(err)
					}
				}
			})
		}
	}
}
