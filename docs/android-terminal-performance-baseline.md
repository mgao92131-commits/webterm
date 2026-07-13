# Android 终端性能基线

> 对应计划：`docs/go-android-terminal-performance-optimization-plan.md` §5.2
> 采集时间：2026-07-13（UTC），commit `7b9cc56`（工作区另有未提交的 Go 侧基线文件）
> 状态：基线初版。所有场景可重复运行，数据生成固定 seed；不设耗时阈值断言，只打印结构化报告供改造前后对比。

## 1. 环境

| 项 | 值 |
|---|---|
| 机器 | Apple M4（arm64），16GB，macOS |
| JDK | OpenJDK 21.0.9（64-Bit Server VM, mixed mode），Gradle 测试 worker 默认堆 |
| 构建 | Gradle 9.1.0，AGP 9.0.1，compileSdk 36 |
| 渲染测试 | Robolectric 4.14.1（`@Config(sdk = 34)` 软件阴影，test 任务堆 2g） |

## 2. 测试基础设施

| 文件 | 内容 |
|---|---|
| `terminal-model/src/test/java/com/webterm/terminal/model/PerformanceBaselineTest.java` | applyPatch / publishRenderSnapshot / 滚动-follow-tail 基线 |
| `terminal-renderer/src/test/java/com/webterm/terminal/renderer/RemoteTerminalRendererRenderBaselineTest.java` | render() 基线（Robolectric） |
| `feature/terminal/src/test/java/com/webterm/feature/terminal/domain/TerminalSessionRuntimeMailboxBaselineTest.java` | mailbox 溢出基线 |
| 三个模块的 `build.gradle.kts` | 新增 `testLogging.showStandardStreams`；terminal-renderer 新增 `testImplementation("org.robolectric:robolectric:4.14.1")` 与 test 堆 2g |

复现：

```bash
cd android-client
./gradlew :terminal-model:test :terminal-renderer:test :feature:terminal:test --console=plain | grep PerfBaseline
```

报告行格式：`[PerfBaseline] scenario=... size=..x.. history=... content=... iters=... p50_us=... p95_us=... p99_us=... max_us=... mean_us=... alloc_bytes_per_op=... gc_count_delta=... gc_time_ms_delta=...`。

测量方法：

- 耗时：`System.nanoTime` 单次采样，先 warmup（model 200 / render 30 帧）再测量（applyPatch 1000 次、publish 500 次、render 100 次），报告 P50/P95/P99/max/mean。
- 分配量：`com.sun.management.ThreadMXBean.getThreadAllocatedBytes` 前后差值 / 迭代次数。patch/帧数据在计时区外预构建，不计入被测分配。
- GC：`GarbageCollectorMXBean` 的 collectionCount/collectionTime 前后差值（1000 帧窗口）。
- `java.lang.management` 不在 Android API 面（unit test 编译走 mockable android.jar），上述 MXBean 全部反射访问；测试实际运行在宿主 HotSpot JVM 上，反射稳定可用。
- `publishRenderSnapshot` 是 private，反射调用以隔离 history 全量复制成本。
- 历史预算 soft==hard==目标行数、字节预算关闭：snapshot 恰好 N 行不触发驱逐，之后每次 append 驱逐 1 行最旧历史，稳态行数恒为 N。

## 3. 固定数据规模与负载

规模：80×24 + 1000 行历史、120×40 + 5000 行、200×50 + 10000 行。
负载：① 每 patch 追加一行历史（tail append）；② 每 patch 只改一行活动屏幕；③ 持续输出时滚动/follow-tail；④ 内容维度 styled ASCII / CJK / emoji / combining mark / 宽字符混合（每 8 格一块：4 ASCII + 1 styled + 1 combining + 1 宽字符）。

## 4. 基线数值

### 4.1 `applyPatch()` — tail append（每帧追加 1 行历史 + 驱逐 1 行）

