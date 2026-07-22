# Agent 配置

WebTerm Agent 只有 `direct` 和 `relay` 两种模式。两种模式不共用配置文件、没有默认模式，
也不会自动回退或同时运行。

## 配置文件

配置文件由 `os.UserConfigDir()` 确定：

- Direct：`<用户配置目录>/WebTerm Agent/direct.json`
- Relay：`<用户配置目录>/WebTerm Agent/relay.json`

初始化只生成模板，不询问、读取或写入密码、密钥等敏感内容：

```sh
webterm-agent config init
webterm-agent config init --mode direct
webterm-agent config init --mode relay
webterm-agent config init --mode direct --path ./my-direct.json
```

默认模板文件和自定义模板文件默认都不会覆盖；需要覆盖时明确使用 `--force`。配置目录为
`0700`，配置文件为 `0600`。Direct 模板默认监听 `127.0.0.1:8080`，避免初始化后直接暴露
到局域网。

## 文件选择优先级

运行配置的文件选择顺序是：

1. `--config <path>`
2. `WEBTERM_AGENT_CONFIG`
3. `--mode direct|relay`，选择对应的默认文件
4. `WEBTERM_AGENT_MODE`，选择对应的默认文件
5. 检测 `direct.json` 和 `relay.json`
6. 交互终端让用户选择

`--config` 和 `WEBTERM_AGENT_CONFIG` 直接决定文件，文件中的 `mode` 是最终模式；环境模式
不会约束显式配置文件。若显式配置文件同时提供 `--mode`，则文件模式必须与之匹配。

`--mode` 只负责选择 `direct.json` 或 `relay.json`，不覆盖文件中的 `mode`。文件必须明确包含
`"mode": "direct"` 或 `"mode": "relay"`；缺少 `mode`、空值及未知值都会拒绝启动。

只有一份默认模式文件时，`webterm-agent run` 可以自动使用它。两份都存在时，交互终端显示
选择菜单；非交互环境必须明确指定 `--mode` 或 `--config`。两份都不存在时，初始化命令生成
模板并退出，不会自动启动 Agent。

## 配置字段

模板包含 `mode`、`ipcEndpoint`、当前模式专属的 `direct` 或 `relay`、`shell`、`scrollback`
和 `upload` 字段。Direct 示例：

```json
{
  "mode": "direct",
  "ipcEndpoint": "",
  "direct": {
    "addr": "127.0.0.1:8080",
    "username": "admin",
    "password": "",
    "allowInsecureRemote": false
  },
  "shell": { "command": "", "cwd": "" },
  "scrollback": { "maxLines": 10000, "maxBytes": 8388608 },
  "upload": { "maxBytes": 104857600 }
}
```

Relay 模板要求用户填写 `relay.url` 和 `relay.secret`，`deviceName` 默认使用当前主机名，
`protocol` 固定为 `v2`。Direct 要求 `direct.username` 和 `direct.password`；非回环明文监听
还必须显式设置 `direct.allowInsecureRemote=true`。

## 字段覆盖与环境变量

在已经选定配置文件后，字段覆盖顺序为：内置公共默认值、配置文件、允许的普通字段环境变量、
CLI 运行覆盖（例如 `--ipc-endpoint`）。`--mode` 和 `WEBTERM_AGENT_MODE` 只参与文件选择，
不覆盖文件内部的 `mode`。

支持的主要环境变量：

- `WEBTERM_AGENT_CONFIG`、`WEBTERM_AGENT_MODE`
- `WEBTERM_AGENT_DIRECT_ADDR`、`WEBTERM_AGENT_DIRECT_USERNAME`、`WEBTERM_AGENT_DIRECT_PASSWORD`
- `WEBTERM_AGENT_DIRECT_ALLOW_INSECURE_REMOTE`
- `WEBTERM_AGENT_RELAY_URL`、`WEBTERM_AGENT_RELAY_SECRET`、`WEBTERM_AGENT_DEVICE_NAME`
- `WEBTERM_AGENT_SOCKET_PATH`、`WEBTERM_AGENT_SHELL`、`WEBTERM_AGENT_SHELL_CWD`
- `WEBTERM_AGENT_SCROLLBACK_MAX_LINES`、`WEBTERM_AGENT_SCROLLBACK_MAX_BYTES`
- `WEBTERM_AGENT_UPLOAD_MAX_BYTES`

安全布尔环境变量只接受 `true`、`false`、`1`、`0`；非法值会拒绝启动。`config show` 会将
`direct.password` 和 `relay.secret` 脱敏为 `********`。

旧的 `control` 字段只为迁移而接受，Agent 不再启动本地 HTTP 控制服务。Direct 认证使用
HttpOnly Cookie；明文 Direct 仅适合可信局域网，公网访问须经 HTTPS 反向代理或 VPN。

## 配置命令

```sh
webterm-agent config path --mode direct
webterm-agent config path --all
webterm-agent config validate --mode relay
webterm-agent config validate --config ./my-agent.json
webterm-agent config show --mode direct
webterm-agent config show --config ./my-agent.json --json
```

交互菜单写入终端错误输出，支持无效输入重试；输入 `0` 正常取消并以退出码 0 结束。非交互
环境不会等待模式选择。

Windows 使用 `npipe://./pipe/<name>` 形式的 IPC 端点；Named Pipe 仅允许当前用户 SID 访问。
