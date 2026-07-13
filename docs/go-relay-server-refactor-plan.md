# Go Relay Server 轻量中转站重构计划

## 目标

本计划的目标不是把现有 Node relay-server 逐行翻译成 Go，也不是把 Relay 做成理解终端业务的中央服务。新的 Go Relay Server 应该是一个轻量、清晰、可观测的中转站：

```text
Go Relay = Auth + Device Registry + Presence + Stream Router + Tunnel Broker
```

Relay 只负责用户、设备、在线状态、连接路由和网络流生命周期。终端、session、pty、shell、屏幕状态和业务语义全部由 Go PC Agent 负责。

重构后的 Relay Server 应该具备这些特征：

1. 设备和连接生命周期可控：Agent、Client、Stream 都有明确状态和关闭路径。
2. 中转职责单一：HTTP、WebSocket、Terminal、P2P fallback 都被当作网络 stream 转发。
3. 业务边界清楚：Relay 不创建、不缓存、不解释 terminal session。
4. 可观测：能看到在线 Agent、活跃 stream、流量、延迟、错误和关闭原因。
5. 可扩展：后续加入限流、审计、P2P、文件传输时仍然围绕 stream/tunnel 模型扩展。

## 边界原则

### Relay 负责

```text
用户登录 / 鉴权
设备注册 / 设备列表
Agent 在线状态
Client -> Agent 路由
HTTP / WebSocket / Terminal stream 中转
连接生命周期和超时清理
基础 debug / metrics / 限流
```

### Relay 不负责

```text
创建真实终端进程
管理 session 列表
缓存 session 摘要
重命名 / 关闭 terminal session
维护 terminal state
处理 lastSeq / replay / screen snapshot
理解 cwd / termTitle / shell / pty
解析终端输入输出语义
```

### Go PC Agent 负责

```text
session 列表
session 创建 / 关闭 / 重命名
pty / shell
终端输入输出
terminal state / replay
termTitle / cwd 等本机业务信息
文件、剪贴板等本机能力
```

## 非目标

第一阶段不要求兼容 Node relay-server 的内部实现和旧协议细节；Android 原生端和 Go PC Agent 可以随 Go Relay 一起迁移到新协议。

但第一阶段不能丢失现有产品能力：

```text
登录
设备列表
Agent 在线状态
创建 / 关闭 / 重命名 session
Android 打开终端
终端输入输出
断线重连
P2P 不可用时 relay fallback
```

第一阶段不做这些事情：

```text
不让 Relay 成为 session truth source
不在 Relay 缓存 session 摘要
不在 Relay 实现 pty / shell / terminal state
不把 Go PC Agent 的 relay client 和 Go Relay Server 放进同一职责包
不一次性实现完整 WebRTC DataChannel
不做多实例 Relay 集群
```

## 当前 Node Relay 的问题

当前 Node relay-server 的主要问题不是语言，而是职责和状态散在 handler 与 map 中：

```text
HTTP API handler
WebSocket upgrade handler
Agent 注册
Manager 推送
HTTP proxy pending
WS tunnel pending
P2P signaling pending
设备在线状态
会话 ID 改写
认证与 Cookie
静态资源托管
```

这些职责集中在少数 handler 和全局 Map 中，带来长期风险：

1. timeout、client close、agent close 都可能触发清理，关闭路径分散。
2. WebSocket 写入路径分散，迁移到 Go 后容易出现并发写同一连接。
3. HTTP、WS、P2P 各自维护 pending 状态，清理规则重复。
4. manager 侧状态由局部逻辑拼出来，不是清晰的 presence/stream 事件。
5. 终端数据、JSON 控制消息、HTTP chunk 的背压和流量统计难以统一。
6. 排障依赖日志，缺少连接和 stream 级别的实时状态。

Go 重构要解决这些网络与生命周期问题，但不能把 session 业务状态搬进 Relay。

## 总体架构

