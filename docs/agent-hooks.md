# Agent Hook 配置指南

WebTerm PC Agent 本身不管理任何 Agent（Claude Code / Kimi Code / Codex）的 hook。它只暴露三个 CLI 命令：

- `webterm notify --level idle|running|error --message "..." --source "..."`
- `webterm state --shell running|prompt|unknown`
- `webterm meta --cwd ... --last-command ... --input-kind shell|agent_prompt|agent_tool`

用户需要自己在各 Agent 的配置文件中声明 hook，让它在合适的事件点调用 `webterm notify`。

## 推荐的 Helper 脚本

Agent 事件通过 stdin 传入 JSON payload，直接在每个 hook 命令里写 Python 提取会很冗长。建议把仓库里的 helper 脚本复制到本地：

```bash
cp scripts/webterm-notify-helper.sh ~/.webterm/bin/webterm-notify-helper
chmod +x ~/.webterm/bin/webterm-notify-helper
```

用法：

```bash
~/.webterm/bin/webterm-notify-helper <level> <default-message> <source>
```

它会从 stdin 读取 payload，按以下优先级提取文本（截断到 60 字符）：

1. `prompt` / `content` / `message` / `text`
2. `messages[-1].content` / `.text` / `.prompt`
3. `tool_input.command` / `.cmd` / `.code` / `.script`

找不到时就使用 `<default-message>`。

## Claude Code

配置文件：`~/.claude/settings.json`

```json
{
  "hooks": {
    "UserPromptSubmit": [
      {
        "matcher": "*",
        "hooks": [
          {
            "type": "command",
            "command": "~/.webterm/bin/webterm-notify-helper running \"Running\" claude"
          }
        ]
      }
    ],
    "PreToolUse": [
      {
        "matcher": "*",
        "hooks": [
          {
            "type": "command",
            "command": "~/.webterm/bin/webterm-notify-helper running \"Running\" claude"
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "*",
        "hooks": [
          {
            "type": "command",
            "command": "~/.webterm/bin/webterm-notify-helper running \"Running\" claude"
          }
        ]
      }
    ],
    "PermissionRequest": [
      {
        "matcher": "*",
        "hooks": [
          {
            "type": "command",
            "command": "~/.webterm/bin/webterm-notify-helper error \"Waiting for approval\" claude"
          }
        ]
      }
    ],
    "Notification": [
      {
        "matcher": "*",
        "hooks": [
          {
            "type": "command",
            "command": "~/.webterm/bin/webterm-notify-helper error \"Needs attention\" claude"
          }
        ]
      }
    ],
    "Stop": [
      {
        "matcher": "*",
        "hooks": [
          {
            "type": "command",
            "command": "~/.webterm/bin/webterm-notify-helper idle \"Done\" claude"
          }
        ]
      }
    ],
    "SessionEnd": [
      {
        "matcher": "*",
        "hooks": [
          {
            "type": "command",
            "command": "~/.webterm/bin/webterm-notify-helper idle \"Idle\" claude"
          }
        ]
      }
    ]
  }
}
```

## Kimi Code

配置文件：`~/.kimi-code/config.toml`

```toml
[[hooks]]
event = "UserPromptSubmit"
matcher = ".*"
command = "~/.webterm/bin/webterm-notify-helper running \"Running\" kimi"
timeout = 5

[[hooks]]
event = "PreToolUse"
matcher = ".*"
command = "~/.webterm/bin/webterm-notify-helper running \"Running\" kimi"
timeout = 5

[[hooks]]
event = "PermissionRequest"
matcher = ".*"
command = "~/.webterm/bin/webterm-notify-helper error \"Waiting for approval\" kimi"
timeout = 5

[[hooks]]
event = "Notification"
matcher = ".*"
command = "~/.webterm/bin/webterm-notify-helper error \"Needs attention\" kimi"
timeout = 5

[[hooks]]
event = "StopFailure"
matcher = ".*"
command = "~/.webterm/bin/webterm-notify-helper error \"Task failed\" kimi"
timeout = 5

[[hooks]]
event = "SessionEnd"
matcher = ".*"
command = "~/.webterm/bin/webterm-notify-helper idle \"Idle\" kimi"
timeout = 5
```

## Codex

配置文件：`~/.codex/hooks.json`

Codex 目前暴露的 hook 事件相对较少，常见的是 `UserPromptSubmit`、`PreToolUse`、`Stop`、`SessionEnd`。

```json
{
  "hooks": {
    "UserPromptSubmit": [
      {
        "matcher": "*",
        "hooks": [
          {
            "type": "command",
            "command": "~/.webterm/bin/webterm-notify-helper running \"Running\" codex"
          }
        ]
      }
    ],
    "PreToolUse": [
      {
        "matcher": "*",
        "hooks": [
          {
            "type": "command",
            "command": "~/.webterm/bin/webterm-notify-helper running \"Running\" codex"
          }
        ]
      }
    ],
    "Stop": [
      {
        "matcher": "*",
        "hooks": [
          {
            "type": "command",
            "command": "~/.webterm/bin/webterm-notify-helper idle \"Done\" codex"
          }
        ]
      }
    ],
    "SessionEnd": [
      {
        "matcher": "*",
        "hooks": [
          {
            "type": "command",
            "command": "~/.webterm/bin/webterm-notify-helper idle \"Idle\" codex"
          }
        ]
      }
    ]
  }
}
```

## 其他 Agent

只要 Agent 支持在事件触发时执行 shell 命令，并且能通过 stdin 或环境变量传入事件信息，都可以按同样模式接入：

```bash
webterm notify --level <idle|running|error> --message "..." --source <agent-name>
```

## 常见问题

### `webterm: command not found`

确保 `webterm` 在 PATH 里，或者用 `WEBTERM_BIN` 指定完整路径：

```bash
export WEBTERM_BIN=/path/to/webterm
```

### 通知没有显示

检查 `WEBTERM_SESSION_ID` 和 `WEBTERM_SOCKET_PATH` 环境变量是否在 Agent 进程里。它们由 WebTerm 注入到 PTY，Agent 必须在这个终端内启动才能正确上报。

### 消息太短或没有提取到

`webterm-notify-helper` 会尝试多个字段，但各家 Agent 的 payload 字段名可能不同。可以先用以下命令调试：

```bash
echo '{"prompt":"test"}' | ~/.webterm/bin/webterm-notify-helper running "Running" claude
```
