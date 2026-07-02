# Relay 统一 Mux 重构计划

## 目标

Relay 模式和 Direct 模式统一使用一条客户端 WebSocket：

```text
Android -> /ws/sessions?deviceId={deviceId} -> Go Relay -> Go Agent
  Sec-WebSocket-Protocol: webterm.mux.v1

同一条物理 WebSocket 上承载：
  manager 通道：/ws/sessions
  terminal 通道：/ws/sessions/{sessionId}
  后续其它本机能力通道
```

迁移后，Relay 模式不再为每个 terminal 单独建立 `/ws/terminal/{sessionId}` 物理 WebSocket。Relay 仍然只做外层鉴权、设备路由和整条 WebSocket stream 透明转发，不解析 mux 控制消息、不解析 tunnel frame、不理解 session 业务。

## 实施前基线和最终决策

实施前 Go Relay 主体已经成型，Relay 模式曾经能通过旧 terminal stream 跑通：

```text
Android -> Go Relay:
  /api/devices
  /api/presence
  /api/sessions with x-device-id
  /ws/terminal/{sessionId}?deviceId={deviceId}

Go Relay -> Go Agent:
  /ws/agent
  StreamRoute path=/api/sessions
  StreamRoute path=/ws/sessions/{sessionId}
```

实施前 Direct 模式已经支持 mux：

```text
GET /ws/sessions
Sec-WebSocket-Protocol: webterm.mux.v1

ws-connect path=/ws/sessions
ws-connect path=/ws/sessions/{sessionId}
```

实施前缺口：

```text
Go Relay Server 还没有 /ws/sessions?deviceId=... 客户端入口。
Go Agent relay client 收到 route.Path=/ws/sessions 时仍按普通 manager WS 处理。
Android relay terminal 仍走 /ws/terminal/{sessionId}。
Android direct mux 的 MuxSession 目前属于单个 TerminalConnection，不适合直接复用为 relay 设备级连接。
```

## 设计原则

```text
Relay 是 Auth + Device Registry + Presence + Stream Router。
Agent 是 session、terminal、pty、本机业务能力的 truth source。
Android relay device 只应拥有一条设备级 MuxSession。
manager channel 和 terminal channel 都是 mux virtual channel。
session mutation 第一阶段继续走 HTTP API，由 Relay 透明转发给 Agent。
不为了兼容 Node relay-server 保留旧协议。
Go Relay 最终不保留客户端 /ws/terminal route。
```

当前结果：

```text
Go Relay Server 已支持 /ws/sessions?deviceId=... mux stream。
Go Agent relay client 已支持 route.Path=/ws/sessions + webterm.mux.v1。
Android relay manager 和 terminal 均已通过 RelayMuxSessionManager 走 mux。
Android TerminalConnection 已移除 /ws/terminal 连接分支。
Go Relay Server 已移除 /ws/terminal route。
```

最终决策：

```text
旧 /ws/terminal 只作为历史基线记录，不作为当前实现目标。
Relay 侧只暴露 /ws/sessions?deviceId=... 这一条 mux WebSocket 给 Android。
Go Agent 可以在内部继续支持已有 direct terminal path，但 Relay 不再把客户端 /ws/terminal 暴露为公共入口。
真实物理 Android 设备和部署 soak 属于发布验收，不阻塞代码计划完成。
```

## 当前执行状态

代码计划状态：

```text
已完成：Go Relay /ws/sessions mux stream 入口。
已完成：Go Agent relay client 按 webterm.mux.v1 进入 mux.Serve。
已完成：Android RelayMuxSessionManager 设备级 mux ownership。
已完成：Android relay manager channel 走 mux。
已完成：Android relay terminal channel 走 mux。
已完成：Go Relay 客户端 /ws/terminal route 清理。
已完成：自动化 smoke 断言 Relay debug streams 不出现 /ws/terminal。
```

剩余只属于发布验收：

```text
真实物理 Android 设备安装当前 APK 后跑通登录、设备列表、创建 session、terminal 输入输出。
部署环境连续开关 terminal / 重启 Agent 后观察 /debug/streams 最终归零。
部署环境如需 P2P fallback，单独执行 P2P 计划，不混入本 mux 计划。
```

## 目标协议

