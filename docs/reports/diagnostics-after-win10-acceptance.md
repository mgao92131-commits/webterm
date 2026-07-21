# Diagnostics 迁移验收报告（Windows 架构合并之后）— 合并后最终版

> **状态：迁移 PR #2 已合入 `main`，并已完成合并后最终加固（`fix/diagnostics-final-hardening`）。**
> 本文为合并后的最终验收报告：§1–§10 为迁移验收，§11 为合并后加固整改记录（均已提交，见 §11.6）。
> 仍待人工完成的项目见 §12「Manual verification pending」。

- **集成分支**：`integrate/network-diagnostics-after-win10`（基于 `main` = `200a4fc5`，feat/win10-support 合并后），已合入 `main`
- **迁移来源（只读）**：`origin/diagnostics/network-traffic-investigation`，合并基点 `75ebaa41`
- **方式**：逐任务手工迁移/重写，**未** merge 原 diagnostics 分支，**未**恢复旧 HTTP Control Server。

---

## 1. 迁移的功能

### Go Agent（go-core）
- **诊断基础**（任务 1）：`internal/logs`（结构化事件日志：内存 Ring + `agent.jsonl` 1 MiB×3 滚动 + 5s 限流 + `SafeID/HashID` 脱敏）；`internal/diagnostics`（进程级指标 `diagnostics.Default` + `Export` 诊断包：manifest/events/metrics/state/summary，4 MiB/1000 条预算，跳过崩溃半行，无日志文件亦容错）。
- **Local IPC + CLI**（任务 4）：`webterm diagnostics summary|export` 经 Local IPC（Unix socket / Windows named pipe）查询/导出；`localipc` 增 `TypeDiagnostics` 命令与请求/响应类型；`handleDiagnostics` 含 panic recover。
- **Mux/Relay/Session 指标**（任务 5）：mux channel open/replace、writer failure、writer queue rejected 计数；mux 日志统一为 `logger.Event` 结构化事件（享受 5s 限流，reason 为错误分类枚举，channelId 经 SafeID）；relay connect/reconnect/disconnect/connect-failure；screen encode failure / snapshot fallback。注：mux 物理连接收发帧/字节统计（`TrafficSnapshot`/`PhysicalTraffic`/`TxSnapshot`）在整改轮作为死代码删除（见 §11）。
- **生命周期整合**（任务 7）：`app` 启动挂 FileSink（与导出同目录，失败降级仅内存）、`runID`/`BuildInfo`（`-ldflags` 注入）、`DiagnosticsState`（relay/mux/terminals 结构化态）、`Shutdown` 幂等关闭句柄。
- **容量边界**（任务 8）：Go `logs.RateLimiter` 状态表超 4096 触发回收（先删过期窗口，仍超低水位则按时间删最旧）。

### Android（android-client）
- **网络流量统计**（任务 2）：`NetworkTrafficStats` 累计器键升级为「归一化 baseUrl + deviceId」，按服务器/设备隔离、重连续累计；`serverOfKey/deviceOfKey`。WebSocket 收发帧/字节、UID 流量（-1→supported=false）main 已具备。
- **诊断日志导出与限流**（任务 3）：`DiagnosticRateLimiter`（线程安全，状态表容量有界；整改轮已接入 `Diagnostics` 门面：`log()` 写 sink 前 tryPass、discriminator 只从白名单字段构造、过窗后下一条附 suppressedCount，另有 `errorUnthrottled` 等不限流入口）；XLog 1 MiB×3 滚动（固定全局文件 `webterm.log` + `.bak.1~3`，滚动时删除最旧备份，≤4 文件/4 MiB 为源头硬上限）；启动/每 60s/导出前 trim 仅兜底清理旧版本遗留文件；`DiagnosticLogExporter` ZIP 原子导出（日志 + `network-traffic-summary.txt` + manifest/android-metrics/android-state JSON），导出默认脱敏（事件写 `deviceHash`/`channelHash`，导出包写 `serverHash`/`deviceHash`，每包独立 salt）。
- **Runtime/Resume 指标**（任务 6）：`TerminalResumeMetrics.snapshot()` + 17 字段 `Snapshot`；`android-metrics.json` 补 resume 段。
- **连接/恢复行为修复**（任务 6b，用户决定单独迁移）：resync 防风暴、resize 去重（`requestResize` 返回 boolean）、`SessionIds.local` sessionId 归一化、DeviceConnection 重连守卫。
- **生命周期**（任务 7）：设备从配置彻底移除时 `unregisterConnection` 清理累计器；UID tracker 注册/注销与诊断导出入口 main 已具备（验证后无需改）。

