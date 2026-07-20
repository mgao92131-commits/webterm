# webterm

此文档由 Cobra 命令定义自动生成。退出码：0 成功或帮助，1 运行失败，2 参数或配置使用错误。

发送一个普通文件。前提：webterm-agent 与至少一个支持文件接收的 Android 设备正在运行。

Usage:
  webterm send <file> [flags]

Examples:
  webterm send ./app-release.apk
  webterm send --device pixel ./report.pdf

Flags:
  -d, --device string   目标设备名称、短 ID、完整 ID 或 recent
  -h, --help            help for send
  -q, --quiet           只输出错误信息
      --socket string   覆盖 Agent 本地 IPC 路径