外部客户端连接 Relay：

```text
GET /ws/sessions?deviceId=d39
Cookie: webterm_auth=...
Sec-WebSocket-Protocol: webterm.mux.v1
```

Relay 发给 Agent 的 stream route：

```json
{
  "method": "GET",
  "path": "/ws/sessions",
  "subprotocol": "webterm.mux.v1"
}
```

注意：

```text
deviceId 是 Relay 外层路由参数，用来选择 Agent。
转发给 Agent 的 path 应保持 /ws/sessions。
除非未来 Agent 明确需要，否则不要把 deviceId 继续作为 Agent 侧业务 query 转发。
```

Mux 内部控制消息由 Android 和 Go Agent 处理：

```json
{"type":"ws-connect","tunnelConnectionId":"manager:d39","path":"/ws/sessions"}
{"type":"ws-connect","tunnelConnectionId":"term:s1","path":"/ws/sessions/s1","protocols":["webterm.binary.v1"]}
{"type":"ws-close","tunnelConnectionId":"term:s1"}
```

终端数据继续使用 binary tunnel frame。Relay 只看到外层 WebSocket 的 text/binary frame，并转成 `FrameTypeWSText` / `FrameTypeWSBinary` 发给 Agent。

## 实施阶段

### 阶段 0：冻结基线

目的：先保住当前已跑通的 Relay 能力，后续每一步都能比较。

当前状态：已完成。

历史产物：

```text
迁移前 /ws/terminal relay terminal smoke 可运行
迁移前 Android emulator --terminal smoke 可运行
```

验收：

```text
Go Relay 能启动
Go Agent 能注册在线
Android 能登录 Relay
Android 能看到在线 Agent
Android 能通过旧 /ws/terminal 打开 terminal 并输入输出
```

进入下一阶段条件：

```text
迁移前旧路径可重复验证，作为回归对照。
```

说明：该阶段是迁移前的回归基线。最终实现已移除旧 `/ws/terminal` route。

### 阶段 1：Go Relay 新增 mux stream 入口

目的：让 Android-like 客户端可以连接 Relay 的 `/ws/sessions?deviceId=...`，Relay 透明打开一条 Agent WebSocket stream。

当前状态：已完成。

产物：

```text
go-core/internal/relayapp/app.go
go-core/internal/relaygateway/ws_gateway.go
go-core/internal/relaygateway/ws_gateway_test.go
go-core/internal/relaymetrics/debug_handlers.go
```

实施：

```text
1. relayapp 注册 /ws/sessions 到 WSGateway。
2. WSGateway 支持外部 WS path：
   - /ws/sessions -> Agent /ws/sessions
3. /ws/sessions 必须要求 deviceId 或 x-device-id，用它选择目标 Agent。
4. Accept WebSocket 时保留客户端请求的 Sec-WebSocket-Protocol。
5. StreamRoute.Subprotocol 使用 conn.Subprotocol()。
6. /ws/sessions stream kind 可继续使用 StreamKindWebSocket，或新增 StreamKindMux。
7. debug streams 列表加入 /ws/sessions?deviceId={deviceId}。
```

验收：

```text
客户端连 /ws/sessions?deviceId=d1 且子协议 webterm.mux.v1 时，Relay 能找到 Agent 并 open stream。
Agent 收到的 route.Path 是 /ws/sessions。
Agent 收到的 route.Subprotocol 是 webterm.mux.v1。
外层 text frame 能透明转发。
外层 binary frame 能透明转发。
客户端关闭时 Relay 发送 StreamClose。
Agent 离线时客户端收到可识别错误。
缺少 deviceId 时返回 400 或 503，不误路由。
```

已验证：

```text
go test ./internal/relayapp
go test ./internal/relaygateway
go run ./cmd/webterm-relay-e2e-smoke --mux --cycles 1 --timeout 20s --cwd /Users/gao/Documents/webterm-clone/go-core
```

进入下一阶段条件：

```text
relaygateway 单测覆盖 /ws/sessions mux stream。
Go Relay e2e mux smoke 通过。
```

### 阶段 2：Go Agent relay client 支持 mux stream

目的：Agent 收到 Relay 转来的 `/ws/sessions` + `webterm.mux.v1` 后进入 mux handler，而不是普通 manager WS。

