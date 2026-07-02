# Go Core 重构 PC Agent 方案

## 背景

当前 WebTerm 的 PC Agent 能力主要由 Node.js 实现：

```text
main.js
  根据 WEBTERM_MODE 选择 direct 或 agent

server/direct.js
  直连模式 HTTP / WebSocket 服务

server/agent.js
  PC Agent 连接 relay-server，处理 HTTP / WebSocket tunnel

server/session-manager.js
  终端会话列表、创建、关闭、manager 广播

server/terminal-session.js
  PTY 会话、输出回放、终端状态维护

shared/constants.js
shared/tunnel-protocol.js
  终端协议与 relay tunnel 二进制帧格式
```

这套实现已经跑通了 direct、relay、Android、Web 前端和二进制终端协议。Go Core 重构的目标不是推翻现有链路，而是先实现一个协议兼容的 PC Agent 内核，再逐步扩展为 macOS App / Flutter 客户端可以复用的本地服务。

## 目标

### 第一目标

实现一个 Go 编写的 `webterm-agent`，替代当前 Node.js 的 PC Agent runtime：

```text
webterm-agent --mode direct
webterm-agent --mode relay
```

第一阶段必须兼容现有客户端和 Relay Server：

```text
Web 前端
Android 客户端
relay-server
webterm.binary.v1
webterm.json.v1
现有 HTTP API
现有 relay tunnel frame
```

### 第二目标

在 Go Core 内部引入 headless terminal 状态层，为未来服务端 screen diff 渲染做准备：

```text
PTY bytes
  -> go-headless-term 解析 ANSI / VT
  -> 维护 screen / cursor / dirty cells / snapshot
  -> 未来发送 screen diff 给 Flutter 客户端
```

### 第三目标

为未来 macOS Dock App 或 Flutter 桌面 App 暴露本地控制 API：

```text
127.0.0.1:18081/control/status
127.0.0.1:18081/control/config
127.0.0.1:18081/control/logs/stream
127.0.0.1:18081/control/sessions/{id}/screen
127.0.0.1:18081/control/sessions/{id}/screen/delta
```

桌面 App 只负责 UI、配置和进程管理；Go Core 负责终端、直连、中转、协议和状态。

## 非目标

第一阶段不做这些事情：

```text
不重写 relay-server
不重写 Android 客户端
不重写 Web 前端
不立即废弃 xterm.js / Android TerminalEmulator
不立即把所有客户端改成 screen diff 渲染
不把 Go Core 和 macOS UI 绑死
```

这样可以把风险控制在 PC Agent 一侧。

## 总体架构

```text
webterm-agent
  |
  +-- direct mode
  |     |
  |     +-- HTTP API
  |     +-- WebSocket terminal
  |     +-- static web/ serving
  |
  +-- relay mode
  |     |
  |     +-- connect relay-server /ws/agent
  |     +-- agent-register
  |     +-- http-request / http-response
  |     +-- ws-connect / ws-close
  |     +-- tunnel binary frame demux
  |
  +-- session core
  |     |
  |     +-- PTY process
  |     +-- session manager
  |     +-- EventRing replay
  |     +-- raw terminal protocol
  |     +-- headless screen state
  |
  +-- local control API
        |
        +-- status
        +-- config
        +-- logs
        +-- mode switching
```

## 技术选型

### PTY

使用：

```text
github.com/creack/pty
```

职责：

```text
启动 shell
读 PTY 输出
写用户输入
处理 resize
设置 TERM=xterm-256color
管理进程退出
```

替代当前：

```text
server/pty-host.js
node-pty
```

### Headless Terminal

已采用：

```text
github.com/danielgatis/go-headless-term
```

职责：

```text
解析 PTY 输出中的 ANSI / VT 控制序列
维护 primary / alternate screen
维护 cursor 状态
维护 colors / attrs
提供 snapshot / dirty cells
接收 resize
```

第一阶段它作为内部状态层引入，不直接改变现有客户端协议。当前 Go Core 已经把 PTY 输出同步写入 headless screen，并暴露 snapshot / dirty cells 的内部方法；未来再把 dirty cells 转成 `screen.diff.v1`。

注意：

```text
go-headless-term v1.0.9 要求 Go 1.25.1
Go Core 构建环境需要固定到 Go 1.25.x
```

### WebSocket

建议优先评估：

```text
nhooyr.io/websocket
```

