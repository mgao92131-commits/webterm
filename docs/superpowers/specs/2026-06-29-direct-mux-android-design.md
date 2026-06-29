# 直连单连接复用（mux）设计：go-core + Android（阶段化）

## 0. 范围声明（修订）

本设计分两阶段。**只有第一阶段是本期可实施范围**。第二阶段明确标注为未决，不假装「relay server 一行不改」。

| 端 | 第一阶段 | 第二阶段（未决） |
|----|---------|----------------|
| go-core direct server | ✅ `/ws/sessions` 新增 mux 入口；**保留** `/ws/sessions/{id}` 旧入口 | 最终目标 mux-only，待其他客户端迁移后删旧入口 |
| go-core relay agent | ✅ 内部重构为 `mux.Serve`，对外协议完全不变 | — |
| Android 直连 | ✅ 终端页迁到 mux 单连接 | — |
| Android 中继 | ❌ 保持现状（每终端独立 WS） | 待定：改 relay server 支持客户端侧 mux，或中继终端维持每终端 WS |
| Node.js relay server | ❌ 不动 | 可能需改（见第二阶段） |
| 浏览器前端 / Flutter | ❌ 不动，继续用 `/ws/sessions/{id}` | 后续单独迁移 |

### 为什么改成分阶段

初版 spec 假设「relay server 一行不改 + Android 中继单连接」，**经核实不成立**（见 §1 blocker）。relay server 对 `/ws/sessions?deviceId=` 是「一条客户端 WS → agent 侧一个 tunnel」的 1:1 包装模式（`relay-server/client-tunnel.js:38-50`、`relay-server/main.js:122-155`）：客户端在这条 WS 上发的文本会被当作该 tunnel 的普通 payload 转给 agent，不会被当作新 `ws-connect` 控制消息。因此 Android 中继单连接要么改 relay server，要么不做——不能糊。

同时，初版「删 `/ws/sessions/{id}`」与「不碰浏览器/Flutter」直接冲突（浏览器终端仍连 `/ws/sessions/{id}`，`frontend/src/lib/terminal-session-context.ts:189`）。第一阶段保留旧入口，避免牵连其他客户端。

---

## 1. 关键事实核实（blocker 说明）

### 1.1 relay server 不是客户端 mux 透传器

`relay-server/main.js:122-155` + `client-tunnel.js`：

- 客户端每条 WS 升级到 `/ws/sessions?deviceId=` 或 `/ws/sessions/{id}?deviceId=`，relay server 向 agent 发**一条** `ws-connect`（带唯一 `tc_xxx`），把这条客户端 WS 1:1 包成 agent 侧的单个 tunnel。
- 客户端在这条 WS 上的所有消息（文本/二进制）被包成 `MSG_TYPE_WS_DATA` tunnel frame，用同一个 `tc_xxx` 转给 agent。
- manager 通道（`/ws/sessions?deviceId=`）还有 `transformOutboundText` 给会话 id 加 `deviceId:` 前缀（`main.js:136-139`）。

**后果**：若 Android 在一条中继 WS 上发 `ws-connect` JSON 想开第二个终端通道，relay server 会把它当作 manager tunnel 的文本 payload 转给 agent 的 manager VirtualSocket。agent 的 `mux.Session` 收到的是 manager 通道的 tunnel frame **数据**，不是控制消息，**不会开新终端通道**。这是 blocker。

**结论**：Android 中继单连接不在第一阶段。第二阶段需独立决策（§7）。

### 1.2 Android 终端连接归属

- `MainActivity` 持有**一个全局** `mTerminalConnection`（`MainActivity.java:96`），终端页一次只显示一个终端。
- `ServerGroupController` 每个 server/group 各建一个 `ServerSessionMonitor`（`ServerGroupController.java:49`），用于首页列表，与终端页分离。
- 终端页用 `terminalState.baseUrl()/cookie()/sessionId()` 独立连接（`TerminalLifecycleController.java:188-190`），不依赖任何首页 monitor。

