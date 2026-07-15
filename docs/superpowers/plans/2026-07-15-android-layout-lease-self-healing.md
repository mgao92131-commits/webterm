# Android 终端输入租约自愈修复计划

**日期：** 2026-07-15  
**状态：** 核心修复完成，等待长期真机观察
**范围：** Go terminal runtime、screen protocol 响应、Android terminal runtime、回归脚本与诊断接口  
**目标：** 消除“单个终端仍能收到输出/标题，但输入永久无效；反复退出重开仍无效，稍后又自行恢复”的 Layout Lease 生命周期故障。

## 1. 现场事实与当前判断

已经观察到的事实：

1. 故障只影响单个终端会话，其他会话仍可正常输入。
2. 故障期间标题仍会更新，部分情况下终端显示也继续变化，说明 screen 下行链路和 PTY 会话仍然存活。
3. 返回会话列表再打开、关闭页面再打开，不能稳定恢复输入；等待一段时间后可能自行恢复。
4. Agent 日志出现过重复 Screen Hello 导致旧 client 关闭，也出现过连续的 `input requires layout lease`。
5. Android 在没有有效 Lease 时直接丢弃输入；收到 `LayoutLease(granted=false)` 后只清空本地状态，不重试。
6. Go 端 Lease 默认 TTL 为 5 分钟，只在成功校验输入/resize 时延长；过期或不匹配时静默拒绝。
7. View detach 后 runtime 保持 HOT，重新进入页面可能复用同一 screen channel，因此“重开页面”不等价于“重建连接并重新同步”。

当前最可信的故障链是：旧 Screen Client 尚未完成 detach 或成为幽灵 owner，新 Client 的一次性 Acquire 被拒绝；Android 进入无 Lease 状态后没有重试。旧 owner 后续 detach、TTL 到期或 mux 再次重连时，新的申请才偶然成功。

本计划把根因定义为 **Layout Lease 生命周期缺少可关联、可续租、可撤销和可重试的自愈闭环**。实现时应先用确定性测试复现该闭环，不能把上述时序当作未经验证的唯一触发场景。

## 2. 修复不变量

实施完成后必须满足以下不变量：

1. 页面可见且 screen 已同步时，Android 最终只能处于 `ACQUIRING` 或 `HELD`，不能因一次拒绝永久停在无 Lease 状态。
2. 页面 detach、runtime 转 WARM、连接断开或关闭后，所有在途 Acquire、续租和重试回调必须失效，不能把已离开的页面重新变成交互 owner。
3. 同一 Screen Client 重复 Acquire 是幂等续租：复用原 Lease ID，只延长过期时间。
4. 不同 Client 不能强行抢占尚未过期的交互 Lease；观察者语义保持不变。
5. 每个 Acquire 响应必须与请求代次关联；迟到的拒绝或授予不能覆盖当前 Lease。
6. Lease 过期或失效后，服务端必须向对应 Screen Client 发出一次明确的 Lease 失效响应，不能继续静默丢输入。
7. Android 不得发送空 Lease ID 的 input、focus 或 resize 帧。
8. 输出、标题、历史同步和 Lease 恢复彼此独立；恢复输入不得重置终端投影或制造第二条 mux 物理连接。

## 3. 协议使用策略

本次不修改 `webterm.screen.v1` 的字段编号或协议版本，直接启用已有字段：

- `AcquireLayout.request_id`：Android 为每次申请或续租生成单调递增、连接代次唯一的请求 ID。
- `LayoutLease.request_id`：Go 原样回显，用于 Android 丢弃迟到响应。
- `LayoutLease.expires_at_ms`：授予时返回服务端绝对过期时间，Android 据此安排提前续租。
- `LayoutLease.granted=false` 且 `request_id` 非空：对应某次 Acquire 被拒绝。
- `LayoutLease.granted=false` 且 `request_id` 为空：服务端主动通知当前 Lease 已失效。

兼容策略：旧客户端发送空 `request_id` 时仍可申请；旧服务端不返回 `expires_at_ms` 时，新 Android 使用保守的本地续租周期。由于 wire schema 不变，不重新生成 protobuf 文件。

## 4. Go：LeaseManager 语义收敛

### 4.1 可测试时钟与结果对象

改造 `go-core/internal/terminalsession/layout_lease.go`：

- 为 LeaseManager 注入可替换时钟和 TTL，生产默认仍为 5 分钟；
- Acquire 返回结构化结果：`leaseID`、`granted`、`interactive`、`expiresAt`；
- 测试不使用真实的五分钟 sleep；
- 过期 Lease 在 Acquire、Validate、Owner 等入口统一惰性清理，不能留下“Owner 非空但 Lease 已不可用”的状态。

### 4.2 幂等续租

Acquire 规则：

- 当前无有效 Lease：授予新 Lease；
- 当前 owner 与申请 client 相同：复用原 Lease ID，更新 interactive 和 expiresAt；
- 当前 owner 不同且 Lease 未过期：拒绝，不抢占；
- 当前 Lease 已过期：先清理，再授予申请者。

