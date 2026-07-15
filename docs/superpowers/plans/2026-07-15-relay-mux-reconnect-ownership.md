# Relay Mux 重连所有权重构实施方案

**日期：** 2026-07-15  
**状态：** 实施完成  
**范围：** Go Relay WebSocket 生命周期、Android mux 传输重连、逻辑 channel 握手、screen 初始同步  
**目标：** 消除 Relay 周期性断线触发的并行物理连接、重复 `ws-connect`、重复 Hello 与终端永久 `sync_timeout`。

## 1. 现场事实

实体设备与模拟器已经确认以下连续故障链：

1. Relay `/ws/sessions` 在创建时写入固定 30 秒 Deadline；清理任务按绝对时间关闭仍然健康的长连接。
2. Android 复用 `WebSocketMuxTransport` 时，旧 socket 迟到的 `onClosed`/`onFailure` 会清空新 socket 状态，并允许再创建一条物理连接。
3. 物理 mux 恢复与页面重新绑定可能同时发送同一个 channel 的 `ws-connect`；两个 ACK 都通知当前 listener。
4. 重复 `onConnected` 让 screen runtime 对同一 channel 发送两个 Hello；Go 按协议关闭重复 Hello 的 channel。
5. Android 等不到权威 Snapshot/ResumeAck，进入 `sync_timeout`；并行物理 mux 使 Agent 的 terminal `clients` 持续增长。

同一 Codex resume 会话经 Relay Agent 可正常使用，说明会话历史内容不是触发条件。

## 2. 设计边界

本次采用有限范围的所有权重构，不扩张成新传输框架：

- 不新增 Gradle module；
- 不新增 `OneShotWebSocketConnection`；
- 不修改 mux/screen wire 协议；
- 不新增服务端 consumer lease；
- 不改变 screen delta resume 的权威版本模型；
- 不用分散的布尔补丁掩盖竞态。

只建立四条可测试的不变量：

1. 一个 `WebSocketMuxTransport` 同时最多有一个当前连接 Attempt；旧 Attempt 的事件不能修改当前 Attempt。
2. `MuxSession` 是物理连接重连的唯一调度者。
3. 一个 logical channel 同时最多有一个 `ws-connect` 在途；一个 ACK 最多完成一次状态转换。
4. 一个 screen 连接阶段最多启动一次初始同步并发送一个 Hello。

## 3. Go Relay：长连接不使用绝对 Deadline

`WSGateway` 在 WebSocket 已经 Accept 后才创建 stream，因此现有 30 秒 timeout 不是握手 timeout，而是错误的连接绝对寿命。

改造：

- `/ws/sessions` 创建 stream 时 Deadline 设为零；
- WebSocket 存活由读写错误、Ping/Pong 和显式关闭决定；
- HTTP 等有界请求继续使用各自 timeout，不修改 `StreamManager` 的通用清理能力。

验收：健康 mux WebSocket 不具有绝对 Deadline；正常 stream close 仍释放 Agent mux 与 terminal client。

## 4. Android transport：Attempt 身份隔离

保留 `WebSocketMuxTransport`，内部增加一次连接尝试的不可变身份：

```text
Attempt
├── listener
├── socket
└── connected
```

`start()` 只在没有当前 Attempt 时创建新 Attempt。OkHttp listener 捕获所属 Attempt。所有回调在统一入口校验 Attempt 身份：

- 当前 Attempt：允许推进 open/message/closed/failure；
- 旧 Attempt：忽略事件，不能清空当前 socket，不能通知当前重连状态机；
- `close()` 原子摘除当前 Attempt，再关闭其 socket；
- `send*()` 只读取当前且 connected 的 Attempt。

这使 transport 可以复用，但连接代次不能相互污染。

## 5. Android core-session：显式 channel 状态机

`RelayMuxSessionManager.Channel.State` 改为：

```text
WAITING_FOR_MUX → OPENING → LIVE
        ↑            │        │
        └────────────┴────────┘  mux/channel 可恢复断线
```

规则：

