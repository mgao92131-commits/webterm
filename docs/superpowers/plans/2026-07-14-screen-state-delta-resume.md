# Screen 状态增量恢复实施方案（修订版）

**日期：** 2026-07-14  
**状态：** 实施中：Task 0–2 已完成（分支 feat/screen-delta-resume），Task 3 起待实施  
**范围：** Go `webterm.screen.v1`、Android terminal runtime、页面生命周期、混合版本发布  
**目标：** 页面切换优先复用同一 Runtime；真实断线或 WARM 会话重连时，服务端根据各状态组件的最后变化 revision 直接生成“当前最终状态 Patch”；只有状态无法连续、协议屏障已跨越或 Patch 不划算时才发送 Snapshot。

## 1. 背景与现状

当前 Android 每次打开终端页都会新建 screen channel。`RemoteTerminalIntegration.stop()` 会关闭连接并调用 `RemoteTerminalModel.resetForReconnect()`，因此同一进程内已经存在的屏幕模型也会被清空。Android 的 `Hello` 只发送 `version/cols/rows`；Go `handleScreenHello()` 无条件 attach 新 client，而新 `FrameDeriver` 没有 baseline，首帧必然是 Snapshot。

现有协议已经具备恢复所需的主要锚点：

- `Hello.instance_id`
- `Hello.layout_epoch`
- `Hello.screen_revision`
- `Hello.has_projection`
- `ScreenPatch.base_revision`
- `ScreenPatch.screen_revision`

但现有协议和实现仍缺少：字段 presence、exact-resume 确认、历史水位与恢复 Patch 的原子关系、初始 revision/epoch 契约、累计 Patch 资源校验，以及 View detach 后副作用事件的持久处理者。

本方案不保存历史 ScreenFrame 环形缓存，也不逐帧回放中间 revision。Go 只维护当前权威投影、各组件最后变化 revision 和有界历史行版本索引；恢复时直接从客户端 revision 生成一帧累计 Patch。

## 2. 不可违反的约束

1. Go 始终是终端屏幕的唯一权威来源。
2. Android 内存模型是可重建投影，不是第二权威源。
3. View/Fragment 生命周期不得拥有 terminal runtime 或 screen channel。
4. `screenRevision` 可以跳跃；连续性的定义是 `patch.baseRevision == client.screenRevision`。
5. 合法 Patch 必须满足 `screenRevision > baseRevision`，exact resume 不使用同 revision 空 Patch。
6. Snapshot 必须可独立显示；Patch 必须以客户端明确声明的完整投影为基线。
7. attach、resync、Snapshot 发送本身不得推进 canonical revision。
8. instance、layout epoch 或快照屏障不兼容时禁止猜测增量。
9. 冷启动不恢复磁盘屏幕，只请求 Snapshot。
10. Relay 透明转发 screen payload，不缓存或解释终端状态。
11. 所有可清空字段必须在 wire 上区分“未变化”和“变为空值”。
12. 恢复首帧与后续实时帧只能形成一条提交过的 revision 链。

## 3. 协议修订

协议修订必须先于 LastChangedRevision 实现落地，并同步重新生成 Go/Java 代码。

### 3.1 明确字段 presence

`ScreenPatch` 中可清空的字符串改为 proto3 optional：

```protobuf
message ScreenPatch {
  // 现有字段保持原编号。
  optional string title = 11;
  optional string working_directory = 12;
  optional uint64 first_available_history_line_id = 15;
}
```

`first_available_history_line_id` 使用当前空闲字段号 15；不得复用或重排已有字段号。

语义：

- `has_title=false`：标题未变化；
- `has_title=true && title=""`：标题已被清空；
- cwd 同理；
- `first_available_history_line_id` 只在需要推进历史水位时出现。

Cursor、Modes、Palette 已是 message 类型，可用 message presence；Go 内部仍必须增加显式 change/presence 标志，不能把零值当作“未变化”。

注意：proto3 optional 会改变 Go 生成代码的 API（`string` → `*string`），`go-core/internal/screenprotocol/encoder.go` 的赋值点及所有读取处必须与 proto 改动同提交更新；Java 端生成代码将新增 `hasTitle()`/`hasWorkingDirectory()`，Android mapper 依赖这两个方法区分三态。

### 3.2 exact resume 确认

