# mux.Session：直连与中继 Agent 统一传输层设计（路线 A）

## 0. 范围声明

本设计**只统一两类"服务端角色"的连接处理**：

| 角色 | 物理连接来源 | 是否纳入 mux |
|------|------------|-------------|
| Go direct server | 客户端直连 `/ws/sessions` | ✅ 纳入 |
| Go relay agent | relay server 连 agent 的 `/ws/agent` | ✅ 纳入 |
| Node.js relay server | — | ❌ **完全不动** |
| 中继客户端（client↔relay 这段） | client 连 relay 的 `/ws/sessions` 等 | ❌ **完全不动** |
| 直连客户端（可选增强） | client 直连 server `/ws/sessions` | ⚪ 可选，非必须 |

**为什么是这个范围**：relay server 是协议翻译代理（拆帧/包帧/生成局部 tunnel ID `tc_xxx`/改写 session ID），不是透明隧道。tunnel ID 不是端到端的。因此"一个对称 mux.Session 走通直连和中继"在端到端意义上不成立。mux.Session 只能在**单段物理连接内**成立——direct server 这段、以及 relay agent 这段。这两段恰好都是"服务端角色"：接受 `ws-connect`、建虚拟通道、跑 `session.Client`/`ManagerClient`。这就是复用点。

**不纳入的部分明确保持现状**：
- 中继客户端：manager 一个 WS（裸 JSON）+ 每终端一个独立 WS
- relay server：原样转发，不改

---

## 1. 问题与动机

### 现状

| 端 | 文件 | 当前行为 |
|----|------|---------|
| direct server | `direct/server.go:111-140` | `/ws/sessions` 直接建 ManagerClient（裸 JSON）；`/ws/sessions/:id` 每终端独立 WS |
| relay agent | `relay/client.go:56-176` + `transport.go` + `virtual_socket.go` | 单 WS 接收 `ws-connect`，用 `connectionTransport` + `virtualSocket` 多路复用 |

**核心重复**：`relay/client.go:handleWSConnect`（127-176）根据 path 决定建 `ManagerClient` 还是 `Client`。`direct/server.go:routeWebSocket` 做完全相同的事。`connectionTransport`（`transport.go`）是通用的 tunnel frame 路由，但被锁在 relay 包内，直连无法复用。

### 目标

1. 提取 `relay/connectionTransport` + `virtualSocket` 为独立 `mux` 包，direct server 和 relay agent 共用
2. direct server 的 `/ws/sessions` 升级为 mux 入口（接受 `ws-connect` + tunnel frame），同时**保留对裸 JSON 旧客户端的兼容**
3. `session.Client` / `session.ManagerClient` / `session.Socket` **零改动**
4. relay server 和中继客户端**一行不改**

### 非目标（明确排除）

- ❌ 端到端单连接多路复用（中继模式下客户端到 agent 的单连接）——需要改 relay server，超出范围
- ❌ 让 Flutter/Android 在中继模式下复用 manager WS 传终端数据——同上原因
- ❌ HTTP API 代理（`http-request`/`http-response`）纳入 mux 包——只在中继存在，保留在 relay.Client

---

## 2. 关键澄清：manager 通道如何建立

之前设计错误地假设 mux.Session 应在 `Run()` 时自动创建一个 id="manager" 的 ManagerClient。**这是错的**，会与 relay 主动发的 `ws-connect path=/ws/sessions` 冲突，造成双重 manager。

正确模型：**manager 通道和其他通道一样，由 `ws-connect` 建立**，不自动创建。

三种实际情况的处理：

| 场景 | manager 怎么建立 | mux.Session 角色 |
|------|----------------|-----------------|
| relay agent（relay 发 `ws-connect` path=/ws/sessions，ID=tc_xxx） | onOpen 收到 path=/ws/sessions → 建 ManagerClient | 纯被动，不自动建 |
| direct server + 新 mux 客户端（客户端发 `ws-connect` path=/ws/sessions） | 同上，onOpen 建 ManagerClient | 纯被动 |
| direct server + 旧裸 JSON 客户端 | **不经过 mux**，直接在 conn 上建 ManagerClient | mux 不参与 |