当前状态：已完成。

产物：

```text
go-core/internal/relay/client_v2.go
go-core/internal/relay/client_v2_test.go
go-core/internal/mux/handler.go
```

实施：

```text
1. relay client 收到 StreamOpen route.Path=/ws/sessions 时，先看 route.Subprotocol。
2. route.Subprotocol == webterm.mux.v1：
   - 用 relayStreamSocket 作为 mux 物理 socket 适配层。
   - 调用 mux.Serve(...)
   - OnOpen 使用 mux.OpenSessionOrManager(ctx, app.Sessions(), vs, path, protocols)
3. route.Subprotocol 为空或不是 webterm.mux.v1：
   - 可保留当前 session.NewManagerClient(socket) 分支，供内部测试或 direct/agent 侧能力复用。
   - Android Relay 客户端不得依赖该分支。
4. Relay 公共入口不再暴露 /ws/terminal/{id}；terminal 必须作为 mux virtual channel 打开。
```

关键约束：

```text
mux 物理连接建立后不自动创建 manager。
manager 也是一个普通 virtual channel，必须由客户端发送 ws-connect path=/ws/sessions 创建。
必须保持 ws-connected 先于 manager 初始 sessions 或 terminal MSG_INFO 写出。
物理 mux stream 关闭时必须关闭所有 virtual socket。
```

验收：

```text
relay client 收到 /ws/sessions + webterm.mux.v1 后启动 mux session。
ws-connect path=/ws/sessions 后，Agent 返回 ws-connected，再发送 sessions 列表。
ws-connect path=/ws/sessions/s1 后，Agent 返回 ws-connected，再发送 terminal info/output。
未知 path 返回 ws-error。
terminal channel 关闭不影响 manager channel。
物理 mux stream 关闭时所有 virtual socket 都关闭。
```

已验证：

```text
go test ./internal/mux
go test ./internal/relay
go run ./cmd/webterm-relay-e2e-smoke --mux --cycles 1 --timeout 20s --cwd /Users/gao/Documents/webterm-clone/go-core
```

进入下一阶段条件：

```text
Go relay client 单测覆盖 manager channel 和 terminal channel。
ws-connected-before-payload 顺序有直接断言。
```

### 阶段 3：Go e2e mux smoke

目的：在不动 Android UI 的情况下，先用 Android-like mux client 验证 Go Relay + Go Agent 的完整 mux relay 通路。

当前状态：已完成。

产物：

```text
go-core/cmd/webterm-relay-e2e-smoke/main.go
scripts/smoke-go-relay-server.mjs 或新增 Go smoke 参数
```

实施：

```text
1. 给 webterm-relay-e2e-smoke 增加 --mux。
2. Android-like client 连接 /ws/sessions?deviceId=d1。
3. 子协议使用 webterm.mux.v1。
4. 发送 ws-connect manager:d1 path=/ws/sessions。
5. 创建 session 第一阶段继续走 HTTP /api/sessions + x-device-id。
6. 发送 ws-connect term:s1 path=/ws/sessions/s1 protocols=["webterm.binary.v1"]。
7. 对 term:s1 发送 MSG_HELLO / MSG_INPUT。
8. 验证收到 terminal output。
9. 关闭 term:s1，确认 manager channel 和物理 mux 仍在。
10. 再打开 term:s2，确认 s1/s2 数据互不串流。
11. 关闭物理 mux，确认 Relay active streams 最终归零。
```

验收：

```text
Go Relay app + Go Agent v2 + Android-like mux client 端到端通过。
manager channel 能收 session 列表或 session 更新。
terminal channel 能输入输出。
s1 close 不影响 manager。
Agent stop 后 presence 消失且 stream 清理。
Agent reconnect 后同一设备可再次打通 mux terminal。
```

已验证：

```text
go test ./cmd/webterm-relay-e2e-smoke
go run ./cmd/webterm-relay-e2e-smoke --mux --cycles 1 --timeout 20s --cwd /Users/gao/Documents/webterm-clone/go-core
go run ./cmd/webterm-relay-e2e-smoke --cycles 1 --timeout 20s --cwd /Users/gao/Documents/webterm-clone/go-core
go run ./cmd/webterm-relay-e2e-smoke --cycles 5 --timeout 40s --cwd /Users/gao/Documents/webterm-clone/go-core
```

