# Android Renderer Performance Round 1

## 1. Environment

本报告对应分支 `agent/android-renderer-performance-round1`，起点为
`agent/android-renderer-phase5-hardening` 的提交 `9be9e5a79f878390aa5c306227ecd26a3362d7e3`。

本轮实际采样环境：

| 项目 | 值 |
| --- | --- |
| 日期 | 2026-08-04 |
| AVD | `medium_phone(AVD) - 16` |
| API / build | API 36 / `13818094` |
| 模拟器型号 | `sdk_gphone64_arm64` |
| 分辨率 / density | 1080×2400 / 420 dpi |
| JDK | 21.0.9（项目 source/target 仍为 Java 17） |
| Gradle / AGP | 9.1.0 / 9.0.1 |
| compileSdk / minSdk | 36 / 29 |
| 字体 / 字号 | `TerminalFontSet.fromContext()`，默认 `Typeface.MONOSPACE`，14sp |
| 采样 | warm-up 10 帧，正式 30 帧，RenderNode cold record |

提交序列：

| 步骤 | 提交 |
| --- | --- |
| 0 基线套件 | `35a4db0b` |
| 1 默认空格快速路径 | `a172c1e7` |
| 2 字体分类快速路径 | `58186d16` |
| 3 编译结果所有权转移 | `cbdd2402` |
| 4 批量 contextual advance | `6557d917` |

验收修订提交：

| 内容 | 完整 SHA |
| --- | --- |
| 性能统计显式注入 | `ba0dd74fe20375e47fd59b80d00e4b90b4fd85be` |
| 统一 base/head benchmark harness | `eff1bdeb33c18452e9379a43a21435b0fe7f1d2e` |

`6557d917` 是原 Round 1 功能 head；后续修订不改变前五个功能提交。

性能测试不会随普通 instrumentation 默认执行，需显式传入 `webtermPerf=true`。

## 2. Baseline — phase5-hardening

提交 0 建立 `RendererFrameWorkStats` 和 API 36 设备冷 RenderNode 录制套件，暂不改变生产
绘制算法。正式采样使用 10 次 warm-up、30 次测量，并输出 P50/P90/P95/max 及编译、绘制、
字体分类和 advance 工作量。

运行命令：

```sh
cd android-client
./gradlew :terminal-renderer:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.webterm.terminal.renderer.RemoteTerminalRendererPerformanceTest \
  -Pandroid.testInstrumentationRunnerArguments.webtermPerf=true \
  --no-daemon --console=plain
```

每个步骤均在同一 AVD 上执行了显式采样。提交 0～3 的早期测试输出只包含 render P50/P90/P95
和 compile 平均值；提交 4 扩展输出后，新增 compile/draw P50/P95 及批量 advance 计数。
这些数字用于观察各优化阶段的趋势；由于早期 harness 的背景常量后来才修正，严格的
提交级 base/head 对照见第 8 节，不能把下面的历史数字当作可由原提交直接重现的证据。

提交 0 的 render P95（ns）/ compile 平均值（ns）如下：

| 场景 | render P95 | compile avg |
| --- | ---: | ---: |
| blank-heavy | 5,724,083 | 1,629,222 |
| dense ASCII | 6,144,625 | 1,468,966 |
| styled ASCII | 7,781,334 | 1,845,645 |
| dense Unicode | 8,621,459 | 1,770,031 |
| special glyph | 4,931,125 | 629,777 |

## 3. Commit 1 — default blank fast path

实现：精确识别 `CellValue.isDefault()`。前导和尾部默认空格直接跳过；同一普通
TextSpan 内部的默认空格延迟到确实需要时才物化。带背景、装饰、hidden、link 和异常
宽度的空格继续走原路径。

JVM 和 API 36 instrumentation 回归已通过。提交 1 的 render P95 / compile 平均值为：

| 场景 | render P95 | compile avg |
| --- | ---: | ---: |
| blank-heavy | 2,099,791 | 638,213 |
| dense ASCII | 6,427,542 | 1,507,127 |
| styled ASCII | 7,351,458 | 1,845,959 |
| dense Unicode | 8,721,750 | 1,664,878 |
| special glyph | 4,895,750 | 678,147 |

blank-heavy 的编译工作量从 4,800 个输入 cell 中的 4,330 个默认空格，降为 600 个
emitted cluster；font resolve 从 4,800 降为 470，Emoji classification 为 0。

