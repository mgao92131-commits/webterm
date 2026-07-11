# Agent Hook 通知指南

WebTerm 不解释 Claude Code、Codex 或 Kimi Code 的任务状态。每个 Agent 的 Hook 自己决定何时通知，然后调用统一接口：

```bash
webterm agent-event --kind completed --message "Done" --source claude
```

支持的事件：

- `started`：仅记录为普通状态，不创建系统通知。
- `completed`：任务完成；未正在查看对应终端时，走“Agent 完成”有声通知。
- `failed`：任务失败；走高优先级有声通知。
- `attention`：等待授权、输入或确认；走高优先级有声通知。
- `session-ended`：仅清理会话状态，不通知。

Helper 用法：

```bash
~/.webterm/bin/webterm-notify-helper <kind> <default-message> <source>
```

它从 Agent stdin JSON 提取简短文本，并自动传递 WebTerm session。建议使用仓库安装器，它会备份旧配置并迁移旧 WebTerm Hook：

```bash
scripts/install-agent-hook-examples.sh
```

## 推荐映射

| Agent Hook | WebTerm 事件 |
| --- | --- |
| Claude/Codex/Kimi `UserPromptSubmit` | `started` |
| Claude/Codex/Kimi `Stop` | `completed` |
| Kimi `StopFailure` | `failed` |
| Claude/Kimi `PermissionRequest` | `attention` |
| 任意 `SessionEnd` | `session-ended` |

不要把 `PreToolUse`、`PostToolUse` 或普通 Agent `Notification` 映射为系统通知；它们会产生高频噪声。每个 Agent 只能保留一个完成 Hook，通常就是 `Stop`。

Android 端会根据当前焦点处理通知：用户正查看同一终端时，只在终端内显示状态，不播放声音、不生成“打开终端”通知；否则按事件类型使用系统通知渠道。