→ 第一阶段 MuxSession 归属见 §4.6。

### 1.3 relay session id 前缀

Android 给中继 session id 加 `deviceId:` 前缀（`ServerSessionMonitor.java:145`）。relay server 拆前缀（`main.js:128-135`）转成本地 id 给 agent。

第一阶段只做 **direct**，Android 用本地 id（无前缀），不涉及前缀问题。第二阶段若做中继 mux，需在 Android 或 relay server mux 层明确拆前缀（§7）。

---

## 2. 核心架构（go-core 侧，两阶段共用）

### 2.1 提取 `mux` 包

把 relay 现有 `connectionTransport` + `virtualSocket` 泛化为 `mux.Session`：

```
go-core/internal/mux/
  session.go          # mux.Session：包装 WS 连接，readLoop 分发 ws-connect/ws-close/tunnel frame
  virtual_socket.go   # VirtualSocket：实现 session.Socket，经 tunnel frame 多路复用
  handler.go          # OpenSessionOrManager：direct/relay 共用的 OnOpen 处理器
  session_test.go     # 单测
```

`mux.Session` 职责（与 relay 现有行为一致）：
- `Serve(conn, opts)` 包装一条已建立 WS；`Run(ctx)` 启动 readLoop，**不自动建任何通道**。
- 收 `ws-connect` → 建 VirtualSocket → 调 `OnOpen` → 成功回 `ws-connected`，失败回 `ws-error`。
- 收 `ws-close` → 关对应通道。
- 收二进制 tunnel frame → 解码按 ID 路由到 VirtualSocket.Emit。
- 物理连接断开 → 关闭所有 VirtualSocket。
- `OnControl` hook（可选）：mux 不识别的控制消息（relay 的 `http-request`/`p2p-*`）透传上层。direct 不设。

`VirtualSocket` 实现现有 `session.Socket` 接口 → `session.Client` / `ManagerClient` **零改动**。

### 2.2 manager 通道由 `ws-connect` 显式建立

`mux.Session.Run()` 不自动创建 manager 通道。manager 与终端通道一样由 `ws-connect path=/ws/sessions` 显式建立，与 Node relay agent（`server/agent.js:228-232`）一致。自动建会造成 relay 侧双重 manager。

### 2.3 `OpenSessionOrManager` — direct/relay 共用

从 `relay/client.go:handleWSConnect` 提取（去掉 sendJSON，由 mux 负责）：
- `path == /ws/sessions` → `session.NewManagerClient(vs)` + `go mc.Run(ctx, manager)`
- `path == /ws/sessions/{id}` → 取终端，`session.NewClient(vs, terminal, mode)` + `go client.Run(ctx)`
- 其余 → 返回 error，mux 发 `ws-error`

**URL 解析/反转义（统一行为）**：handler 收到的 `path` 可能带 query 参数或 percent-encoded session id。relay 当前做 `url.Parse(path)` 取 pathname + `url.PathUnescape(id)`（`relay/client.go:130-133,160`），direct 当前没反转义（`direct/server.go:125` 直接 `TrimPrefix`）。既然抽成共享 handler，**统一**：handler 先 `url.Parse(path)` 取 pathname，匹配 `/ws/sessions/{id}` 后对 id 做 `url.PathUnescape`。这样 direct 和 relay 对 encoded session id 行为一致，避免 direct 端 encoded id 找不到 session。

`mode` 由 `ws-connect.protocols` 经 `selectProtocol` + `session.ClientModeFromProtocol` 得到。direct 与 relay agent 用同一段通道建立逻辑。

---

## 3. 第一阶段：go-core 服务端

### 3.1 relay agent — 改用 `mux.Serve`（内部重构，协议不变）

