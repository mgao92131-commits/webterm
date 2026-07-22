# 终端渲染路径现场捕获器 —— 调查与设计记录

> 本文记录当前分支 `feature/render-capture` 的真实数据链路、hook 点、状态机边界与最终采用的捕获架构。所有类名/行号以当前代码为准。

## 1. 当前真实链路

### Go / Agent 出站（PTY → WebSocket）

```
[readLoop goroutine]  runtime.go:552
  terminalIO.Read(buf)                         (PTY fd, buf 来自 sync.Pool, 用后 release)
  postPTYOutput → events <- ptyOutputEvent     runtime.go:603

[actorLoop goroutine —— 单线程权威]  runtime.go:615
  handleEvent (case ptyOutputEvent)            runtime.go:630
    handlePTYOutput                            runtime.go:845
      engine.Write(data)                        runtime.go:848  ← PTY 进入引擎
      bumpScreenRevision()                      runtime.go:849  (screenRevision++)
      commitEngineSignals()
      scheduleProjectionFlush()                 (16ms 合帧 timer → projectionFlushEvent)
  handleEvent (case projectionFlushEvent)
    broadcastFrame                              runtime.go:1081
      state := projector.ExportState(epoch,rev) runtime.go:1086  ← 完整权威帧（Projector 字典）
        └ mergeAndExport → ReadProjection → ConsumeProjectionDirty (projector.go:335) ⚠️消费 dirty
      for client: c.Send(state)                 runtime.go:1089

[每客户端 writeLoop goroutine]  terminal_channel_runtime.go:263
  writeLatestScreenState                        terminal_channel_runtime.go:339
    frame := screenDeriver.FrameForState(state) :347  ← 派生 Snapshot/Patch（推进 baseline）
    payload := encodeFrame(frame)               :354  ← Protobuf 编码 (EncodeFrameWithCompactLines)
    writeMessage(ctx, {payload, kind})          :441
      sink.WriteFramePriority → mux → PhysicalWriter → Socket.Write
      成功后累计字节计数                          :455-458  ← 物理写成功确认点
      失败 → client.Close(), return false
```

- `screenRevision`：`Runtime.screenRevision`（runtime.go:53），初值 1，`bumpScreenRevision`（:1171）在每个 PTY chunk 与 cwd 变化时推进。
- `layoutEpoch`：`Runtime.layoutEpoch`（:52），resize 时推进。
- `instanceID`（terminalInstanceId）：`Runtime.instanceID`（:26，randomID）。
- `FrameDeriver.baseline`：每客户端，`FrameForState` 推进；空 patch 返回 Kind=0 且不推进。

### Android 入站（WebSocket → Canvas）

```
WS binary → mux tunnel 解码 → TerminalChannel.onData → onScreenMessage
  handleScreenMessage                           TerminalSessionRuntime.java:586
    validateEnvelopeSize                         :589 (≤2MiB)
    classifyScreenMessage (只扫 envelope tag)     :592
    screenMailbox.offer(epoch,conn,payload,ok,kind) :595  ← 原始 bytes 入队（ScreenMailbox）
    modelExecutor.execute(drainScreenMailbox)     :598

[modelExecutor 单线程]
  drainScreenMailbox → processScreenMessage      :748
    ScreenEnvelope.parseFrom(payload)            :752  ← 完整 parse
    [SNAPSHOT] validateSnapshot → mapSnapshot → model.applySnapshot   :791/:804/:809
    [PATCH]    validatePatch → mapPatch → model.applyPatch            :850/:862/:876
    dispatchRenderNeeded → RenderWakeDispatcher → (主线程) onRenderNeeded

[主线程 VSync]
  TerminalScreenController.renderOnFrame         TerminalScreenController.java:314
    update := model.consumeRenderUpdate()        :319  ← 消费 RenderUpdate（破坏性，swap pending）
    v.render(update, viewport)                   :323
      RemoteTerminalView.applyRenderUpdate       RemoteTerminalView.java:194
        renderedSnapshot = update.snapshot        :197
        invalidate() → onDraw                     :256
          renderer.render(canvas, snapshot, viewport)  RemoteTerminalRenderer.java:96
```

- Android model revision = `RemoteTerminalModel.screenRevision/layoutEpoch/instanceId`（:23-25）。
- rendered revision = `RenderSnapshot.screenRevision`（View 持有 renderedSnapshot）。
- clientInstanceId = `ReliableInputTracker.clientInstanceId()`。

## 2. 状态机禁区（捕获绝不触碰）

| 禁止调用 | 原因 |
|---|---|
| `Engine.ConsumeProjectionDirty` / 额外 `Projector.ExportState` | 消费 dirty，破坏下一次正常导出 |
| `FrameDeriver.FrameForState`（额外调用） | 推进客户端 baseline |
| `RemoteTerminalModel.consumeRenderUpdate`（二次调用） | swap pending，饿死 VSync 消费者，丢帧 |
| `ScreenMailbox.poll/reset/finishDrain` | 推进 drain 调度 |
| `requestFullRender` / `requestRender` | 注入 full-invalidate 与 UI wake |
| `Projector.syncHistoryWindow`（额外调用） | 推进历史水位缓存 |
| 额外推进 `screenRevision`/`layoutEpoch`/renderer dirty | 改变业务状态 |