| 规模 | 历史行 | 内容 | P50 µs | P95 µs | P99 µs | max µs | mean µs | alloc B/op | GC 次数/时间(ms) |
|---|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 80×24 | 1000 | ascii | 7.04 | 9.54 | 11.17 | 23.67 | 7.03 | 8520 | 0 / 0 |
| 80×24 | 1000 | mixed | 8.58 | 10.96 | 12.54 | 16.79 | 8.46 | 8839 | 0 / 0 |
| 120×40 | 5000 | ascii | 26.13 | 30.96 | 32.96 | 655.13 | 26.49 | 40560 | 0 / 0 |
| 120×40 | 5000 | mixed | 23.92 | 28.17 | 33.46 | 824.92 | 25.03 | 40860 | 1 / 1 |
| 200×50 | 10000 | ascii | 49.00 | 68.00 | 113.71 | 170.46 | 51.54 | 80597 | 0 / 0 |
| 200×50 | 10000 | mixed | 43.00 | 64.50 | 73.13 | 1398.04 | 47.96 | 80888 | 1 / 1 |

### 4.2 `publishRenderSnapshot()`（反射隔离调用）

| 规模 | 历史行 | 模式 | P50 µs | P95 µs | P99 µs | max µs | mean µs | alloc B/op | GC 次数/时间(ms) |
|---|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 80×24 | 1000 | history-copy | 6.29 | 11.67 | 26.46 | 2580.38 | 12.38 | 8415 | 1 / 2 |
| 80×24 | 1000 | history-reuse | 0.25 | 0.29 | 0.46 | 6.83 | 0.29 | 248 | 0 / 0 |
| 120×40 | 5000 | history-copy | 25.83 | 28.71 | 40.46 | 1747.75 | 29.38 | 40393 | 1 / 2 |
| 120×40 | 5000 | history-reuse | 0.29 | 0.54 | 1.13 | 10.00 | 0.41 | 312 | 0 / 0 |
| 200×50 | 10000 | history-copy | 46.54 | 54.33 | 62.21 | 88.38 | 46.70 | 80432 | 0 / 0 |
| 200×50 | 10000 | history-reuse | 0.25 | 0.29 | 1.00 | 1.25 | 0.27 | 352 | 0 / 0 |

history-copy = `new ArrayList<>(historyCache.values())` 全量复制路径；history-reuse = 历史未变化时复用上一帧列表。

### 4.3 `applyPatch()` — 单行活动屏幕修改（局部重绘，历史不变）

| 规模 | 内容 | P50 µs | P95 µs | P99 µs | max µs | alloc B/op |
|---|---|---:|---:|---:|---:|---:|
| 80×24 | ascii | 0.17 | 0.21 | 0.25 | 2.38 | 464 |
| 80×24 | styled_ascii | 0.29 | 0.33 | 0.54 | 2.88 | 752 |
| 80×24 | cjk | 0.17 | 0.21 | 0.21 | 1.13 | 464 |
| 80×24 | emoji | 0.17 | 0.21 | 0.21 | 1.13 | 464 |
| 80×24 | combining | 0.17 | 0.21 | 0.21 | 0.50 | 464 |
| 80×24 | mixed | 0.29 | 0.33 | 0.50 | 0.79 | 752 |
| 120×40 | ascii | 0.17 | 0.21 | 0.21 | 1.71 | 555 |
| 120×40 | styled_ascii | 0.29 | 0.33 | 0.46 | 3.50 | 816 |
| 120×40 | cjk | 0.17 | 0.21 | 0.25 | 1.17 | 528 |
| 120×40 | emoji | 0.17 | 0.21 | 0.21 | 0.46 | 528 |
| 120×40 | combining | 0.17 | 0.21 | 0.33 | 0.38 | 528 |
| 120×40 | mixed | 0.29 | 0.33 | 0.46 | 0.96 | 816 |
| 200×50 | ascii | 0.33 | 0.38 | 0.42 | 2.00 | 568 |
| 200×50 | styled_ascii | 0.29 | 0.33 | 0.63 | 3.75 | 856 |
| 200×50 | cjk | 0.17 | 0.21 | 0.21 | 1.17 | 568 |
| 200×50 | emoji | 0.33 | 0.38 | 0.50 | 3.75 | 568 |
| 200×50 | combining | 0.17 | 0.21 | 0.25 | 2.33 | 568 |
| 200×50 | mixed | 0.25 | 0.25 | 0.29 | 1.29 | 824 |

GC 次数/时间全部为 0。

### 4.4 滚动 / follow-tail（持续输出时视口钉住）

场景：视口先滚到历史中段（follow-tail 解除），再持续 tail-append 1000 帧，每帧附带 `scrollBy(0, max)`。

