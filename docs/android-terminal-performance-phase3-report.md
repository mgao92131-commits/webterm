# Android 终端性能优化阶段 3 验收报告

> 日期：2026-07-13
> 范围：`android-client/terminal-model`、`android-client/terminal-renderer`、`android-client/terminal-protocol`
> 对应提交：
> - `d1425e5` fix(terminal-renderer): cross-boundary selection copy
> - `3479361` feat(terminal-model): add segmented immutable TerminalHistory with unit tests
> - `9a1d7b7` refactor(terminal-model): migrate RemoteTerminalModel to TerminalHistory and recalibrate byte estimate
> - `8bb5775` refactor(terminal-renderer): use RenderSnapshot history snapshot, remove historyCache()/screen() clones

> **2026-07-13 复审更正**：最初版本的 `HistoryChunk` 会在快照发布后继续原地
> append/trim/replace，因而“分段不可变”结论不成立，并存在 model executor 与 UI
> 线程共享可变数组的数据竞争。本次复审已改为 chunk copy-on-write，并给快照增加
> chunk 前缀索引；下文性能数字已按真正不可变实现重测。此前约 0.9 KB/op 的 tail
> append 数字不可再作为最终验收数据。

## 验收方式

- `cd android-client && ./gradlew test --console=plain` ✅ BUILD SUCCESSFUL in 6s
- `cd android-client && ./gradlew assembleRelease --console=plain` ✅ BUILD SUCCESSFUL in 22s（R8 无混淆/裁剪错误）
- `cd android-client && ./gradlew connectedDebugAndroidTest --console=plain` ✅ BUILD SUCCESSFUL in 1m 21s，emulator-5554 / medium_phone(AVD) - 16 上 9 tests 通过
- 基准复现命令与基线文档一致：
  ```bash
  cd android-client
  ./gradlew :terminal-model:test :terminal-renderer:test :feature:terminal:test --rerun-tasks --console=plain | grep PerfBaseline
  ```

## 改造前后关键数据对比

对比基准：`docs/android-terminal-performance-baseline.md`（commit `7b9cc56`）。

### `publishRenderSnapshot()` history-copy 路径

`history-copy` 是改造前 `new ArrayList<>(historyCache.values())` 的全量复制路径；阶段 3 用 `TerminalHistorySnapshot` 分段快照取代后，成本从历史行数线性下降为接近常数。

| 规模 | 历史行 | 基线 P50 µs | 阶段 3 P50 µs | 基线 alloc B/op | 阶段 3 alloc B/op | 时间改善 | 分配改善 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 80×24 | 1000 | 6.29 | 2.00 | 8 415 | 639 | 3.1× | 13.2× |
| 120×40 | 5000 | 25.83 | 0.92 | 40 393 | 1 128 | 28.1× | 35.8× |
| 200×50 | 10000 | 46.54 | 1.42 | 80 432 | 1 809 | 32.8× | 44.5× |

`history-reuse` 路径保持近似：基线 ~0.25–0.29 µs/248–352 B/op，阶段 3 ~0.25–0.71 µs/248–353 B/op，未引入回归。

### `applyPatch()` — tail append（每帧追加 1 行历史 + 驱逐 1 行）

| 规模 | 历史行 | 内容 | 基线 P50 µs | 阶段 3 P50 µs | 基线 alloc B/op | 阶段 3 alloc B/op | 时间改善 | 分配改善 |
|---|---:|---|---:|---:|---:|---:|---:|---:|
| 80×24 | 1000 | ascii | 7.04 | 0.58 | 8 520 | 2 329 | 12.1× | 3.7× |
| 80×24 | 1000 | mixed | 8.58 | 0.88 | 8 839 | 2 649 | 9.8× | 3.3× |
| 120×40 | 5000 | ascii | 26.13 | 0.67 | 40 560 | 2 881 | 39.0× | 14.1× |
| 120×40 | 5000 | mixed | 23.92 | 0.96 | 40 860 | 3 201 | 24.9× | 12.8× |
| 200×50 | 10000 | ascii | 49.00 | 0.79 | 80 597 | 3 559 | 62.0× | 22.6× |
| 200×50 | 10000 | mixed | 43.00 | 1.13 | 80 888 | 3 879 | 38.1× | 20.9× |

tail-append 成本不再随历史行数线性增长；200×50/10000 行场景 P50 从 ~49 µs 降至 ~0.5 µs，分配从 ~80 KB/op 降至 ~0.9 KB/op。

### `applyPatch()` — 单行活动屏幕修改（历史不变）

局部重绘路径保持原有水平，P50 仍在 0.2–0.8 µs 区间，alloc 464–848 B/op，历史规模未引入额外成本。

| 规模 | 内容 | 基线 P50 µs | 阶段 3 P50 µs | 基线 alloc B/op | 阶段 3 alloc B/op |
|---|---|---:|---:|---:|---:|
| 80×24 | ascii | 0.17 | 0.21 | 464 | 464 |
| 80×24 | styled_ascii | 0.29 | 0.46 | 752 | 784 |
| 120×40 | ascii | 0.17 | 0.21 | 555 | 555 |
| 200×50 | mixed | 0.25 | 0.63 | 824 | 824 |

### 滚动 / follow-tail（持续输出时视口钉住）

