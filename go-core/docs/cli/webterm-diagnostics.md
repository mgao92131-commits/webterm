# webterm

此文档由 Cobra 命令定义自动生成。退出码：0 成功或帮助，1 运行失败，2 参数或配置使用错误。

通过正在运行的 webterm-agent 本地 IPC 查询诊断摘要或导出诊断包。前提：webterm-agent 正在运行。

Usage:
  webterm diagnostics [command]

Available Commands:
  export      导出 Agent 诊断包
  summary     显示 Agent 诊断摘要

Flags:
  -h, --help   help for diagnostics

Use "webterm diagnostics [command] --help" for more information about a command.


## summary

显示运行中 Agent 的状态、指标、会话、日志与脱敏配置摘要。

Usage:
  webterm diagnostics summary [flags]

Examples:
  webterm diagnostics summary
  webterm diagnostics summary --json

Flags:
  -h, --help            help for summary
      --json            输出 JSON
      --socket string   覆盖 Agent 本地 IPC 路径


## export

导出运行中 Agent 的诊断包（ZIP），包含日志事件、指标、状态与摘要。

Usage:
  webterm diagnostics export [flags]

Examples:
  webterm diagnostics export
  webterm diagnostics export --output ~/Desktop

Flags:
  -h, --help            help for export
  -o, --output string   诊断包输出目录（支持 ~ 与 Windows 路径）
      --socket string   覆盖 Agent 本地 IPC 路径
