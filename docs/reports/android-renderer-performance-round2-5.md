# WebTerm Android Renderer Round 2.5 验收闭环

## 状态

Round 2 分支在 `ca7d1abf` 冻结为合并候选后，本轮只加入验收测试、性能基准、CI 配置和
publication 生命周期边界测试；没有加入特殊字符、装饰线或新的缓存策略优化。

本轮结论：

- API 36 模拟器上的 JVM、模块构建、全量 renderer instrumentation 和真实 View 回滚/淘汰基准通过；
- selection projector 已加入固定随机种子的 property test；
- publication executor 拒绝、队尾 listener 和最新版本观察已加入测试；
- API 29、真实 Android 设备和 GitHub Actions 在当前环境尚未执行；
- 当前 View 基准是 immutable `RenderUpdate → RemoteTerminalView → RenderNode/prepared cache`
  链路，不包含真实网络、`TerminalSessionRuntime` 或 PTY，因此不宣称完整产品端到端帧时间。

## 分支与验收提交

| 项目 | 值 |
| --- | --- |
| 分支 | `agent/android-renderer-performance-round2` |
| Round 2 冻结基线 | `ca7d1abf` |
| Round 2.5 验收提交 | `17279c2f` `test(renderer): close round2.5 acceptance coverage` |
| 日期 | 2026-08-05 |

## 新增验收覆盖

### Selection projector property test

`TerminalSelectionProjectorTest.randomWideCellSelectionsMatchLegacyCellOverlapMask` 使用固定种子
执行 2,000 轮随机场景，覆盖：

- 屏幕行、历史行和历史到屏幕的跨区间选择；
- 正向、反向和零长度选择；
- 行首、行尾和宽字符 spacer 边界；
- 随机单宽/双宽 cell 布局。

新 projector 的半开列 mask 与旧逐 cell overlap oracle 完全一致。零长度选择在 oracle 中显式
保持空 mask，避免复现旧死代码在宽字符内部边界的误选行为。

### Publication lifecycle boundary

`RemoteTerminalModelPublicationTest` 新增：

- executor 已拒绝后，后续 mutation 仍同步物化最新 snapshot；
- 队尾 publication listener 只在 snapshot 已物化并递增 version 后观察到回调。

已有 coalesced mutation tests 继续覆盖连续 Baseline、history commit、LineBody batch 与同步
逐 mutation publication 的最终 content axis 等价性。

### View back-scroll / eviction benchmark

新增 `RemoteTerminalViewRound2AcceptanceBenchmarkTest`，仅在显式 `webtermPerf=true` 时运行：

- 1,200 条已加载历史行、8 行 screen、120 列；
- 真实硬件加速 `RemoteTerminalView`；
- 78 个往返视口位置，覆盖全部历史并返回 follow-tail；
- 记录 onDraw 完成时间、Window `FrameMetrics`、RenderNode hit/record、Prepared hit/miss/eviction/bytes。

执行命令：

```sh
cd android-client
./gradlew :terminal-renderer:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.webterm.terminal.renderer.RemoteTerminalViewRound2AcceptanceBenchmarkTest \
  -Pandroid.testInstrumentationRunnerArguments.webtermPerf=true \
  --no-daemon --console=plain
```

原始 JSONL：[Round 2.5 API 36 View benchmark](android-renderer-performance-round2-5-api36.jsonl)

API 36 `medium_phone(AVD) - 16` 结果：

| 指标 | 结果 |
| --- | ---: |
| view frame P50 | 14.53 ms |
| view frame P95 | 16.52 ms |
| view frame P99 | 19.16 ms |
| FrameMetrics samples | 78 |
| FrameMetrics P50 / P95 / P99 | 19.97 / 22.15 / 31.46 ms |
| FrameMetrics >16.67ms | 78 |
| RenderNode records | 2,691 |
| RenderNode hits | 1,621 |
| RenderNode misses | 2,691 |
| Prepared hits / misses | 630 / 2,061 |
| Prepared evictions | 1,575 |
| Prepared peak bytes | 4,498,432 |

该模拟器采样中 FrameMetrics 全部超过 16.67ms，说明当前 AVD/测试链路存在明显调度或模拟器开销，
不能据此判定真机 jank，也不能把它当作 Round 2 相比 Round 1 的同 harness 对照。它证明了缓存
淘汰与回滚链路确实被执行，且 Prepared 峰值低于 12 MiB 预算。

## 本轮验证命令与结果

```sh
cd android-client
./gradlew :terminal-renderer:testDebugUnitTest :terminal-model:test \
  :terminal-runtime:testDebugUnitTest :terminal-renderer:assembleDebug \
  :app:assembleDebug :terminal-renderer:compileDebugAndroidTestJavaWithJavac \
  --no-daemon --console=plain

./gradlew :terminal-renderer:connectedDebugAndroidTest \
  --no-daemon --console=plain

git diff --check
```

结果：

- renderer JVM：169 个测试通过；
- terminal-model：96 个测试通过；
- terminal-runtime：60 个测试通过；
- `terminal-renderer:assembleDebug`：通过；
- `app:assembleDebug`：通过；
- androidTest Java 编译：通过；
- API 36 renderer instrumentation：34 个测试，30 通过、4 个 opt-in 性能测试跳过，无失败；
- Round 2.5 View benchmark：1 个测试通过；
- `git diff --check`：通过。

## CI 配置

`.github/workflows/verify.yml` 新增：

- 显式 renderer/model/runtime 单测、debug 构建和 `git diff --check`；
- API 29、API 36 的 `reactivecircus/android-emulator-runner` instrumentation 矩阵；
- API 36 opt-in 性能 artifact job。该 job `continue-on-error`，只上传原始测试结果，不把模拟器
  P95 噪声作为合并阻断。

当前本地没有执行 GitHub Actions；workflow 配置已提交，实际执行结果以 GitHub run 为准。

## 尚未完成的验收项

- 本机只有 `medium_phone` API 36 AVD；未安装/创建 API 29 AVD；
- 当前没有连接真实 Android 设备；
- GitHub Actions 尚未产生 workflow run；
- 30 分钟持续输出、10,000 行长期 cache stress 尚未执行；当前已完成 1,200 行回滚/淘汰基准；
- 当前基准从 immutable `RenderUpdate` 进入 View，不包含真实网络/PTY/SessionRuntime 链路；
- publication 的 session close、executor 销毁后晚到 flush、断线重连生命周期需要在
  `TerminalSessionRuntime` 测试夹具中继续补齐；
- Round 1 与 Round 2 的同 harness 对照、真机 P50/P95/P99 和 allocation/GC 数据仍未形成。

因此 Round 2.5 当前是“API36 本地验收通过、发布矩阵未闭环”的合并候选状态；不能宣称 API29、
真机、CI 和完整生产端到端性能已经通过。
