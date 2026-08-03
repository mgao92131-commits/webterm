# WebTerm Android Renderer Phase 5：Dynamic Overlays, Glyph Fitting and RenderNode Bleed

## 状态

Phase 5 的生产改动已完成并通过当前环境的 JVM、模块 assemble、app assemble 和 API 36
instrumentation。API 29 模拟器、真实 Android 设备和 GitHub Actions 仍未验证，因此本报告
是当前环境的可复现基线，不宣称完成完整设备矩阵验收。

本阶段没有修改 terminal-model 的 cell 语义、服务端协议、历史加载、UnifiedContentAxis、
特殊字符几何算法、RTL/Bidi 或 GLES30。

## 版本和分支

- Phase 4 不可变基线 tag：`android-renderer-phase4-baseline`
- Phase 4 基线 commit：`a8ed0c56b0daa443a71f68f0b1c75b8f8d1080db`
- Phase 5 分支：`agent/android-renderer-phase5-dynamic-fit-bleed`
- Phase 5 实现提交（报告提交前）：`f95bc7740e2ad1ca96e413915d2ff4d15287cc26`
- 主要提交：
  - `2287ef45 refactor(renderer): separate static and dynamic foreground spans`
  - `41a29c91 feat(renderer): schedule visible slow and fast blink rows`
  - `5aede2ce fix(renderer): shrink overwide glyphs without horizontal distortion`
  - `ac947ac4 fix(renderer): add asymmetric glyph bleed to row render nodes`
  - `ae198b30 fix(renderer): avoid scheduling blink for background-only spaces`

## 实现内容

- 静态 RenderNode 只保存背景和非 blink 前景；slow/fast blink 前景与 decoration 在节点
  回放后动态绘制。
- block cursor 不再走旧的单 cell `measureText()`、`drawText()` 或 `scaleX` 路径，而是
  在 cursor clip 内重放已编译 Span，并保留完整 TextRun context。
- cursor、slow blink、fast blink 使用基于 `SystemClock.uptimeMillis()` 的统一相位；没有
  可见动画的行不会启动 callback，纯 blink 空格只保留静态背景。
- `TerminalGlyphFitter` 按完整 grapheme 和服务端 width 选择 grid-start/centered；不再
  根据右邻居空格决定比例。超宽 glyph 只做等比缩小，`scaleX == scaleY <= 1`；窄 glyph
  不放大。
- RenderNode 行节点加入左右、上下安全边界，缓存身份纳入 expanded node 尺寸和 bleed；
  行录制和回放使用相反偏移，正文 cell 坐标保持不变。
- 屏幕行、历史行和动画行脏区同步覆盖上下 bleed。

## 测试命令和结果

在 `android-client` 目录执行：

```sh
./gradlew :terminal-renderer:testDebugUnitTest --no-daemon --console=plain
./gradlew :terminal-renderer:assembleDebug --no-daemon --console=plain
./gradlew :app:assembleDebug --no-daemon --console=plain
./gradlew :terminal-renderer:connectedDebugAndroidTest --no-daemon --console=plain
```

结果：

- JVM/Robolectric：127/127 通过。
- `:terminal-renderer:assembleDebug`：通过。
- `:app:assembleDebug`：通过。
- API 36 instrumentation：25/25 通过。
- `git diff --check`：通过。

新增/扩展覆盖：

- block cursor 的 TextSpan context 重放和 cursor 外部像素不变；
- blink off 保留背景、blink on 绘制前景；
- wide/CJK、emoji grapheme 和右邻居变化不影响 fit mode；
- fitting 不放大、超宽 glyph 只等比缩小；
- RenderNode expanded 尺寸、回放偏移和几何 bleed；
- blink 空格不启动无意义的动画调度。

## API 36 设备和缓存指标

- 设备：`medium_phone(AVD) - 16` / `sdk_gphone64_arm64`；
- Android API：36；
- 分辨率：1080×2400；density：420；
- 默认字体：`monospace`；默认字号：14sp；
- 默认 geometry：`cellWidth=22.0px`、`lineHeight=44.0px`；
- 8 行首帧：8 个 row node record；
- 单行 patch：新增 1 个 record，保留行 cache hit；
- 同 snapshot 第二帧：`records_delta=0`、`cache_hits_delta=8`；
- cursor blink：`records_delta=0`、`cache_hits_delta=16`；
- selection 改变：`records_delta=0`、`cache_hits_delta=8`，截图变化 2380 像素；
- 40×120 mixed Unicode：40 个 record，单次 `render_duration=13,547,375 ns`。

以上纳秒值是单次 AVD smoke 样本，不代表 P50/P95；本阶段没有把单次模拟器时间作为硬性
性能门槛。

## 未验证项

- API 29 模拟器：当前机器没有可用 API 29 AVD，未执行。
- 真实 Android 设备：当前没有连接真机，未执行。
- GitHub Actions：本阶段未新增 workflow，结果来自本地 API 36 AVD。

## 保留的已知问题

- `KNOWN-07`：完整 RTL/Bidi 重排不在本阶段范围内，当前仍按服务端物理列顺序绘制。
- 设备字体 fallback 的具体字形差异仍然存在。

本阶段已关闭：block cursor legacy 单 cell 重绘、blink 静态节点混入、普通 glyph 的
X-only scaling、RenderNode 行节点严格边界裁切风险。特殊 glyph 的 Box/Block/Braille/
Powerline 几何实现沿用 Phase 3，未在本阶段重写。

## 范围确认

没有引入新的运行时依赖，没有修改 `terminal-model`、服务端协议、历史/滚动逻辑或特殊
字符 painter；没有加入全局 CompiledLine cache、glyph bitmap cache、contextual advance
cache、完整 Bidi、HarfBuzz/NDK 或 GLES30。
