# webterm-relay

此文档由 Cobra 命令定义自动生成。退出码：0 成功或帮助，1 运行失败，2 参数或配置使用错误。

运行 WebTerm Relay

Usage:
  webterm-relay [flags]
  webterm-relay [command]

Available Commands:
  admin       Relay 管理命令
  completion
  config      管理 Relay 配置
  help        Help about any command
  run         启动 Relay
  version

Flags:
  -h, --help   help for webterm-relay

Use "webterm-relay [command] --help" for more information about a command.


## run

启动 WebTerm Relay。前提：配置文件有效且 Store 路径可写。

Usage:
  webterm-relay run [flags]

Examples:
  webterm-relay run --config /etc/webterm/relay.json

Flags:
  -c, --config string   Relay 配置文件
  -h, --help            help for run
      --listen string   临时覆盖监听地址


## config init

创建默认配置

Usage:
  webterm-relay config init [flags]

Flags:
      --force         覆盖已有配置
  -h, --help          help for init
      --path string   配置写入路径


## config validate

校验配置

Usage:
  webterm-relay config validate [flags]

Flags:
  -c, --config string   Relay 配置文件
  -h, --help            help for validate


## admin create

创建管理员

Usage:
  webterm-relay admin create [flags]

Flags:
  -c, --config string          Relay 配置文件
  -h, --help                   help for create
      --password-file string   密码文件
      --role string            角色 (default "admin")
  -u, --username string        管理员用户名
