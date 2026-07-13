package terminalsession

import (
	"fmt"
	"io"
	"sort"
	"strings"
	"testing"
	"time"

	"webterm/go-core/internal/terminalengine"
)

// baseline_bench_test.go 是远程终端主链路性能基线（阶段 1，见
// docs/go-android-terminal-performance-optimization-plan.md §5.1）的
// projection flush 延迟部分：用 io.Pipe 伪 PTY 驱动 Runtime，测量从 PTY 输出
// 写入到 screen client 收到帧的端到端延迟分布（含 16ms 合并窗口
// projectionFlushWindow + ExportState + 每客户端帧发送）。
//
// 该测量必须经由生产代码既有的 Runtime/ScreenClient 抽象完成，未改动任何
// 生产代码。样本数受 16ms 合并窗口限制（默认 benchtime 下约 50-60 个/尺寸），
// P99 在样本量下偏粗，作为基线参照而非精确分位数。

type benchLatencySize struct {
	name string
	rows int
	cols int
}

var benchLatencySizes = []benchLatencySize{
	{"80x24", 24, 80},
	{"120x40", 40, 120},
	{"200x50", 50, 200},
}

// benchFakePTY 用两条 io.Pipe 模拟 PTY：outR 供 Runtime readLoop 读取输出，
// inW 接收引擎回写（终端响应），Close 两侧都关闭。
type benchFakePTY struct {
	reader *io.PipeReader
	writer *io.PipeWriter
}

func (p *benchFakePTY) Read(b []byte) (int, error)  { return p.reader.Read(b) }
func (p *benchFakePTY) Write(b []byte) (int, error) { return p.writer.Write(b) }
func (p *benchFakePTY) Close() error {
	_ = p.reader.Close()
	_ = p.writer.Close()
	return nil
}

// genBenchLatencyPayload 生成确定性混合负载：带 SGR 的文本行 + 一次局部
// CUP/EL 重绘，约 4KB，模拟一次 shell/TUI 输出突发。不含任何会触发终端
// 响应的查询序列（DSR/DA 等）。
func genBenchLatencyPayload(rows, cols int) []byte {
	const alphabet = "abcdefghijklmnopqrstuvwxyz0123456789 .,;:/-_=+ABCDEFGHIJKLMNOPQRSTUVWXYZ"
	var sb strings.Builder
	lines := 32
	for l := 0; l < lines; l++ {
		fmt.Fprintf(&sb, "\x1b[3%dm", l%8)
		for c := 0; c < cols-9; c++ {
			sb.WriteByte(alphabet[(l*31+c*7)%len(alphabet)])
		}
		sb.WriteString("\x1b[0m\r\n")
	}
	fmt.Fprintf(&sb, "\x1b[%d;1Hstatus\x1b[K", rows/2+1)
	return []byte(sb.String())
}

// BenchmarkProjectionFlushLatency 测量从 PTY 输出写入到帧送达 screen client
// 的延迟分布。每次迭代写一块输出并等一帧，互不重叠，因此每次迭代恰好触发
// 一次 16ms 合并窗口后的 broadcastFrame。延迟以自定义指标 p50/p95/p99-ms 上报。
func BenchmarkProjectionFlushLatency(b *testing.B) {
	for _, sz := range benchLatencySizes {
		b.Run(sz.name, func(b *testing.B) {
			outR, outW := io.Pipe()
			inR, inW := io.Pipe()
			pty := &benchFakePTY{reader: outR, writer: inW}
			// 持续排空引擎→PTY 回写，防止任何意外响应阻塞 actor。
			done := make(chan struct{})
			go func() {
				_, _ = io.Copy(io.Discard, inR)
				close(done)
			}()

			r := NewRuntime("bench-latency", pty, sz.rows, sz.cols)
			b.Cleanup(func() {
				_ = r.Close()
				_ = outW.Close()
				_ = inW.Close()
				<-done
			})

			frameCh := make(chan time.Time, 4)
			r.AttachClient(&ScreenClient{
				ID: "c1",
				Send: func(terminalengine.ScreenFrame) {
					frameCh <- time.Now()
				},
			})
			<-frameCh // attach 初始快照

			payload := genBenchLatencyPayload(sz.rows, sz.cols)
			latencies := make([]time.Duration, 0, 128)
			b.ReportAllocs()
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				start := time.Now()
				if _, err := outW.Write(payload); err != nil {
					b.Fatal(err)
				}
				sent := <-frameCh
				latencies = append(latencies, sent.Sub(start))
			}
			b.StopTimer()
			reportBenchLatencyPercentiles(b, latencies)
		})
	}
}

func reportBenchLatencyPercentiles(b *testing.B, latencies []time.Duration) {
	b.Helper()
	if len(latencies) == 0 {
		return
	}
	sorted := make([]time.Duration, len(latencies))
	copy(sorted, latencies)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i] < sorted[j] })
	pick := func(p int) time.Duration {
		idx := (len(sorted) - 1) * p / 100
		return sorted[idx]
	}
	b.ReportMetric(float64(pick(50).Nanoseconds())/1e6, "p50-ms")
	b.ReportMetric(float64(pick(95).Nanoseconds())/1e6, "p95-ms")
	b.ReportMetric(float64(pick(99).Nanoseconds())/1e6, "p99-ms")
}