新增 exact resume 专用消息：

```protobuf
message ResumeAck {
  string instance_id = 1;
  uint64 layout_epoch = 2;
  uint64 screen_revision = 3;
}
```

以新字段号 28 加入 `ScreenEnvelope.oneof payload`。只有服务端确认客户端投影与当前权威状态完全相同时发送 `ResumeAck`。Patch 和 Snapshot 本身就是其他两条恢复路径的完成凭证，不额外发送第二个 decision ack。

`ResumeAck`、恢复 Patch 和 Snapshot 都必须进入同一个按连接串行化的 screen writer，并占用同一个不可覆盖的 initial-sync slot。禁止把 Ack 放进独立 control queue，否则 Ack 可能与首个实时 Patch 乱序。

### 3.3 显式内部 FrameKind

Go `terminalengine.ScreenFrame` 增加内部类型：

```go
type FrameKind uint8

const (
    FrameSnapshot FrameKind = iota + 1
    FramePatch
)
```

编码器不得继续使用 `BaseRevision == 0` 判断帧类型。`BaseRevision` 只表达 Patch 基线；Snapshot 的 base 始终不参与语义。

### 3.4 初始版本

新建 `terminalsession.Runtime` 时：

```text
LayoutEpoch    = 1
ScreenRevision = 1
```

revision 只在可观察终端状态变化时递增。下列操作不递增 revision：

- 新客户端 attach；
- 客户端 detach；
- 发送 Snapshot；
- 处理 ResyncRequest；
- exact ResumeAck；
- 相同尺寸的 resize no-op。

### 3.5 Hello 一致性校验

`ValidateHello` 增加：

```text
hasProjection=false
  → instance/epoch/revision 必须全部为默认值，或由服务端明确忽略并记录兼容日志

hasProjection=true
  → instanceId 非空
  → layoutEpoch >= 1
  → screenRevision >= 1
  → capability.rowPatches=true
```

同一 screen channel 只接受一次 Hello。重复 Hello 直接返回协议错误并关闭该 channel，不能重复 seed baseline（现状为静默容忍：CAS 保证每连接只 attach 一次，但重复 Hello 仍会 SendInfo 并置 ready，需要新增拒绝逻辑）。

## 4. 权威版本模型

### 4.1 外部版本

```text
InstanceID       一次 PTY 进程实例
LayoutEpoch      resize/reflow 后的布局世代
ScreenRevision   可观察终端状态的单调 revision
```

- InstanceID 变化：客户端丢弃全部模型。
- LayoutEpoch 变化：客户端丢弃活动屏幕、历史窗口、字典、选择和 viewport anchor。
- ScreenRevision 只描述同 instance/epoch 下的权威状态演进。

### 4.2 SnapshotBarrierRevision

Go Projector 增加持久字段：

```go
SnapshotBarrierRevision uint64
```

它表示最近一次“更早投影不能再通过当前状态 Patch 恢复”的 revision。下列事件推进 barrier：

- main/alternate buffer 切换；
- style/link 字典 generation 轮转；
- 历史 LineID 体系重置；
- projector/model 整体重建；
- 任何现有 `ForceSnapshot` 事件；
- 投影解释方式变化但协议版本未提升的内部重置。

普通清屏、全屏重绘、光标移动、标题/cwd 变化不推进 barrier；它们通过组件 change revision 表达。resize/reflow 继续推进 LayoutEpoch。

现状说明：当前 `ForceSnapshot` 仅在 style/link 字典超过 4096 项轮转时置位（`go-core/internal/screenprojection/projector.go`）；main/alternate buffer 切换与 epoch 变化目前靠 `FrameDeriver` 的 baseline 比较（`ActiveBuffer`/`Epoch`/`DictionaryGeneration` 不匹配）触发 Snapshot，不经过 ForceSnapshot。实施时需把这些触发点统一改为推进 `SnapshotBarrierRevision`。

实施说明（2026-07-14，Task 2）：barrier 的单调性只在同一 epoch 内成立——epoch 变化时 ChangeIndex 整体重置，barrier 设为新 epoch 首个导出 revision（§6 的 epoch 校验先于 barrier 判定，跨 epoch resume 永远走不到 barrier 判定）。NewProjector 后首次导出视为"projector/model 整体重建"事件，barrier 初值取首次导出 revision。历史 LineID 体系重置目前以 scrollback NextID 回退为探测 seam（`Clear`/`ResetForReflow` 暂无生产调用路径，属前置保守实现）。

