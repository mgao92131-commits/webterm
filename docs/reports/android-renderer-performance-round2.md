# WebTerm Android Renderer Performance Round 2

## 状态

Round 2 的 2A（字体资源、CPU prepared-line cache、Blink metadata、selection projection）
和 2B（model executor 上的 publication coalescing）已实现。随后补充的 hardening 修复了：
历史轴复用必须基于已物化 snapshot、body residency 变化撤销复用、动态覆盖层在 Prepared
entry 淘汰后的按需恢复，以及动态查询对 prepared hit/miss 指标的污染。当前已通过 JVM、模块
构建和 API 36 instrumentation；API 29、真实 Android 设备和 GitHub Actions 当前环境未提供，
因此本报告不把 Round 2 标记为完整设备矩阵验收通过。

本轮没有修改服务端协议、`CellValue`/`StyleValue` 语义、历史 HTTP 协议、特殊 glyph 几何算法、
完整 Bidi 或 GLES/NDK。

## 分支和提交

| 项目 | 值 |
| --- | --- |
| 分支 | `agent/android-renderer-performance-round2` |
| Round 1 基线 | `0eaf90e50598a21d4fd0bc80c7b61a01e2371bf6` |
| Round 2 起点 | `agent/android-renderer-performance-round1` |
| Round 2 基准提交 | `ffa31f3d` |
| 代码修复 HEAD（报告提交前） | `89e84dd5` |
| 日期 | 2026-08-05 |

主要提交：

- `cc6efe8c` `perf(renderer): reuse terminal font resources across renderer views`
- `035a6e0a` `feat(renderer): add bounded prepared line cache`
- `90d1344f` `perf(renderer): cache blink metadata and project selection ranges`
- `230b55fc` `perf(model): coalesce snapshot materialization on the model executor`
- `f3e7e7b7` `fix(renderer): keep prepared cache budget accurate across versions`
- `e95d311e` `test(renderer): establish round2 cache overlay and publication baselines`
- `954f9115` `fix(model): invalidate pending history topology reuse across merged mutations`
- `2e0f66e5` `fix(renderer): advance visual generations before blink metadata lookup`
- `8d1ab938` `fix(renderer): rehydrate prepared lines for dynamic overlays after cache eviction`
- `38f00bcb` `fix(model): gate history axis reuse on a materialized snapshot`
- `649f82b6` `perf(renderer): keep dynamic overlay cache metrics precise`
- `89e84dd5` `test(renderer): verify dynamic overlay rehydration with surviving rendernodes`

## 实现内容

### CPU 行结果与 RenderNode 生命周期分离

新增有界 `TerminalPreparedLineCache`，独立保存 `CompiledTerminalLine`、批量文字布局、
Blink metadata 和源行身份。RenderNode 被淘汰、pinned conflict 导致节点不可用或 Direct
Canvas fallback 时，仍可复用 CPU prepared 结果。

当 RenderNode 仍然命中但 Prepared entry 已被独立预算淘汰时，只有当前 Blink phase 或 BLOCK
cursor 确实需要前景重放才重新 prepare；普通静态命中不会制造缓存抖动。

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
publication version 和 executor 拒绝时的同步兜底。历史拓扑 fast path 现在还要求前一状态
已经物化为当前 RenderSnapshot；连续 Baseline、history commit 和 LineBody batch 都不会把
未物化或旧 residency 的 HistoryPart 带入最终快照。

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
| blank-heavy | 1.118 ms | 1.617 ms | 0.245 ms | 0.319 ms | 361,520 |
| dense ASCII | 1.463 ms | 2.246 ms | 0.302 ms | 0.396 ms | 437,120 |
| styled ASCII | 4.309 ms | 4.866 ms | 2.294 ms | 3.010 ms | 526,720 |
| dense Unicode | 5.803 ms | 6.451 ms | 3.586 ms | 4.086 ms | 600,880 |
| special glyph | 4.404 ms | 5.779 ms | 3.090 ms | 4.344 ms | 576,000 |

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

- renderer JVM 全量通过（168 个测试）；
- `terminal-model:test` 通过（94 个测试）；
- `terminal-runtime:test` 通过（60 个测试）；
- `:terminal-renderer:assembleDebug` 通过；
- `:app:assembleDebug` 通过；
- API 36 instrumentation：33 个测试，30 个通过、3 个性能测试跳过，无失败；
- 显式 Round 2 benchmark：1 个测试通过；
- `git diff --check` 通过。

关键不变量仍保持：

- RenderNode cache hit 不重新编译/录制静态行；
- 单行 patch 只重录对应行；
- cursor blink、文字 blink 和 selection 改变不重录静态正文；
- 选择投影遵循宽字符边界；
- burst publication 只在 executor 队尾物化一次最终快照；
- 默认空白行不会生成无效 TextSpan cluster。
- 连续兼容 Baseline、兼容 Baseline 后 body batch 的合并发布结果与同步逐次发布一致；
- Prepared entry 淘汰但 RenderNode 保留时，Blink 动态覆盖层仍能恢复且不增加行节点录制。

## 未验证项和范围外项目

- API 29 模拟器：当前机器没有可用 API 29 AVD，未执行；
- 真实 Android 设备：当前没有连接真机，未执行；
- GitHub Actions：仓库当前没有本轮新增 workflow，未执行；
- 真实生产长时间 back-scroll/eviction 的 P50/P95 尚未形成独立 benchmark；当前新增测试覆盖
  了存活 RenderNode + 淘汰 Prepared entry 的单行动态恢复，但不宣称完整滚动性能收益；
- selection projector 尚未加入随机 property-test harness，已有确定性宽字符和 bitmap 回归测试；
- decoration run batching、curly path 预构建和特殊 glyph clipping batching 未执行，待有稳定
  profile 证据后再决定；
- RenderSnapshot 的更深层跨组件 publication 调度只覆盖当前 `RemoteTerminalModel` executor
  绑定路径，未改变服务端或协议。

因此当前交付结论是：本轮审查指出的 model/renderer 正确性问题已经修复并由回归测试锁定，
代码可进入合并候选审查；API 29、真机、CI 及长时间生产链路性能验收仍需补跑，不能据此
宣称完整发布验收完成。
