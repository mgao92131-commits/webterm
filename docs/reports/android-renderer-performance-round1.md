# Android Renderer Performance Round 1

## 1. Environment

本报告对应分支 `agent/android-renderer-performance-round1`，起点为
`agent/android-renderer-phase5-hardening` 的提交 `9be9e5a79f878390aa5c306227ecd26a3362d7e3`。

固定环境字段在首次显式运行性能套件后补齐：AVD、API、分辨率、density、JDK、Gradle、
AGP、字体、字号和测试日期。

性能测试不会随普通 instrumentation 默认执行，需显式传入 `webtermPerf=true`。

## 2. Baseline — phase5-hardening

提交 0 建立 `RendererFrameWorkStats` 和 API 设备冷 RenderNode 录制套件，暂不改变生产绘制
算法。正式采样使用 10 次 warm-up、30 次测量，并输出 P50/P90/P95/max 及编译、绘制、
字体分类和 advance 工作量。

运行命令：

```sh
cd android-client
./gradlew :terminal-renderer:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.webterm.terminal.renderer.RemoteTerminalRendererPerformanceTest \
  -Pandroid.testInstrumentationRunnerArguments.webtermPerf=true \
  --no-daemon --console=plain
```

当前工作区尚未执行该显式性能采样；报告中的数值不能视为已验证基线。

## 3. Commit 1 — default blank fast path

实现：精确识别 `CellValue.isDefault()`。前导和尾部默认空格直接跳过；同一普通
TextSpan 内部的默认空格延迟到确实需要时才物化。带背景、装饰、hidden、link 和异常
宽度的空格继续走原路径。

JVM 和 API 36 instrumentation 回归已通过；显式性能采样待执行。

## 4. Commit 2 — font classification fast paths

待提交。

## 5. Commit 3 — ownership transfer

待提交。

## 6. Commit 4 — batch contextual advances

待提交。

## 7. Final comparison

待补充。每个提交只记录同一设备、同一 fixture、同一采样方法下的 P50/P90/P95，
并区分实际时间改善、仅减少分配和未产生显著收益的变化。

## 8. Known limitations

- 本轮不包含 RenderSnapshot 合并、CPU compiled-line 独立缓存、blink 扫描、selection
  投影、RenderNode 淘汰和特殊 glyph batching。
- API 29、真实设备和 GitHub Actions 结果必须以实际运行记录为准，不能由 API 36 的结果推断。
- RenderNode 冷录制套件使用新的节点记录工作量，单次采样可能受模拟器负载影响；正式比较使用
  同一固定 AVD 的重复采样。
