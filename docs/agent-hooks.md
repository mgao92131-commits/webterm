# Agent Hook 通知指南

WebTerm 不解释 Claude Code、Codex 或 Kimi Code 的任务状态。每个 Agent 的 Hook 自己决定何时通知，然后调用统一接口：

```bash
webterm agent-event -i <alert|normal|quiet> -m "MSG" -s <source>
```

importance 只有三档，事件到重要性的映射只存在于安装脚本的配置层，helper 和 CLI 原样透传：

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

| Agent Hook | importance | 默认消息 |
| --- | --- | --- |
| Claude/Codex/Kimi `UserPromptSubmit` | `quiet` | `Running` |
| Claude/Codex/Kimi `Stop` | `normal` | `Done` |
| Claude/Codex/Kimi `PermissionRequest` | `alert` | `Waiting for approval` |
| Claude/Kimi `StopFailure` | `alert` | `Failed` |
| Kimi `Notification`（matcher `task.completed`，后台任务完成） | `normal` | `Done` |
| Claude/Kimi `SessionEnd` | `quiet` | `Session ended` |
| agy（Antigravity）`PreInvocation` | `quiet` | `Running` |
| agy（Antigravity）`Stop` | `normal` | `Done` |

不要把 `PreToolUse`、`PostToolUse` 或普通 Agent `Notification` 映射为通知；它们会产生高频噪声。每个 Agent 只能保留一个完成 Hook，通常就是 `Stop`。

agy（Antigravity）的配置位置与其他 Agent 不同：全局为 `~/.gemini/config/hooks.json`，工作区级为 `.agents/hooks.json`；文件顶层 key 是 hook 名字（安装器使用 `webterm-notify`），`PreInvocation`/`Stop` 为扁平 handler 列表（只有 `PreToolUse`/`PostToolUse` 需要 `matcher` 分组）。agy 没有审批请求或会话结束的等价事件；`Stop` 的 stdin 含 `terminationReason`/`error` 字段，目前统一按 `normal` 处理。

## 通知内容

- 通知标题自动使用终端会话标题（DisplayTitle），无需 hook 提供。
- 正文由 helper 从 hook payload 提取：优先取顶层 `prompt` / `content` / `message` / `last_assistant_message` / `text`，其次 `messages` 数组最后一条，最后 `tool_input.command`（带 `tool_name` 时输出 `工具名: 命令`，如 `Bash: git push`）。提取不到时使用默认消息。
- 正文截断为 120 字符。

## 前台抑制

Android 端会根据当前焦点处理通知：用户正查看同一终端时，只在终端内显示状态，不播放声音、不生成“打开终端”通知；否则按 importance 使用对应的系统通知渠道。