- `runOnce` 同步完成 `agent-register`/`registered` 握手后，`mux.Serve(conn, OnOpen=OpenSessionOrManager, OnControl=client.handleControl)`。
- **registered/error 边界（必须显式处理，不能丢给 mux）**：注册握手在 `mux.Serve` **之前**同步完成——`registered` 调 `app.SetRelayConnected(true, deviceID, "")`，`error`（或非 `registered` 响应）直接返回失败、不进 mux。这两个消息是 relay 对 agent-register 的直接回复，发生在 relay 开始发 `ws-connect`/`http-request` 之前，单读安全。注册完成后才交给 `mux.Serve` 接管后续控制消息。若不显式处理，registered/error 会被 mux 当作 unknown control 经 `OnControl` 透传，relay 连接状态不会被更新。
- `handleControl`（注册后）处理 `http-request`（调 `routeMemoryAPI` 回 JSON）、`p2p-offer`（回 `p2p-unavailable`）——从现有 `relay/client.go` 搬过来。注意：registered/error 已在注册阶段处理，不会进 `handleControl`。
- **删除** `relay/transport.go`、`relay/virtual_socket.go`。
- **relay server 与中继客户端协议层一行不改**——agent 内部重构对外无感。这是第一阶段风险最高处，必须用中继回归验证。

### 3.2 direct server — 新增 mux 入口，保留旧入口

- `/ws/sessions`：**按子协议协商分流**。实现细节：`websocket.Accept` 时传 `&websocket.AcceptOptions{Subprotocols: []string{protocol.MuxSubprotocol}}`，握手后用 `conn.Subprotocol() == protocol.MuxSubprotocol` 判断——命中走 `mux.Serve`，不命中（旧客户端没带该子协议）走现有裸 JSON `ManagerClient`。**不要**在 accept 前手动读 `Sec-WebSocket-Protocol` header 再决定，那会破坏握手；也不要让旧 manager 连接失败——`AcceptOptions.Subprotocols` 仅声明服务端支持的协议，不带该子协议的旧客户端 accept 仍成功、`Subprotocol()` 返回空，自动落旧分支。
  - 新增常量 `protocol.MuxSubprotocol = "webterm.mux.v1"`。
- `/ws/sessions/{id}`：**保留不动**，浏览器/Flutter 每终端独立 WS 照旧。
- REST API（`/api/login`、`/api/sessions` CRUD、`/api/me`）**保留不动**。直连 serve 真 HTTP，CRUD 留 REST，只有实时流走 mux。
- 鉴权不变：升级 `/ws/sessions` 时验 cookie，整条 mux 连接及所有通道继承鉴权。

> 注：第一阶段 direct 是「mux opt-in + 旧入口保留」，不是 mux-only。mux-only 是最终目标，待浏览器/Flutter 迁移后在后续工作中删旧入口。

### 3.3 协议层

- `protocol/tunnel.go` 保留在 `protocol/`，mux 与 relay 共用。
- 新增 `protocol.MuxSubprotocol = "webterm.mux.v1"`（仅 direct `/ws/sessions` 用于区分新旧客户端）。

### 3.4 smoke 测试

- `cmd/webterm-flow-smoke`（直连）：新增一条 mux 路径用例（`/ws/sessions` + `webterm.mux.v1` + `ws-connect` + tunnel frame）；旧 `/ws/sessions/{id}` 用例保留。
- `cmd/webterm-relay-flow-smoke`（中继）：已用 `ws-connect`，agent 侧重构后回归验证。

---

## 4. 第一阶段：Android 直连

### 4.1 新增 `MuxSession`（客户端角色）

对应 go-core `mux.Session`，客户端角色：发起 `ws-connect`、解码 tunnel frame、把每个虚拟通道的帧分发回上层。职责：
- 维护单条 `/ws/sessions` WS（带 cookie + `webterm.mux.v1` 子协议，复用现有 `monitorHttp` 的 ping interval）。
- **二进制帧**：解码 tunnel frame → 按 `tunnelId` 路由到对应通道回调。
- **文本帧**：处理 `ws-connected`/`ws-error`（通道建立结果），其余透传。
- 重连（复用现有指数退避）+ 重连后重建所有活动通道。