因此 **`mux.Session.Run()` 不自动创建任何 manager 通道**。所有通道都通过 `ws-connect` 显式建立。

---

## 3. 架构概览

```
        session.Client / session.ManagerClient           ← 零改动
                    ▲
                    │ session.Socket 接口
        ┌───────────┴───────────┐
        │                       │
  WebSocketAdapter          VirtualSocket              ← 两者都实现 Socket
  (裸 JSON 旧客户端)         (mux 虚拟通道)
        │                       │
        │            ┌──────────┴──────────┐
        │            │     mux.Session      │
        │            │  ReadLoop            │
  direct server      │  WriteLoop (写锁)     │
  /ws/sessions       │  channels map        │
  旧路径分支          │  onOpen handler      │
  (不走 mux)          │  OnControl hook      │
                     │  tunnel frame codec  │
                     └──────────┬──────────┘
                          ▲            ▲
            Serve(conn)   │            │  (relay agent 用)
        ┌─────────────────┘            └────────────────┐
        │  direct server                                 │  relay agent
        │  /ws/sessions (mux 子协议时)                    │  /ws/agent 连接
        └────────────────────────────────────────────────┘
```

---

## 4. 包结构

```
go-core/internal/mux/          ← 新包
  session.go                   ← mux.Session 核心类型 + Serve
  virtual_socket.go            ← VirtualSocket（从 relay 移出，改 session 回指）
  session_test.go              ← 单元测试
  handler.go                   ← OpenSessionOrManager（direct/relay 共用）

go-core/internal/relay/        ← 简化
  client.go                    ← 改用 mux.Serve + OnControl hook
  (删除 transport.go)          ← 逻辑移入 mux.Session
  (删除 virtual_socket.go)     ← 移入 mux 包

go-core/internal/direct/       ← /ws/sessions 升级
  server.go                    ← 按 subprotocol 分流：mux 或旧裸 JSON
                                ← /ws/sessions/:id 保留（向后兼容）
```

`tunnel.go` 编解码保留在 `internal/protocol/`（mux 和 relay 共用，不重复）。

---

## 5. 核心类型

### 5.1 `mux.Session`

```go
package mux

import (
    "context"
    "nhooyr.io/websocket"
    "webterm/go-core/internal/protocol"
)

// OpenHandler 处理一个新建立的虚拟通道。
// 返回 error 时，调用方（mux.Session）负责向客户端发送 ws-error。
type OpenHandler func(ctx context.Context, vs *VirtualSocket, path string, protocols []string) error

// ControlHandler 处理 mux 不识别的控制消息（透传给上层）。
// 用于 relay agent 的 http-request / p2p-* 等。
type ControlHandler func(ctx context.Context, msg map[string]any)

type Session struct {
    conn       *websocket.Conn
    writeMu    sync.Mutex          // 物理连接写锁
    channels   map[string]*VirtualSocket
    channelsMu sync.RWMutex
    onOpen     OpenHandler
    onControl  ControlHandler      // 可为 nil（direct server 不需要）
    done       chan struct{}
    closeOnce  sync.Once
}

type ServeOpts struct {
    OnOpen    OpenHandler     // 必填
    OnControl ControlHandler  // 可选
}

// Serve 包装一个已建立的 WebSocket 连接，启动多路复用。
// 调用方负责 conn 的建立（direct server 用 websocket.Accept；
// relay agent 用 websocket.Dial + agent-register）。
func Serve(conn *websocket.Conn, opts *ServeOpts) *Session
```

**关键决策**：

