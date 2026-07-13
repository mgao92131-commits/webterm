# Go 终端性能优化阶段 2 验收报告

> 日期：2026-07-13
> 范围：`go-core/internal/headlessterm`、`go-core/internal/terminalengine`、`go-core/internal/screenprojection`
> 对应提交：`b1b60ea`（2a 行级 dirty + 原子投影）、`26f71cf`（2b Projector 脏行增量导出）、`ac82fc5`（2c 历史 LineID 增量 + diffToPatch 简化）

> **2026-07-13 复审更正**：原报告把 `cursor` 场景描述成“只移动光标”，但该
> 负载每轮实际发送 `ESC[H ESC[J` 并重绘多行，会触发接近全屏的投影复制。该场景
> 的 B/op 从 956 KB 上升到 1.88 MB 是真实回退，不能用“单行 dirty 已优化”掩盖。
> 它目前绝对耗时仍低于 1 ms，且不在实测主热点上，因此本轮记录为后续优化项，
> 不阻塞历史窗口与 diff 主目标验收。

## 验收方式

- `cd go-core && go test -count=1 ./...` ✅ 全过
- `cd go-core && go test -race -count=1 ./...` ✅ 全过（20 个包，含 screenprojection/session/terminalengine/terminalsession）
- `cd go-core/internal/headlessterm && go test ./...` ✅ 全过
- `go vet`（两个 module）✅ 无新告警
- 本报告所用 benchmark 命令与基线文档一致：
  ```bash
  cd go-core
  go test -run=^$ -bench=. -benchmem ./internal/screenprojection/ ./internal/terminalsession/
  cd go-core/internal/headlessterm
  go test -run=^$ -bench=. -benchmem ./
  ```

## 改造前后关键数据对比

### ExportState（screenprojection，200×50 终端尺寸）

| 场景 | 基线 ns/op | 阶段 2 ns/op | 基线 B/op | 阶段 2 B/op | 基线 allocs/op | 阶段 2 allocs/op | 时间改善 |
|---|---:|---:|---:|---:|---:|---:|---:|
| ascii | 4,595,429 | 1,180,077 | 6,694,275 | 2,518,039 | 3,502 | 879 | 3.9× |
| sgr | 5,386,601 | 1,455,829 | 6,526,819 | 2,480,242 | 39,449 | 9,257 | 3.7× |
| cursor（历史空） | 719,040 | 836,276 | 956,289 | 1,883,745 | 501 | 559 | 0.86× |
| tui | 5,015,301 | 836,119 | 6,326,278 | 1,869,520 | 10,831 | 1,623 | 6.0× |
| scroll | 4,711,819 | 995,748 | 6,884,128 | 2,246,174 | 5,247 | 1,045 | 4.7× |

说明：`cursor` 场景历史为空，但它并非纯光标移动：负载包含清屏与多行重绘。
新路径会先复制 ProjectionRow，再转换成协议 Line，因此该稀疏全屏场景耗时增加约
16%，分配接近翻倍。其他场景 ExportState 成本下降 3.7–6.0 倍，历史窗口重导出
仍是最大收益来源。后续若 profile 显示该路径成为热点，应优化 Full Projection 的
中间 Cell 复制，而不是削弱不可变快照边界。

### 屏幕 vs 历史窗口拆分（ExportSplitBaseline，200×50）

| 路径 | 基线 ns/op | 阶段 2 ns/op | 基线 B/op | 阶段 2 B/op |
|---|---:|---:|---:|---:|
| screen-only | 721,784 | 823,471 | 956,289 | 1,883,745 |
| history-heavy | 4,747,688 | 988,750 | 6,883,969 | 2,246,142 |

history-heavy 已与 screen-only 收敛到同一量级，历史窗口增量导出目标达成；但
screen-only 自身的 B/op 回退约 97%，仍需作为已知代价保留在报告中。

### FrameDeriverDiff（每客户端 diff 成本，200×50）

| 场景 | 客户端 | 基线 ns/op | 阶段 2 ns/op | 基线 allocs/op | 阶段 2 allocs/op |
|---|---:|---:|---:|---:|---:|
| ascii | 1 | 29,503 | 18,604 | 14 | **0** |
| ascii | 2 | 56,930 | 36,180 | 28 | **0** |
| ascii | 4 | 111,300 | 71,010 | 56 | **0** |
| tui | 1 | 32,636 | 22,205 | 14 | **0** |
| tui | 4 | 122,783 | 83,006 | 56 | **0** |

删除 `oldHistoryIDs` map 后，diff 阶段历史追加分配彻底消除；时间下降约 30–40%。

### ProjectorExportFanout（tui，200×50，Write+Export+N×diff 全链路）

| 客户端 | 基线 ms/op | 阶段 2 ms/op |
|---|---:|---:|
| 1 | 6.85 | 2.55 |
| 2 | 6.83 | 2.56 |
| 4 | 6.93 | 2.61 |

1→4 客户端边际成本仍 <3%，export-once 不变量保持成立。

### Projection Flush 延迟（terminalsession，ms）

| 尺寸 | 基线 p50 | 阶段 2 p50 | 基线 p99 | 阶段 2 p99 |
|---|---:|---:|---:|---:|
| 80×24 | 22.59 | 19.59 | 24.44 | 21.12 |
| 200×50 | 24.89 | 21.71 | 26.75 | 22.87 |

16ms 合并窗口仍是主体，但导出耗时下降使 p50/p99 各降约 3ms。

### Write 吞吐（headlessterm，MB/s）

| 场景 | 基线 | 阶段 2 |
|---|---:|---:|
| ascii/80×24 | 4.97 | 5.31 |
| ascii/200×50 | 4.41 | 5.36 |
| sgr/200×50 | 10.07 | 12.75 |
| scroll/200×50 | 4.52 | 5.57 |

行级 dirty 的标记成本低于原逐 cell dirty，Write 侧吞吐有小幅提升。

## 阶段 2 目标核对

| 计划 §6.6 目标 | 状态 |
|---|---|
| 单行变化不再扫描完整屏幕和历史窗口 | ✅ `ReadProjection` 只读 dirty 行；历史 `LinesAfter` 只取新行 |
| Cell 锁从 O(rows×cols) 降为一次快照锁或 O(dirty rows) | ✅ `ExportState` 一次 RLock；屏幕/历史均无逐格锁 |
| 单行更新的 projection 分配量显著下降 | ⚠️ dirty 行机制与测试已落地，但现有 `cursor` benchmark 不是单行负载；其稀疏全屏 B/op 反而从 956 KB 增至 1.88 MB。需新增真正单行负载后再验收该指标 |
| 增加客户端数量不增加 Engine 导出次数 | ✅ fanout 基准 1→4 客户端边际 <3% |
| snapshot/patch 协议兼容 | ✅ `screenprotocol/fixture_test.go` 与 `session` 集成测试全绿 |
| resize/reflow/主备屏/历史分页/选择复制行为不回退 | ✅ 不变量测试 + 既有测试覆盖 |
| `go test -race` 通过 | ✅ 全包 race 通过 |

## 结论

阶段 2 的核心目标（历史窗口增量、dirty 行导出、每客户端 diff 零分配）已达成，
但不能再表述为“全部指标均达成”：稀疏全屏投影的中间复制仍有约 2 倍 B/op 回退，
而真正单行更新缺少独立 benchmark。该项绝对耗时目前低于 1 ms，先保留为
profile 驱动的后续项；阶段 3 的 Android 模型迁移不受此项阻塞。