## 2. 放弃 / 未恢复的旧实现
- 🚫 `go-core/internal/control/server.go` 及整个旧本地 HTTP Control Server **未恢复**（目录不存在）。
- 🚫 HTTP 路由 `/control/traffic`、`/control/diagnostics`、`/control/sessions` **无任何引用**。
- 🚫 未重新开放本机 HTTP 控制端口；诊断查询/导出一律走 Local IPC。
- diagnostics 版 `DiagnosticLogExporter` 中「…available at `/control/traffic`」文案被**拒绝**，保留 main 的「not exposed through the Agent local IPC」。
- diagnostics 版 `app` 使用固定 `~/.webterm/logs`，本迁移改为**按 endpoint 隔离的 runtime 目录**（多 Agent 不串日志）。
- 说明：`internal/relaycontrol`/`relayapp`/`relaymetrics` 的 HTTP 端点是 **Relay 服务端**（独立后端组件）的合法 API，非 Agent 本地 Control Server，不在禁止之列。

## 3. Local IPC 设计
- 复用既有 `localipc.Envelope`（version/kind/type/request_id/payload/error）与 `Dial`/超时机制，未自建连接通道。
- 新增 `TypeDiagnostics="diagnostics"`，`DiagnosticsRequest{action: summary|export, export_path}`、`DiagnosticsResponse{action, summary, export_path}`。
- `Application` 接口扩展 `DiagnosticsSummary()`/`ExportDiagnostics()`，由 `app.App` 实现。
- CLI `webterm diagnostics summary` 用短请求超时；`export` 放宽到 30s；输出目录支持 `~` 与 Windows 路径；Agent 未运行给出明确提示。
- 导出失败/panic 均回错误且 recover，不影响 Agent 主循环。

## 4. 流量统计口径
- **Android（按设备）**：UID 流量（TrafficStats，-1→supported=false/字节 0）+ WebSocket 收发帧/字节（文本按 UTF-8 字节、二进制按 payload）按「归一化 baseUrl + deviceId」累计；重连/Transport 重建**续累计**，设备彻底移除才清理；不逐帧写日志、不阻塞 WS 回调。
- **Go（按会话/连接）**：每会话 `ScreenWireSnapshot`（Snapshot/Patch/HistoryPage/HistoryTrim/Other 字节 + 帧数）+ PTY 输出事件/字节（main 已有）；relay 连接/重连计数。分类字节为旁路 atomic 累计，与实际发送一致，统计失败不影响发送。mux 物理流量统计已删除（整改轮，死代码，见 §11）。

## 5. 容量边界（缓存/指标均有界）
- 日志文件：单文件 1 MiB × 3 备份；导出 events 4 MiB / 1000 条预算。
- Android 诊断日志：固定全局文件（`webterm.log` + `.bak.1~3`）由 XLog 在 1 MiB 滚动时删除最旧备份，≤4 文件 / ≤4 MiB（+单条日志误差）为源头硬上限；启动/每 60s 周期/导出前 trim 仅兜底清理旧版本遗留的按启动命名文件。
- 限流器状态表：Go `logs.RateLimiter` 与 Android `DiagnosticRateLimiter` 均 4096 上限 + 回收（含边界测试）。
- 指标均为固定字段 atomic 计数器 / 有界直方图，无新增无界 goroutine/channel/map，无逐帧落盘。