### 4.3 ChangeIndex

```go
type ChangeIndex struct {
    SnapshotBarrierRevision uint64
    RowChangedRevision      []uint64
    CursorChangedRevision   uint64
    ModesChangedRevision    uint64
    PaletteChangedRevision  uint64
    TitleChangedRevision    uint64
    CWDChangedRevision      uint64
    StyleCreatedRevision    []uint64
    LinkCreatedRevision     []uint64
}
```

规则：

1. dirty row 合并进 projected state 时，把对应 row revision 设为当前导出 revision。
2. 元数据与上一权威投影比较，只在值变化时推进对应 revision。
3. style/link 字典 epoch 内只追加，新增项记录 created revision。
4. 字典重建推进 barrier，再重建 created-revision 索引。
5. change index 只在 Projector 锁/terminal actor 所有权下读写。
6. 同一组件在一次 16ms 合并窗口内多次变化，只保留最终值和最终 revision。
7. 变化后又恢复原值时，允许发送冗余最终状态；不能因值相等而错误声称客户端必然已经看到中间变化。

## 5. 历史窗口恢复

当前视口行不足以恢复断线期间新增的 scrollback，因此历史单独维护有界版本索引：

```go
type HistoryChange struct {
    LineID          uint64
    CreatedRevision uint64
}
```

### 5.1 服务端索引

1. 新行进入 `TrackedScrollback` 后，在 terminal actor 顺序内记录 `LineID -> CreatedRevision`。
2. 索引与 authoritative scrollback 同步 trim，不得比权威 scrollback 窗口活得更久。注意 `TrackedScrollback` 受行数上限（默认 10000）与字节预算（默认 8 MiB）双重限制、先到先驱逐，并不保证 10000 行驻留；trim 同步必须锚定实际 trim 事件，而非固定行数。
3. 索引只保存 LineID/revision；实际 Cell 从 authoritative scrollback 导出。
4. 如果一次 flush 跨过未被索引捕获的 LineID，立即标记 history gap；不能静默少发。
5. 恢复时选择 `CreatedRevision > clientRevision` 的仍存活行，按 LineID 连续顺序加入 `history_append`。

### 5.2 恢复 Patch 的原子水位

恢复 Patch 同时携带当前 `first_available_history_line_id`。Android 在一次 model executor 事务内按以下顺序应用：

```text
1. 校验 instance/epoch/baseRevision
2. 推进 firstAvailableHistoryLineId（只允许单调增加）
3. 删除低于水位的本地历史
4. 丢弃 history_append 中低于水位的行
5. 验证剩余 LineID 连续且无重复
6. 追加历史和更新 screen/metadata
7. 最后提交 screenRevision
```

正常实时流仍可使用独立 `HistoryTrim`；但 resume 首帧的正确性不依赖 control queue 与 screen mailbox 的相对顺序。

### 5.3 History 降级条件

以下任一情况退回 Snapshot：

- history revision index 存在缺口；
- 待追加行超过协议上限；
- LineID 不连续或重复；
- 索引已因内存上限提前淘汰；
- buffer/epoch 不一致；
- Patch 编码后超过 envelope 上限。

第一版不新增 HistoryReset。Snapshot 携带当前活动屏幕和最近 history tail；更老历史继续使用现有分页接口。

## 6. 服务端恢复判定

Hello 的 resume token 必须在 terminal actor 内与当前权威 state 一起判定：

```text
if hasProjection == false
    Snapshot(reason=cold)

else if instanceId != current.instanceId
    Snapshot(reason=instance)

else if layoutEpoch != current.layoutEpoch
    Snapshot(reason=epoch)

else if clientRevision > currentRevision
    Snapshot(reason=future_revision)

else if clientRevision < snapshotBarrierRevision
    Snapshot(reason=barrier)

else if clientRevision == currentRevision
    seed baseline=current
    ResumeAck(current version)

else
    尝试生成累计 Patch
```

累计 Patch：