- 新 channel 初始为 `WAITING_FOR_MUX`；
- mux open 只打开 `WAITING_FOR_MUX` channel，并在发送成功后进入 `OPENING`；
- 页面在 `OPENING` 期间重新绑定 listener，只替换 listener，不重发 `ws-connect`；
- `ws-connected` 只接受 `OPENING → LIVE`；`LIVE` 的重复 ACK 忽略；
- mux disconnect 将所有保留 channel 统一退回 `WAITING_FOR_MUX`；
- 可恢复 channel close/error 先退回 `WAITING_FOR_MUX`，再通过同一 `openChannelIfReady()` 入口打开；
- detached 且仍 LIVE 的 channel 若确需新连接对象接管，显式重新握手一次；HOT 页面重挂继续复用 runtime，不进入此路径。

`MuxSession` 保持唯一物理重连定时器；Manager 只管理 logical channel，不创建第二套重连循环。

## 6. Android terminal：初始同步状态转换幂等

`TerminalSessionRuntime` 只在 `CONNECTING` 或 `RECONNECTING` 接受 `onConnected`：

```text
CONNECTING/RECONNECTING → TRANSPORT_CONNECTED → SYNCING → CONNECTED
```

处于 `TRANSPORT_CONNECTED`、`SYNCING` 或 `CONNECTED` 时收到重复 connected 事件直接忽略。断线会先进入 `RECONNECTING`，所以合法的新一代连接不受影响。

Go 端“同一 screen channel 重复 Hello 即关闭”继续作为协议防线。

## 7. 测试与验收

### 自动化测试

- Relay mux 无绝对寿命；
- 旧 WebSocket `onClosed`/`onFailure` 不能清除新 Attempt；
- 迟到回调不能制造第三条物理 socket；
- `OPENING` 期间页面重新绑定不能发送第二个 `ws-connect`；
- 重复/过期 `ws-connected` 只能通知 listener 一次；
- 重复 `onConnected` 只能产生一个 screen Hello；
- mux/channel 正常关闭仍释放 terminal client。

### 运行时验收

使用 `scripts/repro-relay-android-resume-stuck.sh`：

- 连续 20 轮 Relay 重连、返回列表、重开；
- 目标 session `clients <= 1`；
- `/screen/projected` 在 2 秒内响应；
- 每轮出现 Snapshot、Patch 或 ResumeAck 成功事件；
- 不出现目标会话连续 `sync_timeout`；
- 重开后可继续输入。

## 8. 实施任务

- [x] Task 1：取消 Relay mux WebSocket 绝对 Deadline。
- [x] Task 2：实现 `WebSocketMuxTransport` Attempt 身份隔离。
- [x] Task 3：实现 logical channel `WAITING_FOR_MUX/OPENING/LIVE` 状态机。
- [x] Task 4：实现 screen 初始同步状态转换幂等。
- [x] Task 5：运行相关单测、Android 模块测试、Go 测试与 ADB 回归。
- [x] Task 6：审查零残留旧行为并记录最终结果。

## 9. 完成记录

2026-07-15 完成：

- `go test ./...` 全部通过；
- `go run ./cmd/webterm-relay-e2e-smoke` 通过；该 smoke 同步到当前
  `webterm.screen.v1` protobuf Hello、layout lease 和 input 契约；
- Android `./gradlew test` 全部通过；
- Android `:app:assembleDebug` 通过并安装到 `emulator-5554`；
- transport 迟到 `onClosed`、迟到 `onFailure`、并行第三 socket 回归测试通过；
- channel 重挂单一在途 `ws-connect` 与重复 ACK 回归测试通过；
- screen 重复 connected 只发送一次 Hello 的回归测试通过；
- ADB 实体流程在旧 Relay 仍会周期断线的条件下观察到：
  `mux failure → mux open → exact_resume`；
- 干净目标会话 `s2` 的 Agent client 计数为 `0 → 1 → 1`，返回列表重开后
  `/screen/projected` 正常响应；证据保存在
  `/private/tmp/webterm-relay-resume-fixed-s2`。

说明：原故障会话 `s5` 在修复前已积累大量孤立 client，不能用来证明修复后的
计数回收；它需要重启旧 Agent/会话后才能清除历史污染。本次使用未污染的 `s2`
验证新代码不会继续制造 client 泄漏。
