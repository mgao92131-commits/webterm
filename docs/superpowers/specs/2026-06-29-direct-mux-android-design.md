# 直连单连接复用（mux-only）设计：go-core + Android

## 0. 范围声明

本设计让 **go-core direct server** 与 **Android 原生客户端** 支持「一条 WebSocket 连接复用多个终端通道」。复用 relay agent 已被验证的隧道机制。

| 端 | 是否纳入本次 | 说明 |
|----|------------|------|
| go-core direct server | ✅ | `/ws/sessions` 升级为 mux-only |
| go-core relay agent | ✅ | 内部重构为 `mux.Serve`，对外协议不变 |
| Android 原生客户端 | ✅ | 整体改为 mux 单连接客户端 |
| Node.js relay server | ❌ | 一行不改（本来就支持 ws-connect/tunnel frame） |
| Node.js direct server（参考实现） | ❌ | 不动 |
| 浏览器前端（Vue） | ❌ | 本期不碰，保留现有 `/ws/sessions/{id}` 旧路径消费者 |
| Flutter 客户端 | ❌ | 本期不碰 |

**为什么是这个范围**：用户明确要「更合理的架构」，且客户端跟着改；但本期只做 go-core + Android。relay server 是协议翻译代理，tunnel ID 段内局部，Android 作为中继客户端连 relay master，relay server 透传 ws-connect/tunnel frame 给 agent——这条中继路径天然走单连接，无需改 relay server。

---

## 1. 问题与动机

### 现状

| 端 | 文件 | 当前行为 |
|----|------|---------|
| direct server | `direct/server.go:111-140` | `/ws/sessions` 裸 JSON 建 ManagerClient；`/ws/sessions/{id}` 每终端独立 WS |
| relay agent | `relay/client.go:56-176` + `transport.go` + `virtual_socket.go` | 单 WS 接 `ws-connect`，`connectionTransport`+`virtualSocket` 多路复用 |
| Android `TerminalConnection` | `TerminalConnection.java:132-202` | 每终端独立 `/ws/sessions/{id}` WS（binary 子协议） |
| Android `ServerSessionMonitor` | `ServerSessionMonitor.java:51-109` | 独立 `/ws/sessions` 裸 JSON 收会话列表 |

**核心重复**：`relay/client.go:handleWSConnect`（127-176）与 `direct/server.go:routeWebSocket` 做完全相同的事——按 path 决定建 `ManagerClient` 还是 `Client`。`connectionTransport` 是通用 tunnel frame 路由，但锁在 relay 包内，direct 无法复用。

**Android 现状**：两个模式（直连/中继）都是「manager 一条 WS + 每终端一条独立 WS」，终端数据不复用 manager 连接。

### 目标

1. 提取 `relay/connectionTransport` + `virtualSocket` 为独立 `mux` 包，direct server 与 relay agent 共用。
2. direct server 的 `/ws/sessions` 升级为 **mux-only**：删除 `/ws/sessions/{id}` 路由与重复分流代码，统一 `mux.Serve`。
3. `session.Client` / `ManagerClient` / `Socket` **零改动**。
4. relay server 与中继客户端协议层**一行不改**。
5. Android 改为 mux 单连接客户端：`ServerSessionMonitor` 与所有终端共用一条 WS。

### 非目标（明确排除）

- ❌ 浏览器前端、Flutter 客户端迁移——本期不碰。
- ❌ 让 relay server 变透明转发器（端到端 tunnel ID）——超出范围。
- ❌ Android 引入 screen 模式——统一 binary，YAGNI。
- ❌ 把 session CRUD（REST API）搬进 mux 控制面——直连能 serve 真 HTTP，CRUD 留 REST，只有实时流走 mux。

---

## 2. 核心架构

### 2.1 提取 `mux` 包（direct 与 relay agent 共用）

把 relay 现有 `connectionTransport` + `virtualSocket` 泛化为 `mux.Session`：

```
go-core/internal/mux/
  session.go          # mux.Session：包装 WS 连接，readLoop 分发 ws-connect/ws-close/tunnel frame
  virtual_socket.go   # VirtualSocket：实现 session.Socket，经 tunnel frame 多路复用
  handler.go          # OpenSessionOrManager：direct/relay 共用的 OnOpen 处理器
  session_test.go     # 单测
```