| 决策 | 选择 | 理由 |
|------|------|------|
| 单一入口 `Serve(conn)`，无 `Dial` | ✅ | 两端都是"接受 ws-connect 的服务端角色"，连接建立方式不同（Accept vs Dial+register），由调用方完成。mux 只负责连接建立后的多路复用 |
| `Run()` 不自动建 manager 通道 | ✅ | 修正之前的错误。manager 与其他通道一样由 ws-connect 建立 |
| `OnControl` 透传 hook | ✅ | relay agent 物理连接上还有 http-request/p2p 等控制消息，mux 不识别，透传给 relay.Client 处理 |
| `OnOpen` 返回 error → mux 发 ws-error | ✅ | 责任清晰，handler 只需返回业务错误 |
| 单写锁保护物理连接 | ✅ | 多虚拟通道并发写入需串行化 |

### 5.2 `Run()` 方法

```go
// Run 启动 readLoop，阻塞直到连接关闭。
// 不创建任何通道——所有通道由 ws-connect 显式建立。
// 物理连接断开时自动关闭所有 VirtualSocket。
func (s *Session) Run(ctx context.Context) error {
    defer s.closeAllChannels()
    return s.readLoop(ctx)
}
```

### 5.3 `VirtualSocket`

从 `relay/virtual_socket.go` 移出，改动极小：

```go
type VirtualSocket struct {
    id        string
    protocol  string
    session   *Session          // 改：回指 Session（原为 relayTransport 接口）
    incoming  chan virtualMessage
    done      chan struct{}
    closeOnce sync.Once
}
```

**改动**：
- `transport relayTransport` → `session *Session`。Write 时调用 `session.writeBinary()`（受 writeMu 保护）
- 其余（`Read`/`Write`/`Emit`/`Close`/`CloseWithNotify`）逻辑不变
- 仍实现 `session.Socket` 接口，`session.Client` 零改动即可使用

---

## 6. ReadLoop：统一消息分发

```go
func (s *Session) readLoop(ctx context.Context) error {
    for {
        msgType, data, err := s.conn.Read(ctx)
        if err != nil {
            return err
        }
        switch msgType {
        case websocket.MessageText:
            s.handleControlMessage(ctx, data)   // ws-connect / ws-close / ws-connected / 透传
        case websocket.MessageBinary:
            s.handleBinaryFrame(data)           // tunnel frame → VirtualSocket.Emit
        }
    }
}
```

### 6.1 `handleControlMessage`

```go
func (s *Session) handleControlMessage(ctx context.Context, data []byte) {
    var msg map[string]any
    if json.Unmarshal(data, &msg) != nil {
        return
    }
    switch stringValue(msg["type"]) {
    case protocol.WSConnect:
        s.handleWSConnect(ctx, msg)
    case protocol.WSClose:
        s.closeSocket(stringValue(msg["tunnelConnectionId"]))
    case protocol.WSConnected, protocol.WSError:
        // 服务端角色不应收到这些（它们是服务端发出的）。忽略。
    default:
        // 透传给上层：http-request / p2p-* / agent-register 等
        if s.onControl != nil {
            s.onControl(ctx, msg)
        }
    }
}
```

### 6.2 `handleWSConnect`（修正：onOpen 失败时发 ws-error）

```go
func (s *Session) handleWSConnect(ctx context.Context, msg map[string]any) {
    tunnelID := stringValue(msg["tunnelConnectionId"])
    path := stringValue(msg["path"])
    protocols := protocolsValue(msg["protocols"])
    if tunnelID == "" {
        return
    }
    vs := s.newSocket(tunnelID, selectProtocol(protocols))
    if err := s.onOpen(ctx, vs, cleanPath(path), protocols); err != nil {
        s.removeSocket(tunnelID)
        _ = s.sendJSON(ctx, map[string]any{
            "type":               protocol.WSError,
            "tunnelConnectionId": tunnelID,
            "code":               http.StatusNotFound,
            "message":            err.Error(),
        })
        return
    }
    _ = s.sendJSON(ctx, map[string]any{
        "type":               protocol.WSConnected,
        "tunnelConnectionId": tunnelID,
    })
}
```

