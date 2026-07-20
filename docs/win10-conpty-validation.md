# Windows 10 ConPTY 实机验证 Runbook

本文件是 `feat/win10-support` 分支合并前的 **Windows 10 22H2 / Build 19045** 实机验证清单。
自动化 CI（`windows-latest`）覆盖了 ConPTY 启停、输入输出、Shell Hook IPC 上报、交叉编译与
race；下列条目需要在真实目标系统上人工确认，重点是交互输入、stdout 不泄漏与多启动环境一致性。

> 现有 `STARTF_USESTDHANDLES`（空 std 句柄 + 强制走伪控制台）实现**本次不重写**。
> 其正确性已由 CI 集成测试经验性覆盖：单测断言中文输出经 ConPTY 管道返回，
> `TestPowerShellSessionHookReportsPromptOverIPC` 键入 `echo hello` 并读回 `last_input`
> （证明交互式 stdin 也走 ConPTY）。本 runbook 在目标机上复核这些行为。

## 0. 准备

```powershell
# 在目标机上构建 Agent 与 CLI
cd go-core
go build -o ..\out\webterm-agent.exe .\cmd\webterm-agent
go build -o ..\out\webterm.exe .\cmd\webterm
```

- 确认 Windows 版本：`winver` → 22H2 (19045)。
- 分别在 **PowerShell 5.1**（`powershell.exe`）与 **PowerShell 7**（`pwsh.exe`，若安装）下验证。

## 1. 验证清单

| # | 项目 | 操作 | 通过标准 |
|---|------|------|----------|
| 1 | PS 5.1 启动 | 用 Agent 起默认终端（`powershell.exe`） | 出现 `PS C:\...>` 提示符，无 `0xC0000142` |
| 2 | PS 7 启动 | 配置 shell 为 `pwsh.exe` 起终端 | 提示符正常出现 |
| 3 | 交互输入 | 键入 `echo hello` 回车 | 输出 `hello`；Agent 侧收到 `last_input="echo hello"` |
| 4 | 中文输入/输出 | 键入 `Write-Output '中文'`；粘贴中文 | 输出中文不乱码、不丢字节 |
| 5 | stdout/stderr 不泄漏 | 运行产生大量输出的命令 | 输出全部进入 ConPTY；**Agent 控制台/日志无终端输出泄漏** |
| 6 | Resize | 客户端调整窗口大小 | 提示符按新尺寸重排，`Resize` 生效 |
| 7 | Ctrl+C | 运行 `Start-Sleep 30` 后按 Ctrl+C | 命令被中断，回到提示符 |
| 8 | Hook 上报 | 正常提示符 / `cd` 后提示符 | 通过 Named Pipe 上报正确的 `cwd` 与最近命令 |
| 9 | 退出无残留 | 自然 `exit` 与强制关闭各一次 | 任务管理器无残留 `powershell`/`conhost` 进程；句柄释放 |
| 10 | 多启动环境一致 | 分别从 **Windows 服务**、**命令行**、**IDE** 启动 Agent | 三种环境下 1–9 行为一致 |

## 2. 关键回归点（本次改动相关）

- **退出排空（真 EOF）**：运行输出大量数据后立刻 `exit` 的命令（如 `1..5000 | % { "line-$_" }; exit`），
  确认客户端最终画面包含最后几行（`line-5000`），`Exit` 不早于最终帧。
  对应自动化测试：`TestRuntimeDrainAndCloseCapturesDelayedTailOutput`、`TestConPTYBeginDrainProducesEOFAndIsIdempotent`。
- **可靠输入确认**：在终端关闭瞬间发送输入，Android 端应收到 `REJECTED`/`UNCERTAIN` 的 `InputAck`，
  而不是等待约 60 秒超时。对应自动化测试：`TestRuntimeDrainSettlesQueuedReliableInput`、
  `TestInputWriterShutdownSettlesQueuedJobsOnClose`。
- **Prompt 不阻塞**：停止 Agent（Named Pipe 不可用）后在已开的 shell 里回车，
  提示符应**立即**返回（无可感知停顿），且不每次创建 `webterm.exe` 进程（退避生效）；
  重启 Agent 后无需重开 shell 即恢复上报。对应自动化测试：`TestHookModeFailureRecordsBackoffAndStaysSilent`、
  `TestHookModeSuccessClearsBackoff`、`TestShellHookIsNonBlockingWithBackoff`、`TestPowerShellHookIsNonBlockingWithBackoff`。

## 3. 结果记录

| # | PS 5.1 | PS 7 | 备注 |
|---|--------|------|------|
| 1 | ☐ | ☐ | |
| 2 | ☐ | ☐ | |
| 3 | ☐ | ☐ | |
| 4 | ☐ | ☐ | |
| 5 | ☐ | ☐ | |
| 6 | ☐ | ☐ | |
| 7 | ☐ | ☐ | |
| 8 | ☐ | ☐ | |
| 9 | ☐ | ☐ | |
| 10 | ☐ | ☐ | |

验证人 / 日期：____________________