`mux.Session` 职责（与 relay 现有行为一致，已被 relay 验证）：
- `Serve(conn, opts)` 包装一条已建立的 WS；`Run(ctx)` 启动 readLoop，**不自动建任何通道**。
- 收 `ws-connect` → 建 VirtualSocket → 调 `OnOpen` → 成功回 `ws-connected`，失败回 `ws-error`。
- 收 `ws-close` → 关对应通道。
- 收二进制 tunnel frame → 解码按 ID 路由到 VirtualSocket.Emit。
- 物理连接断开 → 关闭所有 VirtualSocket。
- `OnControl` hook（可选）：mux 不识别的控制消息（relay 的 `http-request`/`p2p-*`）透传上层。direct 不设。

`VirtualSocket` 实现现有 `session.Socket` 接口 → `session.Client` / `ManagerClient` **零改动**即可使用。

### 2.2 manager 通道由 `ws-connect` 显式建立

**`mux.Session.Run()` 不自动创建任何 manager 通道**。manager 与终端通道一样，由客户端发 `ws-connect path=/ws/sessions` 显式建立。这与 Node relay agent（`server/agent.js:228-232`）一致——relay 主动发 `ws-connect path=/ws/sessions` 建 manager，agent 不自动建。自动建会造成 relay 侧双重 manager。

三种场景的处理：

| 场景 | manager 怎么建立 | mux.Session 角色 |
|------|----------------|-----------------|
| relay agent（relay 发 `ws-connect path=/ws/sessions`） | onOpen 收到 → 建 ManagerClient | 纯被动 |
| direct server + Android mux 客户端（客户端发 `ws-connect path=/ws/sessions`） | 同上 | 纯被动 |

### 2.3 `OpenSessionOrManager` — direct/relay 共用

从 `relay/client.go:handleWSConnect` 提取，去掉 sendJSON（由 mux 负责）：

- `path == /ws/sessions` → `session.NewManagerClient(vs)` + `go mc.Run(ctx, manager)`
- `path == /ws/sessions/{id}` → 取终端，`session.NewClient(vs, terminal, mode)` + `go client.Run(ctx)`
- 其余 → 返回 error，mux 发 `ws-error`

`mode` 由 `ws-connect.protocols` 经 `selectProtocol` + `session.ClientModeFromProtocol` 得到。这是最大复用收益：direct 与 relay agent 用同一段通道建立逻辑。

---

## 3. go-core 服务端落地

### 3.1 direct server — mux-only

- `/ws/sessions` 成为**唯一** WS 入口，始终 `mux.Serve`。
- WS 握手**不协商子协议**；终端子协议（binary/screen/json）改由 `ws-connect.protocols` 携带。
- **删除** `/ws/sessions/{id}` 路由分支。
- **删除** `routeWebSocket` 里按 path 分流建 ManagerClient/Client 的重复代码，统一 `mux.Serve(conn, OnOpen=OpenSessionOrManager)`。
- REST API（`/api/login`、`/api/sessions` CRUD、`/api/me`）**保留不动**。直连能 serve 真 HTTP，CRUD 留 REST，只有实时流（manager 推送 + 终端流）走 mux。
- 鉴权不变：升级 `/ws/sessions` 时验 cookie，整条 mux 连接及所有通道继承鉴权。

### 3.2 relay agent — 改用 `mux.Serve`

- `runOnce` 同步完成 `agent-register`/`registered` 握手后，`mux.Serve(conn, OnOpen=OpenSessionOrManager, OnControl=client.handleControl)`。
- `handleControl` 处理 `http-request`（调 `routeMemoryAPI` 回 JSON）、`p2p-offer`（回 `p2p-unavailable`）——逻辑从现有 `relay/client.go` 搬过来。
- **删除** `relay/transport.go`、`relay/virtual_socket.go`。
- **relay server（Node）和中继客户端协议层一行不改**——agent 内部重构对外无感。

### 3.3 协议层

- `protocol/tunnel.go`（tunnel frame 编解码）保留在 `protocol/`，mux 与 relay 共用。
- **不需要** `webterm.mux.v1` 子协议常量——mux-only 无需区分新旧，比原 spec 更简。

### 3.4 smoke 测试

- `cmd/webterm-flow-smoke`（直连）当前连 `/ws/sessions/{id}`——改用 `/ws/sessions` + `ws-connect` + tunnel frame。
- `cmd/webterm-relay-flow-smoke`（中继）已用 `ws-connect`——agent 侧重构后回归验证。

---

## 4. Android 客户端落地

### 4.1 新增 `MuxSession`（核心新组件）

对应 go-core 的 `mux.Session`，但为**客户端角色**（发起 `ws-connect`、解码 tunnel frame、把每个虚拟通道的帧分发回上层）。职责：

