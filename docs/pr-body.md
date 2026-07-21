# PR 标题

feat(diagnostics): adapt network traffic investigation to Local IPC architecture

# PR 正文

## 背景

`feat/win10-support` 已合并进 `main`（`200a4fc5`），带来 Windows 10 支持（ConPTY、Named Pipe Local IPC、标准 CLI、Session Hook、退出排空），并**删除了旧的本地 HTTP Control Server**（`go-core/internal/control/`）。

原 `diagnostics/network-traffic-investigation` 分支在合并基点 `75ebaa41` 之后独立演进，部分能力依赖已删除的旧 Control Server。本 PR 将该分支的诊断能力**逐项手工迁移/重写**到新架构（非 merge），保留 Windows 分支的运行时行为。

## 迁移范围

**Go Agent（go-core）**
- `internal/logs`：结构化事件日志（内存 Ring + `agent.jsonl` 1 MiB×3 滚动 + 5s 限流 + `SafeID/HashID` 脱敏）。
- `internal/diagnostics`：进程级指标 `diagnostics.Default` + 诊断包导出（manifest/events/metrics/state/summary）。
- `internal/localipc` + `internal/webtermcmd`：`webterm diagnostics summary|export` 经 Local IPC 查询/导出。
- mux 物理连接收发帧/字节、relay 连接/重连计数、screen encode failure / snapshot fallback 计数。
- `app` 生命周期：FileSink 落盘、`runID`/`BuildInfo`、`DiagnosticsState`、`Shutdown` 幂等关闭。

**Android（android-client）**
- `NetworkTrafficStats`：按「归一化 baseUrl + deviceId」隔离累计、重连续累计；`serverOfKey/deviceOfKey`。
- 诊断日志导出/限流：`DiagnosticRateLimiter`、`DiagnosticLogFiles.planDeletions`（≤4 文件/4 MiB）、XLog 1 MiB×3、`DiagnosticLogExporter` ZIP（含 `network-traffic-summary.txt` + manifest/metrics/state JSON）。
- `TerminalResumeMetrics.snapshot()` + `android-metrics.json` resume 段。
- 连接/恢复行为修复（单独提交，详见提交 8）：resync 防风暴、resize 去重、`SessionIds.local` 归一化、重连守卫。
- 生命周期：设备彻底移除时 `unregisterConnection` 清理累计器。

## 不包含的内容 / 明确放弃

- 🚫 **未恢复** `internal/control/server.go` 及旧本地 HTTP Control Server（目录不存在）。
- 🚫 **无** `/control/traffic`、`/control/diagnostics`、`/control/sessions` 路由引用；**未**重新开放本机 HTTP 控制端口。
- diagnostics 版「…available at `/control/traffic`」文案被拒绝，保留 main 的「not exposed through the Agent local IPC」。
- `Diagnostics` 门面限流接线**未接入**：源分支 worktree 上有未提交的重设计（`check`→`Decision` + 门面主入口限流），与迁移来源（origin 已提交的 tryPass API）不同，按「迁移来源=origin 已提交」约定未迁移。已迁移的 `DiagnosticRateLimiter`（含容量边界）可独立使用；建议提交该重设计后再适配接线。
- Go 部分计数器（Mailbox/Input/Resync/ProjectionSkipped）未接业务埋点（避改 Windows 状态机），结构已就位。

## Local IPC / CLI 适配

- 复用既有 `localipc.Envelope` 与 `Dial`/超时机制，未自建连接通道；新增 `TypeDiagnostics` 命令与请求/响应类型。
- CLI `webterm diagnostics summary`（短请求）/ `export`（30s）；输出目录支持 `~` 与 Windows 路径；Agent 未运行给出明确提示。
- Unix 走 Unix socket，Windows 走 Named Pipe；导出失败/panic 均回错误且 recover，不影响 Agent 主循环。

## 流量统计口径

- **Android（按设备）**：UID 流量（-1→supported=false/字节 0）+ WebSocket 收发帧/字节（文本 UTF-8、二进制 payload）按「归一化 baseUrl + deviceId」累计；重连续累计，设备彻底移除才清理；不逐帧写日志、不阻塞 WS 回调。
- **Go（按会话/连接）**：每会话 `ScreenWireSnapshot`（Snapshot/Patch/History/Other 字节 + 帧数）+ PTY 输出（main 已有）；mux 物理收发帧/字节（tx 仅 `conn.Write` 成功后计）；relay 连接/重连计数。旁路 atomic 累计，统计失败不影响发送。

## 安全与脱敏

- 日志/诊断输出不记录终端输入正文、Cookie、Token、Relay Secret、SMTP 密码、文件内容。
- config 经 `Redacted()`（Relay Secret→`********`）；ID 经 `SafeID/HashID`；错误仅记类型/枚举；会话项排除 `RecentInputLines/LastCommand`；异常仅报类型。
- 日志目录 0700 / 文件 0600 / 导出 ZIP 0600。限流器状态表容量有界（4096 + 回收）。

## 测试结果

- Go：`go test ./...`（30 包 ok）、`go vet ./...`、`go test -race ./...`（30 包 ok）、`go generate` 后 `docs/cli` 无 diff、`GOOS=windows go build ./...` 及三个 cmd 均 OK。
- Android：`testDebugUnitTest`（全模块）+ `:terminal-model:test` + `lintDebug` + `assembleDebug` + `assembleDiag` 均 BUILD SUCCESSFUL。
- 详见 `docs/reports/diagnostics-after-win10-acceptance.md`。

## Windows 10 手工验收清单

- [ ] `webterm-agent` 在 Windows 启动，Named Pipe endpoint 正常监听（`npipe://./pipe/...`）。
- [ ] ConPTY 终端会话正常打开/输入/输出，退出排空（END_MARKER/Exit 顺序）正确。
- [ ] `webterm diagnostics summary --socket <endpoint>` 返回 Agent 状态/指标/会话/脱敏配置。
- [ ] `webterm diagnostics export --output <dir>` 在 Windows 路径（含 `~`）生成诊断 ZIP。
- [ ] Agent 未运行时上述命令给出明确错误提示。
- [ ] 诊断 ZIP 内 manifest/metrics/state/summary/events 完整，无 Secret/输入正文。
- [ ] PowerShell shell hook 正常（session-update 上报）。

## 回滚方式

- 本 PR 以独立 merge commit 合入时可整体回滚：`git revert -m 1 <merge-commit>`。
- 各能力按提交拆分（10 个提交），可单独 revert 对应提交（如行为修复提交 8）。
- 诊断能力为非关键路径：初始化/导出失败均降级，不影响 Relay/PTY/Session 主流程。

---

> 注：本 Draft PR 由迁移流程自动准备，**未标记 Ready、未合并**。最终 Ready 与合并由人工在 CI 通过后决定。