| 规模 | 历史行 | P50 µs | P95 µs | P99 µs | max µs | alloc B/op |
|---|---:|---:|---:|---:|---:|---:|
| 80×24 | 1000 | 9.25 | 11.42 | 17.50 | 47.00 | 8520 |
| 120×40 | 5000 | 31.75 | 39.33 | 56.71 | 149.42 | 40584 |
| 200×50 | 10000 | 42.54 | 55.21 | 58.46 | 967.75 | 80624 |

正确性断言：append 期间 followTail 保持 false、scrollOffsetPixels 不变；滚回底部（offset==0）立即恢复 followTail。数值与 §4.1 同规模一致——视口状态是纯 POJO，成本可忽略，模型 append 不触碰视口。

### 4.5 `RemoteTerminalRenderer.render()`（Robolectric 软件阴影）

**重要限制**：Robolectric 的 Canvas 是软件阴影——draw 调用走完整 Java 路径但不光栅化，且 ShadowBitmap 为每次 draw 保留调试记录（测试每 20 帧重建 Bitmap/Canvas 防止无界累积）。`alloc_bytes_per_op` 主要是阴影记录开销，**不代表真机分配**；绝对耗时也不代表真机 GPU 管线时间，只可与本仓库后续同环境采样对比。几何固定为 cellWidth=10px、lineHeight=20px。

| 规模 | 内容 | follow-tail P50/P95/max µs | scrolled P50/P95/max µs | alloc B/op（阴影记录） |
|---|---|---|---|---:|
| 80×24 | ascii | 93.58 / 183.17 / 313.21 | 29.13 / 33.75 / 125.50 | 10016 / 5322 |
| 80×24 | styled_ascii | 1657.71 / 4335.29 / 7006.38 | 1481.58 / 1815.83 / 4757.38 | 2799323 / 2624186 |
| 80×24 | cjk | 275.58 / 285.42 / 299.38 | 37.38 / 42.00 / 51.42 | 51255 / 45843 |
| 80×24 | emoji | 44.42 / 52.08 / 60.63 | 38.38 / 43.63 / 55.29 | 51256 / 45843 |
| 80×24 | mixed | 462.83 / 630.71 / 7420.54 | 198.00 / 215.50 / 240.42 | 337139 / 306478 |
| 120×40 | ascii | 61.79 / 64.75 / 73.88 | 26.21 / 57.13 / 59.38 | 14156 / 9553 |
| 120×40 | styled_ascii | 3152.38 / 3988.17 / 10402.13 | 2846.88 / 3362.00 / 12551.17 | 6849872 / 6313963 |
| 120×40 | cjk | 57.21 / 77.79 / 92.92 | 52.42 / 68.08 / 152.92 | 127755 / 121704 |
| 120×40 | emoji | 56.92 / 72.25 / 86.08 | 52.58 / 68.83 / 81.92 | 127755 / 121704 |
| 120×40 | mixed | 356.04 / 416.29 / 493.83 | 352.04 / 408.79 / 6104.75 | 770835 / 772111 |
| 200×50 | ascii | 51.75 / 56.17 / 63.88 | 45.38 / 49.33 / 83.04 | 19338 / 14902 |
| 200×50 | styled_ascii | 6714.13 / 12926.38 / 15251.63 | 6386.46 / 12883.25 / 15422.92 | 14697684 / 13904903 |
| 200×50 | cjk | 133.08 / 167.79 / 11719.25 | 130.58 / 164.46 / 1064.83 | 484458 / 472327 |
| 200×50 | emoji | 136.04 / 232.13 / 485.00 | 127.96 / 138.13 / 141.71 | 484458 / 472327 |
| 200×50 | mixed | 930.75 / 3432.96 / 4335.75 | 1066.58 / 2740.50 / 3421.71 | 1893428 / 1993340 |

（scrolled 视口滚到历史最顶端，只绘制可见历史行；可见行数由画布高度决定，与屏幕模式同量级。）

### 4.6 mailbox 有界性（500 帧突发，排队 executor 模拟喂帧快于消费）

| burst 帧数 | 深度峰值 | 溢出次数 | resync 请求 | channel 重建 | 喂帧耗时 |
|---:|---:|---:|---:|---:|---:|
| 500 | 64 | 7 | 7 | 0 | 3 ms |