Validate 只验证并在成功输入时延长 TTL；续租的权威入口仍是 Acquire，便于客户端在空闲终端上维持交互权。

### 4.3 Runtime 的失效通知与顺序

改造 `go-core/internal/terminalsession/runtime.go` 与 `go-core/internal/session/client.go`：

- Acquire event 携带并回显 `request_id`；
- ScreenClient 增加发送 Lease 状态的回调，由 runtime actor 统一发出；
- semantic input、resize、focus/clipboard 等路径发现 Lease 无效时：
  - 清空该 client 保存的 Lease ID；
  - 对同一个失效代次只发送一次主动 `granted=false`；
  - 输入仍然不写入 PTY，但不再静默失败；
- client detach 在 runtime actor 内释放 Lease 后再删除 client；
- 新 client 被拒绝后不由服务端排队或自动抢占，等待客户端重试，避免服务端保存悬空 waiter。

主动失效通知需要去重，防止用户连续按键产生响应风暴。

## 5. Android：显式 Lease 状态机

### 5.1 状态与所有权

在 `TerminalSessionRuntime` 中用一个由 `modelExecutor` 独占推进的状态机替换分散的 `layoutLeaseGranted/layoutLeaseId` 判断：

```text
DETACHED
   │ attach + CONNECTED
   ▼
ACQUIRING ── granted(current request) ──► HELD
   ▲              │                         │
   │ denied       │ stale response: ignore  │ renew deadline
   └──────────────┘                         ▼
                                      ACQUIRING/HELD

任意状态 -- detach/disconnect/close/generation change --> DETACHED
```

状态至少保存：

- `pageAttached`；
- `leaseGeneration`；
- `pendingRequestId`；
- `leaseId`；
- `expiresAtMs`；
- `retryAttempt`。

所有 scheduler 回调携带 generation；generation 不匹配、页面不可见、连接非 CONNECTED 时直接失效。

### 5.2 申请被拒后的恢复

- 首次同步完成和 HOT reattach 都通过同一个 `ensureLayoutLease()` 入口申请；
- 被拒后按 `250ms → 500ms → 1s → 2s` 退避，之后保持 2 秒上限并加入小幅抖动；
- 重试只在页面可见、连接 CONNECTED 且没有在途请求时运行；
- detach、disconnect、WARM、close 立即取消逻辑代次；
- 重试不调用 `requestReconnect()`，不能为了 Lease 竞争制造新的 mux/channel。

这允许旧 client detach 后当前页面自动拿到 Lease，同时保持另一台真正活跃设备的控制权不被抢占。

### 5.3 续租与过期防线

- 服务端返回 `expires_at_ms` 时，在“剩余 TTL 的一半”或“到期前 60 秒”两者中取更早时间续租，并设置最小/最大调度边界；
- 服务端未返回过期时间时，每 2 分钟发送一次幂等 Acquire；
- 续租期间保留当前 HELD Lease，成功响应应返回同一 Lease ID；
- 到达本地过期时间仍未续租成功时，立即清空 Lease、停止输入并进入 ACQUIRING；
- 收到服务端主动 `granted=false` 时同样进入 ACQUIRING。

### 5.4 输入边界与可观察性

- `TerminalSessionRuntime` 仍是输入是否允许的唯一判断者；
- `ScreenMuxConnection` 再增加一层非空 Lease 防线，禁止构造空 Lease input/focus/resize；
- 增加 Lease 状态诊断计数：acquire、denied、retry、renew、revoked、stale-response；
- 在连接正常但 Lease 尚未取得时，通过现有 runtime listener 暴露“正在恢复输入控制权”状态，终端页面显示轻量提示；取得 Lease 后自动消失，不弹阻塞对话框。

输入内容不得写入日志；诊断只记录 session、client、request/lease 的截断标识、状态与原因。

## 6. 测试计划

### 6.1 Go 单元测试

新增 `layout_lease_test.go`，覆盖：

1. 首次 Acquire 授予新 Lease 和 expiresAt；
2. 同 owner 重复 Acquire 复用 Lease ID 并延长 TTL；
3. 另一 owner 在 TTL 内被拒绝；
4. TTL 到期后旧状态被清理，新 owner 可立即获得；
5. Release 只能释放匹配 Lease；
6. 旧 Lease 在续租后不能误释放新状态。

扩展 runtime/session 测试，覆盖：

1. Client A 持有 Lease，Client B Acquire 被拒；A detach 后 B 下一次 Acquire 成功；
2. Acquire 响应回显 request ID 和 expiresAt；
3. 无效/过期 input 只触发一次主动失效通知且不写 PTY；
4. detach 与 Acquire 以两种 actor 排序执行，最终状态都可恢复；
5. 重复 Hello 关闭旧 channel 后 Lease 最终释放。

### 6.2 Android JVM 测试

在 `TerminalSessionRuntime` 测试中使用 fake scheduler/clock，覆盖：