### 6.3 `handleBinaryFrame`

与现有 `relay/transport.go:handleBinaryFrame` 完全一致：解码 tunnel frame → 按 ID 查 VirtualSocket → `Emit`。

---

## 7. `OpenSessionOrManager`：direct/relay 共用的核心

从 `relay/client.go:handleWSConnect` 提取（去掉 sendJSON 部分，由 mux 负责）：

```go
// handler.go —— direct server 和 relay agent 共用
func OpenSessionOrManager(
    ctx context.Context,
    manager *session.Manager,
    vs *VirtualSocket,
    path string,
    protocols []string,
) error {
    switch {
    case path == "/ws/sessions":
        mc := session.NewManagerClient(vs)
        go mc.Run(ctx, manager)
        return nil

    case strings.HasPrefix(path, "/ws/sessions/"):
        id, _ := url.PathUnescape(strings.TrimPrefix(path, "/ws/sessions/"))
        terminal, ok := manager.Get(id)
        if !ok {
            return fmt.Errorf("session %s not found", id)
        }
        mode := session.ClientModeFromProtocol(selectProtocol(protocols))
        client := session.NewClient(vs, terminal, mode)
        go client.Run(ctx)
        return nil

    default:
        return fmt.Errorf("unknown path: %s", path)
    }
}
```

直连 `direct.Server` 和中继 `relay.Client` 都把这个函数作为 `OnOpen` 传入。这是本设计最大的复用收益。

---

## 8. 各端使用方式

### 8.1 relay agent（中继，agent 侧）

```go
func (client *Client) runOnce(ctx context.Context) error {
    conn, _, err := websocket.Dial(ctx, relayURL, nil)
    if err != nil { return err }
    defer conn.Close(websocket.StatusNormalClosure, "")

    // agent-register / registered 仍由 relay.Client 处理，
    // 但通过 mux 的 OnControl hook 接收后续控制消息（http-request 等），
    // 避免在 mux.Serve 之前裸读 conn 造成消息吞没。
    // 见 §8.3 对 agent-register 的处理。
    if err := client.register(ctx, conn); err != nil {
        return err
    }

    sess := mux.Serve(conn, &mux.ServeOpts{
        OnOpen: func(ctx context.Context, vs *mux.VirtualSocket, path string, protocols []string) error {
            return mux.OpenSessionOrManager(ctx, client.app.Sessions(), vs, path, protocols)
        },
        OnControl: client.handleControl,  // http-request / p2p-*
    })
    return sess.Run(ctx)
}
```

`client.handleControl` 处理 `http-request`（调 `routeMemoryAPI` 回 JSON）和 `p2p-offer`（回 `p2p-unavailable`）——逻辑从现有 `relay/client.go` 搬过来。

### 8.2 direct server（直连，按子协议分流）

```go
func (direct *Server) routeWebSocket(w http.ResponseWriter, r *http.Request, path string) {
    if path == "/ws/sessions" {
        // 用 subprotocol 区分新旧客户端
        conn, _ := websocket.Accept(w, r, &websocket.AcceptOptions{
            Subprotocols: []string{MuxSubprotocol},  // "webterm.mux.v1"
        })
        if conn.Subprotocol() == MuxSubprotocol {
            // 新 mux 客户端：单连接多路复用
            sess := mux.Serve(conn, &mux.ServeOpts{
                OnOpen: func(ctx context.Context, vs *mux.VirtualSocket, p string, protos []string) error {
                    return mux.OpenSessionOrManager(ctx, direct.app.Sessions(), vs, p, protos)
                },
                // direct server 不需要 OnControl
            })
            sess.Run(r.Context())
            return
        }
        // 旧裸 JSON 客户端：现有逻辑，不动
        client := session.NewManagerClient(session.NewWebSocketAdapter(conn))
        client.Run(r.Context(), direct.app.Sessions())
        return
    }
    // /ws/sessions/:id — 保留，向后兼容旧客户端
    // ...现有逻辑...
}
```