建议新增 Go Relay Server 相关目录，并避免复用当前 `go-core/internal/relay` 作为服务端包名。当前 `go-core/internal/relay` 更接近 PC Agent 侧 relay client；服务端应单独建模。

```text
go-core/cmd/webterm-relay/
  main.go

go-core/internal/relayapp/
  app.go
  lifecycle.go
  config.go

go-core/internal/relaycore/
  model.go
  state.go
  frame.go
  stream.go
  event.go
  errors.go

go-core/internal/relayrouter/
  registry.go
  router.go
  stream_manager.go

go-core/internal/relaygateway/
  agent_gateway.go
  client_gateway.go
  http_gateway.go
  ws_gateway.go
  p2p_gateway.go

go-core/internal/relaycontrol/
  auth_handler.go
  device_handler.go
  presence_handler.go

go-core/internal/relaystore/
  db.go
  users.go
  devices.go
  credentials.go
  tokens.go

go-core/internal/relaymetrics/
  metrics.go
  debug_handlers.go
```

分层职责：

```text
relayapp
  组合模块、读取配置、启动 HTTP server、处理 shutdown

relaycore
  纯网络领域模型、状态机、frame 编解码、事件类型、错误类型

relayrouter
  在线连接索引、路由选择、Stream 生命周期管理

relaygateway
  外部协议接入，把 HTTP/WS/Agent/P2P 请求转换为网络 stream

relaycontrol
  控制面 API：用户、设备、presence，不包含 session 业务真相

relaystore
  持久化：用户、设备、凭证、token

relaymetrics
  metrics、debug endpoint、连接快照
```

## 核心领域模型

### AgentConnection

AgentConnection 代表一台受控设备与 Relay 的长连接，不应该只是一个裸 `*websocket.Conn`。

```text
AgentConnection
  ID
  UserID
  DeviceID
  DeviceName
  State
  Conn
  SendQueue
  ActiveStreams
  Heartbeat
  Stats
  ConnectedAt
  LastSeenAt
  CloseReason
```

状态：

```text
connecting
active
draining
closed
```

每个 AgentConnection 固定三个 loop：

```text
readLoop
writeLoop
heartbeatLoop
```

写 WebSocket 必须统一走 SendQueue，不允许业务 goroutine 直接写连接。

### ClientConnection

ClientConnection 代表 Android、Web、Flutter 或 debug 客户端。

```text
ClientConnection
  ID
  UserID
  Kind: manager / terminal / api-stream / debug
  State
  Conn
  SendQueue
  Stats
  ConnectedAt
  CloseReason
```

ClientConnection 只表达网络连接，不保存 session 列表。

### Device Presence

Presence 是 Relay 的权威状态，回答“哪台 Agent 当前在线、属于哪个用户、连接是否可路由”。

```text
DevicePresence
  UserID
  DeviceID
  DeviceName
  AgentConnectionID
  Online
  ConnectedAt
  LastSeenAt
```

Relay 的 `/api/devices` 或等价新接口只能返回设备和在线信息。它不附带 session 摘要，不附带 terminal state。

### Stream

Stream 是 Relay 数据面的统一抽象。HTTP 请求、WebSocket 隧道、Terminal 连接、P2P fallback 都是网络 stream。

```text
Stream
  ID
  Kind: http / websocket / terminal / p2p
  UserID
  DeviceID
  ClientConnectionID
  AgentConnectionID
  State
  Route
  Metadata
  Deadline
  MaxPendingBytes
  MaxPendingMessages
  BytesIn
  BytesOut
  CreatedAt
  LastActivityAt
  CloseReason
```

Stream 可以有 route metadata，例如 path、method、subprotocol，但不能保存 terminal/session 业务字段。

禁止放入 Stream 的字段：

```text
termTitle
cwd
screen state
lastSeq
terminal runtime state
pty pid
shell command
```

状态：

```text
pending
open
half-closed
closing
closed
timeout
failed
```

Stream 必须具备：

```text
close-once
context cancellation
deadline
backpressure
byte counters
last activity tracking
device/client disconnect cleanup
```

## 数据面设计