### 4.2 `WebTermProtocol` 扩展

新增 tunnel frame 编解码（对应 go-core `protocol/tunnel.go`）：
- `encodeTunnelFrame(tunnelId, payload, binary)` → `[0x01 | idLen | id | extraByte | payload]`
- `decodeTunnelFrame(data)` → `(tunnelId, extraByte, payload)`
- 常量：`MSG_TYPE_WS_DATA=0x01`、`WS_DATA_TEXT=0x01`、`WS_DATA_BINARY=0x02`

### 4.3 终端页改造（direct 路径）

`TerminalConnection` 直连路径改为复用通道：
- `connect()` 调用 `MuxSession.openTerminal(sessionId, lastSeq, cols, rows)`：
  - 发 `ws-connect`（`tunnelId="term:{sessionId}"`, `path="/ws/sessions/{id}"`, `protocols=["webterm.binary.v1"]`）
  - **等 `ws-connected` 后**再发 `MSG_HELLO`（封装进 tunnel frame）——顺序约束，单测覆盖。
- 输入/resize/title：封装进 tunnel frame 经 `MuxSession` 发送。现有 `MSG_INPUT`/`MSG_RESIZE` 二进制帧不变，外面包一层 tunnel frame。
- 收数据：`MuxSession` 把 `tunnelId="term:{sessionId}"` 的 payload 投递给 `handleServerMessage`——**现有帧解析零改动**（`MSG_OUTPUT`/`MSG_STATE`/`MSG_INFO`/`MSG_EXIT` 照旧）。
- `close()`：发 `ws-close` 关闭该通道。
- **重连**：由 `MuxSession` 统一负责；终端通道重连后自动重建，`lastSeq` 保留，靠 `MSG_HELLO.lastSeq` 增量恢复。

### 4.4 manager 通道

**第一阶段终端页不打开 manager 通道**——只开终端通道。会话列表推送仍由首页 `ServerSessionMonitor` 负责（保持现状，走旧裸 JSON `/ws/sessions`），避免终端页与首页重复订阅。`MuxSession` 设计上保留打开 manager 通道的能力（`ws-connect` `path="/ws/sessions"`），但第一阶段不使用，为未来多终端/终端页实时标题刷新预留。

### 4.5 终端子协议统一 binary

Android 用 `webterm.binary.v1`，`ws-connect.protocols` 携带。不引入 screen（YAGNI）。

### 4.6 MuxSession 归属与生命周期（回答 reviewer #3）

- **归属**：终端页持有自己的 `MuxSession`（与全局 `TerminalConnection` 绑定，生命周期随终端页）。首页 `ServerSessionMonitor`（每 server/group 一个）**第一阶段不变**，仍走旧裸 JSON `/ws/sessions`。两者分离，互不影响。
- **隔离**：一个 `ServerConfig` → 终端页一个 `MuxSession` → 一条 WS。切换服务器/设备时关闭旧 `MuxSession`、建新的。
- **生命周期**：进入终端页建 `MuxSession`；离开终端页（`closeTerminalConnection`，`TerminalLifecycleController.java:157/184`）关闭 `MuxSession`，发 `ws-close` 关所有通道。首页 monitor 关闭**不影响**终端页。
- **多终端**：Android 一次只显示一个终端，但 `MuxSession` 设计上支持多 `tunnelId` 通道，为未来多终端预留。
- **direct/中继分流**：`TerminalConnection` 根据 `isRelayDevice` 标志判断——direct 走 `MuxSession`，中继走旧每终端 WS（第一阶段中继不变）。

### 4.6.1 调用链改造：把 isRelayDevice 传到 TerminalConnection（回答 reviewer #1、#6）

当前调用链在进入终端页时把 `ServerConfig` 拆成了 `baseUrl/cookie/sessionId`，分流依据丢失：

