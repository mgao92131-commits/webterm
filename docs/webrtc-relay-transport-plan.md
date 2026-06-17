# WebRTC 优先、Relay 回退的统一协议与传输层方案

## 目标

本方案目标是把 WebTerm 当前的通信架构从“业务代码直接依赖 WebSocket/Relay”升级为：

```text
Web / Android
    |
    v
统一协议层 Protocol
    |
    v
Transport Manager
    |
    +-- WebRTC DataChannel    优先
    |
    +-- Relay WebSocket       回退
    |
    v
PC Agent
    |
    v
TerminalSession / 文件系统 / 后续能力
```

Relay Server 的角色从“纯流量中转”升级为：

```text
1. 用户认证
2. 设备发现
3. WebRTC 信令服务器
4. Relay fallback 数据中转
```

核心要求：

```text
1. 一开始就抽象统一协议层
2. 业务层不直接依赖 WebSocket 或 WebRTC
3. 默认优先使用 WebRTC DataChannel
4. WebRTC 失败、超时或中途断开时自动回退到 Relay 中转
5. 终端、session 管理、文件传输都复用同一套协议和传输抽象
```

---

## 核心原则

### 1. 业务层不关心 WebRTC 还是 Relay

终端、session 管理、文件传输都只面对统一接口：

```js
connection.send(message)
connection.sendBinary(frame)
connection.onMessage(...)
connection.onBinary(...)
connection.close()
```

业务层不应该直接写：

```js
new WebSocket(...)
peerConnection.createDataChannel(...)
```

这些细节全部放到 Transport 层。

### 2. 协议层先行

先定义一个统一 envelope，把所有业务消息包起来。

示例：

```js
{
  version: 1,
  channel: "terminal" | "session" | "file" | "control",
  type: "input" | "output" | "resize" | "create-session" | "file-chunk",
  requestId: "optional",
  sessionId: "optional",
  payload: {}
}
```

二进制数据也统一包装：

```text
[protocol header][payload bytes]
```

这样后面无论底层是：

```text
WebSocket
WebRTC DataChannel
Relay WebSocket
```

上层协议都不变。

### 3. WebRTC 优先，Relay 兜底

连接策略：

```text
1. 客户端连接 Relay 控制通道
2. 选择目标 PC Agent
3. Relay 向双方发起 P2P 协商
4. Web / Android 与 PC Agent 交换 offer / answer / ICE candidate
5. WebRTC DataChannel 建立成功后，所有新流量走 WebRTC
6. 如果 WebRTC 超时、失败或中途断开，自动切到 Relay WebSocket
```

用户层面只看到连接状态：

```text
连接中
P2P 已连接
```

或：

```text
连接中
P2P 不可用，已使用中继
```

---

## 模块设计

### 一、`shared/protocol/`：统一协议层

新增目录：

```text
shared/protocol/
  envelope.js
  message-types.js
  binary-frame.js
  request-response.js
  terminal-protocol.js
  session-protocol.js
  file-protocol.js
```

职责：

```text
1. 定义统一消息格式
2. 定义所有 message type
3. 编解码 JSON 消息
4. 编解码二进制消息
5. 管理 requestId / response / error
6. 兼容现有 terminal binary frame
```

建议保留现有终端帧语义：

```text
MSG_INPUT
MSG_OUTPUT
MSG_RESIZE
MSG_HELLO
MSG_INFO
MSG_EXIT
MSG_PING
MSG_PONG
MSG_TITLE
```

但把它们升级为协议层的一部分，而不是直接和 WebSocket 绑定。

### 二、`shared/transport/`：传输抽象层

新增目录：

```text
shared/transport/
  transport.js
  transport-manager.js
  relay-transport.js
  webrtc-transport.js
  connection-state.js
```

核心接口：

```js
class Transport {
  async connect()
  sendEnvelope(envelope)
  sendBinary(channel, metadata, bytes)
  close()
  onOpen(callback)
  onMessage(callback)
  onBinary(callback)
  onClose(callback)
  onError(callback)
}
```

Transport Manager 负责选择链路：

```js
class TransportManager {
  async connectPreferred() {
    // try WebRTC
    // fallback Relay
  }

  switchToRelay()
  switchToWebRTC()
}
```

状态模型：

```text
idle
connecting-relay-control
signaling
connecting-webrtc
webrtc-connected
relay-connected
fallback
closed
failed
```

### 三、`relay-server/`：信令 + 兜底中转

`relay-server/main.js` 后续拆分：

```text
relay-server/
  main.js
  auth.js
  device-registry.js
  signaling-router.js
  relay-data-router.js
```

新增信令消息：