```text
kind           = PATCH
baseRevision   = clientRevision
screenRevision = currentRevision
screenRows     = RowChangedRevision > clientRevision 的行的当前最终值
cursor         = CursorChangedRevision > clientRevision 时出现
modes          = ModesChangedRevision > clientRevision 时出现
palette        = PaletteChangedRevision > clientRevision 时出现
title/cwd      = 对应 changed revision > clientRevision 时以 optional 字段出现
newStyles      = CreatedRevision > clientRevision 的字典项
newLinks       = CreatedRevision > clientRevision 的字典项
historyAppend  = CreatedRevision > clientRevision 且连续可用的历史行
historyWatermark = 当前 firstAvailableHistoryLineId
```

Patch 可以从 revision 100 直接跳到 150；不回放 101..149。只有最终状态必须完整表达。

实施说明（2026-07-14，Task 2）：cursor/modes/palette 第一版在累计 Patch 中**总是携带当前值**（沿用在线 diff 的既有 wire 语义，encoder 对 patch 总是编码这三个组件）；各组件的 ChangedRevision 仅用于空 Patch 判定与 §13 指标，未来引入显式 presence 时再按 `revision > clientRevision` 决定是否出现。另外，revision 有 gap 但 gap 内无任何可观察变化（bell 等只推进 revision 的输出）时按 exact resume 处理——合法 Patch 必须满足 `screenRevision > baseRevision` 且 §10.1 禁止空 Patch，由 Task 4 以 `ResumeAck(currentRevision)` 直接把客户端推进到 currentRevision。推导实现为纯函数 `screenprojection.DeriveResumeFrame`。

### 6.1 成本降级

1. 快速判断：变化活动行超过 60% 时优先 Snapshot。
2. attach/resume 慢路径同时编码候选 Patch 与 Snapshot；当 `patchBytes >= snapshotBytes * 0.8` 时发送 Snapshot。
3. 比较只发生在恢复首帧，不进入 16ms 实时 flush 热路径。
4. 阈值由 benchmark 和真机流量结果校准；不能凭经验永久固定。

## 7. Android 投影健康度与同步状态

### 7.1 ProjectionHealth

`RemoteTerminalModel` 在 model executor（单线程 executor 归 `TerminalSessionRuntime` 所有；model 自身以 `synchronized` 保证线程安全）内维护不可变健康快照：

```text
ProjectionHealth {
    complete
    instanceId
    layoutEpoch
    screenRevision
    schemaGeneration
}
```

只有以下条件全部成立才允许 `hasProjection=true`：

- 成功应用过完整 Snapshot；
- instanceId 非空，epoch/revision 有效；
- screen 行数与 geometry 一致；
- style/link 引用完整；
- 不在 resync fence；
- 没有部分应用失败；
- mailbox 没有已确认的 gap/overflow；
- runtime 未 close/evict；
- auth generation 和 logical identity 仍匹配。

ResumeToken 必须由 model executor 原子产生，连接层不能分别读取多个可变字段自行拼装。

### 7.2 连接状态机

Android 状态改为：

```text
CONNECTING
  → TRANSPORT_CONNECTED
  → SYNCING
  → CONNECTED

失败：
  → RECONNECTING
  → SYNCING
```

现状：连接状态枚举位于 `TerminalSessionRuntime.State`（CONNECTING/CONNECTED/RECONNECTING/CLOSED），`ScreenMuxConnection` 自身不持有状态机、只转发回调；本节改造点在 `TerminalSessionRuntime` 及其 ChannelListener 回调映射。

进入 CONNECTED 的唯一条件：

- 收到并校验 `ResumeAck`；或
- 成功原子应用恢复 Patch；或
- 成功原子应用 Snapshot。

SYNCING 期间：

- 不向 UI 声称终端已经同步；
- 不允许用户输入获取交互 lease；
- resize 只记录最新期望值，待同步完成并取得 lease 后发送；
- 超时进入一次 fresh Snapshot resync，不能无限循环。

## 8. Runtime 生命周期：HOT / WARM / COLD

不再使用“所有打开过的终端永久保持连接”的绝对策略。

### 8.1 状态定义

```text
HOT
  model + screen channel 都存在，后台持续接收 Patch
  重新 attach：0 网络恢复

WARM
  完整 model 保留，screen channel 已关闭
  重新 attach：Hello resume，Ack/Patch/Snapshot

COLD
  model 已淘汰或 App 进程重启
  重新 attach：hasProjection=false → Snapshot
```

