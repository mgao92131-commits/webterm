# Agent 配置

加载顺序固定为：内置默认值、配置文件、环境变量、允许的 CLI 覆盖（`--config`、`--socket`）。显式传入 `--config` 或 `WEBTERM_AGENT_CONFIG` 时，文件不存在或字段未知都会拒绝启动；默认配置文件不存在时允许纯环境变量启动。

```sh
webterm-agent config init --path ./agent.json
webterm-agent config validate --config ./agent.json
webterm-agent config show --effective --config ./agent.json
```

配置文件字段包括 `ipcEndpoint`、`relay.url`、`relay.secret`、`relay.deviceName`、`shell.command`、`shell.cwd`、`scrollback.maxLines`、`scrollback.maxBytes` 与 `upload.maxBytes`。旧的 `control` 字段只为迁移而接受，运行时会忽略它；不会再启动 HTTP 控制服务。

环境变量：

- `WEBTERM_AGENT_CONFIG`
- `WEBTERM_AGENT_RELAY_URL`、`WEBTERM_AGENT_RELAY_SECRET`、`WEBTERM_AGENT_DEVICE_NAME`
- `WEBTERM_AGENT_SOCKET_PATH`
- `WEBTERM_AGENT_SHELL`、`WEBTERM_AGENT_SHELL_CWD`
- `WEBTERM_AGENT_SCROLLBACK_MAX_LINES`、`WEBTERM_AGENT_SCROLLBACK_MAX_BYTES`
- `WEBTERM_AGENT_UPLOAD_MAX_BYTES`

`RELAY_URL`、`RELAY_SECRET`、`DEVICE_NAME`、`WEBTERM_IPC_ENDPOINT`、`WEBTERM_SOCKET_PATH`、`WEBTERM_SHELL` 和 `WEBTERM_MAX_UPLOAD_BYTES` 暂时兼容，并在使用时打印弃用警告。

Windows 使用 `npipe://./pipe/<name>` 形式的 IPC 端点；Named Pipe 仅允许当前用户 SID 访问。Windows 10 需要支持 ConPTY 的 1809 或更高版本。PowerShell 终端自动加载会话更新 Hook；可用 `webterm completion powershell` 生成 CLI 补全脚本。
