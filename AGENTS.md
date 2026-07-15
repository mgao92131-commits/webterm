# WebTerm AI 编码代理指南

## 项目边界

WebTerm 是 Android-only、Relay-only 的远程终端项目。正式组件只有：

| 组件 | 目录 | 职责 |
| --- | --- | --- |
| Go PC Agent | `go-core/cmd/webterm-agent` | PTY、屏幕投影、文件传输、Agent hook |
| Go Relay | `go-core/cmd/webterm-relay` | 认证、设备注册、HTTP/WS 中转 |
| Android | `android-client/` | 设备、会话、终端、文件和通知 UI |

仓库不包含 Web 前端，不支持 PC Agent direct 模式。

## 协议硬约束

- Android 到 Relay：HTTPS API 与 `/ws/sessions`。
- Android 物理 WS：`webterm.mux.v1`。
- 终端虚拟通道：只允许 `webterm.screen.v1` Protobuf。
- Relay 到 Agent：`/ws/agent` 与 `relaycore` 帧。
- manager 和 mux control 仍使用 JSON；不要将 JSON 消息误认为已删除的旧终端子协议。
- 文件接收保留 `/api/file-send/{id}`，上传保留
  `POST /api/sessions/{id}/upload`。
- `shared/proto/terminal_screen.proto` 是 Go/Android 屏幕协议 schema。

## 主要目录

- `go-core/internal/session`、`terminalsession`、`terminalengine`：终端生命周期与权威状态。
- `go-core/internal/screenprotocol`、`screenprojection`：Android 屏幕协议。
- `go-core/internal/mux`：Android 单 WS 多通道复用。
- `go-core/internal/relay*`：Relay/Agent 中转、认证、存储和指标。
- `go-core/internal/agenthooks`、`agentnotify`、`hook`：AI Agent hook 与通知。
- `android-client/core-session`、`core-relay`、`feature/*`：Android 连接与业务 UI。

## 验证命令

```sh
cd go-core && go test ./...
cd android-client && ./gradlew testDebugUnitTest :app:assembleRelease
cd go-core && go run ./cmd/webterm-relay-e2e-smoke
```

协议或传输改动必须同时验证 Go、Android、Relay E2E、文件上传/接收和重连恢复。
项目文档与注释以中文为主，Go 使用 `gofmt`，Android 保持现有 Java/Gradle 风格。