### 新 Relay Frame

因为不要求兼容 Node 旧协议，Go Relay 可以定义更清晰的新 frame。但 frame 仍然只表达网络流，不表达 session 业务真相。

建议格式：

```text
byte 0      version
byte 1      frameType
byte 2      flags
byte 3      idLen
bytes       streamID
bytes       payload
```

frameType：

```text
stream.open
stream.data
stream.close
stream.error
http.headers
http.chunk
ws.text
ws.binary
ping
pong
p2p.offer
p2p.answer
p2p.ice
p2p.unavailable
```

flags：

```text
fin
compressed
urgent
ack
```

第一阶段可以只实现 version、frameType、flags、streamID、payload，不实现压缩和 ack，但字段要预留。

### Text/Binary 规则

Relay 数据面必须保留 text frame 和 binary frame 的语义：

```text
ws.text    -> 对客户端写 WebSocket text frame
ws.binary  -> 对客户端写 WebSocket binary frame
```

终端二进制协议、manager JSON 消息、普通 WebSocket text 消息不能因为中转而丢失 frame 类型。

### Backpressure

不要只限制 pending message 数量，还要限制 pending bytes。

建议默认值：

```text
maxPendingMessages = 256
maxPendingBytes    = 4 MiB
maxStreamBytes     = configurable
```

超过限制时进入明确关闭路径：

```text
stream.error(backpressure)
stream.close
emit stream.closed
update metrics
```

## StreamManager

StreamManager 是 Go Relay 的核心，但它管理的是网络流生命周期，不管理业务 session。

核心接口：

```text
CreateStream(kind, route, metadata)
OpenStream(streamID)
AttachClient(streamID, clientID)
AttachAgent(streamID, agentID)
WriteFromClient(streamID, frame)
WriteFromAgent(streamID, frame)
CloseStream(streamID, reason)
CancelByDevice(deviceID, reason)
CancelByClient(clientID, reason)
CancelExpired(now)
Snapshot()
```

规则：

1. 每个 stream 只能关闭一次。
2. client close、agent close、timeout、explicit error 都走同一个 close path。
3. stream 关闭后必须从 router 移除，并发出 `stream.closed` 事件。
4. agent 断开时，其名下所有 stream 必须被取消。
5. client 断开时，其关联 stream 必须被取消或转为 detached，具体由 stream kind 决定。
6. StreamManager 不保存 session 列表，不缓存 session 摘要，不解释 terminal payload。

## EventBus

EventBus 用于 presence、stream 生命周期、debug 和 metrics。它不是 session 状态缓存。

事件结构：

```text
Event
  ID
  Type
  UserID
  DeviceID
  StreamID
  Payload
  At
```

事件类型：

```text
device.online
device.offline
device.updated
agent.connected
agent.disconnected
client.connected
client.disconnected
stream.created
stream.opened
stream.closed
stream.error
auth.login
auth.logout
auth.failure
credential.rotated
credential.revoked
```

如果后续需要透传 Agent 的 session 事件，可以作为 passthrough event 转发：

```text
agent.event.session.snapshot
agent.event.session.updated
agent.event.session.closed
```

但这些事件必须标注为非权威缓存：

```text
Relay may forward session events, but Agent remains the only session truth source.
Relay must not serve cached session state as authoritative data.
```

第一阶段 EventBus 可以先做成进程内 ring buffer，并通过 `/debug/events` 暴露最近事件；后续如果 manager 需要实时订阅，再扩展为 channel fanout：

```text
Snapshot()
Subscribe(userID, filters)
Publish(event)
CloseSubscription(id)
```

## Gateway 设计

### Agent Gateway

Agent Gateway 只负责接入 Agent 连接和承载 Agent 侧 stream，不写 terminal/session 业务状态机。

职责：

```text
accept agent websocket
authenticate or pair agent
create AgentConnection
start read/write/heartbeat loops
decode agent stream frames
forward frames to StreamManager
receive outbound frames from SendQueue
on close -> router unregister + StreamManager.CancelByDevice
```

