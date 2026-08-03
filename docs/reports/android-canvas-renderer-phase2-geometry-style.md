# Android Canvas / RenderNode 终端渲染第二阶段基线

## 阶段范围

本报告记录 Phase 2「Shared Geometry and ANSI Style Semantics」的实现和 API 36
设备基线。生产基准仍为：

`d2330ad4c515eee5e4ed20be272c89004a60d4c3`

阶段二从第一阶段分支 `agent/android-renderer-phase1-baseline` 的
`8cc5d18b235c6dd6af2ea9eb259efdb01ba84d64` 继续，当前实现包含六个代码提交：

- `18553c10` `refactor(renderer): introduce shared terminal cell geometry`
- `12e96ae5` `refactor(renderer): centralize resolved terminal styles`
- `faf625da` `fix(renderer): complete ANSI decoration rendering`
- `6f6251ce` `fix(renderer): eliminate resolved style hot-path allocations`
- `222e4341` `fix(renderer): stabilize decoration phase and complete integer geometry usage`
- `f86ffc8c` `perf(renderer): skip empty decoration canvas state`

当前分支：`agent/android-renderer-phase2-geometry-style`。

本阶段只修改终端 renderer 的几何、样式和装饰绘制，以及对应测试和文档；没有修改
`terminal-model`、`CellValue`、`StyleValue`、服务端协议、历史加载、滚动逻辑或
RenderNode 行缓存的复用判定。

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
| topInset | 10 px |
| system image build | `BE2A.250530.026.D1`，incremental `13818094` |
| Gradle / AGP / JDK | Gradle 9.1.0 / AGP 9.0.1 / JDK 21.0.9 |

API 29 模拟器和真实 Android 设备在本次环境中不可用，因此仍标记为未验证；API 36
结果不能替代这两项验收。

## 实现内容

### 统一 cell 几何

新增 package-private `TerminalCellGeometry`，并由 renderer、View 命中测试和
`TerminalLineRenderNodeCache` 共同使用。矩形边界统一由
`Math.round(column * cellWidth)` 的相邻整数 edge 差值得到；文字 origin 仍保留浮点
计算，以避免本阶段改变字体 hinting。

已接入的路径包括：

- 背景、选择和 cursor 的 cell/span 矩形；
- View 的 pointer、mouse、selection anchor、selection handle/action-mode X 坐标和 resize
  列/行计算；
- RenderNode 行缓存的 content width 和 line height；
- 宽字符两列边界、top inset 和最后一列命中。

cursor 的 BAR、UNDERLINE、BLOCK 矩形也使用累计取整后的 `left/right`，不再使用浮点
文字 origin；设备 parity 边界计算与生产 geometry 使用同一套累计取整规则。

### 集中样式解析

新增 package-private `TerminalStyleResolver` 和 `ResolvedTerminalStyle`。ASCII run
与逐 cell 路径共享同一个 scratch style；bold indexed color 提升直接按整数 index
解析，不构造临时 `TerminalColor`，resolver 的生产 palette 入口也为非空。

当前规则已固定为：

- `blinkSlow` / `blinkFast` 不再设置 fake bold，也不提升 0～7 indexed color；
- 只有真实 `bold` 才将 0～7 indexed color 提升到 8～15；RGB 和自定义 indexed color
  不被错误提升；
- reverse video、style reverse 和 block cursor inverse 按 XOR 处理；
- dim 在 bright color 解析之后应用；
- hidden 保留背景但不绘制 glyph 或 decoration；
- underline color 独立于正文 foreground，strike 始终使用最终 foreground；
- 多个 underline flag 使用稳定优先级：dashed、dotted、curly、double、single。

### ANSI decoration

新增复用 `Paint` 和 `Path` 的 `TerminalDecorationPainter`，接入 ASCII run 和逐 cell
路径。single、double、curly、dotted、dashed 五种 underline 均绘制在 cell/span 范围
内，strike 与 underline color 隔离，hidden 不绘制装饰。curly、dotted、dashed 使用
固定绝对 X 相位，并在 span 内严格 clip；run 拆分只允许产生抗锯齿颜色差异，不改变
最终 decoration mask。没有 underline 或 strike 的普通样式在进入 Canvas 状态操作前
直接返回，不执行 `save`、`clipRect` 或 `restoreToCount`。

## 测试命令与结果

```sh
cd android-client

./gradlew :terminal-renderer:testDebugUnitTest \\
  --no-daemon --console=plain

./gradlew :terminal-renderer:assembleDebug \\
  --no-daemon --console=plain

./gradlew :terminal-renderer:connectedDebugAndroidTest \\
  --no-daemon --console=plain
```

| 检查 | 结果 |
| --- | --- |
| JVM/Robolectric | 70/70 通过，0 skipped，0 failed，0 error |
| `assembleDebug` | 通过 |
| API 36 `connectedDebugAndroidTest` | 22/22 通过，0 skipped，0 failed |
| API 29 | 未验证 |
| 真实 Android 设备 | 未验证 |
| 运行时依赖 | 未增加 |

新增 JVM 覆盖包括 `TerminalCellGeometryTest`、`TerminalStyleResolverTest`、
`TerminalDecorationPainterTest` 和 renderer decoration Bitmap 测试；其中包含 plain style
跳过 Canvas 状态操作、小数
cellWidth 的 cursor 边界、累计 edge 的 dotted clip 和 4-cell decoration run 拆分
mask 对照。新增设备覆盖包括装饰 Direct Canvas/RenderNode 对照和生产累计 edge；第一
阶段的末列 ink bounds、多行接缝和缓存基线测试继续运行。

## RenderNode 缓存和性能指标