## 6. 安全与脱敏
- 日志/诊断输出不记录：终端输入正文、Cookie、Token、Relay Secret、SMTP 密码、文件内容。
- config 经 `Redacted()`（Relay Secret→`********`）用于 `config show`；诊断摘要/导出改用 `Config.DiagnosticsView(includePaths)`（Relay URL→scheme、DeviceName/Shell Command/CWD→短哈希、IPC 只保留类型、Control.Addr 不输出、Secret 任何模式都脱敏，见 §11.6）；ID 类经 `SafeID/HashID`；错误仅记分类枚举（Relay 错误为 `RelayErrorKind`：dial_failed/tls_failed/auth_rejected/protocol_failed/connection_closed/timeout/unknown；原始错误文本不进 agent.jsonl/events.jsonl/state.json/summary；`SafeEnum` 已无调用方并删除）。
- `DiagnosticsSummary`/`DiagnosticsState` 默认脱敏：session id/termTitle → HashID，cwd → 仅 `cwdHash`（合并后加固移除 `cwdBaseName`，见 §11.6），ipcEndpoint 只保留 unix/npipe 类型；自由文本日志 Message 与路径类 Field 默认折叠（`logs.SanitizeEntries`）；CLI `--include-paths` 恢复完整值。Android 导出包默认脱敏：`DiagnosticIdHasher`（SHA-256(salt+value) 截断 12 hex），事件写 `deviceHash`/`channelHash`（进程级 salt），导出 JSON 写 `serverHash`/`deviceHash`（每包独立 salt），不含原始 URL/设备/通道标识。
- 会话诊断项排除 `RecentInputLines`/`LastCommand`；异常处理仅上报异常类型不含正文。
- 日志目录 0700、文件 0600；导出 ZIP 0600。

## 7. 全部测试结果
**Go（go-core）**
- `go test ./...`：30 包 ok，无 FAIL。
- `go vet ./...`：OK。
- `go test -race ./...`：30 包 ok。
- `go generate ./cmd/...` 后 `git diff --exit-code -- docs/cli`：CLEAN（新增 `webterm-diagnostics.md`）。
- `GOOS=windows GOARCH=amd64 go build ./...` 及 `./cmd/webterm`、`./cmd/webterm-agent`、`./cmd/webterm-relay`：OK。

**Android（android-client）**
- `testDebugUnitTest`（全模块）+ `:terminal-model:test`：BUILD SUCCESSFUL。
- `lintDebug`：BUILD SUCCESSFUL（既有债务由 `lint-baseline.xml` 过滤，无新增问题）。
- `assembleDebug` + `assembleDiag`（diagnostics 变体）：BUILD SUCCESSFUL。

**任务名修正记录**：`:terminal-model`、`:core-contract` 为 `java-library`，测试任务是 `test`；app 无 `testDiagnosticsDebugUnitTest`（diagnostics 是并入 debug/diag build type 的源码目录），对应为 `:app:testDebugUnitTest` / `lintDebug` / `assembleDiag`。

## 8. 未完成项
- **Go 部分 diagnostics.Default 计数器尚未接到业务埋点**：Mailbox/Input/Resync/ProjectionSkippedNoClient 与四个耗时桶位于 Windows 敏感的 terminalsession runtime actor 循环，为避免改动状态机，本迁移仅接入 mux/relay/screen-encode 计数。合并后加固已把这些未埋点占位字段改为轻量 `capabilities` 声明（见 §11.6），待后续安全接入真实埋点。
- 旧 `internal/control` 的 `connection_test_api` 等测试 API 未迁移（随旧 Control Server 一并废弃）。

> 注：原列于此的「`Diagnostics` 门面限流接线未接入」已在整改轮完成（`DiagnosticRateLimiter` 接入 `Diagnostics` 门面，见 §11）。