## 4. Commit 2 — font classification fast paths

实现：ASCII 单字符直接返回 `MAIN_TEXT`；Emoji ranges 改为静态数组；编译器复用已经
计算的单 code point，并通过 `supportsCodePoint()` 进入特殊字符分派。ASCII keycap、
VS、ZWJ、区域旗帜和肤色序列仍保留完整 Emoji 分类路径。

JVM 和 API 36 instrumentation 回归已通过。提交 2 的 render P95 / compile 平均值为：

| 场景 | render P95 | compile avg |
| --- | ---: | ---: |
| blank-heavy | 1,960,041 | 521,149 |
| dense ASCII | 6,199,042 | 753,982 |
| styled ASCII | 6,224,291 | 1,129,046 |
| dense Unicode | 7,887,041 | 1,275,440 |
| special glyph | 5,253,042 | 523,369 |

Dense ASCII 和 blank-heavy 的 Emoji classification 均为 0；ASCII 仍保留按 span 的
font resolve，但不再进入 Emoji classifier 和 coverage 查询。

## 5. Commit 3 — ownership transfer

实现：普通构造器继续防御复制；生产编译路径通过 `TextSpan.takeOwnership()` 和
`CompiledTerminalLine.takeOwnership()` 接管已完成的数组/List；空编译结果使用单例。
测试覆盖了构造器防御边界、不可修改 view 和跨两次 compile 的结果独立性。

JVM 和 API 36 instrumentation 回归已通过。提交 3 的 render P95 / compile 平均值为：

| 场景 | render P95 | compile avg |
| --- | ---: | ---: |
| blank-heavy | 1,785,042 | 493,576 |
| dense ASCII | 5,046,417 | 714,331 |
| styled ASCII | 6,349,458 | 1,137,420 |
| dense Unicode | 6,875,834 | 1,114,363 |
| special glyph | 4,773,750 | 518,924 |

本提交的主要结果是减少生产编译结果的二次数组/List 防御复制；工作量计数和像素结果
保持不变，因此不能把时间变化全部归因于所有权转移。

## 6. Commit 4 — batch contextual advances

实现：每个 TextSpan 复用 `char[]`、UTF-16 advance 和 prefix scratch 数组；真实批量
路径只调用一次 `Paint.getTextRunAdvances()`，whole-run 判断和 cluster fallback 共享同一
批量结果。批量结果全为零时保留受控 legacy `getRunAdvance()` fallback。

JVM 和 API 36 instrumentation 回归已通过。提交 4 的最终有效采样如下：

| 场景 | render P50 | render P95 | compile P50 | compile P95 | draw P95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| blank-heavy | 829,500 | 1,803,708 | 363,127 | 1,170,916 | 726,876 |
| dense ASCII | 1,262,916 | 1,477,958 | 679,249 | 761,711 | 648,581 |
| styled ASCII | 4,179,917 | 5,099,042 | 1,071,292 | 1,256,787 | 3,579,044 |
| dense Unicode | 5,398,375 | 6,080,334 | 1,070,838 | 1,355,083 | 4,737,793 |
| special glyph | 4,050,875 | 4,728,791 | 490,660 | 544,878 | 4,115,166 |

工作量计数也符合预期：

- blank-heavy：`default_cells=4330`、`emitted_clusters=600`、`font_resolve=470`、
  `emoji_classify=0`、`batch_advance=40`、`legacy_run_advance=0`；
- dense ASCII：40 个 TextSpan、4,800 个 cluster、40 次 batch advance、0 次 legacy
  advance；
- styled ASCII：600 次 batch advance，280 个 cluster fallback；
- dense Unicode：1,226 次 batch advance，1,220 个 cluster fallback，保持 3,600 个
  服务端语义 cluster；
- special glyph：不进入普通 TextSpan advance 路径。

## 7. Historical renderer-only measurements

相对提交 0 的 render P95 变化如下；这是同一 AVD 的观测结果，不把单次模拟器数据当作
跨设备性能承诺：

| 场景 | 提交 0 | 提交 1 | 提交 2 | 提交 3 | 提交 4 | 0→4 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| blank-heavy | 5.724 ms | 2.100 ms | 1.960 ms | 1.785 ms | 1.804 ms | -68.5% |
| dense ASCII | 6.145 ms | 6.428 ms | 6.199 ms | 5.046 ms | 1.478 ms | -75.9% |
| styled ASCII | 7.781 ms | 7.351 ms | 6.224 ms | 6.349 ms | 5.099 ms | -34.5% |
| dense Unicode | 8.621 ms | 8.722 ms | 7.887 ms | 6.876 ms | 6.080 ms | -29.4% |
| special glyph | 4.931 ms | 4.896 ms | 5.253 ms | 4.774 ms | 4.729 ms | -4.1% |

