# Go Agent + Android 终端性能优化复审修正报告

> 日期：2026-07-13  
> 范围：Go 权威终端链路与 Android 原生终端；不包含网页端与 Legacy Termux 优化

## 1. 复审结论

原性能计划的大方向正确，Go 阶段 2 的 dirty projection / 历史增量导出和 Android
阶段 3 的分段历史模型都带来了明显收益。但“计划已完成”等于“实现正确且报告准确”
这一结论在复审时不成立，主要原因有两个：

1. Android `HistoryChunk` 发布给 UI 后仍会原地 append/trim/replace，所谓不可变快照
   实际共享可变数组，存在跨线程数据竞争。
2. Go 报告把会清屏并重绘多行的 `cursor` benchmark 当成纯光标/单行负载，掩盖了
   稀疏全屏投影 B/op 从约 956 KB 增到 1.88 MB 的回退。

本轮已修复第一项、纠正两份验收报告，并补充真正的单 dirty row 基准。核心架构可以
继续保留，不需要推倒重做。

## 2. 本轮已完成修正

### 2.1 Android 历史快照改为真正不可变

- `HistoryChunk` 的 offset、size、estimatedBytes 改为 final。
- append、replace、局部 head/tail trim 全部以 copy-on-write 替换 chunk。
- 修复“头部局部 trim 后尾 chunk 已无物理容量，下一次 append 越界”的边界条件。
- `TerminalHistorySnapshot` 复制并只读包装 chunk 列表，同时复制 chunk 起始索引。
- `lineAt` 与 `findLineIndex` 在已发布快照上使用二分索引，不再线性扫描 chunk。
- 新增旧快照在 append/trim/replace 后内容不变、局部 chunk、trim 后 append 等回归测试。

最新同机复测（200×50 / 10 000 行）：

| 路径 | 基线 P50 | COW 修正后 P50 | 基线分配 | COW 修正后分配 |
|---|---:|---:|---:|---:|
| publish/history-copy | 46.54 µs | 1.42 µs | 80 432 B/op | 1 809 B/op |
| tail append / ASCII | 49.00 µs | 0.79 µs | 80 597 B/op | 3 559 B/op |
| tail append / mixed | 43.00 µs | 1.13 µs | 80 888 B/op | 3 879 B/op |
| scrolled append | 42.54 µs | 0.83 µs | 80 624 B/op | 3 559 B/op |

真正不可变会比原先错误的共享可变 chunk 多复制一个 128 行引用数组和字节数组，
因此不能继续宣称约 0.9 KB/op；但它仍保持常数级成本，并远低于旧 TreeMap 全量复制。

### 2.2 Android styled ASCII run 合批

- 连续 ASCII、相同 styleId 的 cell 合并为一次 text draw。
- selection、cursor、styleId 变化会切断 run。
- reverse、前景/背景、bold/dim/italic、hidden、underline、double underline、strike
  均在 run 路径保留；CJK、emoji、combining、宽字符继续走逐 cell 正确性路径。
- 字体测量宽度与格宽不一致、需要 glyph 横向缩放时不合批，回退逐 cell；模拟器
  像素对照曾实际捕获整 run 缩放与逐 cell 缩放的抗锯齿差异，收紧规则后通过。
- 新增 `styled_runs` 基准和 draw-event 回归断言。

200×50 Robolectric 软件阴影结果：连续 12-cell 样式段约 0.60 ms P50；随机每格切换
styleId 的最坏场景仍约 6 ms。后者没有可合并 run，不应把它误写成优化失败，也不能
把连续样式段的结果外推到每格换色的 TUI。Robolectric legacy Paint 固定把 ASCII
测成 1px，因此新增 ASCII run 场景用 1px cell 模拟生产环境“无需 glyph 缩放”的
monospace 路径；该基准用于比较 draw-call 路径，不用于评价真机像素尺寸。

### 2.3 Android 鼠标 MOVE 按帧合并

- 同一 Android frame 内只发送最新 MOVE 坐标。
- DOWN、UP、wheel 前先 flush 待发送 MOVE，保持 press/move/release 顺序。
- View detach、Host 替换/断线、mouse tracking 关闭时丢弃待发送 MOVE。

