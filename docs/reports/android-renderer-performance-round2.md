# WebTerm Android Renderer Performance Round 2

## 状态

Round 2 的 2A（字体资源、CPU prepared-line cache、Blink metadata、selection projection）
和 2B（model executor 上的 publication coalescing）已实现，并已通过当前环境的 JVM、模块构建
和 API 36 instrumentation。API 29、真实 Android 设备和 GitHub Actions 当前环境未提供，
因此本报告不把 Round 2 标记为完整设备矩阵验收通过。

本轮没有修改服务端协议、`CellValue`/`StyleValue` 语义、历史 HTTP 协议、特殊 glyph 几何算法、
完整 Bidi 或 GLES/NDK。

## 分支和提交

| 项目 | 值 |
| --- | --- |
| 分支 | `agent/android-renderer-performance-round2` |
| Round 1 基线 | `0eaf90e50598a21d4fd0bc80c7b61a01e2371bf6` |
| Round 2 起点 | `agent/android-renderer-performance-round1` |
| Round 2 实现/基准提交 | `e95d311eccc0861681573c1d1946ad26590d625c` |
| 日期 | 2026-08-04 |

主要提交：

- `cc6efe8c` `perf(renderer): reuse terminal font resources across renderer views`
- `035a6e0a` `feat(renderer): add bounded prepared line cache`
- `90d1344f` `perf(renderer): cache blink metadata and project selection ranges`
- `230b55fc` `perf(model): coalesce snapshot materialization on the model executor`
- `f3e7e7b7` `fix(renderer): keep prepared cache budget accurate across versions`
- `e95d311e` `test(renderer): establish round2 cache overlay and publication baselines`

## 实现内容

### CPU 行结果与 RenderNode 生命周期分离

新增有界 `TerminalPreparedLineCache`，独立保存 `CompiledTerminalLine`、批量文字布局、
Blink metadata 和源行身份。RenderNode 被淘汰、pinned conflict 导致节点不可用或 Direct
Canvas fallback 时，仍可复用 CPU prepared 结果。

缓存上下文包含 instance、layout epoch、columns、背景色、字体/调色板/style generation、
cell width 和 line height。默认预算为最多 2048 条、12 MiB，并使用 second-chance clock 淘汰。
同一 `lineId` 的新版本替换旧 entry 时会同步扣除旧预算。

### 字体资源复用

`TerminalFontRegistry` 以 application context 进程级复用 `TerminalFontSet`，避免每个
`RemoteTerminalView` 重复加载字体资源；资源加载失败时回退到 `mainOnly()`。

### Blink metadata 和选择投影

Blink 可见性元数据保存在 prepared cache entry 中，动画 tick 不再重复扫描每个 cell 的样式。
选择覆盖层通过 `TerminalSelectionProjector` 将每行选择投影为一个半开列范围，宽字符 spacer
边界仍按旧逐 cell overlap 语义扩展，绘制最多一个矩形。

### Snapshot publication coalescing

`RemoteTerminalModel` 增加 model executor 绑定路径：同一 executor 队尾只安排一个 flush task，
连续 mutation 在物化前合并 dirty/state，快照真正发布后才通知 Runtime。未绑定 executor 的
JVM/旧调用方仍保持同步兼容路径。测试覆盖 1/10/100/500 次 burst、full render 合并、
publication version 和 executor 拒绝时的同步兜底。

## 性能基线

API 36 显式 benchmark 命令：

```sh
cd android-client
./gradlew :terminal-renderer:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.webterm.terminal.renderer.RendererRound2CacheBenchmarkTest \
  -Pandroid.testInstrumentationRunnerArguments.webtermPerf=true \
  --no-daemon --console=plain
```

环境：`medium_phone(AVD) - 16`，`sdk_gphone64_arm64`，API 36，1080×2400，420 dpi，
默认 `monospace`，14sp；每个场景 warm-up 10 帧、正式 30 帧。测试直接记录新的 RenderNode，
避免在软件 Canvas 上调用不支持的 `RenderNode.draw()`；因此这些数字是 renderer cold-record/
prepared-hit microbenchmark，不是完整 View 端到端帧时间。

原始 JSONL：[API 36 Round 2 benchmark](android-renderer-performance-round2-api36.jsonl)

| 场景 | cold P50 | cold P95 | prepared hit P50 | prepared hit P95 | prepared bytes |
| --- | ---: | ---: | ---: | ---: | ---: |
| blank-heavy | 1.044 ms | 1.536 ms | 0.232 ms | 0.675 ms | 361,520 |
| dense ASCII | 1.495 ms | 1.844 ms | 0.283 ms | 0.379 ms | 437,120 |
| styled ASCII | 4.854 ms | 5.518 ms | 2.498 ms | 2.905 ms | 526,720 |
| dense Unicode | 6.421 ms | 7.032 ms | 3.663 ms | 4.243 ms | 600,880 |
| special glyph | 5.111 ms | 6.012 ms | 3.825 ms | 4.646 ms | 576,000 |

本次输出每个场景均为 40 行；prepared cache warm 路径的 `prepared_hits=40`、
`prepared_misses=0`。这些是单台模拟器的一轮 30 样本观察值，不作为跨设备硬性 P95 门槛。

## 测试结果

已执行：

```sh
cd android-client
./gradlew :terminal-renderer:testDebugUnitTest :terminal-model:test :terminal-runtime:test \
  --no-daemon --console=plain
./gradlew :terminal-renderer:assembleDebug --no-daemon --console=plain
./gradlew :terminal-renderer:connectedDebugAndroidTest --no-daemon --console=plain
```

结果：

- renderer JVM 全量通过（166 个测试）；
- `terminal-model:test` 通过（91 个测试）；
- `terminal-runtime:test` 通过（60 个测试）；
- `:terminal-renderer:assembleDebug` 通过；
- `:app:assembleDebug` 通过；
- API 36 instrumentation：31 个测试，28 个通过、3 个性能测试跳过，无失败；
- 显式 Round 2 benchmark：1 个测试通过；
- `git diff --check` 通过。

关键不变量仍保持：

- RenderNode cache hit 不重新编译/录制静态行；
- 单行 patch 只重录对应行；
- cursor blink、文字 blink 和 selection 改变不重录静态正文；
- 选择投影遵循宽字符边界；
- burst publication 只在 executor 队尾物化一次最终快照；
- 默认空白行不会生成无效 TextSpan cluster。

## 未验证项和范围外项目

- API 29 模拟器：当前机器没有可用 API 29 AVD，未执行；
- 真实 Android 设备：当前没有连接真机，未执行；
- GitHub Actions：仓库当前没有本轮新增 workflow，未执行；
- 真实生产 `TerminalLineRenderNodeCache` 的长时间 back-scroll/eviction P50/P95 尚未形成独立
  benchmark；当前测试证明 prepared cache 可独立命中，但不宣称完整滚动性能收益；
- selection projector 尚未加入随机 property-test harness，已有确定性宽字符和 bitmap 回归测试；
- decoration run batching、curly path 预构建和特殊 glyph clipping batching 未执行，待有稳定
  profile 证据后再决定；
- RenderSnapshot 的更深层跨组件 publication 调度只覆盖当前 `RemoteTerminalModel` executor
  绑定路径，未改变服务端或协议。

因此当前交付结论是：Round 2 代码实现和 API 36 验证完成；API 29、真机、CI 及长时间生产链路
性能验收仍需补跑，不能据此宣称完整发布验收完成。
