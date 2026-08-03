# Android Canvas / RenderNode 终端渲染第一阶段基线

## 基线范围

本报告对应基准提交：`d2330ad4c515eee5e4ed20be272c89004a60d4c3`。

第一阶段只增加测试夹具、JVM/Robolectric 测试、设备测试和本报告；没有修改
`android-client/terminal-renderer/src/main/` 下的生产绘制代码，也没有改变服务端协议、
`CellValue` 语义、历史加载或滚动逻辑。系统字体文字只做同设备相对比较，不固化当前 glyph
像素为正确 Golden。

## 测试环境

| 项目 | 值 |
| --- | --- |
| 测试设备 | `medium_phone` AVD，`sdk_gphone64_arm64` |
| Android | API 36，Android 16 |
| 分辨率 | 1080 × 2400 physical |
| density | 420 dpi |
| 字体 | `Typeface.MONOSPACE`（诊断值 `monospace`） |
| 字号 | 14 sp |
| cellWidth | 22.0 px |
| lineHeight | 44.0 px |
| baselineOffset | 34.09424 px |

API 29 模拟器和真实 Android 设备不在本次可用设备列表中，因此本报告不把这两项标记为已验证。

## 测试命令

```sh
cd android-client
./gradlew :terminal-renderer:testDebugUnitTest
./gradlew :terminal-renderer:assembleDebug
./gradlew :terminal-renderer:connectedDebugAndroidTest
```

## 覆盖内容

- `TerminalCompatibilityFixtures` 固化 ASCII、combining、CJK、Arabic、Indic、emoji、Box
  Drawing、Block Elements、Braille、Powerline、ANSI style 和边界场景；宽字符使用显式
  `width=2` 起始 cell 加 spacer，不在 Android 测试中重新计算 Unicode 宽度。
- `TerminalCompatibilityFixturesTest` 严格检查 cell 数组、width、spacer、物理列边界和
  `RenderLine` 构造。
- `RemoteTerminalRendererCharacterizationTest` 记录 ASCII run、逐 cell grapheme、空白/隐藏
 文字和 cursor/selection 的 Canvas 操作特征。
- `RemoteTerminalRendererBitmapInvariantTest` 检查背景、selection、cursor、宽字符覆盖、
  hidden/reverse 和终端边界等与字体轮廓无关的不变量。
- `RemoteTerminalViewRenderNodeBaselineTest` 覆盖首帧、单行 patch、第二帧 cache hit、cursor
  blink、selection 变化及 40 × 120 混合 Unicode 性能场景。
- `RemoteTerminalCanvasRenderNodeParityTest` 在同一设备上使用直接 Canvas 与实际硬件 View/
  RenderNode，并通过 PixelCopy 比较终端有效区域。

## 执行结果

| 检查 | 结果 |
| --- | --- |
| JVM/Robolectric | 43/43 通过，0 failed，0 error |
| `assembleDebug` | 通过 |
| API 36 `connectedDebugAndroidTest` | 19/19 通过，0 skipped，0 failed |
| API 29 | 未验证：当前无 API 29 设备 |
| 真实设备 | 未验证：当前 adb 只有上述 AVD |

### RenderNode 指标

以下数值来自最终一次完整 instrumentation 运行的 `PERF_DEVICE_*` 日志：

| 场景 | 结果 |
| --- | --- |
| 8 × 80 首帧 | `rowNodeRecordCount=8`，`rowCacheMissCount=8`，`rowCacheHitCount=0` |
| 单行 patch | 录制增量 1，`rowCacheMissCount` 增量 1，缓存命中增量 7 |
| 同画面第二帧 | 录制增量 0，缓存命中增量 8，miss 增量 0，render duration 增量 93,042 ns |
| cursor blink off | 录制增量 0，缓存命中增量 8，miss 增量 0 |
| selection 改变 | 录制增量 0，缓存命中增量 8，miss 增量 0，截图变化像素 2,380 |
| 40 × 120 混合 Unicode | 录制 40，miss 40，hit 0，render duration 22,620,791 ns，录制事件 40，visible history rows 0 |

### Canvas / RenderNode 对照

同一设备、同一字体和几何参数下，PixelCopy 对照结果为：

- 有效终端区域比较像素：`4228/19712`，约 21.45%；
- 最大通道差：255；
- 差异包围盒：`Rect(1, 10 - 352, 53)`，仍在首行终端区域内；
- 差异比例低于测试允许阈值 35%；每个非空、非 spacer cell 的 ink 存在性一致；
- 测试通过，未观察到明显的行坐标偏移或 View 外写入。

原始设备测试结果和 logcat 位置：

`android-client/terminal-renderer/build/outputs/androidTest-results/connected/debug/medium_phone(AVD) - 16/`

其中 parity 明细为
`logcat-com.webterm.terminal.renderer.RemoteTerminalCanvasRenderNodeParityTest-directCanvasAndHardwareRenderNodeHaveComparableGeometry.txt`。
parity 测试同时在设备路径
`/storage/emulated/0/Android/data/com.webterm.terminal.renderer.test/files/render-baseline/canvas-rendernode-parity/`
写入 `direct-canvas.png`、`hardware-rendernode.png` 和 `diff.png`，并通过
`PARITY_DEVICE_ARTIFACTS` 日志输出该位置。它们是诊断截图，不是字体 glyph 的正确 Golden；
失败定位仍依赖差异像素数量、最大通道差和包围盒，避免将当前字体/fallback 行为固化为正确结果。

## 已知视觉问题

以下条目是当前行为记录，不作为第一阶段失败，也不代表期望行为：

- `KNOWN-01` blink 被视为 bold；
- `KNOWN-02` curly/dotted/dashed underline 未绘制；
- `KNOWN-03` glyph 可能被 X-only 缩放；
- `KNOWN-04` Box/Block/Braille 依赖字体；
- `KNOWN-05` fallback glyph 存在 RenderNode 裁切风险；
- `KNOWN-06` cursor 重新走单 cell 绘制路径。

## 后续阶段入口

第二阶段可以在这份基线之上引入 `TerminalCellGeometry`、`ResolvedStyle`，拆分 blink/bold，
并逐项关闭上述 known issue；每次改动应重新比较语义、几何、缓存指标和同设备 parity 结果。
