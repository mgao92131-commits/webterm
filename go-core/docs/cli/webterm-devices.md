# webterm

此文档由 Cobra 命令定义自动生成。退出码：0 成功或帮助，1 运行失败，2 参数或配置使用错误。

列出 Agent 已知的 Android 文件接收设备。前提：webterm-agent 正在运行。

Usage:
  webterm devices [flags]

Examples:
  webterm devices
  webterm devices --online
  webterm devices --json

Flags:
  -h, --help            help for devices
      --json            输出 JSON
      --online          只显示在线设备
      --socket string   覆盖 Agent 本地 IPC 路径
