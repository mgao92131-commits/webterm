# WebTerm Android Renderer Phase 4：Contextual Text Runs 基线

## 状态

Phase 4 的代码实现和 API 36 验证已完成；API 29 模拟器和真实 Android 设备尚未在当前环境执行，因此完整阶段验收仍保留这两个未验证项。

本阶段只把普通文字迁移到有序 Span 和 `Canvas.drawTextRun()`，没有修改 terminal-model、服务端协议、历史加载、滚动轴或特殊字符 painter 的几何实现。

## 版本和分支

- 生产基准（Phase 3 head）：`f9e6bba083d5beacc285e61cfac8863eb840ece8`
- Phase 4 分支：`agent/android-renderer-phase4-contextual-text-runs`
- 当前实现提交：`67d16f33` 及其父提交
- Phase 4 提交：
  - `4850d95d test(renderer): add phase4 contextual text fixtures`
  - `29cbaf88 refactor(renderer): compile rows into ordered render spans`
  - `eaff9509 feat(renderer): add contextual terminal text painter`
  - `f61553ca refactor(renderer): render rows from compiled contextual spans`
  - `f08a3807 fix(renderer): keep special span classification graphics-free`
  - `67d16f33 test(renderer): verify contextual text canvas rendernode parity`

## 实现内容

- `TerminalLineCompiler` 消费服务端已经解析好的 `CellValue.text()` 和 `CellValue.width()`。
- `CompiledTerminalLine` 使用有序 `TextSpan`、`SpecialGlyphSpan`、`BlankStyleSpan`。
- TextSpan 保存 UTF-16 grapheme 起始偏移、终端物理列和 width，不重新做 Unicode 分段或宽度判断。
- 相同 `CompiledStyle` 的普通 grapheme 合并为 TextSpan；特殊 glyph、hidden、样式空格切断普通 TextRun。
- `TerminalTextPainter` 优先执行完整上下文 `drawTextRun()`；advance 与终端 cell 边界不匹配时按 grapheme 绘制，但 context 仍为完整 TextSpan。
- 兼容路径保留既有 per-glyph X-only scaling 和 `TerminalVisualRules.shouldPreserveGlyphAspect()`。
- Direct Canvas fallback 与 RenderNode miss 都调用 `drawTerminalLineContent()`；选择和光标在静态正文之后作为覆盖层绘制。
- 旧 `drawAsciiRun()`、ASCII batching 和普通正文 `drawCell()` 入口已移除；block cursor 仍保留独立 legacy 单 cell 重绘路径（KNOWN-06）。
- `TerminalSpecialGlyphPainter` 增加已编译 code point 的直接分派入口，emoji ZWJ、VS、keycap 等多 codepoint grapheme 继续走普通文字路径。

## 测试结果

在 `android-client` 目录执行：

```sh
./gradlew :terminal-renderer:testDebugUnitTest --no-daemon --console=plain
./gradlew :terminal-renderer:assembleDebug --no-daemon --console=plain
./gradlew :terminal-renderer:connectedDebugAndroidTest --no-daemon --console=plain
```

结果：

- JVM/Robolectric：118/118 通过。
- `:terminal-renderer:assembleDebug`：通过。
- API 36 instrumentation：25/25 通过。
- `git diff --check`：通过。

新增 JVM 覆盖：

- 服务端 grapheme 到 UTF-16 offset 的映射；
- width=2 和 spacer 的物理列映射；
- style boundary、special glyph boundary；
- 默认空白裁剪、样式空格、hidden 背景；
- TextRun 完整上下文、cluster fallback、combining 不拆分；
- bold/italic/color 状态不泄漏。

新增 API 36 parity：

- ASCII、combining、CJK、emoji、Arabic、Indic；
- 多 style TextSpan 边界；
- Direct Canvas 与 RenderNode 的普通文字对照；
- 当前设备上下文文字 parity 差异：`0/46464` 像素；
- 既有特殊字符 parity、三行接缝和 decoration parity 继续通过。

## RenderNode 指标

API 36 `medium_phone(AVD)`：

- Android API：36，Android 16；
- 分辨率：1080×2400；density：420；
- 默认字体：`monospace`；默认字号：14sp；
- 默认 geometry：`cellWidth=22.0px`，`lineHeight=44.0px`，`baseline=34.09424px`；
- 首帧 8 行：8 个 row node record；
- 单行 patch：新增 1 个 record，其余行 cache hit；
- 同 snapshot 第二帧：record 增量 0；
- cursor blink：静态行 record 增量 0；
- selection 改变：静态行 record 增量 0；
- 40×120 mixed Unicode 单次 smoke：40 个 record，`render_duration=16,981,666 ns`。该值是单次 AVD 样本，不代表稳定 P50/P95。

## 未验证项

- API 29 模拟器：当前机器没有 API 29 AVD，未执行。
- 真实 Android 设备：当前没有连接真机，未执行。
- GitHub Actions：本阶段未新增 workflow，报告结果来自本地 API 36 AVD。

## 保留的已知问题

- `KNOWN-01B`：slow/fast blink 动画尚未进入动态层。
- `KNOWN-03`：普通 glyph 仍可能执行 X-only scaling。
- `KNOWN-05`：末列 italic/fallback glyph 仍存在 RenderNode 裁切风险。
- `KNOWN-06`：block cursor 仍使用 legacy 单 cell 重绘路径。
- `KNOWN-07`：完整 RTL/Bidi 重排不在 Phase 4 范围内；当前按服务端物理列顺序使用 `isRtl=false`。

## 范围确认

本阶段没有引入运行时依赖，没有修改 `terminal-model` 或服务端协议，没有加入 `CompiledLine` 全局缓存、glyph metrics cache、TextRun 之外的 Bidi 重排、blink 动画、RenderNode bleed 或特殊字符绘制重构。