### 2.4 Go screen protocol Handler 复用

- `screenprotocol.Handler` 从“每条入站消息创建并绑定一组回调闭包”改为 Client 生命周期内创建一次。
- 增加实例复用回归测试；nil session 测试构造路径保留安全退化。

### 2.5 Go 单 dirty row 基准补齐

新增 `BenchmarkProjectorSingleDirtyRow`，把单行稳态更新与原先会清屏的 `cursor` 负载
分开。Apple M4、200×50 下三轮稳定在约 20.8 µs / 40 304 B/op / 14 allocs/op；
80×24 约 9.1 µs / 18 864 B/op。该结果证明 dirty row 稳态路径本身很轻，但不能
消除稀疏 Full Projection 的 1.88 MB/op 已知回退。

## 3. 明确延期项

### 3.1 Go printable span 一次持锁

暂不实现。当前 `pendingInput` 已从 string 改为 byte slice，并用 `uniseg.Step` 处理
grapheme，已经消除了原报告所说的逐 rune `string(r)` 与全字符串重跑；剩余问题是
外部 `go-ansicode` decoder 仍逐 rune 回调 `Input`，因此仍逐 rune 获取 Terminal 锁。

若只在 `Terminal.Write` 外层持锁，decoder 遇到控制序列后回调的 Backspace/Goto/SGR
等方法会再次获取同一把锁而死锁；绕过锁又会破坏公开 Handler API 的并发边界。
在没有 profile 证明它重新成为主热点前，不为减少锁次数 fork decoder 或生成整套
Handler wrapper。后续若要做，应先给 decoder 增加 printable-span callback，并冻结
ANSI 控制边界、跨 chunk grapheme、ZWJ/variation selector 的兼容测试。

### 3.2 Go 稀疏 Full Projection 中间复制

保留为 profile 驱动优化。当前绝对耗时低于 1 ms，主链路 16 ms 合批窗口占比更大。
后续方向是让 Full Projection 与 exporter 共享只读 row snapshot，减少
`Cell[] -> ProjectionRow -> protocol Line` 的中间复制；不能回退到逐 cell 加锁，也不能
让 Projector 持有会被 terminal 原地修改的数组。

### 3.3 随机逐 cell 样式渲染

每格 styleId 都不同就不存在 run。若真机 profile 证明该负载常见且仍是主线程热点，
下一步应评估 RenderNode/text blob 或 GPU atlas，而不是继续扩大当前 Canvas run key。

EventRing 零拷贝、Okio 端到端零拷贝、父进程链批量 `ps`、Manager 锁外启动 PTY、
Legacy Termux 优化仍按原计划延期；当前数据不足以证明它们应排在上述正确性边界之前。

## 4. 最终验收

以下结果均为复审修正后的实际运行结果，不沿用修正前报告：

- Android `./gradlew test --no-daemon --console=plain`：通过（305 tasks）。
- Android 新增 styled-run draw-event 与 mouse MOVE 合并定向测试：通过。
- Android `./gradlew assembleRelease --no-daemon --console=plain`：通过，含 R8/
  lintVital（682 tasks）。
- Android `./gradlew connectedDebugAndroidTest --no-daemon --console=plain`：通过；
  `emulator-5554` / medium_phone（Android 16）。随后新增的 styled-run 像素等价测试
  在 `:terminal-renderer:connectedDebugAndroidTest` 中与既有用例合计 10 tests 全过。
- Go root `go test -count=1 ./...`：沙箱外全量通过。沙箱内最初仅 PID 父子关系和
  Unix socket 可见性测试失败，沙箱外重跑对应包通过，确认不是代码回归。
- Go root `go test -race -count=1 ./...`：沙箱外全量通过。
- 独立 headlessterm module `go test -race -count=1 ./...`：通过。
- 两个 Go module `go vet ./...`：通过。
- `BenchmarkProjectorSingleDirtyRow` 三轮结果稳定；Android COW model 与 renderer
  基准均已重跑。
- `git diff --check`：通过。