## 9. 已知风险
- **限流器回收的精度权衡**：状态表超限回收时，被提前删除的窗口会丢失「上一窗口抑制数」的一次性汇总；诊断场景下以内存有界为先，可接受。
- **lint 基线过期**：`lint-baseline.xml` 有 26 条已被 main（Windows 合并）修复的过期条目；非本迁移引入，建议后续刷新基线。
- **行为修复（任务 6b，提交 `9f245683`）改动了状态机**（resync 抑制 / resize 去重 / sessionId 归一 / 重连守卫），已用 main 既有 Mailbox/Resize/Resync/Recovery 测试 + 增强的 diagnostics 测试覆盖；属行为变更，**Android 真机验收仍待人工完成（见 §12 Manual verification pending）**。
- **Go 诊断摘要中未埋点分组**：Mux/Relay 计数已接入，但 Mailbox/Input/Resync/ProjectionSkippedNoClient/耗时桶未接（见 §8）；合并后加固已把未埋点占位字段改为 `capabilities` 声明、CLI 显示 not instrumented，不再以恒 0 冒充真实观测（见 §11.6）。
- **Windows CI 失败（已根治）**：根因不是测试 flaky。远程插桩证明 PowerShell hook 以 `[System.Diagnostics.Process]::Start` 启动 `webterm internal session-update` 后立即返回 prompt 时，子进程在 ConPTY 会话的初始化阶段静默死亡（无投递、无退避失败记录），仅第一个上报存活；父进程等待子进程退出时则 100% 正常。修复为 hook 模板中有界等待 `WaitForExit(2000)`（正常 ~25ms），见 §11。

## 10. 提交历史（相对 main）
1. `docs: plan diagnostics migration after Windows architecture merge`
2. `feat(diagnostics): add safe logging and export foundations`
3. `feat(android): add persistent per-device traffic diagnostics`
4. `feat(android): harden diagnostic logging and export`
5. `feat(cli): expose agent diagnostics through local IPC`
6. `feat(go): add bounded mux and terminal traffic metrics`
7. `feat(android): add bounded runtime and resume diagnostics`
8. `fix(android): adopt connection and recovery robustness fixes`
9. `feat(diagnostics): integrate metrics with application lifecycles`
10. `test: complete diagnostics migration acceptance`（本提交）

---

## 11. 本轮整改记录（验收后）

在 §1–§10 验收完成后又进行了代码整改，本节为整改摘要与验证结果；上文各节已同步更新为整改后的事实。
整改以独立提交落在加固分支 `fix/diagnostics-final-hardening`（见 §11.6），不再停留于工作区。

### 11.1 Go（go-core）修改摘要
- mux 日志全部从自由文本 `logger.Add` 改为 `logger.Event`（享受 5s 限流），事件名稳定（`mux_read_failed`/`mux_channel_replaced`/`mux_writer_failed` 等），reason 为错误分类枚举，channelId 经 SafeID，原始错误文本不再进日志；summary 事件名统计因此真正生效。
- Relay 错误改为分类枚举 `RelayErrorKind`（dial_failed/tls_failed/auth_rejected/protocol_failed/connection_closed/timeout/unknown），`SetRelayConnected` 只存 kind；原始 err.Error() 不再进入 agent.jsonl/events.jsonl/state.json/summary；`logs.SafeEnum` 已无调用方并删除。
- `App.Shutdown()` 先 `logger.SetSink(nil)` 再 `sink.ClosePermanent()`；FileSink 永久关闭后 Write 返回 ErrSinkClosed 不再重开文件；普通 Close 保留惰性重开（rotate 用）。
- `DiagnosticsSummary`/`DiagnosticsState` 默认脱敏：session id/termTitle → HashID，cwd → cwdBaseName+cwdHash，ipcEndpoint → 只保留 unix/npipe 类型；CLI `webterm diagnostics summary|export` 新增 `--include-paths` 恢复完整值（CLI 文档已重新生成）。
- 导出 ZIP 原子化：文件名毫秒+随机后缀，先写 .tmp 再 rename，失败删临时文件，历史最多保留 5 个。
- 未埋点指标组（mailbox/input/resync/projectionSkippedNoClient/四个耗时桶）在输出中带 `"instrumented": false`，CLI 人类可读输出显示 not instrumented。
- **删除** `mux.Session.TrafficSnapshot`/`PhysicalTraffic` 及 `PhysicalWriter.TxSnapshot`（死代码：mux.Session 按连接临时创建且无注册表，Relay 服务端指标出口与它不在同一进程，没有低成本暴露路径）；mux 物理流量统计功能随之不存在。

