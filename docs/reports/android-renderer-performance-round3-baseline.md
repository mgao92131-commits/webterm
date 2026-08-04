# Android Renderer Round 3 基线

本基线对应分支 `agent/android-renderer-performance-round3` 的提交 0（提交 SHA 以提交完成后为准）。

## 范围

本次只增加 Round 3 的 TUI fixture、显式性能测试和 renderer 操作量计数，不改变生产绘制顺序或几何算法。计数器只有在测试显式注入 `RendererFrameWorkStats` 时启用，生产 renderer 仍传入 `null`。

工作负载包括 Box、Braille、Block、五类 decoration、混合 TUI。每个场景预热 10 次，采样 30 次；输出 JSONL 由 `RendererRound3DrawOperationBenchmarkTest` 生成。

## 运行命令

```sh
cd android-client
./gradlew :terminal-renderer:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.webterm.terminal.renderer.RendererRound3DrawOperationBenchmarkTest \
  -Pandroid.testInstrumentationRunnerArguments.webtermPerf=true \
  --no-daemon --console=plain
```

## 当前证据

已在 `medium_phone(AVD) - 16`、API 36、14sp、默认字体集合上执行 10 次预热和 30 次采样。原始 JSONL 保存在同目录的 `android-renderer-performance-round3-baseline.jsonl`；测试日志也保留在 Gradle connected test artifact 中。

本次只建立基线，不对 P95 做跨提交结论。可以确认当前旧路径的操作量基线包括：Braille/Block 各 4,800 次单 glyph clip；mixed TUI 为 1,920 次特殊 glyph clip；decoration-heavy 中 curly 产生 105,760 个 segment，dotted 产生 35,320 个 primitive，dashed 产生 15,200 个 primitive。

## 指标

重点记录 `backgroundRectDraws`、`decorationClips`、`specialGlyphCellClips`、`specialGlyphRunClips`、`curlyPatternBuilds`、`dottedPrimitives`、`dashedPrimitives` 和 prepared-hit P50/P95。后续提交必须与此基线保持相同 fixture、字号、字体集合和设备环境。
