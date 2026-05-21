# WebTerm

个人自用的 Node WebTerm。服务端通过 node-pty 持有 PTY/ConPTY 会话，并用 headless xterm 维护权威终端状态；浏览器只是可以断开重连的视图。

## 运行

Windows 当前默认使用 `pwsh.exe`，需要先安装 PowerShell 7 并确保它在 `PATH` 中。

```powershell
$env:WEBTERM_PASSWORD = "your-password"
$env:WEBTERM_ADDR = "127.0.0.1:8080"
npm start
```

首次运行前安装依赖：

```powershell
npm install
```

Tailscale 使用时建议绑定 Tailscale IP，而不是直接监听全部网卡：

```powershell
$env:WEBTERM_ADDR = "100.x.y.z:8080"
```

## 功能

- 单用户密码登录，密码来自 `WEBTERM_PASSWORD`。
- `/` 管理终端 session。
- `/terminal/{id}` 打开单个终端页。
- 同一个 session 可被多个页面同时打开，所有页面都可输入。
- 可见页面的 resize 生效。
- 运行中的 session 不会自动关闭。
- shell 进程退出或用户点击关闭时，session 被移除。
- 移动端快捷键栏。
- 深色/浅色主题切换。
- 断线重连时通过 seq replay 或 serialized state 恢复终端状态。

## 限制

- 不提供退出登录按钮。
- PTY 进程不跨服务重启保活。
- 不内置 HTTPS；公网或 HTTPS 场景建议放到 Tailscale Serve、Caddy 或 Nginx 后面。
