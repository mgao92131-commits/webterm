# WebTerm AI 编码代理指南

## 项目边界

WebTerm 是 Android-only 的远程终端项目。PC Agent 支持两种互相独立的接入模式：

- **Direct**：Android 经 HTTP/WebSocket 直连 PC Agent（`webterm-agent run --mode direct`）。
- **Relay**：Android 经中转服务器连接 PC Agent（`webterm-agent run --mode relay`）。

两种模式不共用配置文件，没有默认模式，不自动切换、不回落，不支持 hybrid/auto。Direct 使用
用户配置目录下的 `WebTerm Agent/direct.json`，Relay 使用 `WebTerm Agent/relay.json`；首次使用
通过 `webterm-agent config init` 选择模式并生成模板。正式组件只有：

| 组件 | 目录 | 职责 |
| --- | --- | --- |
| Go PC Agent | `go-core/cmd/webterm-agent` | PTY、屏幕投影、文件传输、Agent hook；按 mode 启动 Direct Server 或 Relay Client |
| Go Relay | `go-core/cmd/webterm-relay` | 认证、设备注册、HTTP/WS 中转 |
| Android | `android-client/` | 设备、会话、终端、文件和通知 UI |

仓库不包含 Web 前端。

## 协议硬约束

- Android 到 Relay：HTTPS API 与 `/ws/sessions`。
- Android 到 Direct Agent：HTTP API 与 `/ws/sessions`（同一套路由，经 `internal/direct` 接入）。
- Android 物理 WS：`webterm.mux.v1`（Direct 与 Relay 共用，不重新设计）。
- 终端虚拟通道：只允许 `webterm.screen.v1` Protobuf。
- Relay 到 Agent：`/ws/agent` 与 `relaycore` 帧。
- Direct Server 与 Relay Client 共享同一个 `SessionRouter`（经 `internal/agentrouter` 统一装配）；不要复制出第二套控制消息注册或文件传输路由。
- manager 和 mux control 仍使用 JSON；不要将 JSON 消息误认为已删除的旧终端子协议。
- 文件接收保留 `/api/file-send/{id}`，上传保留
  `POST /api/sessions/{id}/upload`。
- Direct 认证使用 HttpOnly Cookie `webterm_token`（登录 `POST /api/auth/login`、刷新 `POST /api/auth/refresh`）；HTTP Direct 只适合可信局域网，公网须经 HTTPS 反向代理/VPN。
- `shared/proto/terminal_screen.proto` 是 Go/Android 屏幕协议 schema。

## 主要目录

- `go-core/internal/session`、`terminalsession`、`terminalengine`：终端生命周期与权威状态。
- `go-core/internal/screenprotocol`、`screenprojection`：Android 屏幕协议。
- `go-core/internal/mux`：Android 单 WS 多通道复用。
- `go-core/internal/agentrouter`：Direct/Relay 共享的 SessionRouter 统一装配。
- `go-core/internal/direct`：Direct Server（HTTP/WS 接入、登录认证、Cookie 校验）。
- `go-core/internal/config`：Agent 配置与 mode（direct/relay）校验。
- `go-core/internal/relay*`：Relay/Agent 中转、认证、存储和指标。
- `go-core/internal/agenthooks`、`agentnotify`、`hook`：AI Agent hook 与通知。
- `go-core/internal/logs`：结构化事件日志（内存 Ring + 本地 `agent.jsonl` 1 MiB×3 滚动 + 5s 限流，限流状态表容量有界）；新埋点用 `logger.Event`，ID 类字段经 `SafeID/HashID`。
- `go-core/internal/diagnostics`：进程级指标（`diagnostics.Default`）与本地导出；`webterm diagnostics summary|export` 经 Local IPC 查询/导出诊断包（manifest/events/metrics/state/summary）。
- `android-client/core-session`、`core-relay`、`feature/*`：Android 连接与业务 UI。

诊断约定：正常路径只计数（metrics/snapshot），状态变化与异常才写事件；禁止记录终端正文、输入正文与凭据；日志与诊断包落在按 endpoint 隔离的 runtime 目录下；Agent 版本经 `-ldflags -X main.version/gitCommit/buildTime` 注入。诊断查询/导出统一走 Local IPC（Unix socket / Windows named pipe），不再有本地 HTTP 控制端口。

## 验证命令

```sh
cd go-core && go test ./...
cd android-client && ./gradlew testDebugUnitTest :app:assembleRelease
cd go-core && go run ./cmd/webterm-relay-e2e-smoke
```

协议或传输改动必须同时验证 Go、Android、Relay E2E、文件上传/接收和重连恢复。
项目文档与注释以中文为主，Go 使用 `gofmt`，Android 保持现有 Java/Gradle 风格。