**新增子协议常量** `webterm.mux.v1`（加到 `protocol/constants.go`），用于在握手时区分新旧客户端。旧客户端不带此协议 → 走裸 JSON 分支，零影响。

### 8.3 agent-register 的处理（避免裸读竞态）

之前在 `mux.Serve` 之前裸读一次 `conn.Read` 等 `registered` 会吞掉后续消息。改为：

```go
// register 在 mux.Serve 之前同步完成注册握手。
// 由于 relay 在 registered 之后才会发 ws-connect/http-request，
// 且 nhooyr/websocket 的 Read 是阻塞单读，注册期不会并发其他消息，
// 同步注册是安全的。注册完成后立即交给 mux.Serve 接管。
func (client *Client) register(ctx context.Context, conn *websocket.Conn) error {
    if err := writeJSON(ctx, conn, map[string]any{
        "type":       protocol.AgentRegister,
        "deviceName": client.cfg.DeviceName,
        "secret":     client.cfg.Secret,
    }); err != nil {
        return err
    }
    // 阻塞读一条，期望 registered
    _, data, err := conn.Read(ctx)
    if err != nil { return err }
    var msg map[string]any
    if json.Unmarshal(data, &msg) != nil { return errors.New("bad register response") }
    if stringValue(msg["type"]) != protocol.Registered {
        return fmt.Errorf("relay error: %s", stringValue(msg["message"]))
    }
    client.app.SetRelayConnected(true, stringValue(msg["deviceId"]), "")
    return nil
}
```

注：registered 是 relay 在 agent-register 后**立即**回复的下一条消息（`ws-handlers.js:68`），中间不会插入其他消息，所以同步单读安全。注册后 relay 才可能发 `ws-connect`/`http-request`（由客户端活动触发），交给 mux.Serve。

### 8.4 直连客户端（可选，非必须）

直连客户端若想用单连接多路复用：
- Flutter：已有 `TerminalConnection.multiplexed` + `ServerSessionMonitor._handleTunnelFrame`，**仅直连可用**。握手时协商 `webterm.mux.v1`。
- Android：可选新增 `TunnelFrame`/`MuxSession`，仅直连用。
- **中继模式下客户端不使用 mux**，维持现状。

客户端复用是可选项，不阻塞服务端统一。即使没有客户端 mux，direct server 升级后旧客户端照常工作。

---

## 9. 向后兼容

```
direct server /ws/sessions:
  ├── 客户端带 webterm.mux.v1 子协议 → mux.Serve（单连接多路复用）
  └── 客户端不带该子协议            → 旧裸 JSON ManagerClient（现有逻辑）

direct server /ws/sessions/:id:    保留，旧客户端每终端独立 WS

relay agent:                        ws-connect 行为不变（relay 不感知 agent 内部重构）

relay server:                       不动

中继客户端:                          不动
```

每个旧客户端路径都保留，无 breaking change。

---

## 10. 物理连接消息分层（per-leg）

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
        （direct 段：客户端定的 id；relay agent 段：relay 给的 tc_xxx）
