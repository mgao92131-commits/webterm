# Diagnostics 迁移计划（Windows 架构合并之后）

> 集成分支：`integrate/network-diagnostics-after-win10`（基于最新 `main`）
> 迁移来源（只读）：`origin/diagnostics/network-traffic-investigation`
> 两分支合并基点（merge-base）：`75ebaa41a4b2bb2467036c7884147e2eaf08e0d5`
> main 当前 HEAD：`200a4fc5`（Merge branch 'feat/win10-support' into main，已同步 origin/main）

本文档为**任务 0** 产物，仅做分析，不迁移任何生产代码。

---

## 1. 背景与总原则

`feat/win10-support` 已合并进 `main`。该分支带来 Windows 10 支持（ConPTY、Named Pipe Local IPC、标准 CLI、Session Hook、退出排空），并**删除了旧的本地 HTTP Control Server**（`go-core/internal/control/`）。

原 `diagnostics/network-traffic-investigation` 分支在合并基点之后独立演进，其中部分能力依赖已被删除的旧 Control Server。因此 diagnostics 不能直接 merge，必须以**只读来源**逐项迁移到新架构。

### 总体执行原则（强约束）

- 所有工作统一在 `integrate/network-diagnostics-after-win10` 进行；该分支从最新 `main` 创建。
- `diagnostics/network-traffic-investigation` **保持只读**，仅作为迁移来源。
- **不**直接把原 diagnostics 分支 merge 到集成分支。
- **不**恢复已被 Windows 分支删除的旧 HTTP Control Server。
- 每完成一个任务单独提交一次；每次提交前运行该任务要求的测试。
- **不**直接推送或修改 `main`；**不**删除原分支和 worktree。
- 遇到架构语义不明确时停止修改，在报告中说明，不自行恢复旧实现。
- 测试失败不得通过删测试、降断言、跳过或扩大超时来掩盖。

---

## 2. 🚫 明确禁止恢复的旧架构

| 项 | 状态 | 说明 |
|---|---|---|
| `go-core/internal/control/server.go` | **main 已删除，不得恢复** | 旧本地 HTTP Control Server 入口 |
| `go-core/internal/control/connection_test_api.go` 等 | **main 已删除，不得恢复** | 旧 control 包整体已移除 |
| HTTP 路由 `/control/traffic` | **不得恢复** | diagnostics 曾用于流量查询 |
| HTTP 路由 `/control/diagnostics` | **不得恢复** | diagnostics 分支 `d6bafe8b` 新增，整体放弃 |
| 本机 HTTP 控制端口监听 | **不得重新开放** | 一律改走 Local IPC（Named Pipe / Unix Socket） |

**替代架构（main 现状，迁移目标）：**

- `go-core/internal/localipc/`：`envelope.go`、`endpoint.go`、`server.go`、`ipc_unix.go`、`ipc_windows.go`（+ 各自测试）。
- `go-core/internal/webtermcmd/`：`root.go`（CLI 根命令，+ 测试）。
- `go-core/cmd/webterm`（CLI）、`go-core/cmd/webterm-agent`（Agent）。

diagnostics 的底层数据源（`diagnostics.Default` 指标注册表、`app.DiagnosticsState()`、`app.RunID()`、`app.BuildInfo()`）在新架构中**仍然有效**，需要替换的只是 HTTP 传输层 → Local IPC + CLI。

---

## 3. diagnostics 独有提交清单（7 个，按提交顺序）

范围：`75ebaa41..origin/diagnostics/network-traffic-investigation`

| # | SHA | 提交说明 | 主要改动量 |
|---|---|---|---|
| 1 | `0dda8ade` | fix(android): 优化连接恢复、Resize 去重、流控防暴风及 Relay 会话归一化 | 13 文件 +344/-25 |
| 2 | `5afe7b0b` | feat(go-core): optimize projection export for zero clients and fix traffic metrics | 5 文件 +407/-12 |
| 3 | `cfafd1d9` | fix(android): isolate traffic stats by server and fix resize request retry | 15 文件 +145/-21 |
| 4 | `543bd6c0` | feat(go-core): 终端会话诊断事件与指标 | 4 文件 +314/-82 |
| 5 | `f4c2312c` | feat(go-core): 诊断底座与日志事件基础设施 | 15 文件 +1668/-10 |
| 6 | `d6bafe8b` | feat(go-core): 业务层诊断事件接入 | 10 文件 +315/-39 |
| 7 | `3db8ee8c` | feat(android): 诊断日志、速率限制与恢复指标 | 10 文件 +652/-67 |