- 维护单条 `/ws/sessions` WS 连接（带 cookie 鉴权，复用现有 `monitorHttp` 的 ping interval）。
- **二进制帧**：解码 tunnel frame → 按 `tunnelId` 路由到对应通道回调。
- **文本帧**：处理 `ws-connected`/`ws-error`（通道建立结果），其余透传。
- 重连（复用现有指数退避）+ 重连后重建所有活动通道（沿用现有 `_recoverActiveTerminals` 思路）。

### 4.2 `WebTermProtocol` 扩展

新增 tunnel frame 编解码（对应 go-core `protocol/tunnel.go`）：

- `encodeTunnelFrame(tunnelId, payload, binary)` → `[0x01 | idLen | id | extraByte | payload]`
- `decodeTunnelFrame(data)` → `(tunnelId, extraByte, payload)`
- 常量：`MSG_TYPE_WS_DATA=0x01`、`WS_DATA_TEXT=0x01`、`WS_DATA_BINARY=0x02`

### 4.3 `ServerSessionMonitor` 改造为基于 `MuxSession`

- 现状：独立 `/ws/sessions` 裸 JSON 收列表。
- 改造：`MuxSession` 建连后，**发一条 `ws-connect`（`tunnelId="manager"`, `path="/ws/sessions"`, 无 protocols）** 建 manager 通道；manager 通道的 tunnel frame payload 是裸 JSON，复用现有 `dispatchMessage` 解析。
- 列表/会话/设备推送逻辑（`dispatchMessage`、`prefixRelaySessionIds`）**完全保留**。

### 4.4 `TerminalConnection` 改造为复用通道

- 现状：每终端独立 `/ws/sessions/{id}` WS。
- 改造：不再自己建 WS。`connect()` 调用 `MuxSession.openTerminal(sessionId, lastSeq, cols, rows)`：
  - 发 `ws-connect`（`tunnelId="term:{sessionId}"`, `path="/ws/sessions/{id}"`, `protocols=["webterm.binary.v1"]`）
  - 等 `ws-connected` 后发 `MSG_HELLO`（封装进 tunnel frame）
- 输入/resize/title：封装进 tunnel frame 经 `MuxSession` 发送。现有 `MSG_INPUT`/`MSG_RESIZE` 二进制帧不变，只是外面包一层 tunnel frame。
- 收数据：`MuxSession` 把 `tunnelId="term:{sessionId}"` 的 tunnel frame payload 投递给 `TerminalConnection.handleServerMessage`——**现有帧解析逻辑零改动**（`MSG_OUTPUT`/`MSG_STATE`/`MSG_INFO`/`MSG_EXIT` 照旧）。
- `close()`：发 `ws-close` 关闭该通道。
- **重连**：由 `MuxSession` 统一负责；终端通道在 manager 重连后自动重建，`lastSeq` 保留，靠 `MSG_HELLO.lastSeq` 增量恢复（沿用现有 EventRing 机制）。

### 4.5 终端子协议统一为 binary

Android 现状用 `webterm.binary.v1`（`BINARY_SUBPROTOCOL`）。`ws-connect.protocols` 携带它，服务端 `OpenSessionOrManager` 经 `selectProtocol` 选 binary → `ClientModeFromProtocol` → binary 模式。**Android 不引入 screen 模式**（YAGNI），保持单一模式降低复杂度。

### 4.6 生命周期与连接归属

- 一个 `ServerConfig`（一台服务器/一台设备）→ 一个 `MuxSession` → 一条 WS。
- 直连：`MuxSession` 连 `baseUrl/ws/sessions`。
- 中继：`MuxSession` 连 relay master URL `/ws/sessions?deviceId=xxx`。relay server 透传 ws-connect/tunnel frame 给 agent，agent 侧 `mux.Serve` 处理——**Android 中继路径天然走单连接，无需改 relay server**。
- 多终端共用同一 `MuxSession`，各自独立 `tunnelId` 通道。

---

## 5. 物理连接消息分层（per-leg）

注意：tunnel ID 是**段内局部**的，不端到端。

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

## 6. 向后兼容

```
go-core direct server /ws/sessions:   mux-only（唯一入口，旧 /ws/sessions/{id} 删除）
go-core relay agent:                  ws-connect 行为不变（relay server 不感知 agent 内部重构）
Node.js relay server:                 不动
Android 客户端:                        整体迁移到 mux
浏览器前端 / Flutter:                  本期不碰（go-core 删除 /ws/sessions/{id} 后它们会断——
                                      属已知范围外影响，本期不修复，后续单独迁移）
```

**已知范围外影响**：go-core direct server 删除 `/ws/sessions/{id}` 后，浏览器前端与 Flutter 的直连终端会断。本期明确不修复，作为后续单独工作项。