```text
p2p-prepare
p2p-offer
p2p-answer
p2p-ice-candidate
p2p-connected
p2p-failed
p2p-close
```

Relay 需要维护：

```text
用户 -> 设备列表
设备 -> Agent WebSocket
客户端 -> 控制 WebSocket
P2P 尝试状态
Relay fallback session
```

Relay 的中转能力保留：

```text
客户端无法 P2P 时：
Web / Android -> Relay -> PC Agent
```

### 四、`pc-agent/`：Agent 端统一连接管理

`pc-agent/main.js` 后续拆成：

```text
pc-agent/
  main.js
  relay-client.js
  p2p-agent.js
  agent-session-host.js
  agent-file-service.js
```

职责：

```text
relay-client.js
  - 连接 Relay
  - 注册设备
  - 接收信令
  - Relay fallback 收发

p2p-agent.js
  - 创建 RTCPeerConnection
  - 接收 offer / 发送 answer
  - 建立 DataChannel
  - ICE candidate 处理

agent-session-host.js
  - 管理 TerminalSession
  - create/list/rename/close session
  - input/output/resize/title

agent-file-service.js
  - 后续文件上传/下载
```

Agent 端要支持两条链路同时存在：

```text
Relay WebSocket 控制通道一直保留
WebRTC DataChannel 成功后承载数据
Relay 数据通道作为 fallback
```

### 五、`web/`：Web 客户端接入统一协议

Web 端新增：

```text
web/lib/protocol-client/
  client-connection.js
  session-client.js
  terminal-client.js
  file-client.js

web/lib/transports/
  relay-client-transport.js
  webrtc-client-transport.js
  transport-manager.js
```

`web/app.js` 不再直接：

```js
new WebSocket(...)
```

而是：

```js
const connection = await connectToDevice({
  deviceId,
  preferred: "webrtc",
  fallback: "relay"
});
```

终端页面使用：

```js
terminalClient.sendInput(data)
terminalClient.resize(cols, rows)
terminalClient.onOutput(...)
```

### 六、`android-client/`：后续同样接入协议层

Android 端对应新增：

```text
ProtocolEnvelope
Transport
RelayTransport
WebRtcTransport
TransportManager
TerminalClient
SessionClient
FileClient
```

现有这些类后续逐步迁移：

```text
TerminalConnection.java
ServerSessionMonitor.java
WebTermApi.java
WebTermProtocol.java
```

Android WebRTC 接入会比 Web 端复杂，所以建议实现顺序是：

```text
先 Web + PC Agent
再 Android + PC Agent
```

---

## 连接流程设计

### 1. 初始连接

```text
Web 打开页面
  -> 登录 Relay
  -> 建立 Relay 控制 WebSocket
  -> 获取设备列表
  -> 用户选择设备
```

### 2. 发起 WebRTC

```text
Web -> Relay:
{
  type: "p2p-prepare",
  deviceId
}

Relay -> PC Agent:
{
  type: "p2p-prepare",
  clientId
}
```

Web 创建 PeerConnection 和 DataChannel：

```text
Web:
  create RTCPeerConnection
  create DataChannel("webterm-v1")
  createOffer()
```

Web 通过 Relay 发 offer：

```text
Web -> Relay -> Agent:
p2p-offer
```

Agent 回 answer：

```text
Agent -> Relay -> Web:
p2p-answer
```

双方交换 ICE：

```text
p2p-ice-candidate
```

DataChannel open 后：

```text
Web -> Agent:
protocol hello
```

Agent 回复：

```text
protocol ready
```

连接状态变成：

```text
webrtc-connected
```

### 3. WebRTC 成功后的数据流

```text
Web Terminal
  -> Protocol Envelope
  -> WebRTC DataChannel
  -> Agent Protocol Router
  -> TerminalSession
```

输出反向：

```text
TerminalSession
  -> Protocol Envelope / binary frame
  -> WebRTC DataChannel
  -> Web Terminal
```

### 4. WebRTC 失败回退

失败条件：

```text
1. 信令超时
2. ICE connection failed
3. DataChannel open 超时
4. DataChannel 运行中 close/error
5. 心跳超时
```

回退流程：

```text
TransportManager 标记 WebRTC failed
  -> 打开 Relay data mode
  -> 发送 protocol resume
  -> 使用 lastSeq 恢复终端状态
```

因为现有终端协议已有 `lastSeq` 和 replay/state 恢复能力，所以回退时可以复用。

---

## 统一协议建议

### Envelope 格式

```js
{
  v: 1,
  id: "msg_xxx",
  channel: "control",
  type: "hello",
  target: {
    deviceId: "d1",
    sessionId: "s1"
  },
  payload: {}
}
```