```
MainActivity.openSession(server, sessionId, ...)            ← 有完整 ServerConfig
  → showTerminal(server.getUrl(), server.getCookie(), sessionId, ...)   ← ServerConfig 被拆掉
    → TerminalLifecycleController.showTerminal(baseUrl, cookie, sessionId, ...)
      → terminalState.setServerSession(baseUrl, cookie, sessionId)
        → connectTerminal() → terminalConnection.connect(baseUrl, cookie, sessionId, lastSeq)
```

新建会话入口同样缺：`SessionCommandController.Listener.onOpenTerminal(baseUrl, cookie, sessionId, ...)`（`SessionCommandController.java:139`）也没有 `ServerConfig`。

**改造**（沿调用链加 `isRelayDevice` 布尔，不传整个 ServerConfig 以最小化改动）：

| 文件 | 改动 |
|------|------|
| `TerminalConnection.java` | `connect(...)` 增 `boolean isRelayDevice` 参数；direct 走 `MuxSession`，relay 走旧每终端 WS |
| `TerminalRuntimeState.java` | 加 `boolean isRelayDevice` 字段 + `setServerSession(..., isRelayDevice)`；`clearServerSession` 清空它；`connectTerminal()` 读它传给 `connect` |
| `TerminalLifecycleController.java` | `showTerminal(...)` 增 `isRelayDevice` 参数，透传给 `terminalState.setServerSession` |
| `MainActivity.java` | `openSession` 调 `showTerminal` 时传 `server.isRelayDevice()`（`:460`） |
| `SessionCommandController.java` + 其 `Listener` | `onOpenTerminal(...)` 增 `boolean isRelayDevice`；新建会话路径（`SessionCommandController.java:139`）传入 |
| MainActivity 实现的 `onOpenTerminal` 回调 | 接收 `isRelayDevice` 并传给 `showTerminal` |

这样 direct/relay 分流在 `TerminalConnection.connect` 处有依据，两个入口（点已有 session / 新建 session）都覆盖。

### 4.7 sessionId 前缀（回答 reviewer #4）

- direct：Android 用本地 id（无前缀）拼 `path="/ws/sessions/{id}"`。
- 中继（第一阶段不变）：前缀 `deviceId:` 由 relay server 拆。第二阶段若做中继 mux，需明确拆前缀点（§7）。

---

## 5. 物理连接消息分层（per-leg）

```
一段物理 WebSocket 连接（direct server 或 relay agent 侧）
│
├── MessageText（控制平面）
│   ├── ws-connect   {tunnelConnectionId, path, protocols}   ← 客户端/relay 发起
│   ├── ws-connected {tunnelConnectionId}                    ← 服务端回
│   ├── ws-close     {tunnelConnectionId, code}              ← 双向
│   ├── ws-error     {tunnelConnectionId, code, message}     ← 服务端回
│   └── （relay agent 段额外）http-request / http-response / p2p-* / agent-register
│        → 通过 OnControl hook 透传，mux 不处理
│
└── MessageBinary（数据平面 — Tunnel Frame）
    └── [MsgType=0x01 | idLen | id | extraByte | payload]
        id = 该段的 tunnel ID
        （direct 段：Android 定的 id；relay agent 段：relay 给的 tc_xxx）
```

manager 通道不特殊：path=/ws/sessions 的 ws-connect 建立的普通 VirtualSocket，`ManagerClient` 通过它发裸 JSON（走 tunnel frame 的 text 类型）。

---

## 6. 测试计划（回答 reviewer #5）

### 6.1 go-core