Agent 连接建立流程：

```text
1. websocket upgrade
2. agent register / credential verification
3. create AgentConnection
4. Router.RegisterAgent
5. EventBus.Publish(device.online)
6. start loops
```

### Client Gateway

Client Gateway 负责 Android、Web、Flutter 和 debug 客户端接入。

客户端类型：

```text
manager
terminal
generic-ws
debug
```

Manager 连接流程：

```text
1. authenticate user
2. create ClientConnection(kind=manager)
3. send device presence snapshot
4. subscribe presence / stream events
5. stream events until close
```

Manager 只从 Relay 获取设备在线状态。session 列表要通过对应 device 的 Agent 获取。

Terminal/WS 连接流程：

```text
1. authenticate user
2. resolve target device route
3. create Stream(kind=terminal or websocket)
4. route to AgentConnection
5. wait agent accept
6. pump client frames <-> agent frames
7. close stream on either side close
```

客户端外部入口和 Agent 内部入口要分层：

```text
Android/Web -> Relay:
  GET /ws/sessions?deviceId={deviceId}
  Cookie: webterm_relay_token=...
  Sec-WebSocket-Protocol: webterm.mux.v1

Relay -> Agent:
  stream.open path=/ws/sessions
  ws.text / ws.binary frames

Mux 内部：
  ws-connect path=/ws/sessions
  ws-connect path=/ws/sessions/{sessionId}
```

Relay 只根据外层 `deviceId` 选择 Agent，并透明转发整条 mux WebSocket。Relay 不能读取或缓存 terminal payload，也不能把 session 列表变成自己的状态。

### HTTP Gateway

HTTP Gateway 把 HTTP 请求转换为网络 stream。Relay 只区分控制面请求和需要转发给 Agent 的请求。

Relay 自己处理：

```text
auth APIs
device APIs
presence APIs
debug / metrics
```

Relay 转发给 Agent：

```text
session APIs
terminal-related APIs
file/clipboard APIs
any Agent-owned local capability APIs
```

典型流程：

```text
1. authenticate user
2. resolve target device
3. create Stream(kind=http)
4. send HTTP request metadata/body chunks to Agent
5. current HTTP handler goroutine waits on stream output channel
6. receive response headers
7. receive response chunks
8. write to ResponseWriter
9. close on fin / timeout / client cancel / agent disconnect
```

这样 ResponseWriter 仍由当前 HTTP handler 管理，StreamManager 管生命周期和数据通道。

### P2P Gateway

P2P 在新架构中属于 transport negotiation，不属于 terminal 业务逻辑。

第一阶段只实现 signaling 和 fallback 状态：

```text
p2p.offer
p2p.answer
p2p.ice
p2p.unavailable
p2p.timeout
p2p.fallback
```

真正 WebRTC DataChannel 后续接入同一个 Stream 抽象：

```text
Stream Transport = relay / webrtc
```

## Control Plane

控制面只管理 Relay 自己的长期状态。

核心实体：

```text
User
Device
AgentCredential
TrustedClientDevice
AccessToken
RefreshToken
```

不在 Relay store 中建模这些实体：

```text
Session
Terminal
TerminalSnapshot
SessionSummaryCache
PtyProcess
```

设备凭证可以从永久 secret 升级为可管理生命周期：

```text
1. 用户创建设备
2. Relay 生成一次性 pairing token
3. Agent 用 pairing token 换长期 credential
4. Relay 只保存 credential hash
5. 用户可以 rotate / revoke credential
6. device disabled 后在线 agent 立即踢下线
```

管理接口建议：

```text
POST /api/auth/login
POST /api/auth/refresh
POST /api/devices
GET  /api/devices
GET  /api/devices/{id}
POST /api/devices/{id}/pairing-token
POST /api/devices/{id}/rotate-credential
POST /api/devices/{id}/disable
POST /api/devices/{id}/enable
DELETE /api/devices/{id}
```

## 运行配置