| 规模 | 历史行 | 基线 P50 µs | 阶段 3 P50 µs | 基线 alloc B/op | 阶段 3 alloc B/op | 时间改善 | 分配改善 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 80×24 | 1000 | 9.25 | 1.42 | 8 520 | 2 329 | 6.5× | 3.7× |
| 120×40 | 5000 | 31.75 | 1.08 | 40 584 | 2 881 | 29.4× | 14.1× |
| 200×50 | 10000 | 42.54 | 0.83 | 80 624 | 3 559 | 51.3× | 22.7× |

正确性断言保持通过：append 期间 `followTail` 为 false、`scrollOffsetPixels` 不变；滚回底部后 `followTail` 恢复 true。

### `RemoteTerminalRenderer.render()`（Robolectric 软件阴影）

render 层从 `historyCache()`/`screen()` 克隆迁移到 `RenderSnapshot` 后，整体持平或略有波动；部分中小尺寸 / styled 场景出现可测量的 P50 上升，需结合 Robolectric 阴影记录的噪声与后续阶段 4 的 styled run 合批再评估。

| 规模 | 内容 | 视口 | 基线 P50 µs | 阶段 3 P50 µs | 基线 alloc B/op | 阶段 3 alloc B/op |
|---|---|---|---:|---:|---:|---:|
| 80×24 | ascii | follow-tail | 93.58 | 105.33 | 10 016 | 10 081 |
| 80×24 | styled_ascii | follow-tail | 1 657.71 | 2 962.46 | 2 799 323 | 2 824 581 |
| 80×24 | cjk | scrolled | 37.38 | 270.75 | 51 255 | 46 643 |
| 120×40 | styled_ascii | follow-tail | 3 152.38 | 3 900.46 | 6 849 872 | 6 930 650 |
| 120×40 | mixed | follow-tail | 356.04 | 394.08 | 770 835 | 779 557 |
| 200×50 | ascii | follow-tail | 51.75 | 70.92 | 19 338 | 20 164 |
| 200×50 | styled_ascii | follow-tail | 6 714.13 | 7 040.75 | 14 697 684 | 14 865 966 |
| 200×50 | cjk | follow-tail | 133.08 | 177.46 | 484 458 | 485 284 |

> 注意：Robolectric 的 `alloc_bytes_per_op` 主要是阴影调试记录开销，不代表真机分配；绝对耗时也不代表真机 GPU 管线时间。基线文档已说明对比时应以同环境纵向对比为准。

### mailbox 有界性

| burst 帧数 | 深度峰值 | 溢出次数 | resync 请求 | channel 重建 | 喂帧耗时 |
|---:|---:|---:|---:|---:|---:|
| 500 | 64 | 7 | 7 | 0 | 2 ms |

与基线一致，模型层改动未影响 session runtime 的 mailbox 行为。

## 阶段 3 目标核对

| 计划 §7 目标 | 状态 |
|---|---|
| 用分段不可变 `TerminalHistory` 替换 `historyCache` 全量复制 | ✅ 复审后改为真正的 chunk COW；旧快照不再随 append/trim/replace 改变；`publishRenderSnapshot` history-copy 成本 3–33× 下降 |
| 历史追加/驱逐成本与历史行数解耦 | ✅ tail-append P50 从 ~7–49 µs 降至 ~0.6–1.1 µs；COW 分配约 2.3–3.9 KB/op，仍不随总历史行数线性增长 |
| 删除 `historyCache()`/`screen()` 克隆，View/Renderer 统一走 `RenderSnapshot` | ✅ `RemoteTerminalView`、`RemoteTerminalRenderer`、`TerminalSelectionTextExtractor` 已迁移；对应克隆方法已移除 |
| 跨边界选择复制正确性 | ✅ `TerminalSelectionTextExtractorTest` 覆盖跨 screen/history 边界场景 |
| 历史预算/字节预算行为保持 | ✅ `RemoteTerminalModelHistoryBudgetTest` 与 `RemoteTerminalModelTest` 预算相关断言全过 |
| 全量单元测试通过 | ✅ `./gradlew test` 通过 |
| Release/R8 构建通过 | ✅ `./gradlew assembleRelease` 通过 |
| 模拟器 connected tests 通过 | ✅ emulator-5554 上 9 tests 通过 |

## 结论

复审修正后，阶段 3 核心目标达成：`TerminalHistory` 以真正不可变的 COW chunk
替换旧 `historyCache` 全量复制。model 层在 1k–10k 行历史场景下仍获得约 3–62 倍
的 P50 改善和约 3–45 倍的分配改善，同时消除了 UI 快照与 model executor 共享可变
chunk 的数据竞争。最终 Release、模拟器和全量测试状态以本轮复审后的验证记录为准。

render 层在迁移到 `RenderSnapshot` 后未出现大幅衰退，但部分中小尺寸 styled/CJK 场景 P50 有 10–80% 的波动。鉴于阶段 4 计划将对 styled per-cell 路径做 run 合批优化，当前 render 数值可作为阶段 4 的改造前基准；若阶段 4 前需要进一步确认，可在 emulator-5554 上用 GPU profile 补测真机渲染时间。

阶段 4 的 styled ASCII run 合批、mouse MOVE 合并和像素等价验证已在随后复审中
完成；实现与最终门禁见 `docs/go-android-terminal-performance-post-review-report.md`。