**安全只读观察点（已验证不改状态）：**
- Go：`broadcastFrame` 中已产出的 `state`（:1086，Projector 字典，与 wire 可比）；`writeLatestScreenState` 中已派生的 `frame`（:347）与已编码 `payload`（:354）；`writeMessage` 成功/失败结果（:450-458）。`screenprojection.ExportSnapshot`（exporter.go:24）用 `ReadFullProjection`（不消费 dirty）+独立字典，可作 barrier 兜底。
- Android：`handleScreenMessage` 的 `payload`（:595 旁）；`processScreenMessage` 中 `mapSnapshot/mapPatch` 返回的不可变领域对象；`publishRenderSnapshot`/`renderOnFrame` 返回的 `RenderUpdate`（:319 之后）；`RemoteTerminalView.renderedSnapshot` + renderer 几何 + viewport。

## 3. 建议 hook 点

### Go
- A. PTY 原始输出：`handlePTYOutput`，`engine.Write` **之前**，同步有界拷贝（buf 会被 sync.Pool 复用）写入 PTY ring。
- B. 完整权威帧：`broadcastFrame` `ExportState` 返回后，记录 `state` 引用（不可变）入 canonical ring；同时缓存 `lastCanonicalFrame`（仅捕获激活时保留）供 barrier。
- C. 客户端派生帧：`writeLatestScreenState` `FrameForState` 返回且 `frame.Kind!=0` 后，记录 `frame` + screenClientID + clientInstanceID。
- D. wire bytes：`encodeFrame` 成功后记录 payload（proto.Marshal 新分配，可直接持引用）+ SHA-256 在导出时异步计算。
- E. 写成功：`writeMessage` 返回后补写 `writeSucceeded/writtenAtNanos` 或 `failureKind`（稳定枚举，绝不记 err.Error()）。

### Android
- A. 原始 screen bytes：`handleScreenMessage` `offer` 旁记录 payload（不重复 parse）。
- B. Mapper 输出：`mapSnapshot/mapPatch` 返回后记录领域对象。
- C. Model apply 后：`applySnapshot/applyPatch` 成功后记录模型摘要（RenderSnapshot volatile 字段 + projectionHealth）。
- D. RenderUpdate：`renderOnFrame` `consumeRenderUpdate()` 返回后旁路记录（不额外调用）。
- E. View 绘制状态：`RemoteTerminalView` 新增只读 `captureDiagnostics()` 方法。
- F. 画面：主线程 `View.draw` 到 Bitmap（minSdk 23，PixelCopy API26+ 可选），后台写文件。

## 4. 会改动的模块

**Go**：新增 `internal/terminalcapture`（契约+ring+序列化+enabled/disabled）；`internal/terminalsession`（hook A/B + barrier event + option + lastCanonicalFrame）；`internal/session`（hook C/D/E + capture channel handler）；`internal/application`（mux router 新增 capture case）；`internal/protocol`（CaptureSubprotocol）；`internal/app`（安装 coordinator）。

**Android**：`core-contract`（capture 契约接口，避免反向依赖 app）；`terminal-renderer`（View 只读诊断快照）；`feature/terminal`（菜单注入点 + controller hook）；`app/src/diagnostics`（真实实现 + ZIP + 分享）；`app/src/release`（NOOP stub）；`app`（菜单 wiring + FileProvider 复用）。

**工具**：`tools/render-capture-inspect`（Go CLI）。

## 5. 可能影响状态机的位置 & 避免副作用措施

- 所有 hook 只在正常业务路径**产出不可变结果之后**取副本/引用，绝不额外调用消费型 API。
- 热路径只做：一次 `Enabled()` 廉价判断 + 有界拷贝 + 写有界 ring（ring 自带 mutex，不持业务锁）。
- PTY ring 同步拷贝（buf 复用）；wire payload 直接持引用（proto.Marshal 新分配）；SHA-256/JSON/ZIP 全部放到导出时（capture 专用线程/goroutine），不在热路径。
- ring 严格有界（字节+条数），超限丢最旧并置 `truncated=true`。
- `lastCanonicalFrame` 仅在捕获激活时保留一个不可变帧引用，不参与任何 revision/baseline/dirty/lease/mailbox/renderer 语义。
- capture 走独立 mux 逻辑通道 `webterm.capture.v1`，不混入 screen mailbox，不参与 baseline/revision/lease/resume/input-ack/renderer。

## 6. 传输方案选择：新增 mux 逻辑通道 `webterm.capture.v1`

理由（基于现有架构）：
1. mux 专为独立逻辑通道设计，仅需在 `MuxChannelRouter.OpenOwned`（mux_channel_router.go:33）加一个 path case + 一个 `LogicalChannelHandler`，无需改帧编码/PhysicalWriter/ChannelRegistry。
2. Relay 对 mux 帧**透明转发**（relaygateway/ws_gateway.go 只 pump 原始帧，不解析不存储正文），Direct/Relay 零改动即可路由到正确设备/会话（path 携带 sessionId，经 `Manager.Get` 解析）。满足 Direct/Relay 均能明确路由 + 不通过 Relay 持久化正文。
3. local IPC（~/.webterm/webterm.sock）是 host-local 控制面，无法到达远端 Android。
4. capture 通道是显式开启、短时间、有界的一次性诊断数据面，与已合法承载终端正文的 screen 通道同级，正文不进入结构化日志/诊断包，符合 redaction 不变量。

## 7. Build 隔离

- Go：build tag `webterm_capture`。`coordinator_enabled.go`（真实 ring+coordinator）/ `coordinator_disabled.go`（NOOP，Supported=false）。release（不带 tag）无 ring buffer 内存；热路径仅一次 `Enabled()`。测试用 `-tags webterm_capture`。
- Android：现有 same-FQCN-per-source-set 模式。真实实现在 `app/src/diagnostics`（debug+diag 共享），release stub 在 `app/src/release`（`isSupported()=false`）。菜单项经 `isSupported()` 门控。