或者：

```text
github.com/coder/websocket
```

要求：

```text
支持 subprotocol
能明确区分 text frame 和 binary frame
能处理 backpressure
API 适合 context cancellation
```

### 配置

第一阶段继续兼容现有环境变量：

```text
WEBTERM_MODE
WEBTERM_ADDR
WEBTERM_USER
WEBTERM_PASSWORD
WEBTERM_SHELL
WEBTERM_WEB_ROOT
WEBTERM_CONTROL_ADDR
RELAY_URL
RELAY_SECRET
DEVICE_NAME
WEBTERM_TRACE_TITLE
```

兼容：

```text
WEBTERM_MODE=agent 会被 Go Core 归一化为 relay
```

同时增加可选配置文件：

```text
~/Library/Application Support/WebTerm Agent/config.json
```

配置优先级：

```text
CLI flags > environment variables > config file > defaults
```

### 密钥

第一阶段继续使用环境变量或配置文件读取；本地控制 API 可以写入配置文件。

macOS App 阶段再接入：

```text
macOS Keychain
```

敏感字段：

```text
WEBTERM_PASSWORD
RELAY_SECRET
JWT secret / local control token
```

## Go 目录设计

建议新增：

```text
go-core/
  go.mod

  cmd/webterm-agent/
    main.go

  internal/config/
    config.go
    env.go

  internal/protocol/
    constants.go
    terminal_binary.go
    tunnel.go
    json_wire.go

  internal/session/
    manager.go
    terminal.go
    event_ring.go
    client.go
    pty.go
    screen.go
    cwd.go

  internal/direct/
    server.go
    auth.go
    static.go

  internal/relay/
    client.go
    transport.go
    virtual_socket.go
    reconnect.go

  internal/control/
    server.go
    status.go
    logs.go

  internal/logs/
    logger.go
```

### 模块映射

```text
server/session-manager.js
  -> internal/session/manager.go

server/terminal-session.js
  -> internal/session/terminal.go

server/event-ring.js
  -> internal/session/event_ring.go

server/pty-host.js
  -> internal/session/pty.go

server/client-connections.js
  -> internal/session/client.go
  -> internal/protocol/terminal_binary.go

server/direct.js
  -> internal/direct/server.go

server/agent.js
  -> internal/relay/client.go

shared/constants.js
  -> internal/protocol/constants.go

shared/tunnel-protocol.js
  -> internal/protocol/tunnel.go
```

## 兼容协议

### 终端二进制协议

Go Core 必须保留现有消息码：

```text
MSG_INPUT  = 0x01
MSG_OUTPUT = 0x02
MSG_RESIZE = 0x03
MSG_HELLO  = 0x04
MSG_INFO   = 0x05
MSG_EXIT   = 0x06
MSG_PING   = 0x07
MSG_PONG   = 0x08
MSG_TITLE  = 0x09
MSG_STATE  = 0x0a
```

必须保留 subprotocol：

```text
webterm.binary.v1
webterm.json.v1
```

`MSG_OUTPUT` / `MSG_STATE` 仍使用：

```text
[1 byte type][8 byte seq big endian][payload bytes]
```

### Screen WebSocket 协议

Go Core 新增服务端 screen 协议：

```text
webterm.screen.v1
```

连接地址仍然是：

```text
WS /ws/sessions/{id}
```

客户端发 text JSON：

```json
{"type":"hello","cols":100,"rows":30,"lastSeq":0}
{"type":"input","data":"ls\n"}
{"type":"resize","cols":120,"rows":40}
{"type":"ping"}
```

服务端发 text JSON：

```json
{
  "type": "screen-state",
  "seq": 123,
  "snapshot": {
    "size": {"rows": 30, "cols": 100},
    "cursor": {"row": 0, "col": 0, "visible": true, "style": "block"},
    "lines": []
  }
}
```

```json
{
  "type": "screen-delta",
  "seq": 124,
  "cells": [
    {"row": 0, "col": 0, "char": "a"}
  ]
}
```

约束：

```text
screen 协议只给 Flutter / App 原型使用
现有 Web / Android 默认继续使用 webterm.binary.v1 或 webterm.json.v1
direct 和 relay tunnel 都可以协商 webterm.screen.v1
dirty cells 初版只传 row / col / char，style/cursor diff 后续补齐
```

### Relay tunnel frame

Go Core 必须严格兼容现有 tunnel frame：