`webterm-relay-e2e-smoke` 的 mux probe 会在同一条 `/ws/sessions?deviceId=...` 物理 WebSocket 上同时打开两个 terminal virtual channel，并断言：

```text
term:s1 和 term:s2 都能收到各自输出。
任一 marker 不会出现在错误 channel。
关闭 terminal channel 后 Relay active streams 最终归零。
```

进入下一阶段条件：

```text
--mux smoke 稳定通过。
旧 /ws/terminal smoke 不再作为当前目标；当前目标是 --terminal --mux 通过。
```

### 阶段 4：Android 引入设备级 RelayMuxSessionManager

目的：先建立 Android 侧正确的 ownership，避免每个 TerminalConnection 都创建一条 physical mux socket。

当前状态：已完成。

产物：

```text
android-client/app/src/main/java/com/webterm/mobile/MuxSession.java
android-client/app/src/main/java/com/webterm/mobile/RelayMuxSessionManager.java
android-client/app/src/main/java/com/webterm/mobile/ServerSessionMonitor.java
android-client/app/src/main/java/com/webterm/mobile/TerminalLifecycleController.java
```

设计边界：

```text
RelayMuxSessionManager owns physical WS。
ServerSessionMonitor consumes manager channel。
TerminalConnection consumes terminal channel。
TerminalConnection 不知道 /ws/sessions?deviceId=... 物理 URL。
```

RelayMuxSessionManager：

```text
key: relayBaseUrl + deviceId
owns: MuxSession
opens manager channel
opens/closes terminal channels
dispatches tunnel data by tunnelConnectionId
reconnects physical mux socket
reopens required manager/terminal channels after reconnect
```

实施：

```text
1. 扩展 MuxSession，使它支持多 channel callback，而不是只服务一个 TerminalConnection。
2. 新增 RelayMuxSessionManager，负责设备级 physical mux socket。
3. Relay device 进入 session 页面时，建立：
   /ws/sessions?deviceId={deviceId}
   Sec-WebSocket-Protocol: webterm.mux.v1
4. mux onConnected 后发送：
   ws-connect tunnelConnectionId=manager:{deviceId} path=/ws/sessions
5. manager channel 收到 text frame 后，复用 ServerSessionMonitor 的 JSON dispatch 逻辑更新 session 列表。
6. terminal 从一开始就按阶段 5 的目标切到 mux；不再新增旧路径依赖。
```

验收：

```text
Relay 设备页面只建立一条 /ws/sessions?deviceId=... physical mux。
manager channel 能收到 session 列表。
manager channel 断线重连后能恢复。
terminal channel 最终必须走 mux；不得因为 manager mux 改造而回退到 /ws/terminal。
离开设备页面或登出 Relay 时，manager channel 和 physical mux 被关闭。
```

已验证：

```text
cd android-client && ANDROID_HOME=/Users/gao/Library/Android/sdk ./gradlew :app:compileDebugJavaWithJavac --no-daemon
DEVICE_NAME='Android E2E Agent' scripts/smoke-go-relay-android-emulator.sh --terminal --mux
```

`scripts/smoke-go-relay-android-emulator.sh --terminal --mux` 会额外断言：

```text
Relay debug streams 不包含 /ws/terminal。
Relay debug streams 中 /ws/sessions mux stream 数量等于 1。
terminal stream 有双向流量。
```

进入下一阶段条件：

```text
Android 设备页 manager channel 稳定。
Relay debug streams 中只有 /ws/sessions mux stream，没有 /ws/terminal。
```

### 阶段 5：Android relay terminal 切到 mux

目的：让 Relay terminal 不再连接 `/ws/terminal/{sessionId}`，而是在设备级 mux 上打开 terminal virtual channel。

当前状态：已完成。

产物：

```text
android-client/app/src/main/java/com/webterm/mobile/TerminalConnection.java
android-client/app/src/main/java/com/webterm/mobile/TerminalLifecycleController.java
android-client/app/src/main/java/com/webterm/mobile/SessionCommandController.java
android-client/app/src/main/java/com/webterm/mobile/TerminalRuntimeState.java
```

实施：