---

## 7. 实施顺序与测试

| 阶段 | 内容 | 风险 |
|------|------|------|
| 1 | 新建 `mux/` 包：`session.go`+`virtual_socket.go`+`handler.go`，从 relay 移出并改回指 | 低 |
| 2 | `mux/session_test.go`：ws-connect/ws-close/tunnel frame 路由/onOpen error→ws-error | 低 |
| 3 | relay agent 改用 `mux.Serve`+`OnControl`，删 `transport.go`/`virtual_socket.go` | 中 |
| 4 | 中继模式回归（relay-flow-smoke + 真实中继） | 中 |
| 5 | direct server `/ws/sessions` mux-only，删 `{id}` 路由与重复分流代码 | 中 |
| 6 | flow-smoke 改造为 ws-connect 客户端 | 低 |
| 7 | Android：`WebTermProtocol` 加 tunnel frame；新建 `MuxSession` | 中 |
| 8 | Android：`ServerSessionMonitor` 改 manager 通道；`TerminalConnection` 改复用通道 | 中 |
| 9 | Android 端到端：直连单终端/多终端/重连；中继单终端/多终端/重连 | 中 |

阶段 1-2 纯新增+测试，不碰现有逻辑。3-4 中继侧重构（**最大风险点**：agent 内部从手写 transport 换成 mux，必须保证对 relay server 协议完全不变）。5-6 直连侧。7-9 Android。

---

## 8. 设计决策记录

### Q: 为什么 `Run()` 不自动建 manager 通道？
relay agent 侧，manager 由 relay 主动发 `ws-connect path=/ws/sessions` 建立。自动建会造成 relay 侧双重 manager。所有通道统一由 ws-connect 建立，与 Node relay agent 一致。

### Q: 为什么需要 `OnControl` hook？
relay agent 物理连接上除 `ws-*` 还有 `http-request`/`p2p-offer` 等控制消息。mux 只认 `ws-*`，其余透传给 `relay.Client`。direct server 不设此 hook。

### Q: 为什么 direct server 不用子协议区分新旧客户端？
mux-only，无新旧之分，比 opt-in 方案更简。终端子协议改由 `ws-connect.protocols` 携带（relay 已如此）。

### Q: 为什么 REST API 保留、不搬进 mux 控制面？
直连能 serve 真 HTTP；只有实时流（manager 推送 + 终端流）才需要长连接复用。CRUD 留 REST，职责清晰。relay 才需要 `http-request` 隧道（agent 不能 serve HTTP）。

### Q: 为什么 Android 终端统一 binary、不引入 screen？
现有 binary 用得好；引入 screen 增加复杂度，YAGNI。

### Q: 中继模式下 Android 单连接为何不用改 relay server？
relay server 本来就支持 ws-connect/tunnel frame 透传（agent 的 `server/agent.js` 即被它驱动）。Android 作为中继客户端连 relay master，relay server 把 ws-connect/tunnel frame 转给 agent，agent 侧 `mux.Serve` 处理。tunnel ID 段内局部，relay server 协议层无需改。

### Q: 背压如何处理？
**读侧**（VirtualSocket.incoming）：buffer 256，满则丢帧+关闭该通道（沿用现有 `Emit` 行为），不阻塞物理 ReadLoop。**写侧**：单 writeMu + 每写 10s 超时（沿用现有 `sendBinary`）。一个慢通道的写最坏阻塞所有通道 10s——这是现有行为，本设计未改变也未声称解决。

---

## 9. 文件变更清单

```
go-core 新增:
  internal/mux/session.go
  internal/mux/virtual_socket.go
  internal/mux/handler.go
  internal/mux/session_test.go

go-core 修改:
  internal/relay/client.go            # 改用 mux.Serve + OnControl；register 独立
  internal/direct/server.go           # /ws/sessions mux-only，删 {id} 路由与重复分流

go-core 删除:
  internal/relay/transport.go         # 逻辑移入 mux.Session
  internal/relay/virtual_socket.go    # 移入 mux 包

go-core smoke:
  cmd/webterm-flow-smoke/main.go      # 改为 ws-connect 客户端
  cmd/webterm-relay-flow-smoke/main.go # 回归验证

Android 新增:
  app/src/main/java/com/webterm/mobile/MuxSession.java

Android 修改:
  app/src/main/java/com/webterm/mobile/WebTermProtocol.java   # 加 tunnel frame 编解码
  app/src/main/java/com/webterm/mobile/ServerSessionMonitor.java # 改 manager 通道
  app/src/main/java/com/webterm/mobile/TerminalConnection.java   # 改复用通道
```
