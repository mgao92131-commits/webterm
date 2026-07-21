# Diagnostics 迁移验收报告（Windows 架构合并之后）

- **集成分支**：`integrate/network-diagnostics-after-win10`（基于 `main` = `200a4fc5`，feat/win10-support 合并后）
- **迁移来源（只读）**：`origin/diagnostics/network-traffic-investigation`，合并基点 `75ebaa41`
- **方式**：逐任务手工迁移/重写，**未** merge 原 diagnostics 分支，**未**恢复旧 HTTP Control Server。

---

## 1. 迁移的功能

### Go Agent（go-core）
- **诊断基础**（任务 1）：`internal/logs`（结构化事件日志：内存 Ring + `agent.jsonl` 1 MiB×3 滚动 + 5s 限流 + `SafeID/HashID/SafeEnum` 脱敏）；`internal/diagnostics`（进程级指标 `diagnostics.Default` + `Export` 诊断包：manifest/events/metrics/state/summary，4 MiB/1000 条预算，跳过崩溃半行，无日志文件亦容错）。
- **Local IPC + CLI**（任务 4）：`webterm diagnostics summary|export` 经 Local IPC（Unix socket / Windows named pipe）查询/导出；`localipc` 增 `TypeDiagnostics` 命令与请求/响应类型；`handleDiagnostics` 含 panic recover。
- **Mux/Relay/Session 指标**（任务 5）：mux 物理连接收发帧/字节（tx 仅成功写入后计）、channel open/replace、writer failure、writer queue rejected；relay connect/reconnect/disconnect/connect-failure；screen encode failure / snapshot fallback。
- **生命周期整合**（任务 7）：`app` 启动挂 FileSink（与导出同目录，失败降级仅内存）、`runID`/`BuildInfo`（`-ldflags` 注入）、`DiagnosticsState`（relay/mux/terminals 结构化态）、`Shutdown` 幂等关闭句柄。
- **容量边界**（任务 8）：Go `logs.RateLimiter` 状态表超 4096 触发回收（先删过期窗口，仍超低水位则按时间删最旧）。

### Android（android-client）
- **网络流量统计**（任务 2）：`NetworkTrafficStats` 累计器键升级为「归一化 baseUrl + deviceId」，按服务器/设备隔离、重连续累计；`serverOfKey/deviceOfKey`。WebSocket 收发帧/字节、UID 流量（-1→supported=false）main 已具备。
- **诊断日志导出与限流**（任务 3）：`DiagnosticRateLimiter`（线程安全，状态表容量有界）；`DiagnosticLogFiles.planDeletions`（≤4 文件/4 MiB 双约束清理）；XLog 1 MiB×3 滚动；`DiagnosticLogExporter` ZIP（日志 + `network-traffic-summary.txt` + manifest/android-metrics/android-state JSON）。
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
- **Go（按会话/连接）**：每会话 `ScreenWireSnapshot`（Snapshot/Patch/HistoryPage/HistoryTrim/Other 字节 + 帧数）+ PTY 输出事件/字节（main 已有）；mux 物理连接收发帧/字节（tx 仅 `conn.Write` 成功后计）；relay 连接/重连计数。分类字节为旁路 atomic 累计，与实际发送一致，统计失败不影响发送。

## 5. 容量边界（缓存/指标均有界）
- 日志文件：单文件 1 MiB × 3 备份；导出 events 4 MiB / 1000 条预算。
- Android 诊断日志：≤4 文件 / ≤4 MiB，自动清最旧；XLog 1 MiB×3。
- 限流器状态表：Go `logs.RateLimiter` 与 Android `DiagnosticRateLimiter` 均 4096 上限 + 回收（含边界测试）。
- 指标均为固定字段 atomic 计数器 / 有界直方图，无新增无界 goroutine/channel/map，无逐帧落盘。

## 6. 安全与脱敏
- 日志/诊断输出不记录：终端输入正文、Cookie、Token、Relay Secret、SMTP 密码、文件内容。
- config 经 `Redacted()`（Relay Secret→`********`）；ID 类经 `SafeID/HashID`；错误仅记类型/枚举（`SafeEnum`）。
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
- **`Diagnostics` 门面限流接线**：committed 的 `DiagnosticRateLimiter`（tryPass API）已迁移并加了容量边界，但**未接入** `Diagnostics` 日志门面。源分支 webterm-c1 worktree 上有未提交的重设计（`check`→`Decision` + 门面主入口限流 + `event_suppressed` 补报），与本迁移来源（origin 已提交）API 不同，按约定未迁移。需用户先提交该设计后再适配。
- **Go 部分 diagnostics.Default 计数器尚未接到业务埋点**：Mailbox/Input/Resync/ProjectionSkippedNoClient 计数位于 Windows 敏感的 terminalsession runtime actor 循环，为避免改动状态机，本迁移仅接入 mux/relay/screen-encode 计数；其余计数器结构已就位，待后续安全接入。
- 旧 `internal/control` 的 `connection_test_api` 等测试 API 未迁移（随旧 Control Server 一并废弃）。

## 9. 已知风险
- **限流器回收的精度权衡**：状态表超限回收时，被提前删除的窗口会丢失「上一窗口抑制数」的一次性汇总；诊断场景下以内存有界为先，可接受。
- **lint 基线过期**：`lint-baseline.xml` 有 26 条已被 main（Windows 合并）修复的过期条目；非本迁移引入，建议后续刷新基线。
- **行为修复（任务 6b）改动了状态机**（resync 抑制 / resize 去重 / sessionId 归一 / 重连守卫），已用 main 既有 Mailbox/Resize/Resync/Recovery 测试 + 增强的 diagnostics 测试覆盖，但属行为变更，PR 审查宜重点关注。
- **Go 诊断摘要中部分指标为 0**：Mux/Relay 计数已接入，但 Mailbox/Input/Resync 计数未接（见 §8），摘要中相应字段为 0，非 bug。

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