结论：默认空格和批量 contextual advance 的收益已经实测；字体分类和所有权转移主要
体现为工作量/分配减少，时间变化受模拟器噪声影响，不能单独归因。Special glyph TUI
没有进行专门 batching，本轮仅作为防回退场景，观测未超过 5% 变化。

## 8. Reproducible base/head comparison

为修复早期基线不可直接重现的问题，提交 `eff1bdeb33c18452e9379a43a21435b0fe7f1d2e`
加入了共同 harness：

- base：`9be9e5a79f878390aa5c306227ecd26a3362d7e3`；
- head：`eff1bdeb33c18452e9379a43a21435b0fe7f1d2e`；
- harness：同一个 `RendererRound1CommonBenchmarkTest` 和
  `RendererPerformanceFixtures`，均由 harness SHA 注入两个临时 worktree；
- 两个源 worktree 运行前均为 clean，测试完成后临时 worktree 已删除；
- 每轮均为 10 次 warm-up、30 次测量，同一 `medium_phone(AVD) - 16` API 36 设备。

运行命令：

```sh
tools/android-renderer/run-round1-benchmark.sh \
  9be9e5a79f878390aa5c306227ecd26a3362d7e3 \
  eff1bdeb33c18452e9379a43a21435b0fe7f1d2e
```

原始 JSONL：

- [第一轮原始输出](android-renderer-performance-round1-comparison-run1.jsonl)
- [第二轮原始输出](android-renderer-performance-round1-comparison-run2.jsonl)

两轮 render P95（ms）：

| 场景 | base R1 | head R1 | base R2 | head R2 |
| --- | ---: | ---: | ---: | ---: |
| blank-heavy | 3.893 | 1.387 | 5.995 | 1.310 |
| dense ASCII | 7.223 | 1.651 | 8.054 | 2.233 |
| styled ASCII | 8.641 | 5.515 | 8.952 | 6.074 |
| dense Unicode | 9.171 | 5.827 | 9.855 | 6.762 |
| special glyph | 6.017 | 4.482 | 6.367 | 5.645 |

以第一轮为基准，head 的方向性改善为：blank-heavy -64.4%、dense ASCII -77.1%、
styled ASCII -36.2%、dense Unicode -36.5%、special glyph -25.5%。第二轮的 AVD
波动较大，尤其 dense ASCII 和 special glyph，因此这些结果证明优化方向和工作量变化，
不构成跨设备或稳定 P95 门槛的承诺。

两轮 P95 相对变化（R1→R2）为：

| 场景 | base | head |
| --- | ---: | ---: |
| blank-heavy | +54.0% | -5.6% |
| dense ASCII | +11.5% | +35.2% |
| styled ASCII | +3.6% | +10.2% |
| dense Unicode | +7.5% | +16.1% |
| special glyph | +5.8% | +25.9% |

因此报告状态明确为：Round 1 implementation complete；API 设备验收 incomplete。

## 9. Known limitations

- 本轮不包含 RenderSnapshot 合并、CPU compiled-line 独立缓存、blink 扫描、selection
  投影、RenderNode 淘汰和特殊 glyph batching。
- API 29、真实设备和 GitHub Actions 结果必须以实际运行记录为准，不能由 API 36 的结果推断。
- 本轮当前已实际验证 JVM、assembleDebug 和 API 36 instrumentation；API 29、真实设备和
  GitHub Actions 尚未执行。
- `TerminalFontRegistry` 尚未加入；字体资源仍由每个 `RemoteTerminalView` 创建时加载，
  延后到下一轮。
- 共同 harness 是 renderer cold-record microbenchmark，不包含生产
  `TerminalLineRenderNodeCache` lookup、bleed 平移、View clip 和 cache hit 管理；完整
  cache/View 性能测试延后到下一轮。
- RenderNode 冷录制套件使用新的节点记录工作量，单次采样可能受模拟器负载影响；提交 0～3
  的历史输出没有 compile P50/P95 字段，报告保留了当时实际采集的 compile 平均值，没有
  反推缺失指标。