1. 同步完成只发一次 Acquire；
2. `granted=false` 后按退避重试，后续 granted 自动恢复输入；
3. 迟到的旧 request granted/denied 均不能覆盖当前状态；
4. detach 后排队的重试和续租不再发送；
5. disconnect/reconnect 使旧 generation 全部失效，新同步重新申请；
6. HOT detach/attach 重新申请，不需要重建 channel；
7. 到期前续租，续租时 Lease ID 不被提前清空；
8. 本地到期仍无响应时停止输入并重新申请；
9. 主动 revoked 后进入恢复；
10. 无 Lease 时 connection 不发送 input/focus/resize；
11. 取得 Lease 后补发最新 resize，且只补发一次；
12. 页面不可见时，即使收到迟到 granted 也不恢复交互权。

### 6.3 协议兼容测试

- 新 Android ↔ 新 Go：request ID、expiresAt、续租完整生效；
- 旧式空 request ID ↔ 新 Go：仍可获取 Lease；
- 新 Android 收到不含 expiresAt 的 granted：进入保守续租；
- 现有 relay E2E smoke 的 Hello、Acquire、input 契约继续通过。

## 7. 真机复现与验收

扩展 `scripts/repro-relay-android-resume-stuck.sh`，加入 Lease 场景而不只检查 screen resume：

1. 在测试会话上建立 Client A 并持有 Lease；
2. 让 Android Client B 打开同一会话，确认收到 denied 且输出/标题仍可更新；
3. 断开 A，不触发 Android 页面重建；
4. 要求 B 在 3 秒内通过重试自动获得 Lease并成功写入唯一标记；
5. 连续执行返回列表/重开、前后台切换、Relay 断开重连各 20 轮；
6. 使用 `/control/sessions/{id}/screen/input-trace` 验证输入经过 `encoded → pty-write`；
7. 观察目标 session client 数始终收敛到预期值，不持续增长；
8. 用可配置的短测试 TTL 验证空闲跨 TTL 后仍可首次输入，生产 TTL 保持 5 分钟。

真机完成标准：

- 页面可见且旧 owner 已离开后，输入恢复时间不超过 3 秒；
- 不需要退出页面、杀 App 或重启 Agent；
- 不出现持续的 `input requires layout lease`；
- 标题、输出、历史与 selection 状态不因 Lease 恢复被重置；
- 另一台仍持有有效 Lease 时，新页面只显示等待状态，不抢占控制权；
- Android release 构建、物理手机安装、Agent 中转重启后再次完成上述回归。

## 8. 实施顺序

- [x] Task 1：先补 Go fake-clock LeaseManager 测试和 Android fake-scheduler 失败复现，证明现有代码会永久停在无 Lease 状态。
- [x] Task 2：实现 Go LeaseManager 幂等续租、过期清理和结构化结果。
- [x] Task 3：贯通 request ID、expiresAt 和主动失效通知，补 session/runtime 测试。
- [x] Task 4：实现 Android 代次化 Lease 状态机、拒绝重试和提前续租。
- [x] Task 5：收紧 ScreenMuxConnection 空 Lease 防线并增加非阻塞 UI 状态提示。
- [ ] Task 6：扩展真机复现脚本和本地控制诊断，完成竞态与 TTL 自动化验收。
- [x] Task 7：运行 Go 全量测试、Android 全量单测、release 构建、物理手机安装及中转 Agent 重启回归。
- [x] Task 8：审查协议兼容、日志隐私、scheduler 取消语义和零残留布尔旧路径，记录完成证据。

## 9. 验证命令

```sh
cd go-core && go test ./internal/terminalsession ./internal/session
cd go-core && go test ./...

cd android-client
./gradlew :feature:terminal:testDebugUnitTest --no-daemon
./gradlew test --no-daemon
./gradlew :app:assembleRelease --no-daemon

(cd go-core && go run ./cmd/webterm-relay-e2e-smoke)
scripts/repro-relay-android-resume-stuck.sh
git diff --check
```

真机安装和 Agent 重启沿用仓库现有中转脚本；运行时以控制端点显示 Relay 已连接、目标 session client 数收敛、input trace 出现 PTY 写入为最终证据，不能只以构建成功作为完成信号。

## 10. 本轮完成记录

2026-07-15 完成核心修复与交付：

- `go test ./...` 在正常主机权限下全部通过；
- Android `./gradlew test --no-daemon` 全部通过；
- Android clean release 构建通过；
- 发布版已覆盖安装到实体设备 `V2507A` 并启动；
- relay 模式 Go Agent 已重建重启，控制端显示 `relay.connected=true`、`deviceId=d2`；
- 本次没有修改 Relay 服务端路由或帧协议，不需要重新部署服务器 Relay；
- Task 6 的自动化双 client/短 TTL 真机脚本仍待补充，当前交付需要继续观察原偶发场景是否彻底消失。

## 11. 非目标

- 不改变“一次只有一个交互 controller”的产品语义；
- 不允许新页面无条件抢占另一台活跃设备；
- 不重构 mux transport 或 screen delta resume；
- 不通过杀会话、杀 App、缩短生产 TTL 来掩盖故障；
- 不缓存并补发用户在无 Lease 期间输入的文本，避免恢复后产生意外命令；
- 不在本次修复中修改终端快照、Patch 或历史协议。
