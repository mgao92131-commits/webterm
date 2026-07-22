# macOS Agent 安装包

这两个脚本用于在 macOS 上构建、安装和管理 WebTerm Go Agent。Agent 支持两种互相独立的运行模式：

- Direct：Android 直连 Mac Agent。
- Relay：Android 经 Relay 中转连接 Mac Agent。

安装包不依赖当前终端目录、Shell 配置、Go 开发目录或 PATH。运行参数只有一个可选项：模式。

## 安装并启动

在仓库根目录执行：

~~~sh
chmod +x scripts/macos/*.sh
scripts/macos/install-and-run.sh
~~~

不传参数时会交互选择：

~~~text
请选择 Agent 运行模式：
  1) Direct 直连
  2) Relay 中转
~~~

也可以直接指定：

~~~sh
scripts/macos/install-and-run.sh direct
scripts/macos/install-and-run.sh relay
~~~

脚本依次执行：

1. 运行 go test ./...。
2. 编译 webterm-agent 和 webterm，并注入版本、提交和构建时间。
3. 创建或校验对应模式的配置。
4. 安装二进制和 LaunchAgent。
5. 立即启动 Agent。

首次没有对应配置时，脚本会创建模板并使用 TextEdit 打开。请填写配置、保存并关闭 TextEdit，脚本随后会校验配置；校验失败时会再次打开配置文件。

Direct 至少需要填写：

~~~json
{
  "direct": {
    "username": "admin",
    "password": "你的密码"
  }
}
~~~

Relay 至少需要填写：

~~~json
{
  "relay": {
    "url": "中转服务器地址",
    "secret": "设备密钥"
  }
}
~~~

密码和 Secret 保存在模式配置文件中，不会作为脚本参数，也不会写入 LaunchAgent 的启动参数。

## 固定位置

~~~text
~/Library/Application Support/WebTerm/bin/webterm-agent
~/Library/Application Support/WebTerm/bin/webterm
~/Library/LaunchAgents/com.webterm.agent.plist
~/Library/Logs/WebTerm/agent.out.log
~/Library/Logs/WebTerm/agent.err.log
~~~

LaunchAgent 使用完整绝对路径，并直接传入：

~~~text
run --mode direct
~~~

或：

~~~text
run --mode relay
~~~

它设置了 RunAtLoad 和 KeepAlive，因此登录后会自动启动，异常退出后会自动重启。

## 切换模式

直接重新执行目标模式即可：

~~~sh
scripts/macos/install-and-run.sh relay
scripts/macos/install-and-run.sh direct
~~~

脚本会重新测试、编译和安装，停止旧 LaunchAgent，更新启动模式并立即启动新 Agent。

## 使用 CLI 和诊断

~~~sh
"$HOME/Library/Application Support/WebTerm/bin/webterm" devices
"$HOME/Library/Application Support/WebTerm/bin/webterm" diagnostics summary
~~~

Agent 与 CLI 安装在同一目录，Agent 内部查找相邻 CLI 时不依赖 PATH。

## 卸载

~~~sh
scripts/macos/uninstall.sh
~~~

卸载脚本会停止并删除 LaunchAgent，以及删除已安装的 webterm-agent 和 webterm。默认保留模式配置和日志。

## 验证范围

脚本支持使用 bash -n 做语法检查；当前非 macOS 环境无法实际验证 macOS 的编译、TextEdit、plutil 和 launchctl 启动行为。
