# WebTerm

WebTerm 是个人使用的 Android 远程终端系统，只包含三个正式组件：

- Go PC Agent：管理 PTY、权威终端屏幕、文件传输和 Agent hook。
- Go Relay：负责认证、设备注册以及 Android 与 PC Agent 之间的流量中转。
- Android 客户端：设备管理、终端显示与输入、文件上传/接收和任务通知。

## 正式链路

PC Agent 支持两种互相独立的接入模式（`direct` / `relay`），没有默认模式：

```text
Direct: Android -- HTTP + /ws/sessions --> Go PC Agent --> PTY
Relay:  Android -- HTTPS + /ws/sessions --> Go Relay -- /ws/agent --> Go PC Agent --> PTY
```

两种模式不自动切换、不回落。终端均使用 `webterm.mux.v1` 复用通道，具体终端
通道只接受 `webterm.screen.v2` Protobuf；Direct 与 Relay 复用同一套 Mux 与
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

## 配置与启动

Direct 和 Relay 不共用配置文件：

- Direct：用户配置目录下的 `WebTerm Agent/direct.json`
- Relay：用户配置目录下的 `WebTerm Agent/relay.json`

首次使用时生成模板并选择模式：

```sh
cd go-core
go run ./cmd/webterm-agent config init
```

交互终端会选择 Direct 或 Relay，非交互环境必须明确指定模式：

```sh
go run ./cmd/webterm-agent config init --mode direct
go run ./cmd/webterm-agent config init --mode relay
```

编辑模板填写密码或密钥后启动：

```sh
cd go-core
go run ./cmd/webterm-agent run --mode direct
go run ./cmd/webterm-agent run --mode relay
```

`--mode` 只选择对应的默认配置文件，不覆盖文件内部的 `mode`。也可以用
`--config ./my-agent.json` 运行自定义文件；此时模式由文件中的 `mode` 决定，若同时指定
`--mode` 则必须一致。`WEBTERM_AGENT_CONFIG` 与 `WEBTERM_AGENT_MODE` 分别对应配置文件和
默认模式文件选择。没有配置文件时，交互终端会引导初始化；后台环境应使用 `--mode` 或
`--config` 明确指定。标准启动流程不依赖纯环境变量生成运行配置。

Direct 模式（Android 直连；如需局域网访问，编辑模板中的监听地址并显式确认明文风险）：

```sh
cd go-core
go run ./cmd/webterm-agent config show --mode direct
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