```text
[1 byte : message type]
[1 byte : ID length]
[M bytes: Connection or Request ID UTF-8]
[1 byte : payload type / flags]
[rest   : raw binary data]
```

消息类型：

```text
MSG_TYPE_WS_DATA    = 0x01
MSG_TYPE_HTTP_CHUNK = 0x02
```

payload flag：

```text
WS_DATA_TEXT   = 0x01
WS_DATA_BINARY = 0x02
HTTP_CHUNK_DATA = 0x01
HTTP_CHUNK_FIN  = 0x02
```

关键约束：

```text
text frame 必须重新作为 WebSocket text 发送
binary frame 必须重新作为 WebSocket binary 发送
不能把 JSON control payload 当成 binary terminal frame
不能把 binary terminal frame 转成 UTF-8 string
```

## Direct Mode

命令：

```text
WEBTERM_ADDR=127.0.0.1:8080 WEBTERM_PASSWORD=xxx webterm-agent --mode direct
```

需要实现：

```text
POST /api/login
POST /api/auth/login
GET  /api/me
GET  /api/auth/me
GET  /api/sessions
POST /api/sessions
PATCH /api/sessions/{id}
DELETE /api/sessions/{id}

WS /ws/sessions
WS /ws/sessions/{id}
```

认证第一阶段保持单用户密码模式：

```text
WEBTERM_USER 默认 admin
WEBTERM_PASSWORD 必填
cookie 名称与现有 Web 前端兼容
```

静态文件：

```text
优先继续服务现有 web/
未来可以改成 embed.FS 打包进二进制
WEBTERM_WEB_ROOT 可以覆盖前端资源目录
未命中的非 API 路径回退到 index.html
```

## Relay Mode

命令：

```text
RELAY_URL=https://relay.example.com RELAY_SECRET=xxx webterm-agent --mode relay
```

连接流程：

```text
1. 连接 {RELAY_URL}/ws/agent
2. 发送 agent-register
3. relay 返回 registered
4. 清理旧 virtual sockets
5. 等待 http-request / ws-connect / ws-close
6. relay 断开后指数退避重连
```

注册消息：

```json
{
  "type": "agent-register",
  "deviceName": "MacBook",
  "secret": "..."
}
```

需要处理：

```text
registered
error
http-request
ws-connect
ws-close
p2p-offer
p2p-ice
binary tunnel frame
```

P2P / WebRTC 第一阶段可以暂缓：

```text
Go Core 先完整支持 relay WebSocket tunnel
node-datachannel 对应能力不在第一阶段迁移
后续再评估 pion/webrtc
```

## Session Core

### SessionManager

负责：

```text
分配 s1 / s2 / s3 session id
创建 TerminalSession
关闭 TerminalSession
重命名 TerminalSession
维护 manager WebSocket clients
广播 sessions / session / session-closed
```

信息结构需要兼容现有客户端：

```json
{
  "id": "s1",
  "instanceId": "...",
  "name": "",
  "termTitle": "",
  "displayTitle": "Terminal",
  "cwd": "/Users/gao/Documents/webterm-clone",
  "recentInputLines": [],
  "recentInputHidden": false,
  "command": "/bin/zsh",
  "status": "running",
  "clients": 1,
  "cols": 100,
  "rows": 30,
  "createdAt": "...",
  "lastActiveAt": "..."
}
```

### TerminalSession

职责：

```text
启动 shell
维护 PTY
接收 client input
处理 resize
广播 output
维护 EventRing
维护 headless screen
维护 title
维护 cwd
处理 shell exit
```

PTY 输出路径：

```text
PTY read bytes
  -> EventRing.Push(bytes)
  -> HeadlessTerm.Write(bytes)
  -> Broadcast MSG_OUTPUT / JSON output
```

客户端输入路径：

```text
WebSocket MSG_INPUT / JSON input
  -> PTY write
  -> recent input tracker
```

resize 路径：

```text
WebSocket MSG_RESIZE / JSON resize
  -> pty.Setsize
  -> headlessTerm.Resize
  -> session info broadcast
```

### EventRing

Go 版复刻当前行为：

```text
maxFrames 默认 20000
maxBytes 默认 5 MiB
seq 从 1 开始递增
after(seq) 返回 seq 之后的 frames
canReplayFrom(seq) 判断是否仍可回放
```

重连握手：