```

manager 通道不特殊：它就是 path=/ws/sessions 的 ws-connect 建立的普通 VirtualSocket，`ManagerClient` 通过它发裸 JSON（`socket.Write(MessageText, ...)`），走 tunnel frame 的 text 类型。

---

## 11. 实施顺序

| 阶段 | 内容 | 风险 |
|------|------|------|
| 1 | 创建 `mux/` 包：`session.go` + `virtual_socket.go`（从 relay 移出并改回指） | 低 |
| 2 | `mux/session_test.go`：测 ws-connect/ws-close/tunnel frame 路由/onOpen error→ws-error | 低 |
| 3 | `mux/handler.go`：提取 `OpenSessionOrManager` | 低 |
| 4 | 改造 `relay/client.go` 用 `mux.Serve` + `OnControl`，删除 `transport.go`/`virtual_socket.go` | 中 |
| 5 | relay agent 端 E2E 验证（中继模式回归） | 中 |
| 6 | direct server `/ws/sessions` 按子协议分流，加 mux 分支 | 中 |
| 7 | direct server 旧裸 JSON 分支回归 | 低 |
| 8 | （可选）Flutter/Android 直连客户端协商 `webterm.mux.v1` | 低 |

阶段 1-3 是纯新增 + 测试，不碰现有逻辑，可独立验证。阶段 4-5 是中继侧重构，有中继模式回归风险。阶段 6-7 是直连侧。客户端（8）可选，不阻塞。

---

## 12. 设计决策记录

### Q: 为什么 `Run()` 不自动建 manager 通道？
relay agent 侧，manager 由 relay 主动发 `ws-connect path=/ws/sessions` 建立；direct server 旧客户端根本不走 mux。自动建会造成 relay 侧双重 manager。所有通道统一由 ws-connect 建立。

### Q: 为什么需要 `OnControl` hook？
relay agent 物理连接上除了 `ws-*` 还有 `http-request`/`p2p-offer` 等控制消息。mux 只认 `ws-*`，其余透传给 `relay.Client` 处理。direct server 不设此 hook。

### Q: 为什么 direct server 用子协议区分新旧客户端，而不是首条消息嗅探？
握手时协商子协议是 WebSocket 标准做法，干净且无歧义。首条消息嗅探需要缓冲、超时、协议判定，复杂且易错。旧客户端不带 `webterm.mux.v1` → 自动走旧分支。

### Q: 为什么不把 HTTP API 代理纳入 mux？
HTTP API 代理（`http-request`/`http-response`）只在中继模式存在，直连走真 HTTP。它通过 `OnControl` 透传给 relay.Client，不污染 mux 的单一职责。

### Q: 背压如何处理？
**读侧**（VirtualSocket.incoming）：buffer 256，满则丢帧 + 关闭该通道（沿用现有 `Emit` 行为），不阻塞物理 ReadLoop。
**写侧**：单 writeMu + 每写 10s 超时（沿用现有 `sendBinary`）。一个慢通道的写最坏阻塞所有通道 10s——这是**现有行为，本设计未改变也未声称解决**。若未来需要改进，可引入 per-channel 写队列，但超出本设计范围。

### Q: 为什么客户端中继模式不能也用 mux？
relay server 是协议翻译代理：它自己生成 `tc_xxx` tunnel ID、拆掉 agent→client 方向的 tunnel frame 只发裸 payload、把 client→agent 裸消息包成 tunnel frame。tunnel ID 不端到端。要让客户端中继单连接多路复用，必须把 relay 改成透明转发器（路线 B，本设计不涉及）。

---

## 13. 文件变更清单

```
新增:
  go-core/internal/mux/session.go
  go-core/internal/mux/virtual_socket.go
  go-core/internal/mux/handler.go
  go-core/internal/mux/session_test.go

修改:
  go-core/internal/protocol/constants.go   # 加 MuxSubprotocol = "webterm.mux.v1"
  go-core/internal/relay/client.go          # 改用 mux.Serve + OnControl；register 独立
  go-core/internal/direct/server.go         # /ws/sessions 按子协议分流

删除:
  go-core/internal/relay/transport.go       # 逻辑移入 mux.Session
  go-core/internal/relay/virtual_socket.go  # 移入 mux 包

可选（客户端直连 mux，非必须）:
  flutter_client: 协商 webterm.mux.v1（multiplexed 模式已具备能力）
  android-client: 新增 TunnelFrame / MuxSession（仅直连）
```