### 11.2 Android（android-client）修改摘要
- `DiagnosticRateLimiter` 接入 `Diagnostics` 门面：`log()` 写 sink 前 tryPass，discriminator 只从白名单字段构造，过窗后下一条附 suppressedCount；新增 `errorUnthrottled` 等不限流入口。
- `stopAllDevices()` 逐连接 unregister NetworkTrafficStats；新增 `clearAll()`；重连不清零。
- 日志容量：改用固定全局滚动文件（`webterm.log` + `.bak.1~3`），XLog `FileSizeBackupStrategy2` 在滚动时删除最旧备份，目录恒定 ≤4 文件/约 ≤4 MiB（+单条日志误差）的硬上限；启动/每 60s 周期（daemon 线程）/导出前 trim 退化为兜底，仅清理旧版本遗留的按启动命名文件。周期 trim 执行器幂等（重复初始化先停旧 executor 再重建，不泄漏线程），并提供 `shutdownForTest()` 关闭入口。
- 导出脱敏：新增 `DiagnosticIdHasher`（SHA-256(salt+value) 截断 12 hex）；DeviceConnection 事件字段写 `deviceHash`/`channelHash`（进程级 salt）；exporter 输出 `serverHash`/`deviceHash`（每包独立 salt）；Sanitizer 补 server/deviceId/channelId 键；默认导出包不含原始 URL/设备/通道标识。
- ZIP 原子导出：毫秒+随机后缀、tmp+rename、失败清理、历史保留 5 个；分享提示已脱敏。
- 导出说明文案改为指引 Go Agent 统计用 `webterm diagnostics summary|export`。

### 11.3 CI（.github/workflows/verify.yml）
- windows-runtime：先 `go build ./cmd/webterm ./cmd/webterm-agent`（预热缓存；pty 的 PowerShell hook 测试运行时会现场 build），再把 localipc/pty/session 三个测试包拆成独立 `go test -v` 步骤。
- android job 增加 `assembleDiag`。

### 11.4 Windows hook 根因修复（agenthooks）
- 针对 windows-runtime 持续失败做远程插桩（画面采集 + hook 内部探针 + 子进程退出码），逐层排除后确认：`powershell_hook_integration_windows_test.go` 失败的根因是 hook 模板 spawn `webterm.exe` 后立即返回（无论立即 `Dispose` 还是交 GC 回收），子进程在 ConPTY 会话内初始化阶段静默死亡，第二次及以后的上报全部丢失；main 上同样存在（该 job 随 win10 合并加入 CI 后从未通过）。
- 修复：`webterm-shell-hook.ps1` 模板在 spawn 后有界等待 `WaitForExit(2000)` 再释放（正常 ~25ms，prompt 不会被无限阻塞）；`shell_test.go` 的断言从「禁止 WaitForExit」改为「要求 WaitForExit(2000) 有界等待」。
- 集成测试保留更新时间线日志与超时画面 dump，移除全部临时探针。

### 11.5 验证结果
- Go：`go test ./...` 与 `go test -race ./...` 全绿。
- Android：`testDebugUnitTest` + `assembleDiag` 通过。
- Windows CI：修复前 5/5 稳定失败（main 同样失败），WaitForExit 模式 2/2 通过；最终修复提交后整轮 CI 全绿，并经 rerun 二次确认。

### 11.6 合并后最终加固（fix/diagnostics-final-hardening）

迁移 PR #2 合入 `main` 后，针对最终审查发现的剩余问题做了如下加固，每项独立提交：