Go Relay 的运行参数必须集中在 `relayapp.Config`，并允许通过环境变量覆盖。容量、超时、清理周期都不要散落在 handler 或 manager 内部。

第一阶段配置项：

```text
WEBTERM_RELAY_ADDR
  默认 127.0.0.1:19090
  服务监听地址

WEBTERM_RELAY_STORE_PATH
  默认空，表示内存 store
  设置后使用 JSON 文件持久化用户、设备、credential hash、token 和递增 ID

WEBTERM_RELAY_BOOTSTRAP_USER / WEBTERM_RELAY_BOOTSTRAP_PASSWORD
  可选
  首次启动创建管理员用户；持久化 store 中用户已存在时不应阻止服务启动

WEBTERM_RELAY_EVENT_HISTORY_LIMIT
  默认 256
  /debug/events 和 manager 初始排障可用的进程内事件历史长度

WEBTERM_RELAY_READ_HEADER_TIMEOUT
  默认 10s
  HTTP server 读取请求头超时

WEBTERM_RELAY_SHUTDOWN_TIMEOUT
  默认 5s
  收到退出信号后的优雅关闭窗口

WEBTERM_RELAY_STREAM_CLEANUP_INTERVAL
  默认 30s
  后台扫描并关闭过期 stream 的周期

WEBTERM_RELAY_HTTP_RESPONSE_TIMEOUT
  默认 30s
  HTTP stream 等待 Agent response close 的最长时间

WEBTERM_RELAY_MAX_PENDING_BYTES
  默认 4MiB
  单个 stream 默认 pending byte 背压阈值

WEBTERM_RELAY_MAX_PENDING_MESSAGES
  默认 256
  单个 stream 默认 pending message 背压阈值
```

## Observability

Go Relay 从第一阶段就应该提供调试和指标接口。

健康检查：

```text
GET /healthz
GET /readyz
```

Debug endpoint：

```text
GET /debug/connections
GET /debug/agents
GET /debug/clients
GET /debug/streams
GET /debug/events
GET /debug/routes
```

Metrics：

```text
relay_agents_online
relay_clients_online
relay_streams_active
relay_events_buffered
relay_stream_opened_total
relay_stream_closed_total
relay_stream_errors_total
relay_bytes_in_total
relay_bytes_out_total
relay_ws_close_total
relay_http_proxy_duration_seconds
relay_stream_duration_seconds
relay_backpressure_total
```

每个连接和 stream 至少记录：

```text
id
userID
deviceID
state
createdAt
lastActivityAt
bytesIn
bytesOut
closeReason
lastError
```

禁止把 terminal/session 业务字段放进 metrics label，避免高基数和职责泄漏。

## 阶段计划

### 阶段 1：协议和边界定义

产物：

```text
docs/go-relay-server-refactor-plan.md
go-core/internal/relaycore/model.go
go-core/internal/relaycore/state.go
go-core/internal/relaycore/errors.go
go-core/internal/relaycore/frame.go
```

验收：

```text
明确 Relay 只管 Auth / Device / Presence / Stream
明确 Agent 是 session 和 terminal 的唯一权威
frame encode/decode 单测通过
核心状态枚举和错误类型稳定
```

### 阶段 2：Auth + Device Registry

产物：

```text
go-core/internal/relaystore/*
go-core/internal/relaycontrol/auth_handler.go
go-core/internal/relaycontrol/device_handler.go
go-core/internal/relayrouter/registry.go
```

当前第一阶段实现状态：

```text
relaystore.NewPersistentStore(path)
  JSON 文件持久化用户、设备、credential hash、token
  不写入明文密码或明文设备 credential

cmd/webterm-relay
  支持 WEBTERM_RELAY_STORE_PATH
  支持 bootstrap admin user
  bootstrap 用户已存在时继续启动
```

验收：

```text
用户登录 / refresh token
设备创建 / 禁用 / 删除
Agent credential 校验
按 userID/deviceID 查找在线 Agent
设备列表只返回设备和在线状态，不返回 session 摘要
并发测试无 data race
```

