# WebTerm

WebTerm 是个人使用的 Android 远程终端系统，只包含三个正式组件：

- Go PC Agent：管理 PTY、权威终端屏幕、文件传输和 Agent hook。
- Go Relay：负责认证、设备注册以及 Android 与 PC Agent 之间的流量中转。
- Android 客户端：设备管理、终端显示与输入、文件上传/接收和任务通知。

## 正式链路

PC Agent 支持两种互相独立的接入模式（`--mode direct` / `--mode relay`，默认 relay）：

```text
Direct: Android -- HTTP + /ws/sessions --> Go PC Agent --> PTY
Relay:  Android -- HTTPS + /ws/sessions --> Go Relay -- /ws/agent --> Go PC Agent --> PTY
```

两种模式不自动切换、不回落。终端均使用 `webterm.mux.v1` 复用通道，具体终端
通道只接受 `webterm.screen.v1` Protobuf；Direct 与 Relay 复用同一套 Mux 与
SessionRouter。Direct 经 HttpOnly Cookie `webterm_token` 认证，只适合可信局域网，
公网访问须经 HTTPS 反向代理或 VPN。

## 构建与测试

```sh
cd go-core
go test ./...
go build ./cmd/webterm-agent
go build ./cmd/webterm-relay
go run ./cmd/webterm-relay-e2e-smoke

cd ../android-client
./gradlew testDebugUnitTest
./gradlew :app:assembleRelease
```

## 启动

Relay 模式（默认）：

```sh
cd go-core
WEBTERM_AGENT_RELAY_URL=http://relay.example:9001 \
WEBTERM_AGENT_RELAY_SECRET='agent-secret' \
WEBTERM_AGENT_DEVICE_NAME='my-mac' \
go run ./cmd/webterm-agent run --mode relay
```

Direct 模式（Android 直连；局域网内监听 `0.0.0.0:8080`）：

```sh
cd go-core
WEBTERM_AGENT_MODE=direct \
WEBTERM_AGENT_DIRECT_ADDR=0.0.0.0:8080 \
WEBTERM_AGENT_DIRECT_USERNAME='admin' \
WEBTERM_AGENT_DIRECT_PASSWORD='your-password' \
go run ./cmd/webterm-agent run --mode direct
```

`webterm-agent` 按 mode 只启动 Direct Server 或 Relay Client 之一（绝不两者同时），
并启动本地 IPC 与 PTY；文件发送、设备查询和通知均通过 `webterm` 本地 CLI。
可使用 `webterm-agent config init` 创建模板、`config validate` 校验配置。

Relay 使用显式子命令启动，并通过一次性管理命令创建管理员：

```sh
cd go-core
webterm-relay config init --path /etc/webterm/relay.json
webterm-relay config validate --config /etc/webterm/relay.json
webterm-relay admin create --config /etc/webterm/relay.json \
  --username admin --password-file /run/secrets/webterm-admin-password
webterm-relay run --config /etc/webterm/relay.json
```

完整参数、示例和退出码见 [CLI 文档](go-core/docs/cli/webterm.md)，
配置字段与环境变量见 [Agent 配置](go-core/docs/agent-config.md) 和
[Relay 配置](go-core/docs/relay-config.md)。

Relay 默认监听 `127.0.0.1:19090`。部署时由 Nginx 只代理 `/api/` 和
`/ws/`，不托管静态页面。

敏感信息应通过受限权限的配置文件或 Secret 文件注入，不得提交到仓库。