- **默认摘要完全脱敏**（`fix(diagnostics): fully redact default summary output`）：新增 `Config.DiagnosticsView(includePaths)`，默认把 Relay URL 折叠为 scheme、DeviceName/Shell Command/CWD 折叠为短哈希、IPC 只保留类型、Control.Addr 不输出，Secret 任何模式下都脱敏；新增 `logs.SanitizeEntries` 默认折叠自由文本 Message 与路径类 Field；移除会话摘要中的 `cwdBaseName`（项目目录名亦敏感）；导出 `events.jsonl` 默认同样脱敏，`--include-paths` 才放行原文。
- **诊断包含会话流量**（`feat(diagnostics): include session traffic in exports`）：导出 ZIP 新增 `session-traffic.json`（PTY 输出事件/字节、screen wire 字节），由 App 层 `DiagnosticsSessionTraffic(includePaths)` 脱敏会话 ID；离线导出写 unavailable，无活跃会话写空数组。
- **摘要重写为强类型快照**（`fix(diagnostics): rebuild summary from typed snapshots`）：`summary.txt` 累计值改来自 `metrics.json`/`state.json`/`session-traffic.json`，未埋点分组显示 `Not instrumented` 而非 0，Events 只用于导出条数/是否截断/最近错误事件类型（明确为窗口观测），不再把限流后的日志条数当成系统总次数。
- **Screen 失败事件结构化**（`fix(diagnostics): structure screen failure events`）：screen 编码/初始同步失败改走 `screen_encode_failed` 结构化事件 + `classifyScreenEncodeError` 稳定枚举，不记录 `err.Error()`；初始 resume 决策改为 `screen_resume_decision` 结构化事件；`screen_encode_failed` 明确属关键失败、不受 5s 限流。
- **Android 日志全局上限**（`fix(android): enforce global diagnostic log bounds`）：改用固定全局滚动文件（`webterm.log` + `.bak.1~3`），XLog 滚动时删最旧备份，恒定 ≤4 文件/约 ≤4 MiB 的源头硬上限；周期 trim 退化为遗留文件兜底，执行器幂等并新增 `shutdownForTest()`；删除死代码 `LaunchLogFileNameGenerator`。
- **未埋点指标能力声明**（`refactor(diagnostics): simplify capability reporting`）：删除未埋点的 Mailbox/Input/Resync/Projection 计数与四个耗时直方图的占位字段，改为轻量 `capabilities` 声明（均为 `false`），未埋点能力不再以恒 0 冒充观测、也不再分配无用对象。
- **文档收尾**（`docs: finalize diagnostics post-merge acceptance`）：删除临时 `docs/pr-body.md`，迁移计划归档至 `docs/archive/` 并标注 Historical，本报告更新为合并后最终版，真机验收如实标记为 pending（§12）。

## 12. Manual verification pending（待人工验证）

以下项目**无法在自动化/Agent 环境完成**，状态如实记录为「待人工验证」，**未伪造完成**。Android 状态机行为变更（任务 6b）与日志容量策略需要在真机上确认。

### 12.1 Android 真机验收（未完成 — 需人工执行）

当前 Agent 环境无法运行 Android 真机测试。请人工在真机上执行以下场景并记录结果（设备型号 / Android 版本 / App 构建类型 / 测试日期 / 测试结果）：

1. **连续断网 / 重连**：飞行模式开关或拔插网络，观察 Transport 重建后流量统计续累计、重连守卫不误判。
2. **重复 Transport rejection**：服务端连续拒绝连接，确认退避与 `DeviceConnection` 重连守卫行为，无重复连接风暴。
3. **Mailbox overflow / resync**：人为制造恢复期 mailbox 溢出，确认只累计 `suppressedOverflowCount`、resync 防风暴（不重复触发）。
4. **页面销毁重建**：旋转/后台回收后重建终端页面，确认 sessionId 归一化、状态恢复正确。
5. **Resize 重复触发**：连续/重复触发尺寸变化，确认 `requestResize` 去重（返回 boolean），无重复 resize 帧。
6. **Relay session 文件上传**：经 relay 上传文件，确认大小上限与脱敏（不含文件正文）生效。

### 12.2 日志容量真机抽查（建议）
- 长时间运行并触发日志滚动后，检查 `files/diagnostics/` 目录恒为 `webterm.log` + `.bak.1~3`（≤4 文件、约 ≤4 MiB）。
- 覆盖安装旧版本后启动，确认旧版本按启动命名的遗留日志在一次 trim（启动/60s/导出前）内被清退到预算内。

> 若已有真实验收结果，请在此补充：设备型号 / Android 版本 / App 构建类型（debug/diag）/ 测试日期 / 各项测试结果。