第一阶段已完成：

```text
用户登录
refresh token 轮换
设备创建
设备禁用 / 启用 / 删除
rotate credential API
禁用 / 删除 / rotate 后清理在线 presence 和该设备 streams
Agent credential 校验
按 userID/deviceID 查找在线 Agent
设备列表不返回 session 摘要
JSON 持久化 reload 测试
```

仍需后续补齐：

```text
更适合多实例部署的数据库后端
```

### 阶段 3：Agent Gateway

产物：

```text
go-core/internal/relaygateway/agent_gateway.go
go-core/internal/relaygateway/agent_dispatcher.go
go-core/cmd/webterm-relay/main.go
```

验收：

```text
Go Relay 能启动
Go PC Agent 能连接并注册
Agent outbound write 统一通过 per-agent send queue
Agent 断开能触发 device.offline
Heartbeat 能清理死连接
/debug/agents 能看到在线设备
```

当前第一阶段已完成：

```text
Go Relay 能启动
Go PC Agent 能连接并注册
Agent outbound write 通过 per-agent send queue 独立写循环
Agent 断开能触发 device.offline
Heartbeat ping 能检测并关闭失败连接
/debug/agents 能看到在线设备
```

### 阶段 4：Stream Router / Tunnel Broker

产物：

```text
go-core/internal/relayrouter/stream_manager.go
go-core/internal/relaygateway/http_gateway.go
go-core/internal/relaygateway/ws_gateway.go
```

验收：

```text
Create/Open/Close stream
close-once 测试
timeout cleanup 测试
CancelByDevice 测试
CancelByClient 测试
backpressure 测试
byte counter 测试
HTTP request/response 能经 Agent 转发
WebSocket text/binary 能经 Agent 转发且类型不丢
Terminal stream 能经 Agent 转发，但 Relay 不解析 terminal payload
```

当前第一阶段已完成：

```text
Create/Open/Close stream
close-once 测试
timeout cleanup 测试
CancelByDevice 测试
CancelByClient 测试
backpressure byte/message 上限配置
backpressure counter 测试
byte counter 测试
HTTP request/response 能经 Agent 转发
WebSocket text/binary 能经 Agent 转发且类型不丢
Terminal stream 能经 Agent 转发，但 Relay 不解析 terminal payload
```

### 阶段 5：Android + Go Agent 新协议接入

产物：

```text
go-core/internal/relay/client_v2.go
go-core/cmd/webterm-relay-e2e-smoke/main.go
android-client/app/src/main/java/com/webterm/mobile/*
```

当前接入约定：

```text
Agent -> Relay:
  /ws/agent
  agent.register { credential, deviceName }

Android -> Relay:
  /api/devices
  /api/presence
  /api/sessions with x-device-id
  /ws/sessions?deviceId={deviceId} with webterm.mux.v1
```

当前第一阶段已完成：

```text
Go PC Agent relay client 默认可使用 v2 协议
Android TerminalConnection 已接入 /ws/sessions?deviceId={deviceId} mux terminal channel
Android session API 请求携带 x-device-id 由 Relay 路由到 Agent
Android Relay master 设备发现直接使用 HTTP polling，不再反复探测旧 `/ws/sessions`
Android debug Java 编译通过
Android emulator 已安装 debug APK 并完成 Go Relay 端到端验证
Relay 通过 HTTP stream 把 /api/sessions 转发给 Agent
Relay 通过 mux WebSocket stream 把 manager / terminal virtual channel 透明转发给 Agent
webterm-relay-e2e-smoke 已验证：
  Go Relay app
  Go Agent v2 client
  真实 webterm-agent 二进制 --mode relay
  Android-like HTTP client
  Android-like mux WebSocket client
  list/create/rename/delete session 均由 Relay 转发给 Agent
  创建 session 后通过 mux terminal virtual channel 收到终端输出
  HTTP/mux/agent stop 后 Relay active streams 最终归零
  Agent 离线后 presence 消失，重新上线后同一设备可再次打通 session/terminal
  --cycles 可配置多轮 reconnect / stream cleanup soak
Android emulator 真实 app 已验证：
  通过 10.0.2.2 登录宿主机 Go Relay
  展示在线中转设备 Android E2E Agent
  创建 session s1
  打开 terminal channel，Relay debug 显示 /ws/sessions mux stream open
  输入 pwd 并收到 /Users/gao/Documents/webterm-clone/go-core 输出
```