```text
1. TerminalConnection relay 模式不再调用 connectRelay() 创建 /ws/terminal socket。
2. TerminalConnection 向 RelayMuxSessionManager 申请 terminal channel：
   tunnelConnectionId=term:{localSessionId}
   path=/ws/sessions/{localSessionId}
   protocols=["webterm.binary.v1"]
3. TerminalConnection.sendBinary 在 relay 模式下也走 mux tunnel frame。
4. TerminalConnection.close 只发 ws-close(term:{localSessionId})，不关闭设备级 mux。
5. resize、hello、lastSeq、MSG_INPUT、MSG_TITLE 与 direct mux 逻辑保持一致。
6. 同一个 relay device 同时打开 s1/s2 时，共用同一条 MuxSession。
```

Session ID 规则：

```text
UI / 缓存层可以继续使用全局 id：{deviceId}:{sessionId}
发给 Agent 的 mux path 必须使用 Agent 本地 session id：/ws/sessions/{sessionId}
打开 terminal channel 前必须把 d39:s1 还原成 s1
收到 manager sessions 后可以继续在 Android 本地加 deviceId 前缀，避免多设备 session id 冲突
```

验收：

```text
打开 s1 terminal 后新增 term:s1 virtual channel，但不新增 /ws/terminal physical socket。
同时打开 s1 / s2 时，两条 terminal virtual channel 共用同一个 mux socket。
关闭 s1 不影响 s2 和 manager channel。
terminal 输入输出正常。
lastSeq/replay 行为与 direct mux 一致。
resize 行为与 direct mux 一致。
```

已验证：

```text
cd android-client && ANDROID_HOME=/Users/gao/Library/Android/sdk ./gradlew :app:compileDebugJavaWithJavac --no-daemon
DEVICE_NAME='Android E2E Agent' scripts/smoke-go-relay-android-emulator.sh --terminal --mux
```

进入下一阶段条件：

```text
Android emulator --terminal --mux 通过。
Relay debug/events 不再出现 Android 新路径的 /ws/terminal stream。
```

### 阶段 6：清理旧 terminal stream

目的：确认新 mux 路径稳定后，移除临时迁移路径。

当前状态：已完成。

```text
Android TerminalConnection 已不再保留 /ws/terminal 连接分支。
Android emulator --terminal --mux 已通过。
Go Relay Server 已移除 /ws/terminal route。
Go e2e smoke 默认使用 /ws/sessions?deviceId=... + webterm.mux.v1。
```

产物：

```text
go-core/internal/relayapp/app.go
go-core/internal/relaygateway/ws_gateway.go
go-core/internal/relaymetrics/debug_handlers.go
android-client/app/src/main/java/com/webterm/mobile/TerminalConnection.java
go-core/cmd/webterm-relay-e2e-smoke/main.go
```

删除条件：

```text
Android relay terminal 已完全走 mux。
Go Relay e2e smoke 已覆盖 mux terminal。
Android emulator --terminal --mux 已确认不再出现 /ws/terminal。
```

发布验收项：

```text
真实物理 Android 设备完成部署环境验证。
部署环境 soak 未发现 mux stream 泄漏。
```

实施：

```text
1. 删除或停用 Android connectRelay() 的 /ws/terminal 分支。        # 已完成
2. 删除或降级 Go Relay /ws/terminal route。                        # 已完成
3. 更新 debug streams 文案，只保留 /ws/sessions?deviceId={deviceId}。 # 已完成
4. 删除旧 /ws/terminal smoke 或改为 legacy-only 测试。               # 已完成
5. 更新主计划验收矩阵。
```

验收：

```text
Relay 模式打开 terminal 只使用 /ws/sessions?deviceId=...。
所有 session 业务仍由 Agent 执行。
Relay active streams 能在 client/agent close 后归零。
Android emulator 已通过。
物理设备作为部署验收项单独执行。
```

已验证：

```text
go test ./internal/mux ./internal/direct ./internal/relaygateway ./internal/relayapp ./internal/relay ./internal/relaymetrics ./cmd/webterm-relay-e2e-smoke
go run ./cmd/webterm-relay-e2e-smoke --cycles 1 --timeout 20s --cwd /Users/gao/Documents/webterm-clone/go-core
go run ./cmd/webterm-relay-e2e-smoke --cycles 5 --timeout 40s --cwd /Users/gao/Documents/webterm-clone/go-core
DEVICE_NAME='Android E2E Agent' scripts/smoke-go-relay-android-emulator.sh --terminal --mux
```

