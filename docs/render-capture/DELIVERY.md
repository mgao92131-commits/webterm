# 终端渲染路径现场捕获器 —— 交付说明

实现于 worktree `feature/render-capture`。完整链路/ hook 点调查见 [INVESTIGATION.md](./INVESTIGATION.md)。

## 1. 当前真实数据链路

**Go 出站**：`readLoop`(PTY 读) → `postPTYOutput` → (actor)`handlePTYOutput`[`engine.Write`→`bumpScreenRevision`→`scheduleProjectionFlush`] → `projectionFlushEvent` → `broadcastFrame`[`projector.ExportState`→每客户端 `Send(state)`] → (每客户端 writeLoop)`writeLatestScreenState`[`FrameDeriver.FrameForState`→`EncodeFrameWithCompactLines`→`writeMessage`→mux→`PhysicalWriter`→`Socket.Write`]。

**Android 入站**：WS→mux tunnel→`TerminalChannel.onData`→`onScreenMessage`→`handleScreenMessage`[`validateEnvelopeSize`→`classifyScreenMessage`→`ScreenMailbox.offer`]→(modelExecutor)`processScreenMessage`[`parseFrom`→`validate`→`mapSnapshot/mapPatch`→`model.apply*`]→`dispatchRenderNeeded`→(主线程 VSync)`renderOnFrame`[`model.consumeRenderUpdate()`→`v.render`→`applyRenderUpdate`→`onDraw`→`renderer.render`]。

## 2. 采用的 capture 架构

旁路观察 + 独立诊断数据面，三层：
- **公共契约层**（Android `terminal-model` 纯 Java `…model.capture`；Go `internal/terminalcapture`）：`Sink`/`Controller`/`Identity`/`Limits`/`Status`/`Result` 等，不依赖 Activity/View/control server/文件格式。
- **热路径 hook**：在正常业务路径产出不可变结果**之后**取副本/引用入有界 ring；绝不消费 dirty/baseline/revision/history watermark/render dirty。
- **Agent↔Android 传输**：新增 mux 逻辑通道 `webterm.capture.v1`（path `/ws/capture/{localSessionId}`），Relay 透明转发、不解析、不持久化正文；Direct/Relay 零改动路由。

## 3. 修改文件列表

**Go**
- 新增 `internal/terminalcapture/`：`capture.go`（契约+NOOP+门面）、`ring.go`（有界环）、`serialize.go`（ScreenFrame→JSON）、`build_payload.go`（barrier+rings→文件集）、`coordinator_enabled.go`（`//go:build webterm_capture`）、`coordinator_test.go`。
- `internal/terminalsession/runtime.go`：捕获点 A/B、`lastCanonicalFrame`、`captureBarrierEvent`+`CaptureBarrier()`+`CaptureSink()`、`captureSink` 字段。
- `internal/terminalsession/options.go`：`WithCaptureSink`。
- `internal/terminalsession/runtime_capture_test.go`（新增）。
- `internal/session/terminal_channel_runtime.go`：捕获点 C/D/E（`recordDerivedFrame`/`recordWireFrame`/`writeScreenMessage`）。
- `internal/session/terminal.go`：`CaptureBarrier()`。
- `internal/session/capture_channel.go`（新增）+ `capture_channel_test.go`（新增）。
- `internal/application/mux_channel_router.go`：`/ws/capture/` 路由。
- `internal/protocol/constants.go`：`CaptureSubprotocol`。
- `internal/app/app.go` + `capture_enabled.go`/`capture_disabled.go`（新增）：启动安装。

**Android**
- `terminal-model/…/capture/`（新增 11 个契约类）+ `CaptureContractTest`。
- `terminal-renderer/RemoteTerminalView.java`：`captureDiagnostics()`（点 E）+`captureScreenshotPng()`（点 F）；`RemoteTerminalRenderer.getBaselineOffset()`。
- `terminal-runtime/TerminalSessionRuntime.java`：点 A/B/C + `connection()`；`TerminalChannel.java`：`captureDeviceConnection()`/`captureLocalSessionId()`。
- `feature/terminal/TerminalScreenBuilder.java`：`DebugMenuItem` 注入 + 动态可见性；`TerminalScreenController.java`：点 D；`RemoteTerminalIntegration.java`：绑定会话源 + 菜单透传；`AgentCaptureChannelLink.java`、`TerminalCaptureSessionSource.java`（新增）。
- `app/src/diagnostics/`（新增）：`RealTerminalCaptureController`、`CaptureSerializer`、`TerminalCaptureMenu`、`TerminalCaptureInstaller`。
- `app/src/release/`（新增 stub）：`TerminalCaptureMenu`、`TerminalCaptureInstaller`（NOOP）。
- `app/.../WebTermApplication.java`、`AppFlowCoordinator.java`：安装 + 菜单注入。
- `app/src/test/.../RealTerminalCaptureControllerTest.java`（新增）。