验收：

```text
Android 登录 Go Relay
Android 看到在线 Agent 设备
Android 对选中设备请求 session 列表时，由 Relay 转发到 Agent
Android 创建 / 重命名 / 关闭 session 时，由 Agent 执行业务逻辑
Android 打开终端，输入输出正常
Agent termTitle / session 更新可通过 Relay 透传给 Android，但 Relay 不缓存
断线重连后 stream 清理正确
```

仍需后续补齐：

```text
真实物理 Android 设备连接部署后的端到端验证
部署环境中的长时间 soak 观察
```

### 关联计划：Relay 统一 Mux

Relay 模式已经切到 Direct 模式同款的一条 `/ws/sessions?deviceId=...` 物理 WebSocket，并使用 `webterm.mux.v1` 在同一连接上承载 manager 和 terminal virtual channel。

这个改动同时影响 Go Relay Server、Go PC Agent relay client 和 Android 原生端，属于跨端协议重构，不在本 Go Relay Server 主计划里展开细节。详细落地方案见：

```text
docs/relay-unified-mux-refactor-plan.md
```

本计划只保留 Go Relay Server 侧边界：

```text
Relay 负责 /ws/sessions?deviceId=... 外层鉴权、设备路由和整条 WS stream 透明转发。
Relay 不解析 webterm.mux.v1 控制消息。
Relay 不解析 tunnel frame。
Relay 不创建、不缓存、不解释 session。
```

### 关联计划：Web 接入 Go Relay + Go PC Agent

Web 前端当前仍有 Node relay-server 时代的账号接口和旧 WebSocket 连接模型。Go Relay 需要分阶段补齐 Web 认证/账号控制面，并让 Web terminal 切到和 Android 相同的 mux 连接模型。详细方案见：

```text
docs/web-go-relay-pc-agent-integration-plan.md
```

该计划覆盖：

```text
GET /api/auth/me
POST /api/auth/logout
GET /api/auth/devices
DELETE /api/auth/devices/{id}
POST /api/auth/register
POST /api/auth/verify-email
POST /api/auth/verify-otp
POST /api/auth/resend-otp
真实 trusted-device store
Web RelayMuxSessionManager
P2P fallback 对齐 mux
```

### 阶段 6：P2P fallback

产物：

```text
go-core/internal/relaygateway/p2p_gateway.go
```

验收：

```text
p2p offer/answer/ice 信令可路由
Agent 不支持 P2P 时能快速返回 unavailable
Client 能 fallback 到 relay stream
P2P pending 超时会关闭 stream 并清理状态
```

### 阶段 7：Debug / Metrics / 压测

产物：

```text
go-core/internal/relaymetrics/metrics.go
go-core/internal/relaymetrics/debug_handlers.go
go-core/cmd/webterm-relay-storm-smoke/main.go
```

验收：

```text
/healthz
/readyz
/debug/connections
/debug/streams
/debug/events
/metrics
agents/clients/active streams gauge
stream opened/closed counter
stream errors/backpressure counter
无 data race
无 goroutine 泄漏
无重复 ResponseWriter write
StreamManager active count 最终归零
```

## 验收矩阵

第一版 Go Relay 完成时，至少要跑通这些场景：

```text
Android -> Go Relay 登录成功
Go PC Agent -> Go Relay 注册在线
Android -> Go Relay 获取设备列表
Android -> Go Relay -> Go Agent 获取 session 列表
Android -> Go Relay -> Go Agent 创建 session
Android -> Go Relay -> Go Agent 打开 terminal stream
terminal 输入输出正常
Android -> Go Relay -> Go Agent 重命名 session
Android -> Go Relay -> Go Agent 关闭 session
Go Agent 断线后 Android 设备列表显示离线
Client/Agent 任一端断开，stream 都能清理
P2P unavailable 后 fallback 到 relay stream
```