### channel 分类

```text
control   连接控制、能力协商、心跳
session   session 列表、创建、关闭、重命名
terminal  终端输入输出、resize、title、replay
file      文件上传、下载、取消、进度
```

### control 消息

```text
hello
ready
ping
pong
capabilities
resume
error
```

### session 消息

```text
list-sessions
sessions
create-session
session-created
rename-session
session-updated
close-session
session-closed
```

### terminal 消息

```text
terminal-hello
terminal-input
terminal-output
terminal-resize
terminal-title
terminal-info
terminal-exit
terminal-replay
terminal-state
```

### file 消息预留

```text
file-offer
file-accept
file-chunk
file-complete
file-cancel
file-error
```

---

## 传输选择策略

默认：

```text
preferredTransport = "webrtc"
fallbackTransport = "relay"
```

流程：

```text
connect()
  1. 保持 Relay control online
  2. 尝试 WebRTC
  3. 如果 WebRTC 在 N 秒内 ready，使用 WebRTC
  4. 否则启用 Relay data
```

建议超时：

```text
WebRTC signaling timeout: 8s
DataChannel open timeout: 10s
ICE failed fallback delay: 1s
heartbeat interval: 15s
heartbeat timeout: 45s
```

---

## 实施阶段

### 阶段 1：协议抽象

目标：不引入 WebRTC，先把协议层独立出来。

改动：

```text
新增 shared/protocol/
梳理 message types
把现有 relay-protocol 和 protocol-binary 的语义归档
定义 envelope
定义二进制 frame 包装
```

产出：

```text
统一协议代码
协议单元测试
```

### 阶段 2：Relay Transport 适配统一协议

目标：现有 Relay 行为不变，但走新协议层。

改动：

```text
relay-server 使用 ProtocolRouter
pc-agent 使用 ProtocolRouter
web 使用 RelayTransport
```

此时链路仍然是：

```text
Web -> Relay -> PC Agent
```

但上层已经不直接依赖旧格式。

### 阶段 3：WebRTC 信令

目标：Relay 支持信令，但数据还可以先不切。

改动：

```text
relay-server 增加 signaling-router
web 增加 webrtc-client-transport
pc-agent 增加 p2p-agent
```

验证：

```text
Web 和 PC Agent 能建立 DataChannel
DataChannel 能 ping/pong
```

### 阶段 4：终端数据走 WebRTC

目标：WebRTC 成功后，终端 session 走 DataChannel。

改动：

```text
terminalClient 走 TransportManager
Agent terminal router 同时支持 Relay 和 WebRTC
```

验证：

```text
创建 session
输入命令
输出显示
resize
rename
close
断开重连
```

### 阶段 5：Relay fallback

目标：WebRTC 不通时自动回退 Relay。

测试场景：

```text
1. 禁用 STUN/ICE，确认 fallback
2. 建连超时，确认 fallback
3. DataChannel 中途断开，确认 fallback
4. fallback 后 lastSeq 恢复正确
```

### 阶段 6：Android 接入

目标：Android 复用统一协议模型。

改动：

```text
Android 增加 ProtocolEnvelope
Android 增加 RelayTransport / WebRtcTransport
TerminalConnection 改用 TransportManager
```

### 阶段 7：文件传输

目标：基于统一协议新增文件传输。

数据路径：

```text
WebRTC 成功：
Web / Android -> DataChannel -> PC Agent

WebRTC 失败：
Web / Android -> Relay -> PC Agent
```

---

## 风险点

### 1. Node 端 WebRTC 库

PC Agent 是 Node，不能直接用浏览器 WebRTC，需要选择 Node WebRTC runtime。

这是最大工程风险之一。

### 2. Android WebRTC 集成

Android 接入比 Web 复杂。

建议 Web 端先跑通，再迁移 Android。

### 3. 协议迁移成本

如果一次性把所有旧消息替换掉，风险大。

建议新协议先兼容旧终端帧。

### 4. 回退一致性

WebRTC 到 Relay 的切换必须处理：

```text
sessionId
lastSeq
pending input
resize 状态
文件 chunk 传输状态
```

---

## 推荐落地顺序

严格按这个顺序推进：

```text
1. shared/protocol 协议抽象
2. shared/transport 抽象接口
3. RelayTransport 接入，保持现有功能不变
4. WebRTCTransport 打通 ping/pong
5. 终端数据迁移到 WebRTC
6. 实现 Relay fallback
7. Android 接入
8. 文件传输
```

这样可以满足目标：

```text
一开始就抽象协议，最终直接使用 WebRTC，失败回退到中转。
```