```text
客户端发送 hello + lastSeq

如果 lastSeq 可回放:
  发送 replay / output frames

如果 lastSeq 不可回放:
  第一阶段发送 MSG_STATE
  MSG_STATE 可先由 headless snapshot 转成文本屏幕
  如果 snapshot 质量不足，发送 clear screen + 最近输出作为兜底
```

## Headless Terminal 与 Screen Diff

第一阶段引入 `go-headless-term`，同时保留现有 raw protocol。

用途：

```text
更新 title
维护当前 screen
为 MSG_STATE 提供 snapshot
为 webterm.screen.v1 提供 dirty cells
```

已新增初版协议：

```text
webterm.screen.v1
```

screen delta 当前格式：

```json
{
  "type": "screen-delta",
  "seq": 123,
  "cells": [
    {
      "row": 1,
      "col": 1,
      "char": "a"
    }
  ]
}
```

注意：

```text
screen diff 已作为 Go Core 初版实验协议提供
screen diff 客户端更适合 Flutter CustomPainter
现有 Web / Android 仍使用 raw output
style / cursor / image diff 后续在协议 v1 内扩展或升级 v2
```

## Local Control API

Go Core 预留本地控制口：

```text
127.0.0.1:18081
```

只监听 loopback，不暴露到局域网。

接口：

```text
GET  /control/status
GET  /control/config
PUT  /control/config
POST /control/restart
POST /control/stop
POST /control/mode/direct
POST /control/mode/relay
GET  /control/logs
GET  /control/logs/stream
POST /control/connection/test
```

已落地：

```text
GET  /control/status
GET  /control/config
PUT  /control/config
POST /control/restart
POST /control/stop
POST /control/mode/direct
POST /control/mode/relay
GET  /control/logs
GET  /control/logs/stream
POST /control/connection/test
GET  /control/sessions
GET  /control/sessions/{id}/screen
GET  /control/sessions/{id}/screen/delta
```

状态返回：

```json
{
  "running": true,
  "mode": "relay",
  "configMode": "direct",
  "restartRequired": true,
  "version": "0.1.0",
  "direct": {
    "listening": false,
    "addr": "127.0.0.1:8080"
  },
  "relay": {
    "configured": true,
    "connected": true,
    "deviceId": "d1",
    "url": "https://relay.example.com",
    "lastError": null
  },
  "sessions": {
    "count": 2
  }
}
```

日志返回：

```json
[
  {
    "seq": 1,
    "time": "2026-06-23T10:00:00Z",
    "level": "info",
    "source": "runtime",
    "message": "runtime started mode=direct"
  }
]
```

日志流：

```text
event: log
data: {"seq":2,"time":"2026-06-23T10:00:01Z","level":"info","source":"direct","message":"direct listening addr=127.0.0.1:8080"}
```

连接测试请求：

```json
{
  "mode": "relay",
  "live": true,
  "config": {
    "relay": {
      "url": "https://relay.example.com",
      "secret": "..."
    }
  }
}
```

连接测试返回：

```json
{
  "ok": true,
  "mode": "relay",
  "live": true,
  "message": "relay registration succeeded"
}
```

说明：

```text
direct 会检查目标地址可监听；如果当前 runtime 已在同一地址监听则视为通过
relay 默认只校验 URL / secret；live=true 时才连接 relay /ws/agent 并发送 test agent-register
```

配置更新返回：

```json
{
  "config": {
    "mode": "relay",
    "direct": {"addr": "127.0.0.1:8080", "user": "admin", "password": "********"},
    "relay": {"url": "https://relay.example.com", "secret": "********", "deviceName": "MacBook"},
    "control": {"addr": "127.0.0.1:18081"},
    "shell": {"command": "/bin/zsh", "cwd": "/Users/gao"}
  },
  "saved": true,
  "configPath": "~/Library/Application Support/WebTerm Agent/config.json",
  "restarted": true,
  "restartRequired": false
}
```

注意：

```text
PUT /control/config 会保留传回来的 ******** 密钥占位符，不覆盖真实 password / relay secret
Go Core CLI 已接入 runtime supervisor 时，PUT /control/config 和 POST /control/mode/* 会立即重启 direct / relay runtime
没有 supervisor 的嵌入测试场景仍只会写配置并标记 restartRequired
```

未来 macOS App / Flutter App 通过这个 API 控制 Go Core。

## 阶段计划

