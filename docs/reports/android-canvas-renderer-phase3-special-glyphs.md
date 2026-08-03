# Android Canvas / RenderNode 终端渲染第三阶段基线

## 阶段范围

本报告记录 Phase 3「Canvas Special Terminal Glyph Rendering」的实现和 API 36
设备基线。生产基准仍为：

`d2330ad4c515eee5e4ed20be272c89004a60d4c3`

第三阶段从 Phase 2 最终状态继续：

`b306f150` `docs(renderer): record decoration fast path baseline`

当前分支：`agent/android-renderer-phase3-special-glyphs`

阶段实现提交：

- `2c9edce2` `feat(renderer): add canvas special terminal glyph painters`
- `993a16a8` `test(renderer): verify special glyph rendernode parity`

改动只涉及 `terminal-renderer` 的特殊字符绘制、测试和本报告；没有修改
`terminal-model`、`CellValue`、`StyleValue`、服务端协议、历史加载、滚动逻辑或
RenderNode 行缓存身份判定。

## 实现内容

### 统一特殊字符分派

新增 package-private `TerminalSpecialGlyphPainter`。只有恰好一个 Unicode code point
的服务端 grapheme 才能进入分派；emoji variation selector、ZWJ、keycap、regional
indicator、肤色修饰符和其他多 codepoint grapheme 都返回 `false`，继续走原有字体
fallback。

分派范围为：

- U+2500–U+257F：Box Drawing 全范围；
- U+2580–U+259F：Block Elements 全范围；
- U+2800–U+28FF：Braille Patterns 全范围；
- U+E0B0、U+E0B2、U+E0B4、U+E0B6：已验证 Powerline allowlist。

每次特殊字符绘制都在目标 cell span 内执行 `Canvas.save()`、`clipRect()` 和
`restoreToCount()`。每个字符族保留开发期内部开关，关闭时返回 `false`，可以独立
回退到字体路径。

### Box Drawing

`BoxDrawingGlyphPainter` 使用静态 descriptor 表描述四个方向的 light、heavy、double
stroke 及 horizontal/vertical dash pattern，绘制算法统一复用。已覆盖：

- light、heavy、double 线；
- mixed light/heavy junction；
- double/triple/quadruple dash；
- rounded corner；
- diagonal、half-line 和 transition glyph。

线宽基于实际 cell 像素尺寸计算，连续 cell 使用累计整数 edge；虚线按绝对 Canvas
坐标取相位，不在每个 cell 边界重启。

### Block Elements

`BlockElementGlyphPainter` 使用整数 cell rect 和累计取整的 1/8 edge，覆盖完整
U+2580–U+259F，包括半块、八分之一块、左右块、四象限组合和 `░▒▓`。shade 使用
绝对像素锚定的 2×2 ordered pattern，并复用 `Path`，不在逐像素路径中调用独立
`drawRect()`。

### Braille Patterns

`BrailleGlyphPainter` 直接消费 code point 的低 8 bit。Unicode dot 位序固定为：

```text
左列：dot 1、2、3、7
右列：dot 4、5、6、8
```

点位固定在 2×4 网格，点半径由 cell rect 计算。U+2800 被识别为已支持字符，但不
绘制任何点，避免字体 fallback 产生缺字框或错误 advance。

### Powerline

`PowerlineGlyphPainter` 只绘制四个 allowlist code point，使用复用的 `Path` 和
抗锯齿 `Paint`。其他 PUA（包括 U+E0A0、U+E0B1、U+E0B3、U+E0B5、U+E0B7、U+E0D7）
仍返回 `false`，由现有字体和比例保护逻辑处理。

### Renderer 接入边界

特殊分派只接入 `RemoteTerminalRenderer.drawCell()`：

1. style resolve；
2. cell background；
3. cursor background；
4. special glyph 或原有普通字体 glyph；
5. decoration；
6. selection overlay。

ASCII run batching、普通 ASCII/CJK/emoji/复杂 grapheme 的字体路径、X-only scaling、
cursor 单 cell 重绘路径均保持不变。特殊 painter 内没有 `measureText()`、
`Canvas.scale()` 或 `Canvas.drawText()`，也没有逐 cell 新建 `Paint`、`Path`、`Rect`、
descriptor 或 code point 数组。

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

## 测试命令与结果

```sh
cd android-client

./gradlew :terminal-renderer:testDebugUnitTest \
  --no-daemon --console=plain

./gradlew :terminal-renderer:assembleDebug \
  --no-daemon --console=plain

./gradlew :terminal-renderer:connectedDebugAndroidTest \
  --no-daemon --console=plain
```

| 检查 | 结果 |
| --- | --- |
| JVM/Robolectric | 97/97 通过，0 skipped，0 failed，0 error |
| `assembleDebug` | 通过 |
| API 36 `connectedDebugAndroidTest` | 23/23 通过，0 skipped，0 failed |
| API 29 | 未验证 |
| 真实 Android 设备 | 未验证 |
| 运行时依赖 | 未增加 |

