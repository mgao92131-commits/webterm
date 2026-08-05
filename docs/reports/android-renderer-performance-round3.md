# Android Renderer Round 3 性能验收

## 基线与范围

分支：`agent/android-renderer-performance-round3`  
Round 2 基线：`b2326f53`  
Round 3 操作量基线：`57e212f5`  
初次实现提交：`81de941d`；Hardening 复测数据提交：`7efaa4b1`；预算修订提交：`e5add424`

本轮只改 `terminal-renderer` 的 Prepared 绘制计划、背景/装饰/特殊 glyph 绘制路径和测试基准，没有修改终端协议、CellValue 宽度语义、模型快照合并、字体路由或 Prepared cache 的默认预算。

## 提交结果

已完成：

1. PreparedLineDrawPlan 预计算物理 span 边界、背景/装饰/特殊 glyph 操作索引；
2. 连续背景 run 合并；
3. decoration run 合并；
4. 特殊 glyph run 预分类；
5. Block/Braille 安全批量 clip，Box/Powerline 保留 cell clip；
6. dotted/dashed 有界 Path cache；
7. curly 周期 Path cache，保留原一像素子路径结构；
8. 静态、slow blink、fast blink 操作索引；
9. 真实 `RemoteTerminalView` 历史滚动和缓存 churn 基准；
10. legacy逐 Span 与 prepared plan 的独立 Bitmap parity 测试。

特殊 glyph run 的裁剪策略另有修复提交 `3c7e0c81`：避免在 family 数组填充前读取默认 clip policy。

## 操作量结果

初始数据来自 API 36 `medium_phone(AVD) - 16`、14sp、默认字体、每场景 10 次预热和 30 次采样；原始结果见 [android-renderer-performance-round3-final.jsonl](android-renderer-performance-round3-final.jsonl)。这些是 renderer cold/prepared-hit 微基准，不是完整应用帧时间。Hardening 后的复测见 [android-renderer-performance-round3-hardening-api36.jsonl](android-renderer-performance-round3-hardening-api36.jsonl)。

| 场景 | Round 3 基线 P95 | 初次实现 P95 | Hardening 后 P95 | 主要变化 |
| --- | ---: | ---: | ---: | --- |
| Box TUI | 2.544 ms | 1.704 ms | 1.776 ms | special run 1632→254；gap 修复后 decoration run 121→135 |
| Braille TUI | 3.950 ms | 3.112 ms | 2.389 ms | 4800 个 cell clip→0；run clip 40 |
| Block TUI | 3.298 ms | 1.608 ms | 1.537 ms | 4800 个 cell clip→0；run clip 40 |
| Curly | 24.925 ms | 0.432 ms | 0.350 ms | 105760 segments→0；cache hit 40 |
| Dotted | 5.694 ms | 0.431 ms | 0.360 ms | 35320 primitive→40 |
| Dashed | 2.934 ms | 0.429 ms | 0.444 ms | 15200 primitive→40 |
| Mixed decoration | 12.819 ms | 2.611 ms | 2.884 ms | curly build 200→0；pattern hit 200 |
| Mixed TUI | 4.831 ms | 3.741 ms | 3.614 ms | special run 1920→1467；cell clip 1920→960 |

P95 会受模拟器负载影响，表格只用于同一设备上的方向性观察；操作量指标比单次时间更可靠。

新增 `background_tui` 场景的初次实现 P95 为 `3.744 ms`，Hardening 后为 `3.592 ms`，40 行共绘制 320 个合并背景 run。该场景是在初始基线提交后补入的，因此没有伪造 Round 3 基线对照数字。

## 真实 View 滚动基准

执行的是 opt-in 的 `RemoteTerminalViewRound2AcceptanceBenchmarkTest`，经过生产 `RemoteTerminalView`、`TerminalLineRenderNodeCache` 和 `TerminalPreparedLineCache`；不是裸 RenderNode 录制。Hardening 后复测原始结果见 [android-renderer-performance-round3-view-hardening-api36.jsonl](android-renderer-performance-round3-view-hardening-api36.jsonl)。

| 场景 | View P95 | FrameMetrics P95 | RenderNode records | hits | Prepared hits/misses | max bytes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1200 行简单回滚 | 16.937 ms | 23.672 ms | 2691 | 1621 | 630/2061 | 4.44 MiB |
| 5000 行复杂 TUI | 13.697 ms | 50.259 ms | 4350 | 0 | 2492/4005 | 11.99 MiB |

Prepared hit/miss 现在直接累加每帧计数，不再把 frame-local counter 当作累计 counter；因此它们与实际生产路径访问量处于同一统计口径。当前 API 36 模拟器的 FrameMetrics 样本在这次运行中全部超过 16.67ms；这说明测试环境有明显长帧，但不能证明长帧全部来自 RenderNode record。复杂场景的 View 侧 P95 接近预算，Prepared cache 保持在 12 MiB 预算内。

## Hardening 修复

- 背景和 decoration run 现在同时检查物理像素边界，不会跨过被编译器省略的默认空白或 spacer；
- 移除 draw plan 中生产绘制不再使用的索引数组，降低 Prepared cache 单行估算；
- 修正 `PreparedSpecialGlyphRun` 的四组 `int[]` 和一个 `byte[]` 的元素及数组头估算，缓存预算采用有界近似；
- Special glyph clip safety 已对完整 Block `U+2580..U+259F` 和 Braille `U+2800..U+28FF` 做 Bitmap 越界扫描；
- 增加 gap parity 和 400 条固定种子随机 prepared-vs-legacy bitmap 测试；
- GitHub Actions Android Gradle 验证改为 literal block，避免 shell 把续行反斜杠解析为任务参数。

## RenderNode 预热决策

没有提交方向性 RenderNode prewarm。现有证据只有总帧指标、record/hit 计数和 renderer 微基准，缺少可以证明“RenderNode record 是主要长帧原因”的分段证据；在这种情况下加入 UI 线程预热会增加调度和长帧风险。后续若 Perfetto/FrameMetrics 明确显示新行 record 是主因，再单独开可回滚提交。

## 验证结果

已通过：

- `:terminal-renderer:testDebugUnitTest`
- `:terminal-renderer:assembleDebug`
- `:terminal-renderer:compileDebugAndroidTestJavaWithJavac`
- 完整 `:terminal-renderer:connectedDebugAndroidTest`，API 36
- 显式 Round 3 renderer 操作量基准，API 36
- 显式真实 View 滚动/缓存 churn 基准，API 36
- legacy逐 Span 与 prepared plan Bitmap parity
- gap、decoration key、special glyph clip safety 和随机 prepared-vs-legacy parity 测试
- `git diff --check`

尚未验证：

- API 29 模拟器：当前本机只安装 API 36 system image；
- 真实 Android 设备；
- GitHub Actions/CI workflow。

因此本报告可以宣布第三轮 renderer hardening 代码和 API 36 验收完成，但不能把设备矩阵状态写成完整发布通过。GitHub Actions workflow 已修正，尚未产生远程 workflow run。

## 后续热点

如果要继续优化，应先用 Perfetto 或真实设备确认：

- complex TUI 的 FrameMetrics 长帧是否由 RenderNode recording、View invalidation 或 GPU 合成造成；
- Prepared cache 在 5000 行回滚中的命中率是否值得改变预算；
- decoration run 合并是否需要更多跨字体角色 fixture。

本轮不继续扩大 RenderNode 容量，也不在没有 profile 证据时加入预热。