### 阶段 0：基线确认

目标：

```text
确认当前 Node direct / agent 行为
确认 Android 与 Web 使用的协议路径
确认 Relay Server 不需要改
```

建议命令：

```text
npm test
npm run smoke:binary
```

验收：

```text
现有测试通过
记录 direct / relay 启动参数
记录 Android 直连和 relay 关键路径
```

### 阶段 1：Go Core 空壳

交付：

```text
go-core/go.mod
cmd/webterm-agent/main.go
config 加载
日志初始化
--mode direct / --mode relay 参数解析
--version
/control/status
```

验收：

```text
go test ./...
webterm-agent --version
webterm-agent --mode direct --dry-run
```

### 阶段 2：协议移植

交付：

```text
internal/protocol/constants.go
internal/protocol/tunnel.go
internal/protocol/terminal_binary.go
```

测试：

```text
encode/decode tunnel frame
text / binary flag 保真
terminal binary seq big endian
JSON payload decode
```

验收：

```text
Go 编码的 tunnel frame 可被 Node decode
Node 编码的 tunnel frame 可被 Go decode
```

如有必要，增加一个跨语言 fixture：

```text
testdata/tunnel-frame-*.bin
```

### 阶段 3：Session Core

交付：

```text
EventRing
PTY host
TerminalSession
SessionManager
Manager WebSocket broadcast
raw JSON protocol
raw binary protocol
```

验收：

```text
创建 session
输入 echo WEBTERM_OK
收到输出
resize 生效
关闭 session
manager 收到 session/session-closed
```

### 阶段 4：Direct Mode

交付：

```text
HTTP server
cookie auth
session API
terminal WebSocket
static web serving
```

验收：

```text
现有 Web 前端可登录
Web 可创建终端并执行命令
Android 可直连 Go Agent
二进制协议正常
JSON 协议正常
```

建议对照测试：

```text
Node direct 与 Go direct 在相同前端/Android 上行为一致
```

### 阶段 5：Relay Mode

交付：

```text
Relay client
agent-register
http-request handling
ws-connect handling
virtual socket
binary demux
reconnect
```

验收：

```text
Go Agent 能在现有 relay-server 注册为在线设备
Android relay 设备列表显示在线
Android 可通过 relay 创建 session
Android 可通过 relay 打开终端
WebSocket text/binary frame 不混淆
断开 relay 后可自动重连
```

### 阶段 6：Headless Terminal PoC

交付：

```text
go-headless-term 接入 TerminalSession
PTY 输出同时写入 headless term
title extraction
snapshot 导出
dirty cells 统计
```

验证命令：

```text
echo hello
ls --color
printf 中文
vim
less
top
clear
resize
```

验收：

```text
普通输出正确
中文宽字符位置正确
alternate screen 可恢复
resize 后 screen 不崩
title 能更新
snapshot 可用于 MSG_STATE 兜底
```

### 阶段 7：Screen Diff 实验协议

交付：

```text
screen snapshot / diff 数据结构
实验 subprotocol webterm.screen.v1
最小 Flutter / Web canvas 渲染器 PoC
```

验收：

```text
客户端无需解析 ANSI
客户端只 apply snapshot/diff
输入、输出、resize、光标、选择复制可跑通
```

这一阶段不阻塞 Go Core 替换 Node Agent。

### 阶段 8：macOS App 接入

交付：

```text
SwiftUI 或 Flutter desktop shell
启动 / 停止 Go Core
配置视图
模式切换 direct / relay
日志视图
状态视图
Keychain 密钥存储
Dock / menu bar 行为
```

验收：

```text
用户无需打开终端即可启动 Agent
App 能显示 direct / relay 状态
App 能修改配置并重启 Go Core
App 能展示最近日志和错误
```

## 测试策略

### Go 单元测试

覆盖：

```text
protocol constants
tunnel encode/decode
terminal binary encode/decode
EventRing replay
SessionManager create/rename/close
config precedence
auth token/cookie
```

### 集成测试

覆盖：

```text
direct API
direct WebSocket terminal
relay registration
relay HTTP request
relay WebSocket tunnel
text/binary preservation
reconnect
```

### 兼容测试

用现有客户端验证：

```text
Web frontend direct -> Go Agent
Android direct -> Go Agent
Android relay -> relay-server -> Go Agent
```

### 压测与边界

覆盖：