### 8.2 默认策略

- 当前可见会话始终 HOT。
- View detach 后保留 HOT grace period，初始默认 60 秒。
- 最多 3 个 HOT runtime；超过后最久未使用且无 lease 的会话转 WARM。
- 最多 5 个 WARM model，并同时受全局 history 字节预算限制。
- App 进入后台或屏幕熄灭后 30 秒，将不可见 HOT 会话转 WARM；设备级通知连接仍由 `WebTermDeviceService` 保持。
- 内存压力回调优先 WARM→COLD，再 HOT→WARM；当前可见会话不淘汰。
- 处于 SYNCING、持有 layout lease、显式关闭流程中的 runtime 不参与普通 LRU。

阈值集中为可测试常量，真机测量后调整。完成标准中的“页面切换 0 Snapshot”只适用于 HOT grace period 内、未被资源策略淘汰的会话。

### 8.3 Registry 身份

Registry key 至少包含：

```text
serverConfigId
authGeneration/accountIdentity
normalizedBaseUrl
relayDeviceId
sessionId
```

- cookie refresh 不改变 auth generation；账号切换必须改变。
- logout 原子关闭该 auth generation 下全部 runtime。
- 删除服务器关闭对应 serverConfigId 下全部 runtime。
- Snapshot 到达后再次校验 instanceId；不匹配时原子替换旧模型。

## 9. View 与副作用所有权

### 9.1 View detach

`View detach` 只做：

- controller 移除 model listener；
- 保存 session-scoped viewport anchor/selection；
- 发送 focus=false；
- 释放 layout lease；
- 进入 HOT grace period。

不得关闭 channel，不得调用 `resetForReconnect()`。

注：`docs/superpowers/specs/2026-07-09-android-terminal-connection-refactor-design.md` 曾定义“detach 保留 channel”的语义，但现码在 View detach 时仍会经 `RemoteTerminalIntegration.stop()` 关闭连接并重置 model；本节恢复该设计意图，并扩展为 HOT/WARM/COLD 生命周期。

### 9.2 session-scoped UI 状态

- 普通 detach/attach 保留 viewport anchor、follow-tail 和未失效 selection。
- LayoutEpoch 或 instance 变化清除 selection 和旧 screen anchor。
- history trim 删除 anchor 时回退到最近可用行并提示。
- 字体和输入设置继续使用用户设置，不放进 terminal model。

### 9.3 持久 effect sink

Runtime 必须拥有不依赖 Activity 的 effect sink：

```text
title/cwd       → 更新 model，必要时更新首页摘要
notification    → AndroidNotificationRenderer / DeviceService
bell            → 当前可见时 UI；后台按产品策略处理
clipboard read  → 有前台交互上下文才询问，否则拒绝或超时
clipboard write → 按权限策略处理，不依赖 TerminalFragment 存活
exit            → 标记 CLOSED、从 registry 移除、通知首页
```

页面 listener 只是附加观察者，不能成为副作用的唯一消费者。effect 是 fire-and-forget，不在 resume 时重放；title/cwd/palette 等持久状态必须同时存在于 Snapshot/Patch。

## 10. 严格接收与资源验证

### 10.1 Patch 结构校验

Go 和 Android validator 同步增加：

- `instance_id` 非空且长度受限；
- `layout_epoch >= 1`；
- `base_revision >= 1`；
- `screen_revision > base_revision`；
- screen row 数量 `<= rows`；
- row index 位于 `[0, rows)` 且不重复；
- `history_append` 数量受限；
- history LineID 严格递增、不重复；
- history cell/run/style/link 引用合法；
- promoted row 范围和映射不重复；
- title/cwd UTF-8 和长度合法；
- style/link 数量、ID 和 URI 合法；
- envelope 总大小 `<= 2 MiB`；
- metadata-only Patch 至少包含一个实际变化字段。

混合版本容忍（§15，2026-07-14 实施时补充）：旧 Go Agent（Task 0 之前）仍在野期间，接收端暂缓执行其中两条——`layout_epoch >= 1`（旧 Go 初始 epoch 为 0，其 patch 合法）与"metadata-only Patch 至少一个变化字段"（旧 Go 可能因 bell 等输出发送空 patch，接收端按 no-op 容忍）。空 patch 的抑制由新版服务端在源头保证（`FrameDeriver` 对无可观察变化的 diff 返回不发送信号，且不推进 client baseline）。待混合版本窗口结束（§15 第 6 步）后接收端再收紧为拒绝。