新增 JVM 覆盖包括：

- 完整三类 Unicode 范围和 Powerline allowlist 分类；
- Box descriptor 完整性、边界、double band、rounded/diagonal、fractional edge；
- Block 比例、四象限、shade absolute phase；
- Braille 八个单点、U+2800 空点和 U+28FF 全点；
- Powerline fallback、clip 和 renderer resolved style；
- hidden、reverse、dim、block cursor、wide span、decoration 和 selection overlay。

新增设备覆盖包括特殊字符 Direct Canvas/RenderNode mask 对照；现有 RenderNode cache
测试继续覆盖首帧、第二帧、单行 patch、cursor blink、selection 和 40×120 混合画面。

## RenderNode 缓存和性能指标

以下数据来自 API 36 最终完整 instrumentation 运行。时间是单次 emulator smoke
采样，只用于确认路径和缓存行为，不代表稳定的 P50/P95 性能承诺。

| 场景 | 指标 |
| --- | --- |
| 8 × 80 首帧 | `baseline_records=8`，`baseline_cache_misses=8` |
| 单行 patch | `patch_records=1`，`patch_cache_hits=7`，`patch_cache_misses=1` |
| 同画面第二帧 | `records_delta=0`，`cache_hits_delta=8`，`row_cache_miss_delta=0`，render duration 增量 `69,250 ns` |
| cursor blink | `records_delta=0`，`cache_hits_delta=8`，`row_cache_miss_delta=0` |
| selection 改变 | `records_delta=0`，`cache_hits_delta=8`，`row_cache_miss_delta=0`，变化像素 `2,380` |
| 40 × 120 混合 Unicode | `records=40`，`visible_rows=40`，`recordable_rows=40`，`row_cache_misses=40`，`render_duration=16,325,375 ns` |

特殊字符在混合 Unicode 行中已经进入静态行 RenderNode 录制；第二帧、cursor blink 和
selection 改变没有触发静态行重录。没有设置特殊字符独立的纳秒硬门槛。

## Canvas / RenderNode 对照

API 36 使用同一设备、同一 `Typeface.MONOSPACE`、同一 14 sp 字号和同一几何参数；
直接 Canvas 路径先按设备 density 将 sp 转成 px。普通混合画面对照结果：

- 有效终端区域：`20/19712` 个像素不同，约 `0.10%`；
- 最大通道差：`59`；
- 差异包围盒：`Rect(267, 13 - 283, 51)`；
- 特殊字符三行 mask 对照：通过；
- Box 相邻 cell、Block 覆盖、Braille 点位和 Powerline edge 对照：通过；
- 多行 RenderNode 背景接缝扫描：通过。

设备端 parity artifact 位置：

- Gradle 结果目录：
  `android-client/terminal-renderer/build/outputs/androidTest-results/connected/debug/medium_phone(AVD) - 16/`
- 设备端诊断目录：
  `/storage/emulated/0/Android/data/com.webterm.terminal.renderer.test/files/render-baseline/canvas-rendernode-parity/`
- 文件：`direct-canvas.png`、`hardware-rendernode.png`、`diff.png`

这些截图只用于定位和比较，不是普通字体 glyph 的严格正确 Golden。

## 已关闭和保留的 known issue

本阶段关闭：

- Box Drawing U+2500–U+257F 的系统字体依赖；
- Block Elements U+2580–U+259F 的系统字体依赖；
- Braille Patterns U+2800–U+28FF 的系统字体依赖；
- 四个已验证 Powerline separator 的系统字体依赖；
- 特殊 glyph 的 X-only scaling 和 `Paint.measureText()` 路径；
- 特殊 glyph 的 RenderNode/Canvas 路径分叉；
- 特殊 glyph 目标 cell 外的绘制泄漏风险；
- U+2800 空 Braille 的字体 fallback 风险。

本阶段仍保留：

- `KNOWN-01B` 真正的 slow/fast blink animation 尚未进入动态层；
- `KNOWN-03` 普通 glyph X-only scaling；
- `KNOWN-04A` 未在 allowlist 内的 PUA 和其他未支持符号仍依赖系统字体；
- `KNOWN-05` 普通 fallback/斜体 glyph 存在 RenderNode 末列裁切风险；本阶段没有扩大到
  CJK 或 emoji；
- `KNOWN-06` block cursor 仍重新走单 cell 绘制路径；
- Box mixed junction 的字体级视觉差异只能由本阶段几何规则统一，后续仍可继续细化
  个别 Unicode glyph 的精确规范形状，但不再依赖系统字体轮廓。

## 验收边界

在已执行的 API 36 环境中，四类特殊字符的 Canvas 几何绘制、fallback 分派、样式
语义、cell clip、Direct Canvas/RenderNode mask 对照和 RenderNode cache 不变量均已
通过。API 29 和真实设备没有可用目标，本报告明确标记为未验证，不能由 API 36 结果
替代；当前也没有新增 GitHub Actions workflow 或 PR。