> ⚠️ **本地未提交改动提示**：`/Users/gao/Documents/webterm-c1` worktree 上，`3db8ee8c` 之上还有**未提交**改动（`DiagnosticRateLimiter.java`、`Diagnostics.java` 及对应测试等）。这些改动**不在 origin**，因此**不在本迁移范围内**。若需纳入，请先在 diagnostics 分支提交并推送，再重新评估。本计划只迁移 `origin` 上已提交的内容。

---

## 4. 功能分组

| 分组 | 能力 | 来源提交 | 目标任务 |
|---|---|---|---|
| **A. Go 诊断基础** | logs（脱敏/限流/file sink）+ diagnostics 数据模型（exporter/manifest/metrics/summary） | `f4c2312c`（包部分） | 任务 1 |
| **B. Go Mux/Relay/Session/Terminal 指标** | 物理连接收发、relay 重连、session screen 分类、mailbox 溢出、projection 导出 | `5afe7b0b`、`543bd6c0`、`d6bafe8b`（mux/relay/session/terminalsession 部分） | 任务 5 |
| **C. Go Local IPC / CLI 诊断查询导出** | 重写旧 `/control/diagnostics`、`/control/traffic` 为 `webterm diagnostics …` | `d6bafe8b`（control 部分，**重写**） | 任务 4 |
| **D. Go 生命周期接入** | app/state、webterm-agent main 中诊断初始化/关闭 | `f4c2312c`（app/main 部分） | 任务 7 |
| **E. Android 网络流量统计** | UID/WebSocket 按 deviceId 分组累计、重连续累计、分类统计 | `cfafd1d9`、`0dda8ade`（DeviceConnection/transport/NetworkTrafficStats 部分） | 任务 2 |
| **F. Android 日志导出与限流** | DiagnosticLogExporter/LogFiles/XLogDiagnostics/RateLimiter，ZIP 导出 | `3db8ee8c`、`cfafd1d9`（exporter 部分） | 任务 3 |
| **G. Android Runtime/Resume 指标** | LayoutLease/Resync/ScreenMailbox/RuntimeKey/ResumeMetrics/Resize | `0dda8ade`、`3db8ee8c`（terminal/domain 部分） | 任务 6 |
| **H. Android 生命周期整合** | DeviceService、UID tracker、DeviceConnection 统计器生命周期 | 跨提交 | 任务 7 |

---

## 5. 高风险冲突文件（两个分支都修改）

计算方式：`75ebaa41..diagnostics` 与 `75ebaa41..main` 改动文件交集。

| 文件 | diagnostics 侧 | main(Windows) 侧 | 冲突等级 | 处理 |
|---|---|---|---|---|
| `go-core/internal/control/server.go` | 新增 `/control/diagnostics` | **整个文件被删除** | 🔴 致命 | **放弃 diagnostics 改动**，能力改由任务 4 经 Local IPC 重写 |
| `go-core/internal/session/terminal_channel_runtime.go` | `5afe7b0b`/`543bd6c0` 大改（指标） | Windows 退出排空/投影大改 | 🔴 高 | 任务 5 人工重写，保留 Windows 排空/Exit 顺序 |
| `go-core/internal/terminalsession/runtime.go` | `5afe7b0b`/`543bd6c0` 指标 | Windows 生命周期改动 | 🔴 高 | 任务 5 人工重写 |
| `go-core/internal/session/terminal.go` | 指标事件 | Windows 改动 | 🟠 中 | 任务 5 适配 |
| `go-core/internal/session/manager.go` | 诊断事件接入 | Windows 改动 | 🟠 中 | 任务 5/7 适配 |
| `go-core/internal/terminalsession/input_writer.go` | 输入写入事件 | Windows 改动 | 🟠 中 | 任务 5 适配 |
| `go-core/internal/app/app.go` | `f4c2312c` 诊断状态/初始化 | Windows 启动/关闭顺序 | 🟠 中 | 任务 7 适配，保留 Windows 顺序 |
| `go-core/cmd/webterm-agent/main.go` | `f4c2312c` 接线 | Windows 改动 | 🟠 中 | 任务 7 适配 |
| `android .../diagnostics/DiagnosticLogExporter.java` | `cfafd1d9`/`3db8ee8c` | Windows 合并带入改动 | 🟡 低-中 | 任务 3 适配 |
| `scripts/smoke-go-relay-android-emulator.sh` | 冒烟脚本调整 | Windows 改动 | 🟡 低 | 任务 8 核对 |
| `go-core/internal/session/*_test.go`、`terminalsession/runtime_test.go` | 诊断测试 | Windows 测试改动 | 🟠 中 | 随对应任务适配 |