### 10.2 Android 原子应用

`RemoteTerminalModel.applyPatch()` 在任何 mutation 之前验证：

```text
patch.instanceId == model.instanceId
patch.layoutEpoch == model.layoutEpoch
patch.baseRevision == model.screenRevision
patch.screenRevision > patch.baseRevision
```

然后在一个 synchronized/model-executor 事务内应用历史水位、历史追加、屏幕行、字典和元数据，最后提交 revision。任何失败都不得留下部分 mutation。

失败处理：

- 丢弃整帧；
- 不推进本地 revision；
- 进入单一 resync fence；
- 只发送一次 ResyncRequest；
- 只允许权威 Snapshot 解除 fence。

现状说明：`RemoteTerminalModel.applyPatch()` 已有 instanceId/layoutEpoch/baseRevision 前置校验（不匹配抛 `RevisionGapException`），`TerminalSessionRuntime` 已有 Resync 围栏与重试/退避机制（最多重试 3 次后退化为重建 channel）。本节为**增强**而非新建：补充 `screenRevision > baseRevision` 严格校验、history watermark 与 `history_append` 的事务式应用、optional 字段三态处理，并把失败路径收敛为本节定义的单一 resync fence。

## 11. Go 并发、首帧与提交点

1. ChangeIndex、HistoryChangeIndex 和 attach 判定归 terminal actor/Projector 锁所有。
2. `handleClientAttach()` 不再调用 `nextRevision()`。
3. `handleClientResync()` 使用当前 revision 发送 Snapshot，不推进 revision。
4. ResumeAck 与 Patch/Snapshot 走同一个串行 screen writer 和 initial-sync slot；由于 exact resume 没有 screen frame，writer 在成功写出 Ack 后提交 current baseline。
5. Patch/Snapshot 恢复首帧进入不可覆盖的 initial-sync slot，不得直接放入可被实时状态覆盖的单槽 mailbox。
6. initial frame 编码并成功写入后，才提交 client baseline，并开放实时 mailbox。
7. initial frame 写失败时关闭 client，禁止使用未送达 revision 派生后续 Patch。
8. initial frame 等待期间的最新 canonical state 合并到首帧，或在首帧成功后从已提交 baseline 派生下一帧；不能形成两条链。
9. 正常实时 mailbox 继续允许覆盖中间完整 state，但每个 Patch 必须基于最后实际写出的 revision。
10. auth refresh callback 绑定完整 runtime key 和 generation，过期回调丢弃。

## 12. 实施任务

### Task 0：协议 presence、FrameKind 与版本契约

**修改：**

- `shared/proto/terminal_screen.proto`
- `scripts/generate-proto.sh` 生成的 Go/Java 文件
- `go-core/internal/terminalengine/export.go`
- `go-core/internal/screenprotocol/encoder.go`
- Go 端所有 `ScreenPatch.title/working_directory` 读写点（optional 化后生成代码由 `string` 变为 `*string`，不同步修改将无法编译）
- Go/Android validator 与 mapper

**验收：** 空标题/cwd可以被清除；Snapshot/Patch 不依赖 base=0 判断；初始 epoch/revision 固定为 1；ResumeAck 可跨语言解析。

### Task 1：冻结协议与恢复契约测试

新增测试覆盖：

- optional title/cwd 的 absent、非空、清空三态；
- exact ResumeAck；
- revision 跳跃累计 Patch；
- instance/epoch/barrier/future revision Snapshot；
- attach/resync 不推进 revision；
- strict `screenRevision > baseRevision`；
- malformed Hello、重复 Hello；
- History watermark 与 Patch 乱序无关；
- envelope/row/history/style/link 资源上限。

实施状态（2026-07-14，分支 feat/screen-delta-resume）：optional 三态与 ResumeAck wire 已在 Task 0 落地；malformed Hello、重复 Hello 拒绝、strict `screenRevision > baseRevision`、envelope/row/history/style/link 资源上限、空 patch 源头抑制已在本任务落地并测试。revision 跳跃累计 Patch、instance/epoch/barrier/future revision Snapshot、attach/resync 不推进 revision、History watermark 乱序无关四项依赖 Task 2–6 的机制，已以 Go `t.Skip`（`session/resume_contract_test.go`）与 Android `@Ignore`（`ScreenResumeContractTest`）桩冻结，随对应任务启用。

