# webterm

此文档由 Cobra 命令定义自动生成。退出码：0 成功或帮助，1 运行失败，2 参数或配置使用错误。

向指定会话发送 alert、normal 或 quiet 通知。quiet 只更新会话信息，不弹系统通知。

Usage:
  webterm notify [flags]

Examples:
  webterm notify --importance normal --message 'Codex 已完成任务' --source codex

Flags:
  -h, --help                help for notify
  -i, --importance string   通知重要性：alert、normal、quiet
  -m, --message string      通知内容
      --pid int             根据调用进程解析会话
      --session string      指定 WebTerm 会话
      --socket string       覆盖 Agent 本地 IPC 路径
  -s, --source string       通知来源 (default "webterm-cli")
