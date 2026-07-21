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
- mux 通道打开/替换、writer failure 等结构化事件（统一走 `logger.Event`，享受 5s 限流，reason 为稳定错误分类枚举，channelId 经 SafeID，原始错误文本不进日志）；relay 连接/重连计数；screen encode failure / snapshot fallback 计数。mux 物理流量统计已删除（见「不包含的内容」）。
- `app` 生命周期：FileSink 落盘、`runID`/`BuildInfo`、`DiagnosticsState`、`Shutdown` 幂等关闭。

**Android（android-client）**
- `NetworkTrafficStats`：按「归一化 baseUrl + deviceId」隔离累计、重连续累计；`serverOfKey/deviceOfKey`。
- 诊断日志导出/限流：`DiagnosticRateLimiter` 已接入 `Diagnostics` 门面（`log()` 写 sink 前 tryPass，discriminator 只从白名单字段构造，过窗后下一条附 suppressedCount；`errorUnthrottled` 等不限流入口）；日志容量为运行时强约束（启动 trim + 每 60s 周期 trim + 导出前强制 trim，≤4 文件/4 MiB + 单条日志误差）；XLog 1 MiB×3；`DiagnosticLogExporter` ZIP 原子导出（毫秒+随机后缀、tmp+rename、失败清理、历史保留 5 个，含 `network-traffic-summary.txt` + manifest/metrics/state JSON），导出默认脱敏（事件写 `deviceHash`/`channelHash`，导出包写 `serverHash`/`deviceHash`，每包独立 salt）。
- `TerminalResumeMetrics.snapshot()` + `android-metrics.json` resume 段。
- 连接/恢复行为修复（单独提交 `9f245683`）：resync 防风暴、resize 去重、`SessionIds.local` 归一化、重连守卫。**属行为变更，合并前需真机验收**（详见「行为变化与验收」）。
- 生命周期：设备彻底移除时 `unregisterConnection` 清理累计器。

## 不包含的内容 / 明确放弃

- 🚫 **未恢复** `internal/control/server.go` 及旧本地 HTTP Control Server（目录不存在）。
- 🚫 **无** `/control/traffic`、`/control/diagnostics`、`/control/sessions` 路由引用；**未**重新开放本机 HTTP 控制端口。
- diagnostics 版「…available at `/control/traffic`」文案被拒绝，保留 main 的「not exposed through the Agent local IPC」。
- 🚫 **mux 物理流量统计已删除**（`mux.Session.TrafficSnapshot`/`PhysicalTraffic`、`PhysicalWriter.TxSnapshot`）：属死代码——mux.Session 按连接临时创建且无注册表，Relay 服务端指标出口与它不在同一进程，没有低成本暴露路径。
- Go 部分计数器（Mailbox/Input/Resync/ProjectionSkippedNoClient/四个耗时桶）未接业务埋点（避改 Windows 状态机），结构已就位；快照中带 `"instrumented": false` 标记，CLI 人类可读输出显示 not instrumented，避免恒 0 被误读为真实观测。

## Local IPC / CLI 适配

- 复用既有 `localipc.Envelope` 与 `Dial`/超时机制，未自建连接通道；新增 `TypeDiagnostics` 命令与请求/响应类型。
- CLI `webterm diagnostics summary`（短请求）/ `export`（30s）；输出目录支持 `~` 与 Windows 路径；Agent 未运行给出明确提示。
- Unix 走 Unix socket，Windows 走 Named Pipe；导出失败/panic 均回错误且 recover，不影响 Agent 主循环。
- `DiagnosticsSummary`/`DiagnosticsState` 默认脱敏：session id/termTitle → HashID，cwd → cwdBaseName+cwdHash，ipcEndpoint 只保留 unix/npipe 类型；CLI `summary|export` 新增 `--include-paths` 恢复完整值（CLI 文档已重新生成）。
- 导出 ZIP 原子化：文件名毫秒+随机后缀，先写 .tmp 再 rename，失败删临时文件，历史最多保留 5 个。

## 流量统计口径

- **Android（按设备）**：UID 流量（-1→supported=false/字节 0）+ WebSocket 收发帧/字节（文本 UTF-8、二进制 payload）按「归一化 baseUrl + deviceId」累计；重连续累计，设备彻底移除才清理；不逐帧写日志、不阻塞 WS 回调。
- **Go（按会话/连接）**：每会话 `ScreenWireSnapshot`（Snapshot/Patch/History/Other 字节 + 帧数）+ PTY 输出（main 已有）；relay 连接/重连计数；mux 只有通道/写失败计数与结构化事件，不再有物理流量字节统计。旁路 atomic 累计，统计失败不影响发送。

## 安全与脱敏

- 日志/诊断输出不记录终端输入正文、Cookie、Token、Relay Secret、SMTP 密码、文件内容。
- config 经 `Redacted()`（Relay Secret→`********`）；ID 经 `SafeID/HashID`；错误仅记分类枚举（Relay 错误为 `RelayErrorKind`：dial_failed/tls_failed/auth_rejected/protocol_failed/connection_closed/timeout/unknown；原始 err.Error() 不进 agent.jsonl/events.jsonl/state.json/summary）；会话项排除 `RecentInputLines/LastCommand`；异常仅报类型。
- 日志目录 0700 / 文件 0600 / 导出 ZIP 0600。限流器状态表容量有界（4096 + 回收）。

## 测试结果

- Go：`go test ./...`（30 包 ok）、`go vet ./...`、`go test -race ./...`（30 包 ok）、`go generate` 后 `docs/cli` 无 diff、`GOOS=windows go build ./...` 及三个 cmd 均 OK。
- Android：`testDebugUnitTest`（全模块）+ `:terminal-model:test` + `lintDebug` + `assembleDebug` + `assembleDiag` 均 BUILD SUCCESSFUL。
- CI（`.github/workflows/verify.yml`）：android job 已含 `assembleDiag`；windows-runtime 先 `go build ./cmd/webterm ./cmd/webterm-agent` 预热构建缓存，再把 localipc/pty/session 三个测试包拆成独立 `go test -v` 步骤。此前 Windows CI 失败经静态分析最大嫌疑是 main 上已有的 pty PowerShell hook 测试 flaky（该测试运行时现场 go build 且 deadline 90s），拆步骤+预热用于定位与规避，需待下次 CI 运行确认。
- 详见 `docs/reports/diagnostics-after-win10-acceptance.md`。

## 行为变化与验收

- **Android 状态机修复（提交 `9f245683`，单独列出）**：resync 防风暴（恢复期间重复 mailbox overflow 只累计 suppressedOverflowCount）、resize 去重（`requestResize` 返回 boolean）、`SessionIds.local` sessionId 归一化、DeviceConnection 重连守卫。这些是真实行为变更，已用 main 既有 Mailbox/Resize/Resync/Recovery 测试 + 增强的 diagnostics 测试覆盖，但**合并前需真机验收**。
- 其余改动均为诊断/日志/导出路径，初始化或导出失败均降级，不影响 Relay/PTY/Session 主流程。

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
- 各能力按提交拆分（10 个提交），可单独 revert 对应提交（如行为修复提交 `9f245683`）。
- 诊断能力为非关键路径：初始化/导出失败均降级，不影响 Relay/PTY/Session 主流程。

---

> 注：本 Draft PR 由迁移流程自动准备，**未标记 Ready、未合并**。最终 Ready 与合并由人工在 CI 通过后决定。
