# Go 终端性能基线（改造前）

> 日期：2026-07-13
> 环境：Apple M4（darwin/arm64），JDK 无关，Go 默认 benchtime（1s/子基准）
> 用途：`go-android-terminal-performance-optimization-plan.md` 阶段 1 交付物，作为阶段 2 完成后的对比基准（计划 §10 步骤 6/11）。
> 对应测试文件：`go-core/internal/headlessterm/baseline_bench_test.go`、`go-core/internal/screenprojection/baseline_bench_test.go`、`go-core/internal/terminalsession/baseline_bench_test.go`

## 运行方式

```bash
# 注意：headlessterm 是嵌套 module，必须在其目录内单独跑
cd go-core/internal/headlessterm && go test -run=^$ -bench=. -benchmem        # ~20s
cd go-core && go test -run=^$ -bench=. -benchmem ./internal/screenprojection/ # ~145s（含既有基准）
cd go-core && go test -run=^$ -bench=. -benchmem ./internal/terminalsession/  # ~5s
```

负载全部纯算法生成（按位置取模的字符表 + 循环 SGR 色），无随机源，可重复。

## 覆盖矩阵

- `BenchmarkTerminalWriteBaseline`：ascii / sgr / cursor / tui / scroll × 80x24、120x40、200x50（Terminal.Write 热路径，`b.SetBytes` 报 MB/s，scrollback 10000 行）
- `BenchmarkExportStateBaseline`：同 5 场景 × 3 尺寸，单独计时 `ExportState`
- `BenchmarkExportSplitBaseline`：screen-only vs history-heavy——量化历史窗口导出的边际成本
- `BenchmarkFrameDeriverDiffBaseline`：ascii/tui × 3 尺寸 × clients=1/2/4，纯 `FrameForState` diff
- `BenchmarkProjectorExportFanoutBaseline`：tui × 3 尺寸 × clients=1/2/4，Write+Export+N×diff 全链路
- `BenchmarkProjectionFlushLatency`：io.Pipe 伪 PTY 驱动真实 Runtime，测量"写 PTY → 帧送达"p50/p95/p99

## 基线数值

### Write 吞吐（headlessterm）

| name | ns/op | MB/s | allocs/op | B/op |
|---|---:|---:|---:|---:|
| ascii/80x24 | 521,730 | 4.97 | 5,540 | 572,821 |
| ascii/120x40 | 826,230 | 4.69 | 8,283 | 766,772 |
| ascii/200x50 | 1,457,622 | 4.41 | 13,769 | 1,294,000 |
| sgr/80x24 | 464,276 | 11.44 | 7,588 | 630,682 |
| sgr/200x50 | 1,290,148 | 10.07 | 18,731 | 1,436,528 |
| cursor/80x24 | 53,078 | 4.75 | 4,830 | 39,005 |
| cursor/200x50 | 287,155 | 3.60 | 25,237 | 202,617 |
| tui/80x24 | 319,488 | 9.48 | 878 | 36,179 |
| tui/200x50 | 1,638,797 | 7.56 | 2,752 | 85,357 |
| scroll/80x24 | 258,664 | 5.32 | 2,854 | 287,586 |
| scroll/200x50 | 729,367 | 4.52 | 6,969 | 648,224 |

### ExportState（screenprojection，历史窗口满 300 行）

| name | ns/op | allocs/op | B/op |
|---|---:|---:|---:|
| ascii/200x50 | 4,595,429 | 3,502 | 6,694,275 |
| sgr/200x50 | 5,386,601 | 39,449 | 6,526,819 |
| cursor/200x50（历史空） | 719,040 | 501 | 956,289 |
| tui/200x50 | 5,015,301 | 10,831 | 6,326,278 |
| scroll/200x50 | 4,711,819 | 5,247 | 6,884,128 |

### 屏幕 vs 历史窗口拆分（200x50）

- screen-only：721,784 ns/op，501 allocs，0.96 MB
- history-heavy：4,747,688 ns/op，5,247 allocs，6.88 MB
- **结论：历史窗口（300 行重导出）占导出成本约 85%、分配约 86%，是阶段 2c 最主要的优化靶点。**

### FrameForState diff（每客户端）

- ascii/200x50：clients=1/2/4 = 29,503 / 56,930 / 111,300 ns/op（线性）
- tui/200x50：32,636 / 63,557 / 122,783 ns/op；allocs 14/28/56

### 扇出（tui，Write+Export+N×diff 全计时）

- 200x50 clients=1/2/4 = 6.85 / 6.83 / 6.93 ms/op——export-once 不变量成立，1→4 客户端边际成本 <2%。

### Projection flush 延迟（terminalsession）

| 尺寸 | p50 | p95 | p99 |
|---|---:|---:|---:|
| 80x24 | 22.59 ms | 24.40 ms | 24.44 ms |
| 120x40 | 23.79 ms | 25.10 ms | 25.42 ms |
| 200x50 | 24.89 ms | 26.71 ms | 26.75 ms |

即 16ms 合并窗口 + 2-5ms 导出 + ~5ms 管线开销；尺寸增大时导出占比上升。

## 已知限制

- terminalsession P99 样本偏粗：16ms 合并窗口决定每次迭代 ≥16ms，默认 benchtime 下每尺寸约 56-60 个样本，P99 近似 max，作基线参照够用。
- `FrameDeriverDiff` 的状态环回绕（63→0）那 1/64 样本历史窗口为反向，不影响结论（代码注释已写明）。

## 阶段 2 改造后的对比口径

- `ExportState`（或改造后的增量 flush）在 ascii/tui/scroll 场景下，单行变化应不再出现 300 行历史窗口重导出：history-heavy 的 ns/op 与 B/op 应显著下降并向 screen-only 收敛。
- `FrameForStateDiff`：diff 不再每帧构建 `oldHistoryIDs` map，allocs/op 应下降。
- Write 吞吐（headlessterm）不应回退；阶段 4 的 printable 批处理若实施，ascii/sgr 的 allocs/op 应明显下降。
- flush 延迟 p50/p95 应随导出成本下降而下降。