### Task 2：Projector ChangeIndex 与持久 barrier

**修改：**

- `go-core/internal/screenprojection/projector.go`
- `go-core/internal/terminalengine/export.go`
- projector tests/benchmarks

实现 row/metadata/style/link revisions、持久 barrier 和纯函数式 resume derivation。正常在线 FrameDeriver 热路径先保持，避免一次性重写稳定路径。

### Task 3：HistoryChangeIndex

**修改：**

- `go-core/internal/terminalsession/runtime.go`
- `go-core/internal/terminalengine` tracked scrollback
- `go-core/internal/screenprojection/history.go`（已存在，现为 `HistoryView` 分页查询，在其基础上扩展）

实现 LineID/revision 绑定、trim 同步、连续查询、缺口显式失败和恢复 Patch 水位。

### Task 4：Hello 驱动 actor 内初始同步

**修改：**

- `go-core/internal/session/client.go`
- `go-core/internal/terminalsession/runtime.go`
- `go-core/internal/screenprotocol/handler.go`

实现一次性 Hello、actor 判定、ResumeAck、不可覆盖 initial-sync slot、成功写入后提交 baseline，以及 Resync 的同 revision Snapshot。

### Task 5：Android ProjectionHealth、ResumeToken 和 SYNCING

**修改：**

- `terminal-model/RemoteTerminalModel.java`
- `feature/terminal/TerminalSessionRuntime.java`
- 新增 UI 无关的 ResumeToken/ProjectionHealth 类型

实现原子 token、SYNCING 状态、恢复完成门、超时 fresh Snapshot 和输入/lease 门控。

### Task 6：Android 完整 Hello 与原子 Patch

**修改：**

- `terminal-protocol/ScreenMessageBuilder.java`
- `terminal-protocol/ScreenMessageMapper.java`
- `feature/terminal/ScreenMuxConnection.java`
- `terminal-model/RemoteTerminalModel.java`

实现完整 Hello、ResumeAck 解析、optional 字段、history watermark、严格预校验和事务式 Patch 应用。`applyPatch()` 的原子校验与 Resync 机制已有基础（见 §10.2 现状说明），本任务为增强而非新建。

### Task 7：Runtime 所有权与 HOT/WARM/COLD

**修改：**

- `RemoteTerminalIntegration.java`
- `TerminalSessionRuntime.java`
- `TerminalSessionRuntimeRegistry.java`
- `TerminalScreenController.java`
- `ScreenMuxConnection.java`
- Application/DeviceService effect routing

实现 connection/runtime 所有权迁移、完整 registry key、grace/LRU/内存压力策略、viewport state 和持久 effect sink。

### Task 8：兼容发布、指标和真机回归

实现结构化日志、混合版本测试、运行时降级开关和真机流量/电量验收。

## 13. 可观测性

结构化字段：

```text
resume_decision=exact|patch|snapshot
resume_reason=cold|instance|epoch|future_revision|barrier|history_gap|patch_size|resync|timeout
client_revision
server_revision
snapshot_barrier_revision
changed_rows
history_append_lines
patch_bytes
snapshot_bytes
runtime_state=hot|warm|cold
sync_state=connecting|syncing|connected|reconnecting
```

禁止记录终端文本、剪贴板正文和通知正文。增加计数器：页面 reattach、exact resume、累计 Patch、Snapshot 降级、resync、sync timeout、HOT→WARM、WARM→COLD。

## 14. 验证矩阵

### 14.1 Go

```sh
cd go-core
go test ./internal/screenprotocol ./internal/screenprojection ./internal/terminalsession ./internal/session
go test -race ./internal/screenprojection ./internal/terminalsession ./internal/session
go test ./...
```

Benchmark：0/1/10/60/100% 行变化、cursor-only、title clear、300/500 行 history append、history gap、字典追加/轮转、Patch/Snapshot 双编码成本、多 client。

### 14.2 Android

