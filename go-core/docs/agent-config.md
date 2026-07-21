# Agent 配置

加载顺序固定为：内置默认值、配置文件、环境变量、允许的 CLI 覆盖（`--config`、`--mode`、`--ipc-endpoint`/`--socket`）。显式传入 `--config` 或 `WEBTERM_AGENT_CONFIG` 时，文件不存在或字段未知都会拒绝启动；默认配置文件不存在时允许纯环境变量启动。

```sh
webterm-agent config init --path ./agent.json
webterm-agent config validate --config ./agent.json
webterm-agent config show --effective --config ./agent.json
```

## 接入模式（mode）

`mode` 选择 Agent 的接入方式，只支持两种，互相独立、不回落：

- `relay`（默认）：Android 经中转服务器连接。要求 `relay.url` 合法、`relay.secret` 非空；不校验 Direct 字段。旧配置不写 `mode` 时按 relay 处理。
- `direct`：Android 经 HTTP/WebSocket 直连。要求 `direct.addr`、`direct.username`、`direct.password` 均非空；不要求 `relay.url`/`relay.secret`。

`hybrid`、`auto`、`direct+relay` 等其它值会被拒绝（提示「支持的模式：direct、relay」）。`--mode` 标志优先级最高，覆盖配置文件与环境变量中的 `mode`。Agent 运行时只启动对应模式的 Direct Server 或 Relay Client 之一；Direct 端口绑定失败时进程退出。

## 字段

配置文件字段包括 `mode`、`ipcEndpoint`、`direct.addr`、`direct.username`、`direct.password`、`relay.url`、`relay.secret`、`relay.deviceName`、`shell.command`、`shell.cwd`、`scrollback.maxLines`、`scrollback.maxBytes` 与 `upload.maxBytes`。旧的 `control` 字段只为迁移而接受，运行时会忽略它；不会再启动 HTTP 控制服务。

Direct 模式示例：

```json
{
  "mode": "direct",
  "direct": { "addr": "0.0.0.0:8080", "username": "admin", "password": "your-password" }
}
```

`direct.password` 与 `relay.secret` 一样在 `config show` 和日志中脱敏为 `********`。HTTP Direct 只适合可信局域网；默认监听 `127.0.0.1:8080`，局域网可配 `0.0.0.0:8080`，公网访问须经 HTTPS 反向代理或 VPN。

环境变量：

- `WEBTERM_AGENT_CONFIG`
- `WEBTERM_AGENT_MODE`
- `WEBTERM_AGENT_DIRECT_ADDR`、`WEBTERM_AGENT_DIRECT_USERNAME`、`WEBTERM_AGENT_DIRECT_PASSWORD`
- `WEBTERM_AGENT_RELAY_URL`、`WEBTERM_AGENT_RELAY_SECRET`、`WEBTERM_AGENT_DEVICE_NAME`
- `WEBTERM_AGENT_SOCKET_PATH`
- `WEBTERM_AGENT_SHELL`、`WEBTERM_AGENT_SHELL_CWD`
- `WEBTERM_AGENT_SCROLLBACK_MAX_LINES`、`WEBTERM_AGENT_SCROLLBACK_MAX_BYTES`
- `WEBTERM_AGENT_UPLOAD_MAX_BYTES`

`RELAY_URL`、`RELAY_SECRET`、`DEVICE_NAME`、`WEBTERM_IPC_ENDPOINT`、`WEBTERM_SOCKET_PATH`、`WEBTERM_SHELL` 和 `WEBTERM_MAX_UPLOAD_BYTES` 暂时兼容，并在使用时打印弃用警告。

Windows 使用 `npipe://./pipe/<name>` 形式的 IPC 端点；Named Pipe 仅允许当前用户 SID 访问。Windows 10 需要支持 ConPTY 的 1809 或更高版本。PowerShell 终端自动加载会话更新 Hook；可用 `webterm completion powershell` 生成 CLI 补全脚本。