---

## 6. 每个提交的迁移策略

### 提交 1 `0dda8ade`（android 连接恢复/Resize/流控/归一化）— **部分迁移**
- 涉及 `terminal/domain/*`（LayoutLeaseCoordinator、ResyncCoordinator、ScreenMailbox、TerminalRuntimeKey、TerminalSessionRuntime）、`DeviceConnection`、`WebSocketMuxTransport`。
- `terminal/domain/*` 不在重叠清单内（Windows 未改），冲突低；但本提交含**行为修复**（Resize 去重、流控防暴风、Relay 归一化），非纯指标。
- 策略：**拆分**。指标/Runtime 部分 → 任务 6；DeviceConnection/transport 统计部分 → 任务 2。行为修复需对照当前 main 评估，**不盲目套用**。

### 提交 2 `5afe7b0b`（go projection 零客户端导出 + 流量指标）— **部分迁移 / 重写**
- 文件全部在重叠清单（`terminal_channel_runtime.go`、`terminalsession/runtime.go`）。
- 策略：→ 任务 5。以当前 main 为基准重写指标旁路，**保护 DrainAndClose、最终投影、Exit 顺序**；仅采纳“零客户端投影导出优化”中与新架构兼容的部分。

### 提交 3 `cfafd1d9`（android 按 server 隔离流量 + resize 重试）— **部分迁移**
- `NetworkTrafficStats` 按设备/server 隔离 → 任务 2；`DiagnosticLogExporter` → 任务 3；`terminal/domain` 测试调整 → 任务 6。
- 策略：以当前 main 连接/传输生命周期为主体增量迁移，**不整体覆盖**。

### 提交 4 `543bd6c0`（go 终端会话诊断事件与指标）— **重写为主**
- 大改 `terminal_channel_runtime.go`(+233)、`terminalsession/runtime.go`(+84)，均高冲突。
- 策略：→ 任务 5。抽取“screen 发送分类（Snapshot/Patch/History Page/Trim）”等指标语义，重写为旁路统计，**WriteFrame 成功后才累计**，不改变帧发送主流程。

### 提交 5 `f4c2312c`（go 诊断底座与日志事件基础设施）— **拆分：包可迁移，接线延后**
- `internal/logs/*`（file_sink/rate_limiter/safe/logger）与 `internal/diagnostics/*`（exporter/manifest/metrics/summary）为**独立可测基础组件**，与 PTY/Session/IPC 无强耦合 → **任务 1（接近直接迁移，适配 main API）**。
- `internal/app/app.go`、`app/state.go`、`cmd/webterm-agent/{main,diagnostics}.go` 的接线 → **任务 7**（保留 Windows 启动/关闭顺序）。
- 任何指向旧 Control Server 的接线一律丢弃。

### 提交 6 `d6bafe8b`（go 业务层诊断事件接入）— **拆分：指标保留，control 路由重写**
- `mux/physical_writer.go`、`mux/session.go`、`relay/client_v2.go`、`session/{diagnostics,events,manager,terminal}.go`、`terminalsession/input_writer.go` 的事件/指标接入 → **任务 5**（适配，不依赖 control）。
- `internal/control/server.go` 的 `/control/diagnostics` 路由 → **整体放弃**；其查询/导出能力在 **任务 4** 用 Local IPC + CLI 重写（复用 `diagnostics.Default.Snapshot()` / `app.DiagnosticsState()`）。