## Session API 选择

第一阶段允许 Android 继续用 HTTP API 创建 / 重命名 / 删除 session：

```text
POST   /api/sessions      + x-device-id
PATCH  /api/sessions/{id} + x-device-id
DELETE /api/sessions/{id} + x-device-id
```

这些 HTTP 请求仍由 Relay 透明转发给 Agent，业务真相仍在 Agent。这样可以把“统一 manager/terminal WS 连接”和“session mutation 也走 mux”分开实施，降低风险。

第二阶段再考虑把 session mutation 放到 manager virtual channel：

```text
manager command: create-session
manager command: rename-session
manager command: close-session
```

只有当 Android、Go Agent、Web/Flutter 客户端都准备统一 manager command 协议时，才迁移这部分。

## 自动化测试计划

Go 单测：

```text
relaygateway:
  /ws/sessions?deviceId=d1 accepts webterm.mux.v1
  creates stream route path=/ws/sessions subprotocol=webterm.mux.v1
  forwards text frame
  forwards binary frame
  stream close cleanup

relay client:
  /ws/sessions + webterm.mux.v1 starts mux
  manager ws-connect receives ws-connected before sessions payload
  terminal ws-connect receives ws-connected before terminal payload
  ws-close closes one virtual channel only
```

Go e2e smoke：

```text
cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go run ./cmd/webterm-relay-e2e-smoke --mux --cwd /Users/gao/Documents/webterm-clone/go-core
cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go run ./cmd/webterm-relay-e2e-smoke --mux --cycles 5 --timeout 40s --cwd /Users/gao/Documents/webterm-clone/go-core
```

Android 验收：

```text
cd android-client && ANDROID_HOME=/Users/gao/Library/Android/sdk ./gradlew :app:compileDebugJavaWithJavac --no-daemon
cd android-client && ANDROID_HOME=/Users/gao/Library/Android/sdk ./gradlew :app:testDebugUnitTest --no-daemon
DEVICE_NAME='Android E2E Agent' scripts/smoke-go-relay-android-emulator.sh --terminal --mux
```

人工验证：

```text
登录 Relay
看到在线 Agent
进入中转设备
创建两个 session
同时打开两个 terminal
分别输入 pwd / echo
关闭其中一个 terminal
断开并重启 Agent
确认首页在线状态和 terminal 恢复行为符合预期
```

## 成功判定

```text
Relay 模式打开两个 terminal，只出现一条 /ws/sessions?deviceId=... 物理 WS。
Relay debug streams 只有一个 mux WS stream，不再有 /ws/terminal/{id}。
s1/s2 输入输出互不串流。
关闭 s1 不影响 s2。
manager channel 持续收到 session/title 更新。
Agent 重连后设备在线状态和 manager channel 能恢复。
所有 stream 在 client/agent close 后最终归零。
```

## 风险和处理

```text
风险：Relay Agent 侧把 /ws/sessions 误走普通 manager WS。
处理：以 subprotocol 分流；webterm.mux.v1 必须进入 mux.Serve。

风险：Android 每个 TerminalConnection 都 new MuxSession，导致仍然多物理连接。
处理：把 mux ownership 上移到设备级 RelayMuxSessionManager。

风险：d39:s1 被直接发给 Agent，导致 session not found。
处理：统一增加 localSessionId 解析，发给 Agent 前去掉 deviceId 前缀。

风险：ws-connected 顺序被破坏，terminal payload 早于 tunnel ready。
处理：继续使用 mux.OpenSessionOrManager 的 start-after-ack 约束，并加测试。

风险：manager channel 断线重连后重复派发旧 sessions。
处理：重连 generation 标记；旧 channel 回调丢弃。

风险：关闭一个 terminal 误关设备级 mux。
处理：TerminalConnection 只发 ws-close(term:id)，不 stop MuxSession。

风险：一次切换三端导致问题定位困难。
处理：按阶段推进，先 Go e2e mux smoke，再 Android manager channel，最后 Android terminal。
```