- `mux/session_test.go`：ws-connect 建通道 / ws-close 关通道 / tunnel frame 路由 / onOpen error→ws-error / 物理连接断开关所有通道。
- `OpenSessionOrManager` 单测：`url.Parse`+`PathUnescape` 对 encoded session id 与带 query 的 path 行为一致（回答 reviewer #5）。
- relay agent 中继回归：`webterm-relay-flow-smoke` + 真实中继。**重点验证 registered/error 在注册阶段被处理**（`app.SetRelayConnected` 更新），而非被 mux 当 unknown control 透传忽略（回答 reviewer #4）。
- direct 兼容测试：`/ws/sessions` 带 `webterm.mux.v1` 走 mux；不带走旧裸 JSON（旧客户端仍能连）；`/ws/sessions/{id}` 旧入口仍可用。**明确 `/ws/sessions/{id}` 第一阶段保留**。

### 6.2 Android

- `WebTermProtocol` tunnel frame encode/decode 单测（含 idLen 边界、extraByte、payload 截取）。
- `MuxSession` 单测：`ws-connected` 后才发 HELLO 的顺序；重连后重建活动通道；多 tunnelId 路由。
- 调用链验证（回答 reviewer #1、#6）：点已有 session 与新建 session 两个入口都能正确把 `isRelayDevice` 传到 `TerminalConnection.connect`——direct 走 `MuxSession`、relay 走旧 WS。
- 端到端：直连单终端 / 重连恢复 / 切换服务器隔离。中继路径不在第一阶段测试范围（维持现状，回归验证中继仍走旧每终端 WS）。

### 6.3 relay-server 层（第二阶段才需要）

第二阶段若做 Android 中继单连接，需补 relay-server 层测试，验证客户端 mux 控制帧（ws-connect）能到 agent 并开新通道。第一阶段不需要。

---

## 7. 第二阶段（未决，不在本期实施）

Android 中继单连接。两个选项，需独立 spec 决策：

- **选项 A**：改 relay server 支持客户端侧 mux passthrough——`client-tunnel.js`/`main.js` 识别客户端 WS 上的 `ws-connect` 控制帧，转成 agent 侧多条 `ws-connect`（多个 `tc_xxx`）。这是对 relay server 的实质性改动，需单独设计与测试。
- **选项 B**：Android 中继维持每终端独立 WS（现状），只让 direct 走 mux。

无论哪个，都不能用「relay server 一行不改」糊。本期不实施，标记为后续工作项。

---

## 8. 实施顺序（第一阶段）

| 阶段 | 内容 | 风险 |
|------|------|------|
| 1 | 新建 `mux/` 包：`session.go`+`virtual_socket.go`+`handler.go`，从 relay 移出并改回指 | 低 |
| 2 | `mux/session_test.go` | 低 |
| 3 | relay agent 改用 `mux.Serve`+`OnControl`，删 `transport.go`/`virtual_socket.go` | 中 |
| 4 | 中继模式回归（relay-flow-smoke + 真实中继） | 中（最高风险） |
| 5 | direct server `/ws/sessions` 加 mux 分支（子协议区分），旧入口保留 | 低 |
| 6 | flow-smoke 加 mux 用例 | 低 |
| 7 | Android：`WebTermProtocol` 加 tunnel frame；新建 `MuxSession` + 单测 | 中 |
| 8 | Android 调用链：`isRelayDevice` 从 `MainActivity.openSession`/`SessionCommandController` 透传到 `TerminalConnection.connect`（§4.6.1） | 中 |
| 9 | Android：`TerminalConnection` direct 路径改复用通道；中继路径不变 | 中 |
| 10 | Android 端到端：直连单终端/重连/切换服务器隔离；中继回归旧 WS | 中 |

阶段 1-2 纯新增+测试。3-4 中继侧重构（最大风险：agent 内部换 mux，必须保证对 relay server 协议不变）。5-6 直连侧加分支不破坏旧入口。7-9 Android 直连迁移。

---

## 9. 设计决策记录

### Q: 为什么第一阶段不删 `/ws/sessions/{id}`？
浏览器终端仍连 `/ws/sessions/{id}`（`frontend/src/lib/terminal-session-context.ts:189`），Flutter 同。删了会断它们。第一阶段保留旧入口，Android 直连迁到 mux，其他客户端不受牵连。mux-only 是最终目标，待后续迁移。