```text
大量输出
快速 resize
多客户端连接同一 session
断线重连
Relay 断开重连
shell 退出
cwd 不存在
shell 不存在
```

## 风险与对策

### 风险 1：headless terminal 兼容性不足

对策：

```text
第一阶段仍然以 raw output 为主
go-headless-term 先只做 snapshot/diff PoC
必要时保留 EventRing replay 作为恢复主路径
```

### 风险 2：Relay text/binary frame 混淆

对策：

```text
tunnel frame 单元测试
跨语言 fixture
Android binary terminal smoke test
明确 WS_DATA_TEXT / WS_DATA_BINARY 分支
```

### 风险 3：Go direct 与现有 auth/cookie 不兼容

对策：

```text
先复刻 direct 简单密码登录
Cookie 名称与路径保持一致
保留 /api/login 和 /api/auth/login 两套路由
```

### 风险 4：PTY 在 macOS / Linux / Windows 差异

对策：

```text
第一阶段优先 macOS/Linux
Windows ConPTY 后续单独评估
保留 WEBTERM_SHELL 覆盖能力
```

### 风险 5：一次性重构范围过大

对策：

```text
先 direct 后 relay
先 raw protocol 后 screen diff
先 CLI 后 App
Relay Server 和客户端暂不动
```

## 交付顺序建议

```text
1. go-core scaffold
2. protocol package + tests
3. EventRing + PTY session
4. Direct mode
5. Relay mode
6. go-headless-term snapshot PoC
7. local control API
8. macOS App shell
9. Flutter screen diff client
```

## 最终形态

```text
webterm-agent
  一个 Go 二进制
  可以命令行启动
  可以被 macOS App 启动
  可以 direct
  可以 relay
  兼容现有 Web / Android
  支持未来 Flutter screen diff 客户端
```

核心原则：

```text
先成为现有 PC Agent 的协议兼容替代品，
再逐步成为服务端渲染和桌面 App 的稳定内核。
```

## 当前实现进度

截至当前 Go Core 初版实现，已经完成：

```text
go-core module scaffold
webterm-agent CLI
webterm-flow-smoke 端到端验证命令
webterm-relay-flow-smoke 端到端验证命令
smoke:go-relay-server 真实 relay-server 集成验证命令
配置加载与 dry-run 脱敏
配置文件保存
WEBTERM_MODE=agent -> relay 兼容
本地 control API
control config PUT 持久化
control mode shortcut
runtime supervisor start / stop / restart
control restart / stop API
config PUT / mode shortcut 自动重启 runtime
in-memory runtime log ring
control logs JSON / SSE API
control connection test API
Flutter PC Agent settings screen
Flutter Go Core control API service
direct HTTP API 登录与 session CRUD
direct static web serving / SPA fallback
direct /ws/sessions manager WebSocket
direct /ws/sessions/{id} terminal WebSocket
真实 PTY shell 启动与输入输出
terminal binary protocol 输出/状态编码
EventRing replay
relay client 注册骨架
relay http-request -> session API -> http-response
relay ws-connect / ws-close tunnel
relay binary MSG_TYPE_WS_DATA demux
relay manager WebSocket tunnel
relay terminal WebSocket tunnel
go-headless-term screen state
screen snapshot
screen dirty cells delta
control screen snapshot / delta API
webterm.screen.v1 WebSocket subprotocol
direct screen WebSocket negotiation
relay screen WebSocket protocol selection
Go relay agent p2p-offer 快速降级响应
relay-server p2p-unavailable -> HTTP 503 fallback
```

已验证：

