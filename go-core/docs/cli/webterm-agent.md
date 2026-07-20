# webterm-agent

此文档由 Cobra 命令定义自动生成。退出码：0 成功或帮助，1 运行失败，2 参数或配置使用错误。

运行 WebTerm Go Agent

Usage:
  webterm-agent [flags]
  webterm-agent [command]

Available Commands:
  completion
  config      管理 Agent 配置
  help        Help about any command
  run         启动 Agent
  version     显示版本

Flags:
  -c, --config string         配置文件路径
  -h, --help                  help for webterm-agent
      --ipc-endpoint string   覆盖本地 IPC endpoint。Unix 示例：unix:/tmp/webterm.sock；Windows 示例：npipe://./pipe/webterm-agent

Use "webterm-agent [command] --help" for more information about a command.


## run

启动 Relay Runtime、本地 IPC 与 PTY/ConPTY 会话。前提：配置中的 Relay URL 和 Secret 有效。

Usage:
  webterm-agent run [flags]

Examples:
  webterm-agent run
  webterm-agent run --config ./agent.json --ipc-endpoint unix:/tmp/webterm.sock

Flags:
  -h, --help   help for run

Global Flags:
  -c, --config string         配置文件路径
      --ipc-endpoint string   覆盖本地 IPC endpoint。Unix 示例：unix:/tmp/webterm.sock；Windows 示例：npipe://./pipe/webterm-agent


## config init

写入默认配置

Usage:
  webterm-agent config init [flags]

Flags:
      --force         覆盖已有配置
  -h, --help          help for init
      --path string   写入路径

Global Flags:
  -c, --config string         配置文件路径
      --ipc-endpoint string   覆盖本地 IPC endpoint。Unix 示例：unix:/tmp/webterm.sock；Windows 示例：npipe://./pipe/webterm-agent


## config show

显示配置

Usage:
  webterm-agent config show [flags]

Flags:
  -c, --config string   配置文件路径
      --effective       严格解析并显示最终生效配置
  -h, --help            help for show
      --json            以紧凑 JSON 输出

Global Flags:
      --ipc-endpoint string   覆盖本地 IPC endpoint。Unix 示例：unix:/tmp/webterm.sock；Windows 示例：npipe://./pipe/webterm-agent


## config validate

校验配置

Usage:
  webterm-agent config validate [flags]

Flags:
  -c, --config string   配置文件路径
  -h, --help            help for validate

Global Flags:
      --ipc-endpoint string   覆盖本地 IPC endpoint。Unix 示例：unix:/tmp/webterm.sock；Windows 示例：npipe://./pipe/webterm-agent


## config path

显示默认配置路径

Usage:
  webterm-agent config path [flags]

Flags:
  -h, --help   help for path

Global Flags:
  -c, --config string         配置文件路径
      --ipc-endpoint string   覆盖本地 IPC endpoint。Unix 示例：unix:/tmp/webterm.sock；Windows 示例：npipe://./pipe/webterm-agent


## completion

Usage:
  webterm-agent completion [bash|zsh|fish|powershell] [flags]

Flags:
  -h, --help   help for completion

Global Flags:
  -c, --config string         配置文件路径
      --ipc-endpoint string   覆盖本地 IPC endpoint。Unix 示例：unix:/tmp/webterm.sock；Windows 示例：npipe://./pipe/webterm-agent