以下数据来自 API 36 的最终完整 instrumentation 运行；性能时间是单次 smoke 采样，
用于确认路径和缓存行为，不代表稳定的 P50/P95 性能承诺。

| 场景 | 指标 |
| --- | --- |
| 8 × 80 首帧 | `rowNodeRecordCount=8`，`rowCacheMissCount=8`，`rowCacheHitCount=0` |
| 单行 patch | 录制增量 `1`，缓存命中增量 `7`，miss 增量 `1` |
| 同画面第二帧 | 录制增量 `0`，缓存命中增量 `8`，miss 增量 `0`，render duration 增量 `82,500 ns` |
| cursor blink | 录制增量 `0`，缓存命中增量 `8`，miss 增量 `0` |
| selection 改变 | 录制增量 `0`，缓存命中增量 `8`，miss 增量 `0`，变化像素 `2,380` |
| 40 × 120 混合 Unicode | `records=40`，`visible_rows=40`，`recordable_rows=40`，`misses=40`，`hits=0`，`render_duration=11,767,333 ns` |

这些结果保持第一阶段的缓存不变量。几何、style resolver 和 decoration painter 的
热路径均复用已有对象；空 cell 仍跳过 glyph 绘制，ASCII batching 判定和行级缓存
结构未改变。混合 Unicode 时间仍是单次 emulator smoke 采样，不能据此宣称稳定的
性能百分比门槛。

## Canvas / RenderNode 对照

API 36 使用同一设备、同一 `Typeface.MONOSPACE`、同一 14 sp 字号和同一几何参数；直接
Canvas 路径先将 sp 按设备 density 转成 px。PixelCopy 对照结果：

- 有效终端区域：`46/19712` 个像素不同，约 `0.23%`；
- 最大通道差：`255`；
- 差异包围盒：`Rect(242, 8 - 265, 10)`；
- 新增 decoration 对照日志：`DECORATION_PARITY_DEVICE direct_and_rendernode=true`；
- 三行纯色背景接缝扫描通过；
- decoration 只在对应 cell/span 内产生像素，hidden 只保留背景。

边界测试仍明确记录第一阶段已知的 RenderNode 末列行为：

| fixture | Direct Canvas ink | Hardware RenderNode ink | 结论 |
| --- | --- | --- | --- |
| `last-italic-f` | `Rect(338, 15 - 360, 44)` | `Rect(338, 16 - 352, 44)` | `KNOWN-05`，ink count 差异约 20.42% |
| `last-italic-W` | `Rect(333, 17 - 362, 44)` | `Rect(333, 17 - 352, 44)` | `KNOWN-05`，ink count 差异约 21.75% |
| `last-two-emoji` | `Rect(311, 12 - 350, 50)` | `Rect(311, 12 - 350, 50)` | 边界一致 |
| `last-two-cjk` | `Rect(310, 14 - 352, 48)` | `Rect(310, 14 - 352, 48)` | 边界一致 |
| `last-italic-overhang` | `Rect(333, 17 - 358, 44)` | `Rect(333, 20 - 352, 44)` | `KNOWN-05`，ink count 差异约 20.45% |

差异和截图位置：

- Gradle 设备结果目录：
  `android-client/terminal-renderer/build/outputs/androidTest-results/connected/debug/medium_phone(AVD) - 16/`
- parity 日志：
  `logcat-com.webterm.terminal.renderer.RemoteTerminalCanvasRenderNodeParityTest-directCanvasAndHardwareRenderNodeHaveComparableGeometry.txt`
- 设备端诊断目录：
  `/storage/emulated/0/Android/data/com.webterm.terminal.renderer.test/files/render-baseline/canvas-rendernode-parity/`
- 设备端文件：`direct-canvas.png`、`hardware-rendernode.png`、`diff.png`；这些是诊断
  截图，不是当前字体 glyph 的正确 Golden。

## 已关闭和保留的 known issue

本阶段关闭：

- `KNOWN-01A` blink 被错误当作 bold 或 bright color；当前 blink 保持常亮，尚未做动态
  blink 调度；
- `KNOWN-02` curly、dotted、dashed underline 未绘制；
- underline color 污染 strike；
- ASCII run 与逐 cell 路径重复解析样式；
- 绘制、命中测试和缓存尺寸使用分散 cell 几何计算。
- bold indexed color 提升造成的 resolved-style 热路径临时对象分配；
- cursor 和 selection handle 使用浮点文字 origin 的几何偏差；
- decoration run 拆分导致的周期重启和 dotted 右边界泄漏；
- 设备 parity 使用四舍五入后整数 cellWidth 的测试盲区，以及 glyph 误满足 strike 断言。
- 无 decoration 普通文字仍执行 Canvas 状态操作。

本阶段仍保留：

- `KNOWN-01B` 真正的 blink animation 尚未进入动态层；
- `KNOWN-03` glyph X-only scaling；
- `KNOWN-04` Box、Block、Braille 和 Powerline 仍依赖系统字体；
- `KNOWN-05` fallback/斜体 glyph 存在 RenderNode 末列裁切风险；本阶段没有扩大到
  CJK 或 emoji；
- `KNOWN-06` cursor 仍重新走单 cell 绘制路径。

## 验收边界

在已执行的 API 36 环境中，几何共享、ANSI 样式语义、五种 underline、strike/underline
color 隔离、hidden 背景保留、Canvas/RenderNode 装饰对照和 RenderNode 缓存不变量均已
通过；本次审查指出的代码级修复项和无 decoration 快速路径也已提交并验证。API 29 和真实设备仍需要
在可用设备上补跑，不能由本报告中的 API 36 结果代替；当前分支也没有新增 GitHub
Actions workflow 或 PR。