**工具**：`tools/render-capture-inspect/`（Go CLI，仅 stdlib）。

## 4. 每个捕获点说明

| 点 | 位置 | 记录内容 | 安全性 |
|---|---|---|---|
| Go-A | `handlePTYOutput`，`engine.Write` 前 | eventSeq/revBefore/offset/原始 bytes（同步有界拷贝，不校验 UTF-8） | buf 复用故必须拷贝；仅 enabled 时拷贝 |
| Go-B | `broadcastFrame` `ExportState` 后 | 完整权威 `ScreenFrame`（Projector 字典，与 wire 可比） | 持不可变引用，不额外调用 |
| Go-C | `writeLatestScreenState` `FrameForState` 后 | 派生帧 + screenClientID/clientInstanceID | 不额外调用 deriver |
| Go-D | `encodeFrame` 成功后 | kind/base/screenRevision/payload（proto.Marshal 新分配，持引用）；SHA-256 导出时算 | 不在热路径算 hash |
| Go-E | `writeMessage` 返回后 | writeSucceeded/writtenAtNanos 或稳定枚举 failureKind | 绝不记 `err.Error()` |
| barrier | `captureBarrierEvent`（actor 顺序） | 当前 screenRevision + 只读 `ExportSnapshot` 现场生成的当前权威帧 | 不消费 dirty/不改字典/不推进 |
| And-A | `handleScreenMessage` offer 旁 | connectionEpoch/receivedAt/kind/原始 bytes（不重复 parse） | 持引用 |
| And-B | `mapSnapshot/mapPatch` 后 | 不可变领域对象 | 持引用 |
| And-C | `apply*` 成功后 | 模型摘要（identity/geometry/projectionHealth） | 有界字段读 |
| And-D | `renderOnFrame` `consumeRenderUpdate` 后 | 不可变 `RenderUpdate` | 绝不二次调用 consume |
| And-E | `RemoteTerminalView.captureDiagnostics()` | 几何/字体/viewport/渲染身份/光标/选择 | 只读，主线程 |
| And-F | `captureScreenshotPng()` | viewport PNG（`View.draw`，minSdk23） | 主线程取图，失败返回 null |

## 5. 为什么不会改变终端状态机

- 所有 hook 只读取正常路径已产出的**不可变**结果（`ScreenFrame`/`RenderUpdate`/领域对象/原始 bytes），从不回写。
- 绝不调用消费型 API：`ConsumeProjectionDirty`、额外 `ExportState`、`FrameForState`、`consumeRenderUpdate`、`ScreenMailbox.poll/reset`、`requestFullRender`、`syncHistoryWindow`。
- barrier 始终用只读 `ExportSnapshot`（内部 `ReadFullProjection` 不消费 dirty、构建独立字典不触碰 Projector 缓存/ChangeIndex）现场生成当前权威帧，不生成业务 Patch、不推进 revision/epoch/baseline/history watermark，且绝不复用跨 capture 生命周期的旧缓存帧。
- capture 通道独立于 screen mailbox，不参与 baseline/revision/lease/resume/input-ack/renderer。

## 6. build type / build tag 隔离

- **Go**：build tag `webterm_capture`。`coordinator_enabled.go` 含真实 ring/Coordinator；默认（release）只有 NOOP，无任何 ring 内存；热路径仅一次 `Enabled()`。app 经 `capture_enabled/disabled.go` 在启动时安装。测试用 `-tags webterm_capture`。
- **Android**：沿用 same-FQCN-per-source-set。真实实现 + 菜单 + 安装器在 `app/src/diagnostics`（debug+diag 共享）；`app/src/release` 提供 NOOP stub（`items()` 返回空、`install()` 空）。已验证 release 产物**不含** `RealTerminalCaptureController` 类，debug 含。菜单项另经 `TerminalCapture.isSupported()` 与动态 `visible` 双门控。

## 7. 现场包 schema