深度峰值经反射读取 `pendingScreenMessages` 实测，恰好触及 64 帧上限；每次溢出清空积压并升级为一次 resync（等待期间再次溢出会重发，故 resync=溢出次数）；drain 先消费完全部 500 帧（最终 revision=500），无 channel 重建。溢出收敛语义由 `TerminalSessionRuntimeResizeTest` 覆盖。

## 5. 关键观察

1. **history 全量复制是 tail-append 的主导成本**：publish/history-copy 的 P50 随历史规模线性增长（6.3 → 25.8 → 46.5 µs @ 1k/5k/10k 行），alloc 同步线性增长（8.4 → 40.4 → 80.4 KB/op ≈ 行数×8B 引用 + 对象开销）；tail-append 的 P50 与之几乎重合（7.0 → 26.1 → 49.0 µs）。这是计划阶段 3（分段不可变历史）的直接量化依据。
2. **单行屏幕修改与历史规模无关**：局部重绘 P50 稳定在 0.17–0.35 µs，alloc ≈ 0.5–0.9 KB/op（`screen.clone()` + RenderSnapshot 对象），historyLines 复用路径生效；内容类型（CJK/emoji/combining）对 model 层无可见影响。
3. **视口状态零成本**：scrolled-append 与 tail-append 数值一致；模型 append 不读写 TerminalViewportState，follow-tail 钉住语义正确。
4. **render 的 styled per-cell 路径最贵（阴影口径）**：plain ASCII 有 run 合批，200×50 整屏 P50 ≈ 52 µs；全 styled 内容禁用合批走逐 cell 路径，P50 ≈ 6.7 ms（80×24 也已达 1.7 ms）；CJK/emoji 居中（130–140 µs @ 200×50）；mixed ≈ 1 ms。阶段 4 的 styled run 合批（§8.3）应以本表 styled_ascii 行作为改造前后对比基准，但须注意阴影与真机的差距。
5. **mailbox 有界性符合设计**：64 帧上限实测成立，溢出→resync 收敛确定（500 帧突发 = 7 次溢出 = 7 次 resync）。

## 6. 未覆盖指标与原因

| 指标 | 状态 | 原因 |
|---|---|---|
| 主线程 dropped frames | 跳过 | 需要真机/模拟器 UI 线程与 Choreographer 回调，JVM 单测与 Robolectric 均无此机制；后续可用 androidTest + `Choreographer.FrameCallback` 或 Macrobenchmark 补测。 |
| render() 真机像素时间 / 真实分配 | 部分覆盖 | Robolectric 软件阴影不光栅化，alloc 含阴影调试记录开销；本报告 render 数值仅供同环境纵向对比。真机数据建议阶段 4 前用 emulator-5554 + GPU 渲染 profile 补测。 |
| 长按选择 / 拖动选择手柄 | 跳过 | 选择手势路径在 `RemoteTerminalView`（MotionEvent + Android View），JVM 层无驱动入口；Renderer 的 selection 绘制开销已被 §4.5 的 per-cell 路径间接覆盖（selection 只增加每 cell 一次比较与末尾一次 drawRect）。跨边界选择复制正确性由计划 §7.0 的 failing test 另行处理。 |
| Java heap 绝对占用 / GC pause 分布 | 替代指标 | 按任务约定以 alloc_bytes/op（ThreadMXBean）+ GC 次数/时间（GarbageCollectorMXBean）近似；JVM 单测无精确分配回调，pause 分布需 JFR 或真机 ART 日志。 |
| patch 构造（ScreenMessageMapper）分配 | 未覆盖 | 本基线只测 model 层，patch 对象在计时区外预构建；mapper 侧分配属于协议层，计划未列入 §5.2。 |

## 7. 注意事项

- 所有场景无耗时阈值断言，只有正确性断言（稳态历史行数、revision 推进、snapshot 复用语义、mailbox 上界等），避免 CI 抖动。
- max/p99 偶有尖峰（个别达 ms 级）来自宿主 JVM 的 GC/JIT 与 Robolectric 阴影记录，对比时应以 P50/P95 为准。
- 二次运行 render 基线需要网络（Robolectric 首次运行会下载 android-all-instrumented，已缓存于 Gradle/Maven 本地仓库）。
