# Agent Hook 通知指南

WebTerm 不解释 Claude Code、Codex 或 Kimi Code 的任务状态。每个 Agent 的 Hook 自己决定何时通知，然后调用统一接口：

```bash
webterm agent-event -i <alert|normal|quiet> -m "MSG" -s <source>
```

importance 只有三档，事件到重要性的映射只存在于安装脚本的配置层，helper 和 CLI 原样透传，不根据工具名推导状态：

| importance | 语义 | Android 行为 |
| --- | --- | --- |
| `alert` | 等待审批 / 任务失败 | 高优先级横幅 + 声音 |
| `normal` | 任务完成 | 默认有声通知 |
| `quiet` | 运行中 / 会话结束 | 不发系统通知，仅更新会话徽标 |

Helper 用法（位置参数）：

```bash
~/.webterm/bin/webterm-notify-helper <importance> <default-message> <source>
```

它从 Agent stdin JSON 提取简短文本，并自动传递 WebTerm session。建议使用仓库安装器，它会备份旧配置并迁移旧 WebTerm Hook：

```bash
scripts/install-agent-hook-examples.sh
```

## 推荐映射

| Agent Hook | importance | 默认消息 | 说明 |
| --- | --- | --- | --- |
| Claude/Codex/Kimi `UserPromptSubmit` | `quiet` | `Running` | 提问提交后，置为静默运行态 |
| Claude/Kimi `PreToolUse`（matcher `AskUserQuestion`） | `alert` | `Question` | 提问开始；从 `tool_input.questions[0].question` 提取正文 |
| Claude/Kimi `PostToolUse`（matcher `AskUserQuestion`） | `quiet` | `Running` | 用户回答完成后确定性复位为灰色运行态 |
| Claude/Codex/Kimi `PermissionRequest` | `alert` | `Waiting for approval` | 挂起等待权限审批，发出高优先级声音与横幅 |
| Kimi `PermissionResult` | `quiet` | `Running` | 权限审批完成（允许或拒绝）后，即时将终端指示芯片复位为灰色运行态 |
| Claude/Codex/Kimi `Stop` | `normal` | `Done` | 任务正常完成，发出有声通知 |
| Claude/Kimi `StopFailure` | `alert` | `Failed` | 任务失败，发出声音与横幅 |
| Kimi `Notification`（matcher `task.completed`） | `normal` | `Done` | 后台任务运行完成 |
| Claude/Kimi `SessionEnd` | `quiet` | `Session ended` | 会话正常退出 |
| agy（Antigravity）`PreInvocation` | `quiet` | `Running` | 开始运行时静默更新徽标 |
| agy（Antigravity）`Stop` | `normal` | `Done` | 运行结束 |

禁止把通配 `PreToolUse`、`PostToolUse` 或普通 Agent `Notification` 映射为通知，否则每次工具调用都会产生高频 session 更新。提问通知必须在配置层用精确 matcher 成对声明：`PreToolUse → alert`、`PostToolUse → quiet`。`PermissionResult` 只表示审批完成，不等于普通问题已回答。每个 Agent 只能保留一个任务完成 Hook，通常就是 `Stop`。

Codex 当前的 `PreToolUse` / `PostToolUse` 对普通非 Shell、非 MCP 工具拦截不完整，因此安装器不为 Codex 配置未经验证的提问 Hook，只保留 `UserPromptSubmit`、`PermissionRequest` 和 `Stop`。安装器修改 `~/.codex/hooks.json` 后，请在 Codex 中执行 `/hooks`，审查并信任新的 Hook 定义，否则新定义会被跳过。

agy（Antigravity）的配置位置与其他 Agent 不同：全局为 `~/.gemini/config/hooks.json`，工作区级为 `.agents/hooks.json`；文件顶层 key 是 hook 名字（安装器使用 `webterm-notify`），`PreInvocation`/`Stop` 为扁平 handler 列表（只有 `PreToolUse`/`PostToolUse` 需要 `matcher` 分组）。agy 没有审批请求或会话结束的等价事件；`Stop` 的 stdin 含 `terminationReason`/`error` 字段，目前统一按 `normal` 处理。

## 通知内容

- 通知标题自动使用终端会话标题（来自 termTitle，即终端通过 OSC 转义序列上报的标题；若为空则按规则回退使用 source 或事件类型），无需 hook 提供。
- 正文由 helper 从 hook payload 提取：优先取 `questions` 或 `tool_input.questions` 的首个问题，其次取顶层 `prompt` / `content` / `message` / `last_assistant_message` / `text`，再取 `messages` 数组最后一条，最后取 `tool_input.command`（带 `tool_name` 时输出 `工具名: 命令`，如 `Bash: git push`）。提取不到时使用默认消息。
- 正文截断为 120 字符。
- `quiet` 事件仍更新 session/chip，但 Go 后端不会把它发送到设备级 `agent_notification` 通道。

## 前台抑制

Android 端会根据当前焦点处理通知：用户正查看同一终端时，只在终端内显示状态，不播放声音、不生成“打开终端”通知；否则按 importance 使用对应的系统通知渠道。