`webterm-render-capture-<captureId>.zip`：
```
manifest.json            schemaVersion/captureId/createdAt/started/finished/androidApp+device+sdk/
                         agentVersion+platform+buildMode/sessionId/clientInstanceId/terminalInstanceId/
                         layoutEpoch/androidModelRevision/androidRenderedRevision/agentRevision/
                         rows cols viewWidth viewHeight cellWidth lineHeight fontSize typeface
                         keyboardVisible activeBuffer compactLineEncoding/
                         screenshotAvailable agentAvailable/truncated{...}
checksums.sha256         "<sha256>  <path>" 逐文件
android/
  actual-screen.png      主线程 View.draw PNG（可缺）
  view-state.json        点 E 几何/字体/viewport/渲染身份
  model-state.json       点 C 模型摘要数组
  mapped-frames.jsonl    点 B Snapshot/Patch 领域对象（每行一条）
  render-snapshot.json   点 D 最近 RenderSnapshot
  render-dirty.json      点 D 最近 RenderDirtyState
  render-updates.jsonl   点 D RenderUpdate 序列
  wire/index.json        点 A 原始帧索引（seq/kind/length/sha256/offset）
  wire/NNNNNN.pb         点 A 原始 protobuf bytes
agent/
  capture-meta.json      Agent 身份/revision/截断/计数
  canonical-state.json   barrier 一致性权威帧（JSONScreenFrame）
  canonical-frames.jsonl 点 B 权威帧环
  derived-frames.jsonl   点 C 客户端派生帧
  pty.bin / pty-index.json  点 A PTY 原始 bytes + 索引（offset/length/sha/revBefore）
  wire/index.json + wire/NNNNNN.pb  点 D/E 编码字节 + 写状态（writeSucceeded/failureKind）
```
JSON 中 bytes 一律 base64（非十进制数组）；原始二进制单独成文件；索引含 offset/length/hash/revision。

## 8. UI 使用方式

终端“更多”菜单（仅 debug/diag 出现）：
- 未记录：**保存当前现场**（barrier，含当前状态 + 已记录的最近数据）、**开始现场记录**。
- 记录中：**保存并结束记录**（finish，停止 Agent/Android ring 并合并出包）、**取消现场记录**（释放内存）。
- 保存前弹出敏感内容确认对话框；确认后生成 ZIP 并经 FileProvider（`${applicationId}.diagnostics`，复用 `diagnostics-export/` 路径）调起系统分享面板。
- 历史包最多保留 5 个，`.tmp` 失败清理，文件名含唯一 captureId 不覆盖。

## 9. 测试结果

- **Go**：`go test ./...`（release）与 `go test -tags webterm_capture ./...` 全绿。新增覆盖：未启用不存数据、ring 字节/条数双限、PTY 原始 bytes（含断开的多字节）完整保留且被拷贝、barrier 不推进 revision、barrier 不消费 dirty（后续帧仍含新内容）、barrier 幂等、revision 一致性、wire SHA-256 正确、写失败稳定枚举无原始错误文本、多 session 不串、多 client 不串 derived、cancel 释放、capture 不推进 deriver baseline、capture 通道 start→finish 协议端到端（blob 重组 + sha 校验）。
- **Android**：`./gradlew testDebugUnitTest` 全绿。新增覆盖：NOOP 契约、身份关联/限额/状态、ring 有界、未记录不入队、cancel 清空、宽字符/Emoji/组合字符序列化、ZIP manifest 与 checksums 一致、SHA-256 正确、截断标志、Agent 文件合并。
- **构建**：`./gradlew :app:assembleRelease` 成功；release 产物经核验不含真实捕获类。
- **工具**：`tools/render-capture-inspect` 编译/vet 通过，合成包冒烟测试正确报出文本差异/悬空 style/revision 对比/wire sha 重叠。

## 10. 尚未覆盖的风险

- `sendScreenFrameNow`（初始同步/即时路径）未挂 wire/derived hook（仅 steady-state `writeLatestScreenState`）；恢复首帧的 wire 不进捕获。
- Android 原始 wire（点 A）在 parse 前记录，无 screenRevision，与 Agent wire 仅能按 sha256/（kind+length）配对，不能精确按 revision 配对。
- 截图用 `View.draw`（非 PixelCopy），硬件加速特殊效果可能差异；API26+ 可升级 PixelCopy。
- `lastCanonicalFrame` 在合帧窗口内可能略滞后于当前 screenRevision（barrier revision 与帧 seq 可能不等——这是有意保留的诊断信息）。
- 单次只允许一个活跃捕获（`ErrCaptureActive`）；多终端同时捕获需扩展为按实例多 ring。
- 现场包含终端正文，**未脱敏**；仅经用户显式确认分享，不应上传至长期存储。

## 11. 手工复现步骤

1. `cd go-core && go build -tags webterm_capture ./cmd/webterm-agent`（得到含捕获的 Agent）。
2. 启动 Agent（direct 或 relay），用 debug/diag Android 连接打开终端。
3. 运行会触发问题的命令（如 `ls --color`、`vim`、宽字符 `echo 中文😀`、大量滚动 `seq 1 1000`）。
4. 终端“更多”→**开始现场记录**；复现问题；**保存并结束记录**→确认敏感提示→分享 ZIP。
5. （可选）“保存当前现场”可在不先开始记录的情况下抓取当前权威帧 + Android 状态。