当前可重复执行的自动验收：

```text
cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go test ./internal/relaycore ./internal/relaystore ./internal/relaycontrol ./internal/relayrouter ./internal/relaygateway ./internal/relaymetrics ./internal/relayapp ./internal/relay ./cmd/webterm-relay ./cmd/webterm-relay-flow-smoke ./cmd/webterm-relay-e2e-smoke ./cmd/webterm-relay-storm-smoke

cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go run ./cmd/webterm-relay-e2e-smoke --cwd /Users/gao/Documents/webterm-clone/go-core

cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go run ./cmd/webterm-relay-e2e-smoke --cycles 5 --timeout 40s --cwd /Users/gao/Documents/webterm-clone/go-core

cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go build -o /private/tmp/webterm-agent-v2-e2e ./cmd/webterm-agent

cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go run ./cmd/webterm-relay-e2e-smoke --agent /private/tmp/webterm-agent-v2-e2e --cycles 3 --timeout 40s --cwd /Users/gao/Documents/webterm-clone/go-core

cd android-client && ANDROID_HOME=/Users/gao/Library/Android/sdk ./gradlew :app:compileDebugJavaWithJavac --no-daemon

cd android-client && ANDROID_HOME=/Users/gao/Library/Android/sdk ./gradlew :app:assembleDebug --no-daemon

DEVICE_NAME='Android E2E Agent' scripts/smoke-go-relay-android-emulator.sh

DEVICE_NAME='Android E2E Agent' scripts/smoke-go-relay-android-emulator.sh --terminal --mux
```

其中 `webterm-relay-e2e-smoke` 是不依赖 Node 兼容协议的三端路径检查。它用真实 Go Relay 和真实 Go Agent v2 client，模拟 Android 原生端通过 Cookie、`x-device-id`、`/api/sessions`、`/ws/sessions?deviceId=...` + `webterm.mux.v1` 打通 session 列表、创建、重命名、关闭和终端输入输出，并在每个 HTTP/mux/agent stop 阶段确认 Relay active streams 最终归零。它会按 `--cycles` 指定轮数停止 Agent、确认 presence 离线、重新启动 Agent，再次验证同一设备可继续打通 session/terminal。传入 `--agent /path/to/webterm-agent` 时，它会启动真实 Go PC Agent 二进制验证部署形态。

其中 `scripts/smoke-go-relay-android-emulator.sh` 会构建 Go Relay / Go Agent、启动临时 Relay 服务、创建设备 credential、启动真实 Agent、构建并安装 Android debug APK、向 emulator 注入 Relay master 配置、启动 Android app，并确认首页能发现在线中转设备。传入 `--terminal` 时，会继续通过 Android UI 进入中转设备并创建 session，最后通过 Relay API 验证 session 已由 Agent 创建。

## 关键设计约束

1. Relay 是设备、presence、连接和 stream 的权威。
2. Agent 是 session、terminal、pty、本机能力的权威。
3. Relay 不缓存 session 摘要作为业务数据。
4. Relay 可以转发 Agent session 事件，但不能把它们作为真相来源对外服务。
5. Relay 不解析 terminal payload。
6. 所有 WebSocket 写入必须走单一写路径或 SendQueue。
7. 所有 stream 必须 close-once。
8. 所有 pending 必须有 deadline 和取消路径。
9. Debug/metrics 只暴露连接和 stream 维度，不暴露终端业务状态。

## 一句话总结

这次重构要做的是一个轻量 Go 中转站：

```text
Relay 管登录、设备、在线状态和网络中转。
Agent 管 session、terminal、pty 和所有本机业务逻辑。
```

只要坚持这个边界，Go Relay 可以比 Node 版更清晰、更可靠，也不会长成第二个中央业务服务。