```sh
cd android-client
./gradlew :terminal-model:testDebugUnitTest \
  :terminal-protocol:testDebugUnitTest \
  :feature:terminal:testDebugUnitTest \
  :core-session:testDebugUnitTest \
  :app:compileDebugJavaWithJavac \
  :app:assembleDebug --no-daemon
```

### 14.3 真机

1. HOT grace 内 A/B 会话切换 50 次：不新建 channel、不请求 Snapshot。
2. HOT→WARM 后重开：分别覆盖 ResumeAck、累计 Patch、Snapshot。
3. WARM→COLD 后重开：`hasProjection=false`，Snapshot。
4. 熄屏/后台：不可见 terminal channel 按策略转 WARM，设备通知连接继续工作。
5. 短断网少量输出：累计 Patch。
6. 长断网 history gap：Snapshot。
7. 断线期间 resize：epoch Snapshot。
8. main/alternate 切换：barrier Snapshot。
9. Agent/PTY 重启：instance Snapshot。
10. title/cwd 从非空变为空：Android 正确清除。
11. HistoryTrim 与 resume Patch 人为交换顺序：结果一致。
12. 故意破坏 base/revision/row/history：整帧拒绝，只触发一次 Resync。
13. 同设备多终端：共享 mux 不被单 terminal reconnect 替换。
14. logout/login 另一账号：不复用旧 runtime/model。
15. 记录流量、电量、Patch/Snapshot 比例和 sync 延迟。

## 15. 混合版本发布

兼容矩阵：

- 旧 Android → 新 Go：缺少 resume token，按 cold Snapshot。
- 新 Android → 旧 Go：旧 Go忽略新增 Hello 字段并发送 Snapshot；新 Android 以 Snapshot 完成 SYNCING。
- 新 Android → 新 Go：启用 ResumeAck/Patch/Snapshot 判定。
- Relay 不参与 screen payload 判定，无需与两端同版本发布。

发布顺序：

1. 先发布兼容旧 Android 的 Go Agent。
2. 观察旧客户端 Snapshot 路径无回退。
3. 再发布新 Android。
4. 新 Android 在 SYNCING 超时后强制 `hasProjection=false` 重连一次。
5. 稳定期保留 `WEBTERM_SCREEN_RESUME=0` / Android remote-config 等价降级开关；只关闭增量恢复，不回退 screen-only 架构。
6. 指标稳定后删除临时开关和旧兼容分支。

## 16. 提交拆分

1. `test(screen): freeze resume and field-presence contract`
2. `feat(screen-proto): add resume ack and patch field presence`
3. `refactor(screen): make frame kind and initial revisions explicit`
4. `feat(screen): track component revisions and snapshot barrier`
5. `feat(screen): index history revisions and atomic watermark`
6. `feat(screen): derive initial sync from hello resume token`
7. `feat(android): add projection health and syncing state`
8. `feat(android): apply cumulative resume patch atomically`
9. `refactor(android): retain runtime with hot warm cold lifecycle`
10. `feat(android): route detached terminal effects through service`
11. `test(screen): cover mixed versions and physical-device resume`
12. `docs(screen): record rollout metrics and final thresholds`

每个提交保持对应范围可编译；proto、生成代码、mapper 和跨语言 fixture 必须同提交更新。

## 17. 完成标准

- HOT 会话 View detach 不关闭 runtime、不清模型；grace 内重新 attach 为 0 网络恢复。
- WARM 会话通过 ResumeAck、累计 Patch 或安全 Snapshot 完成同步。
- COLD 会话只发送 `hasProjection=false` 并请求 Snapshot。
- attach/resync/Snapshot 不推进 canonical revision。
- 任何 Patch 满足 `baseRevision == Android 当前 revision` 且 `screenRevision > baseRevision`。
- history watermark 与恢复 Patch 原子应用，不会复活已 trim 行。
- title/cwd 空值、cursor、modes、palette、字典和 history 均能正确增量恢复。
- Android 只有在 Ack/Patch/Snapshot 成功后进入 CONNECTED。
- 页面不存在时 effect 仍有持久处理者。
- auth/account/server identity 不会误复用 Runtime。
- malformed/超大 Patch 被两端一致拒绝且不产生部分 mutation。
- 新旧 Go/Android 组合均有明确降级路径。
- Relay 无 terminal 状态缓存。
- Go 全量测试、目标包 race、Android 单测与 Debug APK 构建通过。