```text
go test ./...
go run ./cmd/webterm-flow-smoke --url http://127.0.0.1:19080 --user flow --password flowpass --cwd /Users/gao/Documents/webterm-clone
go run ./cmd/webterm-relay-flow-smoke --agent /private/tmp/webterm-agent-relay-flow --cwd /Users/gao/Documents/webterm-clone
npm run smoke:go-relay-server -- --agent /private/tmp/webterm-agent-relay-server-flow --cwd /Users/gao/Documents/webterm-clone
config save / load / redacted secret merge 单元测试
control config PUT / mode shortcut 单元测试
runtime supervisor start / restart / stop 单元测试
runtime immediate start error 单元测试
logger ring / publish 单元测试
control logs JSON / SSE 单元测试
control connection test direct / relay validation 单元测试
control connection test relay live fake-server 单元测试
flutter analyze
flutter test
direct 登录、创建 session、control 状态同步
direct HTTP 登录 -> session 创建 -> binary terminal WebSocket 输入输出端到端流程
direct static index / asset / SPA fallback 单元测试
direct binary WebSocket 输入输出 smoke
manager WebSocket sessions/session 推送 smoke
relay client 连接假 relay、agent-register、处理 HTTP request smoke
relay manager WebSocket tunnel smoke
relay terminal WebSocket tunnel smoke
relay agent-register -> http-request -> ws-connect -> binary terminal tunnel 端到端流程
真实 Node relay-server + Go Core relay agent + relay HTTP/WS client 端到端流程
真实 relay-server /api/p2p/offer 快速 fallback 到 relay tunnel
screen ANSI state / dirty cells 单元测试
真实 PTY 输出进入 StateBytes screen state
control screen snapshot API 单元测试
screen-state / screen-delta client 单元测试
direct screen subprotocol 握手测试
relay screen protocol selection 单元测试
```

尚未完成：

```text
WebRTC P2P DataChannel transport
screen diff style / cursor 增量
macOS Dock App packaging / launch agent
Flutter 启动和管理本机 Go Core 进程
```

当前阶段先暂停 Flutter 接入 Go Core 与 macOS Dock App，优先把 Go Core 自身流程跑通。已用临时端口启动：

```text
WEBTERM_CONTROL_ADDR=127.0.0.1:19081
WEBTERM_ADDR=127.0.0.1:19080
WEBTERM_USER=flow
WEBTERM_PASSWORD=flowpass
webterm-agent --mode direct
```

并验证：

```text
GET /control/status
GET /control/config
POST /control/connection/test
POST /api/login
GET /api/me
POST /api/sessions
GET /api/sessions
GET /control/sessions
WebSocket /ws/sessions/{id} with webterm.binary.v1
```

`webterm-flow-smoke` 会完成登录、创建 session、连接 terminal WebSocket、发送 `MsgInput`、读取 `MsgOutput` 或 `MsgState` 中的标记输出。这个命令可以作为后续 direct 流程的回归验证入口。

relay 流程也已用本地假 relay 跑通。验证命令会启动一个本地 relay WebSocket server，再启动真实 `webterm-agent --mode relay` 二进制连接它：

```text
go build -o /private/tmp/webterm-agent-relay-flow ./cmd/webterm-agent
go run ./cmd/webterm-relay-flow-smoke --agent /private/tmp/webterm-agent-relay-flow --cwd /Users/gao/Documents/webterm-clone
```

`webterm-relay-flow-smoke` 会验证：

```text
agent-register
registered
http-request POST /api/sessions
http-response with created session
ws-connect /ws/sessions/{id}
ws-connected
binary MSG_TYPE_WS_DATA tunnel
terminal MsgHello / MsgInput
terminal MsgOutput 或 MsgState 标记输出
```

真实 relay-server 集成也已跑通。验证入口会创建临时 SQLite DB，预置已验证用户、可信浏览器设备和 PC Agent 设备，启动现有 `relay-server/main.js`，再启动真实 Go `webterm-agent --mode relay`：

```text
npm_config_cache=/private/tmp/webterm-npm-cache npm ci
go build -o /private/tmp/webterm-agent-relay-server-flow ./cmd/webterm-agent
npm run smoke:go-relay-server -- --agent /private/tmp/webterm-agent-relay-server-flow --cwd /Users/gao/Documents/webterm-clone
```

`smoke:go-relay-server` 会验证：

```text
临时 DB migrations
relay-server HTTP/WebSocket 启动
/api/auth/login
/api/devices online
Go agent 使用 DB 设备 secret 完成 agent-register
relay HTTP POST /api/sessions 代理到 Go agent
relay WebSocket /ws/sessions/{deviceId}:{sessionId}
binary terminal MsgHello / MsgInput / MsgOutput
P2P offer 返回 503 快速 fallback
```

Go Core 当前不实现 WebRTC P2P DataChannel。为了先跑通真实 relay 流程，Go agent 收到 `p2p-offer` 会返回 `p2p-unavailable`，relay-server 会立即结束 pending `/api/p2p/offer` 并返回 HTTP 503。前端按现有逻辑会 fallback 到 relay tunnel，不再等待 10 秒 P2P timeout。`p2p-ice` 在 Go agent 中安全忽略。