### 提交 7 `3db8ee8c`（android 诊断日志/限流/恢复指标）— **部分迁移**
- `DiagnosticLogExporter`、`DiagnosticLogFiles`、`XLogDiagnostics`、`DiagnosticRateLimiter` → **任务 3**。
- `TerminalResumeMetrics` → **任务 6**。
- `AGENTS.md`、冒烟脚本 → 任务 8 核对。
- 注意：本地未提交改动（见 §3 提示）不在此列。

---

## 7. 迁移策略汇总（按任务）

| 任务 | 范围 | 方式 |
|---|---|---|
| 任务 1 | Go `internal/logs` + `internal/diagnostics` 基础组件 | 接近直接迁移 + 适配 main API；不接 Agent 启动流程 |
| 任务 2 | Android 网络流量统计（按 deviceId 累计） | 增量迁移，以 main 生命周期为主体 |
| 任务 3 | Android 日志导出/限流/清理 | 增量迁移 + 加固 |
| 任务 4 | Go diagnostics → Local IPC + CLI | **重写**（替代旧 HTTP Control Server） |
| 任务 5 | Go Mux/Relay/Session/Terminal 指标 | 人工重写为旁路统计，保护 Windows 排空/Exit |
| 任务 6 | Android Runtime/Resume/Mailbox/Resize 指标 | 增量迁移，只记录不驱动状态 |
| 任务 7 | Go + Android 生命周期整合 | 适配，保留 Windows 启动/关闭顺序 |
| 任务 8 | 清理 + 全量验证 + 验收报告 | 删除残留旧入口，跨平台验证 |
| 任务 9 | 准备 Draft PR（不自动合并） | 整理历史与 PR 描述 |

---

## 8. 测试策略

- **每任务提交前**运行该任务指定的测试（Go `go test`/`go test -race`；Android `./gradlew :module:testDebugUnitTest`、`lint`、`assemble`）。
- Go 关键包 race 必测：`internal/logs`、`internal/diagnostics`、`internal/mux`、`internal/relay`、`internal/session`、`internal/terminalsession`、`internal/app`、`internal/runtime`。
- Windows 交叉构建：`GOOS=windows GOARCH=amd64 go build ./cmd/webterm ./cmd/webterm-agent ./cmd/webterm-relay`。
- 任务 4 验证 CLI 文档生成无 diff：`go generate ./cmd/... && git diff --exit-code -- docs/cli`。
- 回归重点：终端大量输出后退出的 `END_MARKER` 与 Exit 顺序不变；尾部输出不丢；重连续累计；多设备不串号。
- 任务 8 全量：`go test ./... && go vet ./... && go test -race ./...`；Android `testDebugUnitTest`/`lintDebug`/`assembleDebug`（含 diagnostics variant）。

---

## 9. 安全与脱敏（贯穿所有任务）

日志与诊断输出**不得**记录：终端输入正文、Cookie、Token、Relay Secret、SMTP 密码、文件内容。
统计代码不得逐帧写日志、不得阻塞 WebSocket 回调线程；所有指标/缓存/日志文件必须有容量边界；日志写入失败不得导致 Agent 主流程失败。

---

## 10. 已知风险与待人工确认项

1. **行为修复 vs 指标**：`0dda8ade`、`cfafd1d9` 含 Resize 去重、流控防暴风、Relay 归一化等**行为修复**，非纯指标。迁移时需逐项判断当前 main 是否已有等价逻辑，避免重复或冲突。**语义不明确时停止并报告**。
2. **高冲突 Go 文件**：`terminal_channel_runtime.go`、`terminalsession/runtime.go` 被两侧大改，任务 5 需人工逐段对齐，不能 cherry-pick。
3. **本地未提交改动**不在迁移范围（见 §3），需人工决定是否先提交。
4. **Android Gradle 任务名**：diagnostics variant 的精确测试/lint 任务名需在对应任务中用 `./gradlew tasks` 核实并记录。
5. 旧 `/control/diagnostics` 的字段（runId/buildInfo/metrics/state）在 Local IPC 下的命名与 envelope 类型设计，留待任务 4 确定，需与现有 CLI 子命令风格一致。