### Q: 为什么 direct `/ws/sessions` 用子协议区分新旧，而非首条消息嗅探？
握手时协商子协议是 WebSocket 标准做法，干净无歧义。旧客户端不带 `webterm.mux.v1` → 自动走旧裸 JSON 分支。首条消息嗅探需缓冲/超时/协议判定，复杂易错。

### Q: 为什么 Android 中继第一阶段不做单连接？
relay server 是 1:1 tunnel 包装（§1.1），不是客户端 mux 透传器。Android 中继单连接需改 relay server，超出第一阶段范围。中继终端维持每终端独立 WS。

### Q: 为什么 `Run()` 不自动建 manager 通道？
relay agent 侧 manager 由 relay 主动发 `ws-connect path=/ws/sessions` 建立。自动建会造成双重 manager。所有通道统一由 ws-connect 建立，与 Node relay agent 一致。

### Q: 为什么终端页第一阶段只开终端通道、不订阅 manager？
首页 `ServerSessionMonitor` 已负责会话列表。终端页只显示一个终端，不需要列表推送，避免重复订阅。`MuxSession` 设计支持后续按需加 manager 通道。

### Q: 为什么 REST API 保留、不搬进 mux 控制面？
直连 serve 真 HTTP；只有实时流才需长连接复用。CRUD 留 REST，职责清晰。relay 才需 `http-request` 隧道（agent 不能 serve HTTP）。

### Q: 背压如何处理？
**读侧**（VirtualSocket.incoming）：buffer 256，满则丢帧+关闭该通道（沿用现有 `Emit`），不阻塞物理 ReadLoop。**写侧**：单 writeMu + 每写 10s 超时（沿用现有 `sendBinary`）。一个慢通道最坏阻塞所有通道 10s——现有行为，本设计未改变也未声称解决。

---

## 10. 文件变更清单（第一阶段）

```
go-core 新增:
  internal/mux/session.go
  internal/mux/virtual_socket.go
  internal/mux/handler.go
  internal/mux/session_test.go

go-core 修改:
  internal/protocol/constants.go        # 加 MuxSubprotocol = "webterm.mux.v1"
  internal/relay/client.go              # 改用 mux.Serve + OnControl；register 独立
  internal/direct/server.go             # /ws/sessions 加 mux 分支（子协议区分），旧入口保留

go-core 删除:
  internal/relay/transport.go           # 逻辑移入 mux.Session
  internal/relay/virtual_socket.go      # 移入 mux 包

go-core smoke:
  cmd/webterm-flow-smoke/main.go        # 加 mux 用例，旧用例保留
  cmd/webterm-relay-flow-smoke/main.go  # 回归验证

Android 新增:
  app/src/main/java/com/webterm/mobile/MuxSession.java
  app/src/test/... (WebTermProtocol / MuxSession 单测)

Android 修改:
  app/src/main/java/com/webterm/mobile/WebTermProtocol.java      # 加 tunnel frame 编解码
  # ── 调用链：把 isRelayDevice 传到 TerminalConnection（见 §4.6.1）──
  app/src/main/java/com/webterm/mobile/TerminalConnection.java   # connect 增 isRelayDevice；direct 走 MuxSession，relay 走旧 WS
  app/src/main/java/com/webterm/mobile/TerminalRuntimeState.java # 加 isRelayDevice 字段 + setServerSession 透传
  app/src/main/java/com/webterm/mobile/TerminalLifecycleController.java # showTerminal 增 isRelayDevice 透传
  app/src/main/java/com/webterm/mobile/MainActivity.java         # openSession 传 server.isRelayDevice()；实现 onOpenTerminal 回调接收并透传
  app/src/main/java/com/webterm/mobile/SessionCommandController.java   # Listener.onOpenTerminal 增 isRelayDevice；新建会话路径传入
  （ServerSessionMonitor.java 第一阶段不变）
```