## 12. 生成现场包的完整验证流程

```sh
# Agent（含捕获）
cd go-core && go build -tags webterm_capture -o /tmp/webterm-agent ./cmd/webterm-agent
/tmp/webterm-agent run --mode direct   # 或 relay

# Android debug 包
cd android-client && ./gradlew :app:assembleDebug
# 安装后连接、开始记录、复现、保存并结束 → 取得 webterm-render-capture-<id>.zip

# 离线对比
cd tools/render-capture-inspect && go run . /path/to/webterm-render-capture-<id>.zip
# 输出：manifest 摘要 / checksums 校验 / revision 对比 / wire sha 对比 / 行文本宽度差异 /
#       layout line-id 差异 / style-link 引用缺失 / 最可能出错阶段

# 测试
cd go-core && go test ./... && go test -tags webterm_capture ./...
cd android-client && ./gradlew testDebugUnitTest && ./gradlew :app:assembleRelease
```

---

## 13. 合并前审查修复（P1/P2）

针对代码审查，已在合并前完成以下修复并补充测试：

**P1（6 项，全部修复）**
1. **Android 会话级隔离**：所有 `record*` 携带 `CaptureStreamIdentity`（sessionId/terminalInstanceId/clientInstanceId），控制器在复制/序列化前 `matchesActive` 校验；`bindSession` 返回 `CaptureBinding` token，`unbindSession(token)` 仅在令牌匹配时解绑，`startCapture` 快照 `activeSource`，杜绝多 Session 串包与旧页面清空新绑定。
2. **全链路硬字节上限**：Go canonical/derived ring 增加字节预算（`estimateFrameBytes`）；服务端 `Hard*` 硬上限 + `clampLimits`（请求只能降不能升），修复 `MaxStructuredFrames*4` 溢出；Agent `BuildAgentPayload` 强制单文件/文件数/总负载上限（`fileCollector`）；Android mapped/render ring 字节预算，Agent 接收用有界队列 + 字节/文件数上限，超限返回 `payload_too_large`。
3. **Android 校验 Agent 文件**：保存期望 length/sha256；要求所有文件 `final=true`；chunk seq 严格递增；拒绝重复/未知文件；校验长度与 SHA-256；路径白名单（仅 `agent/` 相对路径，拒绝 `../`、`\`、绝对、空、重复、`android/`）；任一失败 `agentAvailable=false`。
4. **canonical 缓存跨 capture 复用**：barrier 始终在 actor 内用只读 `ExportSnapshot`（`ReadFullProjection`，不消费 dirty/不改字典/不推进 revision/baseline）现场生成当前状态；移除 `lastCanonicalFrame` 跨生命周期复用。
5. **过期 capture 自动释放**：`StartCapture` 懒清理过期 active（CAS 释放），同 captureId 重复 start 幂等，避免进程被杀/断网后永久占据捕获槽。
6. **保存当前现场含 Android 当前状态**：`CaptureSessionSource` 增加 `currentModelSnapshot()`（模型锁只读 `peekRenderSnapshot`）/`currentRenderedSnapshot()`/`lastAppliedDirty()`；`buildPackage` 无论是否记录都写入 `current-model-state.json`/`render-snapshot.json`（含完整 history 窗口）。

**P2（重要项，已修复）**
- 热路径不做 JSON：mapped/render ring 只存不可变对象引用，JSON 在导出 executor 生成。
- 截图：主线程仅取有界 ARGB 像素（`CAPTURE_MAX_PIXELS` 下界缩放），PNG 压缩在后台；manifest 记录 original/captured 尺寸与 scaled。
- `capture-meta.json` 最后序列化，counts 为最终值；`manifest`/`meta` 标注 `initialSyncCaptured=false`。
- `CaptureIdentity` 经模型锁内整体发布的 `peekRenderSnapshot` 原子读取，避免组合不一致。
- `CaptureLimits` 服务端/客户端均 clamp 到硬上限。
- 区分 `model-state.jsonl`（记录期间摘要）与 `current-model-state.json`（当前完整状态）；render-snapshot 含 history 窗口 + `historyCapturedFromSeq/ToSeq/historyTotalSize/historyTruncated`。

**新增测试**：Go（自动过期/幂等 start/limits clamp/meta counts/fileCollector 上限/barrier 当前语义）；Android（会话隔离过滤/token 解绑/ring 有界/Agent 文件 length+SHA+final+seq+路径白名单校验/宽字符序列化/ZIP 一致性）。

**已知遗留**：初始同步路径（cold attach/resume/reconnect 首帧）仍未接入 wire/derived 捕获，已在 manifest/agent-meta 明确 `initialSyncCaptured=false`。
