# Go PC Agent 权威终端状态与 Android 远程渲染实施计划

**状态：** Final Architecture Draft

**日期：** 2026-07-11

**范围：** `go-core/`、`android-client/`、终端会话线协议

**目标：** Go PC Agent 是唯一终端状态源；Android 只维护可丢弃的显示投影，负责终端画面、滚动浏览、选择复制和输入。

---

## 1. 最终结论

目标数据流固定为：

```text
PTY / Shell
    ↓ raw output
Go PC Agent
    ├── 唯一 ANSI / VT 解析器
    ├── 主屏 / 备用屏
    ├── 光标 / 模式 / 标题
    ├── 当前活动屏幕
    ├── 最多 10000 行 scrollback
    └── screen snapshot / patch / history page
                    ↓
Android RemoteTerminalModel
    ├── 当前屏幕投影
    ├── 有界历史页缓存
    └── instance / layout epoch / revision 校验
                    ↓
Android RemoteTerminalView
    ├── Canvas 绘制
    ├── 本地浏览历史
    ├── 选择 / 复制
    └── 键盘 / 粘贴 / 鼠标 / resize 输入
```

本项目尚未上线，不保留迁移兼容包袱。实现完成后 Android 只存在结构化屏幕链路；旧 Android ANSI 模拟链路、实验性的旧 screen 协议和中间 feature flag 全部删除。

硬性边界：

1. Android 新链路不解析 ANSI，不执行光标移动、清屏、插入删除、滚动区域等 VT 语义。
2. Go 决定每个 Cell 的字符、宽度、颜色、样式和最终位置。
3. Android 本地模型是投影和缓存，任何时候都可由 Go 的权威快照重建。
4. Android 用户浏览位置属于 UI 状态，不属于终端状态，必须与远端屏幕模型分离。
5. 历史不一次性下发全部 10000 行；首次下发最近窗口，向上滚动时按需分页。
6. Android 只有一个正式协议 `webterm.screen.v1`，直接按最终形态定义。
7. 屏幕协议从第一版开始使用共享 Protobuf，不采用临时 JSON 生产协议。

---

## 2. 当前仓库基线

### 2.1 Go 已有能力

- `go-core/internal/infrastructure/emulator/screen.go`
  - 使用 `github.com/danielgatis/go-headless-term`。
  - 已维护当前屏幕、光标、样式、scrollback、备用屏和终端模式。
  - 当前 scrollback 上限为 10000 行。
- `go-core/internal/session/snapshot_encoder.go`
  - 当前生产恢复协议把 Go 屏幕重新编码为 ANSI 状态流。
- `go-core/internal/session/client.go`
  - 已有实验性的 `webterm.screen.v1`。
  - `screen-state` 当前使用 headless-term `Snapshot("styled")`。
  - `screen-delta` 当前只发送 `row / col / char`。
- `go-core/internal/session/client_screen_test.go`
  - 已覆盖实验性 screen hello 和字符 delta 的最小测试。

### 2.2 Android 已有能力

- `TerminalSession.createExternalSession(...)` 已把本地 PTY 与远程 I/O 分开。
- `TerminalRuntimeRegistry` 已按逻辑终端维护运行时实例。
- `RemoteTerminalModel` 与 `TerminalSessionRuntimeRegistry` 已把恢复状态从 Fragment/View 生命周期中分离。
- `TerminalView` / `TerminalRenderer` 已具备：
  - Canvas 字符网格渲染；
  - 字体测量与宽字符缩放；
  - 光标、选择、颜色和样式；
  - fling、缩放、长按选择、复制粘贴；
  - IME、Ctrl、特殊键和鼠标事件。

### 2.3 当前缺口

实验性的 `webterm.screen.v1` 不能直接作为最终 Android 协议：

1. `Snapshot("styled")` 只包含当前活动屏幕，不包含可分页 scrollback。
2. snapshot 没有完整输出 `wrapped`、活动 buffer、终端 modes、历史范围和 layout epoch。
3. dirty delta 只有字符，没有颜色、样式、宽字符占位、光标和滚屏信息。
4. dirty cell 清空是全局行为，多个 screen client 会互相竞争同一批 dirty 状态。
5. 没有稳定历史行 ID，Android 无法在新输出到来时保持用户浏览锚点。
6. 没有历史分页、trim、缺包 resync 和 resize layout epoch 语义。
7. Android `TerminalView` 直接依赖 `TerminalEmulator` / `TerminalBuffer`，不能直接渲染远端模型。

现有实验性 `webterm.screen.v1` 没有外部兼容责任，应直接删除旧消息形状和实现，然后以相同协议名建立本计划定义的最终 Protobuf 协议。不新增 `screen.v2`，不保留旧 `screen.v1` 分支。

---

## 3. 范围与非目标

### 3.1 本计划包含

- Go 权威屏幕导出模型。
- 稳定历史行 ID 与分页 scrollback。
- snapshot、整行更新、滚动、历史分页、trim、resync、resize 协议。
- Android 纯 Java 远程终端模型。
- Android Canvas 远程终端渲染器。
- 本地滚动、按需历史加载、锚点保持、未读输出提示。
- 选择、复制、键盘、粘贴、鼠标和 resize。
- View detach 后模型继续接收状态。
- 删除 Android ANSI 解析、旧 `TerminalSession` 显示链路和相关屏幕缓存。
- Go 语义输入编码、布局租约和终端副作用事件。
- 共享 Protobuf schema 与 Go/Java 生成代码。
- Go、Android、端到端和性能测试。

### 3.2 第一版不包含

- 把 Go 画面渲染成位图传给 Android。
- Android 重新解释 ANSI 或重新实现 VT 状态机。
- 跨 resize 精确保留旧物理行选择范围。
- SIXEL / Kitty 图片完整显示；第一版只定义能力位和安全忽略策略。
- 在 Android 磁盘上持久化完整 Cell 网格；进程冷启动重新向 Go 请求快照。
- 为旧 Android 版本保留兼容、回退、feature flag 或双渲染链路。
- 把实验性旧 `webterm.screen.v1` 消息形状继续保留。

---

## 4. 核心不变量

实现和评审必须逐条守住以下不变量：

1. **Single Authority**：只有 Go emulator 能改变终端语义状态。
2. **Ordered Apply**：Android 只应用 `baseRevision == localRevision` 且 instance/layout epoch 相同的 patch。
3. **Authoritative Snapshot**：snapshot 原子替换 Android 屏幕和历史窗口。
4. **No Guessing**：Android 遇到缺包、未知 style、越界 row、instance/layout epoch 不一致时请求 resync，不猜状态。
5. **Stable History Anchor**：用户浏览历史时使用 `lineId` 锚定，不能用会随新输出变化的数组下标。
6. **Bounded Client Memory**：Android 历史缓存有硬上限，Go 仍是完整可用历史的拥有者。
7. **Detach Is Not Close**：View detach 不关闭 channel、不清模型、不停止接收更新。
8. **Resize Is a Generation Change**：会触发 reflow 的 resize 必须切换 layout epoch 并发送完整 snapshot。
9. **Single Scrollback**：Go headless terminal 使用的 scrollback provider 就是分页历史源，不维护第二份历史副本。
10. **Per-client Projection**：每个 screen client 独立维护发送基线，不能依赖全局 ClearDirty 结果。
11. **Explicit Layout Ownership**：同一 PTY 只有一个布局控制者，只有持有 layout lease 的客户端可以 resize。
12. **Semantic Side Effects**：铃声、标题、剪贴板、通知等由 Go 解析并发送语义事件，不能随 Android ANSI 解析器一起丢失。
13. **Bounded Decode**：所有线协议字段在分配内存前校验大小、数量和范围。

---

## 5. 目标模块与文件结构

### 5.1 Go

```text
shared/proto/
    terminal_screen.proto                # 唯一 screen wire schema

go-core/internal/terminalengine/
    engine.go                            # headless terminal、模式和副作用
    tracked_scrollback.go                # 唯一 scrollback provider
    export.go                            # canonical frame 导出
    input_encoder.go                     # 语义输入按权威 modes 编码
    engine_test.go

go-core/internal/terminalsession/
    runtime.go                           # PTY + engine 单 actor 生命周期
    layout_lease.go                      # resize 所有权
    runtime_test.go

go-core/internal/screenprojection/
    projector.go                         # per-client snapshot/patch 基线
    history.go                           # 分页查询视图
    backpressure.go                      # 合并、快照替代和慢客户端
    projector_test.go

go-core/internal/screenprotocol/
    generated/                           # Protobuf Go 生成代码
    handler.go                           # hello/input/history/resize/event 路由
    validation.go                        # 上下行边界校验
    handler_test.go

go-core/internal/session/
    terminal.go                          # 迁移后只保留会话入口或并入 terminalsession
    client.go                            # 拆除 ClientModeScreen 分支式大类
```

`terminalengine` 是唯一终端领域层；`terminalsession` 负责进程和串行调度；`screenprojection` 只生成客户端视图；`screenprotocol` 只处理 wire DTO。领域模型不能依赖 Protobuf 生成类型。

`go-headless-term` 已支持自定义 `ScrollbackProvider`，因此使用 `TrackedScrollback` 直接替代 `MemoryScrollback`。当前 provider 缺少 wrapped 元数据，需要维护最小 fork，把 scrollback 行从 `[]Cell` 提升为包含 `Cells + Wrapped` 的值对象。不得再额外复制一份独立历史 ring。

### 5.2 Android

最终模块：

```text
android-client/terminal-model/
    build.gradle.kts
    src/main/java/com/webterm/terminal/model/
        TerminalColor.java
        TerminalStyle.java
        TerminalCell.java
        TerminalLine.java
        TerminalCursor.java
        TerminalModes.java
        ScreenSnapshot.java
        ScreenPatch.java
        HistoryPage.java
        RemoteTerminalModel.java
        TerminalViewportState.java
        ModelChange.java

android-client/terminal-protocol/
    build.gradle.kts
    # Gradle protobuf sourceSet 直接读取 ../../shared/proto，禁止复制 schema
    src/main/java/com/webterm/terminal/protocol/
        ScreenMessageValidator.java
        ScreenMessageMapper.java
```

渲染层：

```text
android-client/terminal-renderer/
    src/main/java/com/webterm/terminal/renderer/
        RemoteTerminalView.java
        RemoteTerminalRenderer.java
        TerminalGestureController.java
        TerminalSelectionController.java
        TerminalInputConnection.java
        TerminalViewportController.java
```

运行时和业务接入：

```text
android-client/feature/terminal/src/main/java/com/webterm/feature/terminal/domain/
    TerminalSessionRuntime.java            # 无 Activity/View；连接+模型+协议
    TerminalSessionRuntimeRegistry.java    # Application/会话级
    TerminalScreenController.java          # 页面级；attach/detach/输入/UI
    TerminalConnection.java                # 字节通道，不承担绘制
```

依赖方向固定：

```text
terminal-renderer → terminal-model
terminal-protocol → terminal-model
feature-terminal  → protocol + model + renderer + core-session
```

禁止 `terminal-model` 依赖 Android、网络或 Protobuf；禁止 renderer 直接依赖 mux/session。新 renderer 验收后，删除旧 `terminal-emulator`、旧 `terminal-view` 和 Android ANSI 状态缓存，不保留 Legacy Adapter。

---

## 6. Go 权威数据模型

### 6.1 传输无关模型

禁止直接把第三方 headless-term 的 struct 当成公开线协议。新增内部稳定模型：

```go
type ScreenFrame struct {
    Version       int
    SessionID     string
    InstanceID    string
    Epoch         uint64
    Seq           uint64
    Rows          int
    Cols          int
    ActiveBuffer  BufferKind
    ReverseVideo  bool
    DefaultFG     Color
    DefaultBG     Color
    CursorColor   Color
    Cursor        Cursor
    Modes         Modes
    History       HistoryWindow
    Screen        []Line
}

type Line struct {
    ID      uint64 // 历史行使用；活动屏幕行为 0
    Wrapped bool
    Runs    []CellRun
}

type CellRun struct {
    Col   int
    Cells []Cell
}

type Cell struct {
    Text           string
    Width          uint8 // 0 spacer, 1 single, 2 wide
    FG             Color
    BG             Color
    UnderlineColor Color
    Attrs          CellAttrs
    Hyperlink      *Hyperlink
}
```

颜色必须保留语义类型，不能只输出已解析 RGB：

```go
type Color struct {
    Kind  string // default-fg, default-bg, indexed, rgb
    Index int
    RGB   uint32
}
```

这样 Android 既可严格显示 ANSI indexed/RGB，也可继续使用自己的默认终端前景和背景主题。

### 6.2 字符宽度与组合字符

- Go 输出 `Width`，Android 不再运行自己的 `wcwidth` 决策。
- 双宽字符：起始 Cell `Width=2`，后续 spacer `Width=0`。
- 组合字符必须与基础字符合并为同一个 `Text`；底层库当前若只存 rune，必须先补充字符簇保真能力，不能把它留作切流后的已知缺口。
- Android 只按 `column * cellWidth` 定位，不根据 `Paint.measureText()` 重新决定列数。

### 6.3 唯一 scrollback provider 与历史行 ID

Go 不新增独立历史副本。直接把可跟踪 provider 注入 headless terminal：

```go
type HistoryLine struct {
    ID      uint64
    Wrapped bool
    Cells   []Cell
}

type TrackedScrollback struct {
    layoutEpoch uint64
    firstID     uint64
    nextID      uint64
    capacity    int // 10000
    lines       Ring[HistoryLine]
}

terminal := headlessterm.New(
    headlessterm.WithScrollback(trackedScrollback),
)
```

规则：

1. headless terminal 调用 `Push` 的那一刻就是历史追加的唯一事实来源。
2. ID 在同一 layout epoch 内永不复用；相同文本的重复行仍有不同 ID。
3. 行进入历史后内容不可原地修改。
4. 超过上限时 provider 丢弃最老行并推进 `firstID`，同时记录 trim 事件。
5. resize 导致 reflow 时 `layoutEpoch++`，旧物理行 ID 全部失效。
6. alternate screen 使用 no-op scrollback，不写入主屏历史。
7. 分页直接读取该 provider，不再复制到 `TerminalSession` 的第二个 ring。

当前第三方 `ScrollbackProvider.Push([]Cell)` 无法携带 `Wrapped`。实现前必须完成最小 fork，把 provider API 升级为 `Push(ScrollbackLine)`，并确保 Pop/reflow 同样保留 wrapped。这个改动是 Task 1 的阻断门，不能用字符串 diff 或 hash 猜测替代。

### 6.4 状态身份与修订号

不复用现有 raw output ring 的 `seq`。screen 协议定义三个正交标识：

```go
type ScreenVersion struct {
    InstanceID     string // 一次 PTY 进程实例
    LayoutEpoch    uint64 // resize/reflow/模型重建时递增
    ScreenRevision uint64 // 每次可观察屏幕状态变化时递增
}
```

- `InstanceID` 变化：Android 丢弃该 session 的全部投影和历史。
- `LayoutEpoch` 变化：Android 丢弃旧活动屏幕、历史页、style/link 字典和选择。
- `ScreenRevision`：每次权威屏幕状态变化时递增；patch 使用 `baseRevision → revision` 严格衔接。
- `outputSeq`：只属于原始字节流 projection，不进入 screen 协议。

### 6.5 单 actor 写模型

每个 PTY 对应一个 `TerminalSessionRuntime` actor。以下事件全部进入同一队列：

```text
PTY output
semantic input
resize/layout lease
client attach/detach
history request
terminal provider side effect
```

actor 内顺序：

```text
读取事件
→ 更新 PTY/headless engine
→ 记录 scrollback append/trim 和 side effects
→ screenRevision++
→ 导出一次不可变 canonical frame
→ 各client projector生成自己的patch/snapshot
```

这样替代当前 `TerminalSession.mu + Screen.mu + callback` 的嵌套锁组合。provider 回调只能把不可变事件交回 actor，不能反向获取 session 锁。

### 6.6 底层库能力审计

实现前必须确认或补齐：

- scrollback append/pop/trim 与 wrapped；
- active buffer 切换；
- cursor、cursor style 和 blink；
- reverse video；
- application cursor/keypad、modifyOtherKeys/keyboard protocol；
- bracketed paste；
- mouse tracking 和 mouse encoding；
- focus reporting；
- palette/default/cursor color 语义；
- 字符簇、组合字符、variation selector 和 ZWJ emoji；
- bell、title、working directory、clipboard、notification 和 shell integration provider。

任何影响屏幕、输入编码或用户可见副作用的能力缺失，都必须先修 terminal engine，再开发 Android 绘制。不能让 Android 猜测或补偿。

### 6.7 终端副作用

Go 除了输出 screen frame，还产生有序语义事件：

```go
type TerminalEffect interface {
    Bell | TitleChanged | WorkingDirectoryChanged |
    ClipboardRead | ClipboardWrite | DesktopNotification |
    PaletteChanged | ShellIntegrationMark
}
```

effect 与 screen revision 在同一 actor 中排序。设备查询响应（DA/DSR等）由 Go emulator 直接写回 PTY，不经过 Android。

---

## 7. `webterm.screen.v1` 最终协议

### 7.1 协商

唯一正式 Android screen subprotocol：

```text
webterm.screen.v1
```

旧实验性 v1 的 JSON `screen-state/screen-delta` 实现和测试直接删除。生产消息使用 `shared/proto/terminal_screen.proto` 生成的 Protobuf。WebSocket 自带消息边界，每个 binary WebSocket payload 对应一个 `ScreenEnvelope`，不再添加长度前缀。

```protobuf
message ScreenEnvelope {
  uint32 protocol_version = 1;
  oneof payload {
    Hello hello = 10;
    ScreenSnapshot snapshot = 11;
    ScreenPatch patch = 12;
    HistoryRequest history_request = 13;
    HistoryPage history_page = 14;
    HistoryTrim history_trim = 15;
    ResyncRequest resync = 16;
    AcquireLayout acquire_layout = 17;
    LayoutLease layout_lease = 18;
    ReleaseLayout release_layout = 19;
    Resize resize = 20;
    TerminalInput input = 21;
    TerminalEffect effect = 22;
    ClipboardResponse clipboard_response = 23;
    TerminalInfo info = 24;
    Exit exit = 25;
    Ping ping = 26;
    Pong pong = 27;
  }
}
```

本节 JSON 片段仅作为 Protobuf 内容的可读 debug 表示，不是生产编码格式。

### 7.2 Hello

```json
{
  "type": "hello",
  "version": 1,
  "cols": 120,
  "rows": 40,
  "instanceId": "",
  "layoutEpoch": 0,
  "screenRevision": 0,
  "hasProjection": false,
  "history": {
    "tailLines": 300,
    "pageSize": 250
  },
  "capabilities": {
    "rowPatches": true,
    "scrollOps": false,
    "images": false
  }
}
```

冷启动或没有本地完整 projection 时必须发送 `hasProjection=false`，服务端返回 snapshot。只有 Android 内存中仍持有相同 `instanceId/layoutEpoch` 的完整模型时，才允许携带 `screenRevision` 请求增量恢复。

### 7.3 Screen snapshot

snapshot 是“可独立显示的权威状态”，不是图片，也不是全部 10000 行历史：

```json
{
  "type": "screen-snapshot",
  "version": 1,
  "sessionId": "s1",
  "instanceId": "i1",
  "layoutEpoch": 8,
  "screenRevision": 1500,
  "geometry": {"rows": 40, "cols": 120},
  "activeBuffer": "main",
  "cursor": {
    "row": 12,
    "col": 25,
    "visible": true,
    "shape": "block",
    "blink": true
  },
  "modes": {
    "applicationCursor": false,
    "applicationKeypad": false,
    "bracketedPaste": true,
    "mouseTracking": "none",
    "mouseEncoding": "sgr",
    "focusReporting": false
  },
  "history": {
    "firstAvailableLineId": 5000,
    "firstIncludedLineId": 9700,
    "lastIncludedLineId": 9999,
    "hasMoreBefore": true,
    "lines": []
  },
  "screen": {"lines": []}
}
```

默认策略：

- 首次快照包含当前活动屏幕和最近 300 行主屏历史。
- alternate screen 快照不附带新的 alternate scrollback；客户端保留但隐藏主屏历史缓存。
- snapshot 原子替换同 instance/layout epoch 旧模型；新 instance 或 layout epoch 必须清除旧屏幕、历史、字典和选择。

### 7.4 实时 row patch

第一版使用“整行替换”，不发送 VT 操作：

```json
{
  "type": "screen-patch",
  "layoutEpoch": 8,
  "baseRevision": 1500,
  "screenRevision": 1501,
  "historyAppend": [
    {"id": 10000, "wrapped": false, "runs": []}
  ],
  "screenRows": [
    {"row": 38, "wrapped": false, "runs": []},
    {"row": 39, "wrapped": false, "runs": []}
  ],
  "cursor": {"row": 39, "col": 4, "visible": true, "shape": "block"},
  "modes": null,
  "title": null
}
```

规则：

1. `baseRevision` 必须等于 Android 当前 `screenRevision`。
2. patch 中包含所有受本次输出影响的最终行，不要求 Android 执行清除或位移语义。
3. `historyAppend` 中的行必须使用连续稳定 ID。
4. 光标只要变化就随 patch 下发。
5. 模式、title、palette 只在变化时下发。
6. 如果变化行数超过阈值，例如活动屏幕的 60%，直接发送 snapshot。

### 7.5 可选 scroll 优化

整行替换达到性能目标前不实现 scroll op。只有 profiling 证明 row patch 是瓶颈时，才允许在同一个 v1 schema 中启用可选 `scrollOps` capability：

```json
{
  "type": "screen-scroll",
  "layoutEpoch": 8,
  "baseRevision": 1501,
  "screenRevision": 1502,
  "region": {"top": 0, "bottom": 39},
  "delta": -1,
  "historyAppend": [],
  "replacementRows": []
}
```

这只是传输压缩：滚动区域、方向和替换行全部由 Go 决定。Android 不得根据终端内容自行触发 scroll。

首版 capability 固定为 `scrollOps=false`。

### 7.6 历史分页

请求：

```json
{
  "type": "history-request",
  "requestId": "h-17",
  "layoutEpoch": 8,
  "beforeLineId": 9700,
  "limit": 250
}
```

响应：

```json
{
  "type": "history-page",
  "requestId": "h-17",
  "layoutEpoch": 8,
  "asOfRevision": 1500,
  "firstAvailableLineId": 5000,
  "hasMoreBefore": true,
  "lines": []
}
```

规则：

- 页必须按 lineId 升序返回。
- `limit` 服务端限制在 1..500。
- 重复 requestId 必须幂等或返回相同范围。
- Android 可忽略已经缓存的重复行。
- instance/layout epoch 变化后到达的旧页直接丢弃。
- `asOfRevision` 只用于诊断并发 append/trim；稳定 lineId 是合并依据。
- 用户距离本地缓存顶部 50 行时预取上一页。

### 7.7 History trim

Go 丢弃最老历史时发送：

```json
{
  "type": "history-trim",
  "layoutEpoch": 8,
  "firstAvailableLineId": 5200
}
```

Android 删除所有 `lineId < 5200` 的缓存。如果用户锚点已被删除，则移动到 5200，并显示一次“更早历史已清理”的非阻断提示。

### 7.8 Resync

Android 在以下情况发送：

```json
{
  "type": "screen-resync",
  "layoutEpoch": 8,
  "screenRevision": 1500,
  "reason": "sequence-gap"
}
```

触发条件：

- `baseRevision != localRevision`；
- instance/layout epoch 不一致；
- 行索引越界；
- history ID 不连续；
- 未知必需字段；
- 模型 reducer 抛出校验错误。

服务端收到后返回完整 snapshot，不发送猜测性的补丁。

### 7.9 布局租约与 Resize

一个 PTY 只有一套 rows/cols。客户端必须先获得 layout lease，observer 无权 resize：

```text
客户端 attach
→ AcquireLayout(interactive=true)
→ Go 根据当前 owner/活跃时间授予 LayoutLease
→ 只有 leaseId 匹配的 Resize 才生效
→ controller detach/超时/显式释放后可转移 lease
```

同一用户多个客户端同时打开时，当前交互客户端是 controller，其余是 observer。observer 按 Go 当前 geometry 显示，不得用本地 View 尺寸反复争抢 PTY。

observer 的 View 必须按服务端 rows/cols 计算可见区域：默认保持配置字体并允许裁切/滚动；用户开始交互时先申请 lease，获批后再按本机尺寸 resize。Web raw projection 如保留，也必须接入同一 layout lease 控制面，不能绕过 lease 直接调用 `TerminalSession.Resize()`。

```json
{
  "type": "resize",
  "cols": 80,
  "rows": 24,
  "leaseId": "layout-12",
  "requestId": "r-9"
}
```

顺序固定为：

```text
Android 计算新 geometry
→ 发送 resize
→ Go resize PTY
→ Go resize emulator / reflow
→ Go layoutEpoch++
→ 重置style/link字典和所有client baseline
→ Go 返回新 layout epoch 的 screen-snapshot
→ Android 原子替换模型
```

等待新 snapshot 时 Android 可继续绘制旧帧，但显示尺寸变化遮罩；不得在本地对旧历史做终端 reflow。

### 7.10 语义输入

Android 不根据可能滞后的 modes 生成特殊 ESC 序列。它发送语义输入，Go actor 根据当下权威 modes 编码并写入 PTY：

```protobuf
message TerminalInput {
  string layout_lease_id = 1;
  oneof input {
    TextInput text = 10;
    KeyInput key = 11;
    PasteInput paste = 12;
    MouseInput mouse = 13;
    FocusInput focus = 14;
  }
}
```

- 普通 IME 文本保留原始 UTF-8。
- KeyInput 传逻辑键、修饰键、按下/释放。
- PasteInput 由 Go 根据 bracketed-paste mode 包装。
- MouseInput 传 Cell 坐标、按钮、滚轮和修饰键，由 Go 根据 tracking/encoding mode 编码。
- FocusInput 由 Go 根据 focus-reporting mode 决定是否写入 PTY。
- observer 默认不能发送输入；只有 interactive layout lease owner 可以输入。

### 7.11 终端副作用事件

```protobuf
message TerminalEffect {
  string instance_id = 1;
  uint64 screen_revision = 2;
  oneof effect {
    Bell bell = 10;
    TitleChanged title = 11;
    WorkingDirectoryChanged cwd = 12;
    ClipboardReadRequest clipboard_read = 13;
    ClipboardWriteRequest clipboard_write = 14;
    DesktopNotification notification = 15;
    PaletteChanged palette = 16;
    ShellIntegrationMark shell_mark = 17;
  }
}
```

安全策略：

- OSC 52 读取 Android 剪贴板默认禁止。
- OSC 52 写入需要用户设置授权，后台 observer 不执行。
- 剪贴板请求只发给当前 interactive controller，并带 requestId/超时；Android通过 `ClipboardResponse` 明确返回允许、拒绝、超时或有界内容。
- 通知、URI、标题和cwd在Go与Android两端都做长度限制和控制字符清理。
- OSC 8 hyperlink 点击前经过 URI scheme allowlist；默认只允许 `http/https` 并交由用户确认。

### 7.12 Style/Hyperlink 字典与稀疏行

每个 layout epoch 维护只增不改的字典：

```text
styleId → TerminalStyle
linkId  → Hyperlink
```

snapshot携带完整字典；patch可追加 `newStyles/newLinks`，不得重定义旧ID。行只发送非默认内容和必要背景 run，未覆盖列解释为默认空白 Cell。尾部默认空白不得逐 Cell 传输。

行编码必须保留每个 glyph 的 `text + width + styleId + linkId`。`width=0` 是宽字符 spacer，不能被省略成普通默认空格。

### 7.13 线协议资源限制

Go 发送前和 Android 分配内存前同时校验：

```text
rows                   5..200
cols                  10..500
history page           1..500 行
snapshot history       <=500 行
ScreenEnvelope         <=2 MiB
title/cwd              <=4 KiB
hyperlink URI          <=8 KiB
clipboard payload      <=1 MiB
notification payload   <=64 KiB
styles per layoutEpoch <=4096
links per layoutEpoch  <=4096
cell text              <=64 UTF-8 bytes
```

非法 UTF-8、未知必需枚举、越界 run、重叠宽字符、过量字典和超大消息直接拒绝并记录 reason；不得尝试部分应用。

服务端生成 snapshot/history page 时必须先计算编码大小：超过 2 MiB 就减少附带历史行数或缩小分页结果，当前活动屏幕本身仍超限时返回明确错误并断开，不能截断一条 Line/Cell 形成半状态。

---

## 8. Android 本地模型

### 8.1 模型与 viewport 分离

```java
final class RemoteTerminalModel {
    String instanceId;
    long layoutEpoch;
    long screenRevision;
    int rows;
    int columns;
    BufferKind activeBuffer;

    NavigableMap<Long, TerminalLine> historyCache;
    TerminalLine[] screen;

    long firstAvailableHistoryId;
    boolean hasMoreHistoryBefore;
    TerminalCursor cursor;
    TerminalModes modes;
    TerminalPalette palette;
    StyleTable styles;
    HyperlinkTable hyperlinks;
}

final class TerminalViewportState {
    boolean followTail;
    Long anchorHistoryLineId;
    int anchorPixelOffset;
    SelectionRange selection;
    int unreadLineCount;
    boolean loadingOlderHistory;
}
```

`RemoteTerminalModel` 跟随 Go；`TerminalViewportState` 跟随用户。二者不能合并。

### 8.2 Android 行与 Cell

```java
final class TerminalLine {
    LineKey key;
    boolean wrapped;
    TerminalCell[] cells;
    volatile RenderRun[] renderRuns;
}

final class TerminalCell {
    String text;
    byte width;
    int styleId;
    Hyperlink hyperlink;
}
```

内存规则：

- 行和 Cell 对 View 暴露为不可变对象。
- style 使用 intern pool，Cell 只保存 `styleId`。
- 默认空白 Cell 可共享，不为每个空格创建独立对象。
- render runs 惰性生成，行替换时清除缓存。
- snapshot/patch 在 model executor 解码，在主线程一次性发布变更。

### 8.3 Model reducer API

```java
ModelChange applySnapshot(ScreenSnapshot snapshot);
ModelChange applyPatch(ScreenPatch patch) throws RevisionGapException;
ModelChange prependHistoryPage(HistoryPage page);
ModelChange trimHistory(long firstAvailableLineId);
void resetForReconnect();
```

`ModelChange` 至少包含：

```java
final class ModelChange {
    boolean fullInvalidate;
    IntSet changedScreenRows;
    boolean historyChanged;
    boolean cursorChanged;
    boolean modesChanged;
}
```

### 8.4 本地历史缓存策略

默认参数：

```text
snapshot tail history   300 行
history page size       250 行
prefetch threshold       50 行
Android soft limit     1500 行
Android hard limit     3000 行
Go authoritative limit 10000 行
```

淘汰策略：

1. `followTail=true` 时优先淘汰最老 Android 页。
2. 用户浏览历史时保留 anchor 前后页，淘汰距离 anchor 最远的页。
3. 被 Android 淘汰但 Go 仍保留的页面可以重新加载。
4. 选择范围跨入未加载页时先加载，再扩展选择。
5. 达到 hard limit 且无法安全淘汰时停止扩大选择并提示。

### 8.5 View detach

`RemoteTerminalModel` 归无 Activity 的 `TerminalSessionRuntime` 所有，不归 Fragment/View 所有：

```text
TerminalSessionRuntime → Application/会话级，持有连接和模型
TerminalScreenController → 页面级，持有View和viewport

View attach   → controller注册model listener，渲染当前模型
View detach   → controller移除listener，runtime和连接继续运行
重新 attach   → 新controller立即渲染最新模型
close session → registry关闭runtime并销毁模型
```

冷启动只恢复 session/server identity 和用户设置；删除旧 ANSI state/output、缓存 `TerminalSession` 和本地 emulator 屏幕文件。hello发送 `hasProjection=false`，直接请求 Go snapshot，不能携带一个本地并不存在的 last revision 请求 patch。

### 8.6 主屏、备用屏和选择

- Android 永远只维护当前 active buffer 的屏幕投影；main/alternate 切换强制 snapshot。
- 已加载的主屏 history cache 可以在 alternate 期间保留但不可见。
- selection 使用 `HistoryLineKey(lineId)` 或 `ScreenLineKey(layoutEpoch,row)`。
- 活动行滚入历史时，patch携带 `promotedFromScreenRow → historyLineId`，Android同步迁移选择锚点。
- layout epoch变化时取消选择，不尝试跨reflow猜测位置。
- history trim删除选择锚点时结束选择并给出非阻断提示。

---

## 9. Android 渲染器

### 9.1 不使用 RecyclerView

终端需要高频局部更新、固定 Cell 坐标、光标、选择和连续手势。目标实现继续使用自定义 `View + Canvas`，不采用一行一个 View 或 RecyclerView。

### 9.2 建立 WebTerm 自有 renderer

新建 `terminal-renderer` 模块，namespace 使用 `com.webterm.terminal.renderer`，不继续扩展 `com.termux.view`。可以迁移现有 TerminalRenderer 中经过验证的字体测量、glyph aspect、Canvas run绘制、手势和选择算法，并保留许可证/来源说明，但新代码只读取 `terminal-model`：

```java
final class RemoteTerminalRenderer {
    void render(Canvas canvas,
                RemoteTerminalModel model,
                TerminalViewportState viewport,
                SelectionRange selection);
}
```

不建立 `LegacyTerminalRenderSource`。旧 Termux View 只作为测试对照和算法参考；新 renderer 达到验收门槛后直接替换并删除旧模块依赖。

交互职责进一步拆分：

```text
RemoteTerminalView          Canvas和Android View生命周期
TerminalViewportController follow-tail、锚点、fling、分页触发
TerminalSelectionController 选择和复制
TerminalGestureController  touch/mouse/scale
TerminalInputConnection    IME composing和语义输入
RemoteTerminalRenderer     纯绘制
```

### 9.3 绘制顺序

每帧固定：

```text
1. 默认背景
2. 每个 RenderRun 的非默认背景
3. 选择区域背景
4. 字符 / 字符簇
5. 下划线、双下划线、波浪线、删除线
6. 光标
7. 新输出提示或加载提示等 UI overlay
```

### 9.4 字符定位

```java
float x = column * cellWidth;
float baseline = row * lineHeight + baselineOffset;
```

- `width=2` 占两列，只绘制一次。
- `width=0` spacer 不绘制。
- Android 可按两列物理宽度缩放 glyph，但不能改变 Go 指定的 Cell 宽度。
- 组合字符与基础字符作为一个 `text` 绘制。

### 9.5 批量 run 绘制

相邻且样式一致的 Cell 合并为 `RenderRun`，减少 `drawText()` 和 Paint 状态切换。run 边界至少由以下因素决定：

- styleId；
- cursor 覆盖；
- selection 覆盖；
- 宽字符；
- hyperlink；
- 需要保持 glyph aspect 的字符。

### 9.6 局部 invalidate

- 普通 row patch：只 invalidate 变化行矩形和新旧光标行。
- history append 且用户不在尾部：不强制滚动，只更新 scrollbar/unread。
- snapshot、resize、字体、缩放、buffer 切换：全屏 invalidate。
- View 自己的像素滚动：全屏重绘，但不改变 model。

### 9.7 光标

Go决定位置、可见性、形状和是否 blink；Android只负责闪烁动画。新状态或输入到来后重置闪烁计时，不回写终端状态。

---

## 10. 滚动与按需历史

### 10.1 三种滚动严格分开

| 类型 | 所有者 | 行为 |
|---|---|---|
| PTY 输出推动屏幕滚动 | Go | 更新屏幕、追加主屏历史、发 patch |
| 用户手指浏览 scrollback | Android viewport | 本地移动锚点，必要时分页请求 |
| 应用鼠标模式中的滚轮 | Android 输入 → Go | 发送鼠标/按键输入，不移动本地历史 |

### 10.2 Follow tail

```text
用户位于底部：followTail=true
新输出：更新屏幕并保持底部

用户向上滚动：followTail=false
新输出：模型继续更新，anchorLineId 不变，unreadLineCount++

用户回到底部或点击“最新输出”：followTail=true，unreadLineCount=0
```

### 10.3 锚点保持

加载旧页前后，屏幕顶部保持：

```text
anchorHistoryLineId + anchorPixelOffset
```

不得使用“插入 N 行后 topRow += N”作为唯一逻辑，因为并发 history append/trim 会使数组索引不稳定。

### 10.4 Alternate screen

- 进入 alternate screen 时隐藏主屏 history viewport，但保留已加载主屏缓存。
- alternate 内部滚动不追加主屏历史。
- mouse tracking 开启时，滚动手势编码为远端鼠标事件。
- 未开启 mouse tracking 时，第一版不提供 alternate 本地历史；可按现有交互转换为方向键或忽略。
- 退出 alternate 后 Go 强制发送 snapshot 恢复主屏。

---

## 11. 输入、模式和 resize

### 11.1 输入交互复用与语义化

从现有 `TerminalView` 复用：

- IME InputConnection；
- Ctrl/Alt/Shift/Fn；
- 特殊键映射；
- 快捷栏；
- 粘贴；
- 鼠标点击和滚轮；
- 焦点事件。

复用的是 Android 交互采集，不复用本地终端模式判断。`TerminalInputConnection` 把 IME/按键/粘贴/鼠标转换成语义消息；Go actor读取当下权威 modes，生成最终PTY字节。

### 11.2 Bracketed paste

```text
Android → PasteInput(原始文本)
Go modes.bracketedPaste=false → 直接写UTF-8
Go modes.bracketedPaste=true  → ESC[200~ + UTF-8 + ESC[201~
```

### 11.3 Resize 去抖

键盘动画和旋转可能连续产生多个尺寸。持有 layout lease 的 Android controller 使用约 100ms trailing debounce，只发送最终 rows/cols；observer不发送resize，尺寸与上次相同不发送。

resize 等待期间：

- 不自行 reflow；
- 暂存同旧 layout epoch 到达的 patch，但新 snapshot 到达后丢弃未应用旧 patch；
- 超过 2 秒未收到新 snapshot，发送 resync；
- 超过重试上限展示重连，并重新申请layout lease；不存在旧协议回退。

---

## 12. 线程与背压

### 12.1 Go

- 每个 `TerminalSessionRuntime` 的 PTY output、input、resize、screen export 和 layout epoch 更新必须按确定顺序串行。
- 不允许在一次 frame 导出期间获得混合的旧 geometry 和新 Cell。
- 每个 client 独立保存最后发送的 frame 基线。
- 高频输出按 16ms 左右窗口合并 row patch；交互式少量输出可立即 flush。
- client send queue 满时不无限堆积 patch：丢弃未发送 patch 基线，排队一份最新 snapshot，必要时断开慢客户端。

### 12.2 Android

- 网络回调只负责解码 envelope 和投递 model executor。
- model reducer 单线程按 instance/layout epoch/revision 应用。
- 主线程只接收不可变状态引用和 `ModelChange`。
- 每帧最多安排一次 invalidate，合并同一 UI frame 内多个更新。
- history page 解码不能阻塞主线程。

---

## 13. 分阶段实施任务

### Task 0：冻结最终协议与跨语言 fixture

- [ ] 新建 `shared/proto/terminal_screen.proto`，定义全部 envelope、字典、行、输入、副作用和layout lease。
- [ ] 固定 instance/layout/revision 语义、资源限制和非法消息行为。
- [ ] 删除实验性 JSON `screen-state/screen-delta` 契约说明，避免两种 v1 并存。
- [ ] 建立跨语言 fixture：普通文本、全部SGR、宽字符、组合字符、emoji/ZWJ、软换行、主屏滚动、alternate、mouse、resize、clipboard、bell、title。
- [ ] 每个 fixture 保存 `input.ansi`、`expected.pb` 和人类可读 `expected-debug.json`。

**完成标准：** Go和Java生成代码可编译，同一个 `expected.pb` 能被两端读取，协议审查不再有未定字段。

### Task 1：改造 headless-term 与唯一 TrackedScrollback

- [ ] 审计 Cell、grapheme、mode、provider 和buffer API。
- [ ] 最小fork：scrollback provider携带 Cells+Wrapped，并保留Pop/reflow语义。
- [ ] 实现 `TrackedScrollback`，直接注入headless terminal。
- [ ] 实现稳定lineId、10000行trim和分页。
- [ ] 增加重复空行、相同行、满容量trim、alternate隔离和reflow测试。
- [ ] 补齐bell/title/cwd/clipboard/notification等provider。

**阻断门：** wrapped、字符簇或scrollback事件不可靠时不得进入协议和Android实现。

### Task 2：建立 Go TerminalSessionRuntime actor

- [ ] 把PTY output、input、resize、provider effect和client操作串行化。
- [ ] 明确 `InstanceID/LayoutEpoch/ScreenRevision` 生命周期。
- [ ] 去除 screen/session 嵌套锁和回调反向加锁。
- [ ] 实现layout lease申请、转移、超时和observer。
- [ ] 实现语义输入编码器并覆盖application cursor/keypad、paste、mouse和focus。
- [ ] 保留Web raw projection所需outputSeq，但与screen revision彻底分离。

### Task 3：实现 canonical frame 与 per-client projection

- [ ] 稳定 `ScreenFrame/Line/Cell/Color/Modes/Effect` 领域类型。
- [ ] 导出active buffer、cursor、palette、style/link字典和稀疏行。
- [ ] 实现整行patch、historyAppend/promoted-row映射、historyTrim和snapshot阈值。
- [ ] 每个client独立baseline，不调用全局ClearDirty。
- [ ] 16ms合并、慢客户端snapshot替代和队列背压。
- [ ] main/alternate切换强制snapshot。

### Task 4：实现 `webterm.screen.v1` Protobuf handler

- [ ] 删除旧实验性screen v1 JSON代码和测试。
- [ ] 接入Protobuf binary subprotocol。
- [ ] 实现hello/snapshot/patch/history/resync/layout/resize/input/effect/info/exit/ping。
- [ ] 所有消息先做资源和结构校验。
- [ ] Relay mux 使用唯一的 screen handler，不复制协议逻辑。
- [ ] 建立恶意/超大/越界消息测试。

### Task 5：建立 Android terminal-model 与 terminal-protocol

- [ ] 新建纯Java `terminal-model`。
- [ ] 新建Protobuf `terminal-protocol`和wire→domain mapper。
- [ ] 实现snapshot原子替换、patch严格revision、history分页/trim和resync。
- [ ] 实现style/link intern table、稀疏行展开和内存上限。
- [ ] 实现main/alternate切换、promoted-row选择映射和layout epoch清理。
- [ ] 使用Go生成的同一套fixture验证canonical model。

### Task 6：建立无Activity的 Session Runtime

- [ ] `TerminalSessionRuntime`只持有channel、codec、model和model executor。
- [ ] `TerminalSessionRuntimeRegistry`提升为Application/会话级。
- [ ] `TerminalScreenController`持有Activity/View/viewport并负责attach/detach。
- [ ] View销毁后runtime继续接收patch/history/effect。
- [ ] 多session各自拥有instance/layout/revision/model；viewport按页面独立。
- [ ] 冷启动发送`hasProjection=false`并请求snapshot。

### Task 7：建立 WebTerm 自有 terminal-renderer

- [ ] 新建 `com.webterm.terminal.renderer` 模块。
- [ ] 迁移并注明现有Termux绘制、字体、glyph aspect、手势和选择算法来源。
- [ ] 实现RemoteTerminalView、Renderer、Viewport、Gesture、Selection和InputConnection。
- [ ] 实现style run、宽字符、字符簇、cursor、selection和局部invalidate。
- [ ] 不建立Legacy Adapter，不让renderer依赖terminal-emulator或网络。
- [ ] 建立截图/golden和60fps性能测试。

### Task 8：完整滚动、历史和交互

- [ ] follow-tail、unread、新输出不打断历史浏览。
- [ ] lineId+pixel锚点、50行预取、1500/3000行缓存上限。
- [ ] selection跨wrapped行、活动行promote、分页扩展和trim处理。
- [ ] 本地浏览与远端mouse mode滚动分流。
- [ ] IME composing、Ctrl/Alt/Shift/Fn、语义key/paste/mouse/focus。
- [ ] layout lease切换、observer显示和resize去抖。
- [ ] bell/title/cwd/clipboard/notification/hyperlink安全处理。

### Task 9：端到端、弱网和性能验收

- [ ] Go→Protobuf→Android model→Renderer全链路fixture。
- [ ] Android emulator和真实设备跑bash/vim/tmux/top/less。
- [ ] 10000行trim、持续输出时历史锚点、多session和View重建。
- [ ] 乱序/重复/缺patch、断网重连、慢客户端和超大快照。
- [ ] Web与Android同时连接时验证layout lease，不发生resize震荡。
- [ ] 记录snapshot/patch/history字节、resync、apply和render耗时。

### Task 10：直接切换并删除旧 Android 终端链路

- [ ] Android正式入口只协商新的 `webterm.screen.v1`。
- [x] 删除零引用的旧 `TerminalProjection`。
- [ ] 删除缓存 `TerminalSession` 和旧屏幕磁盘文件读取路径。
- [ ] 删除 feature-terminal 对 `terminal-emulator/terminal-view` 的依赖。
- [ ] 删除旧 Android TerminalSession/TerminalEmulator显示代码和无用模块。
- [ ] 更新构建、ProGuard、README、架构文档和smoke脚本。
- [ ] 全量测试通过后完成一次干净源码搜索，确保Android生产路径不存在 `appendOutput(ANSI)`。

---

## 14. 测试矩阵

### 14.1 Go 单元测试

| 类别 | 必测内容 |
|---|---|
| Cell | 默认色、indexed、RGB、所有 attrs、wide/spacer、组合字符 |
| 屏幕 | clear、erase、insert/delete、scroll region、reverse video |
| Buffer | main/alternate 切换和恢复 |
| Cursor | 位置、可见、block/bar/underline、blink |
| Modes | application cursor/keypad、paste、mouse、focus |
| History | append、分页、重复行、trim、10000 边界 |
| Revision | patch、snapshot替代、per-client baseline、revision gap |
| Resize | layout lease、layout epoch、reflow、旧请求失效 |
| Backpressure | 合并、snapshot 替代 patch、慢客户端 |
| Effects | bell、title、cwd、clipboard、notification、palette |
| Input | text、key、paste、mouse、focus按权威modes编码 |

### 14.2 Android 纯模型测试

| 类别 | 必测内容 |
|---|---|
| Snapshot | 原子替换、instance/layout epoch更新、历史窗口、字典 |
| Patch | 正常、重复、缺包、乱序、越界、revision gap |
| History | prepend、重复页、trim、缓存淘汰 |
| Viewport | followTail、锚点保持、unread |
| Selection | wrapped、wide、未加载页 |
| Lifecycle | detach 后更新、reattach 最新模型 |
| Validation | 超大消息、过量字典、越界run、非法width/UTF-8 |

### 14.3 Android 渲染测试

- screenshot/golden：ASCII、中文、emoji、组合字符、16/256/RGB 色。
- 光标三种形状、选择、反显、dim、下划线和删除线。
- 宽字符不能重复绘制，spacer 不得显示。
- 字体切换、缩放、横竖屏和软键盘。
- 3000 行本地缓存下快速 fling 不崩溃、不明显掉帧。

### 14.4 端到端测试

必须覆盖：

```text
bash / zsh
vim / nano
less
top / htop
tmux
大量连续输出
中文 / emoji / 组合字符
alternate screen
鼠标模式
主屏向上翻页并继续产生新输出
历史按需加载
历史 trim
resize / 旋转 / 键盘开合
前后台切换
断网重连
多 session 快速切换
Web和Android同时打开时的layout lease转移
bell/title/cwd/OSC 52/notification安全行为
```

---

## 15. 性能预算与可观测性

初始预算：

| 指标 | 目标 |
|---|---|
| 交互式 output → Android model | P95 < 50ms（不含网络） |
| model apply | P95 < 8ms |
| 普通局部 render | P95 < 16ms |
| snapshot apply | P95 < 50ms，不能阻塞主线程解码 |
| history page | 250 行默认，单页解码不阻塞 UI |
| Android 本地历史 | soft 1500 / hard 3000 行 |
| resync | 正常稳定连接接近 0 |

新增指标：

- `screen_snapshot_bytes_total`
- `screen_patch_bytes_total`
- `screen_history_bytes_total`
- `screen_resync_total{reason}`
- `screen_client_queue_depth`
- `screen_rows_changed_per_patch`
- Android `model_apply_ms`
- Android `render_ms`
- Android `history_cache_rows`
- Android `history_page_latency_ms`

日志必须带 `sessionId / instanceId / layoutEpoch / screenRevision`，但不得记录终端文本正文、剪贴板正文或通知正文。

---

## 16. 风险与处理

| 风险 | 影响 | 处理 |
|---|---|---|
| headless-term 无可靠 scroll 事件 | 无法稳定分页和锚定 | 先补最小 fork/API，阻断上层开发 |
| Go/Android Unicode 字形宽度不同 | 重叠、空洞、光标错位 | Go下发 width；Android只缩放glyph，不重判列宽 |
| snapshot 过大 | 重连流量和卡顿 | Protobuf、稀疏行、最近历史窗口、后台解码 |
| patch 丢失 | 屏幕不一致 | baseRevision 严格校验并 resync |
| resize 与输出竞态 | 新旧 geometry 混合 | layout lease、单 actor、layout epoch、resize 后 snapshot |
| 多客户端争抢尺寸 | 持续reflow和历史失效 | 显式layout lease，observer禁止resize |
| View detach 丢状态 | 切页回来空白 | model归无Activity runtime，controller detach只解绑listener |
| 历史缓存过大 | Android OOM/GC | soft/hard limit、分页淘汰、style intern |
| 新renderer与现有体验差异 | 字形、手势或选择退化 | 旧实现只作测试oracle，golden/交互矩阵达标后直接替换 |
| 高频输出 patch 太多 | 带宽和帧率下降 | 16ms合并、整屏阈值转snapshot、背压 |
| alternate/mouse 手势冲突 | vim/tmux 不可用 | Go权威modes编码语义输入，本地浏览严格分流 |
| OSC 52/URI滥用 | 隐私或安全风险 | 默认拒绝读取、显式授权、大小限制、scheme allowlist |
| 字符簇保真不足 | emoji/组合字符错位 | 作为engine阻断门，不能留到切流后 |

---

## 17. 验收与切流门槛

删除旧 Android 终端链路前必须同时满足：

1. Go canonical fixture 全部通过。
2. Android model reducer 的 revision/layout/history/validation 测试全部通过。
3. 新 renderer 与当前产品要求的视觉和交互 golden 全部通过，不要求保留旧类结构。
4. remote renderer 的核心样式、字符簇、宽字符、光标和选择 golden 全部通过。
5. Android emulator 端到端可创建 session、输入、resize、向上翻历史、分页、重连。
6. 用户向上浏览时持续输出 5 分钟，锚点不跳、回到底部正确。
7. 10000 行 Go 历史发生 trim 时 Android 不崩溃、不显示错序内容。
8. vim/tmux/top 的 alternate 和鼠标模式行为通过。
9. View detach/reattach、多 session 切换不丢 patch/effect。
10. 弱网制造缺包/重连后自动 snapshot 恢复，无需重启 App。
11. Web与Android同时打开时layout lease稳定，不发生resize震荡。
12. OSC 52、hyperlink、notification和超大消息安全测试通过。
13. 性能预算无严重超标。

---

## 18. 推荐的第一批实际提交

按最终模块边界拆分提交，但不建立临时兼容层：

1. `docs: define final screen v1 protobuf contract`
   - 冻结wire schema、identity/revision、history、lease、effect和资源限制。
2. `feat(go): add tracked terminal engine scrollback`
   - 最小headless fork、唯一provider、lineId、wrapped、trim和分页。
3. `refactor(go): serialize terminal runtime through actor`
   - PTY、engine、layout lease、revision和语义输入。
4. `feat(go): add canonical screen projection`
   - Cell/style/cursor/modes、per-client patch、history和背压。
5. `feat(go): replace experimental screen v1 with protobuf`
   - 删除旧JSON实现，接入最终handler。
6. `feat(android): add terminal model and protobuf modules`
   - snapshot/patch/history reducer和跨语言fixture。
7. `refactor(android): split session runtime from screen controller`
   - 无Activity runtime和Application级registry。
8. `feat(android): add WebTerm terminal renderer`
   - 新Canvas View、viewport、selection、gesture和IME。
9. `refactor(android): remove local ANSI terminal emulator`
   - 切正式入口并删除旧模块、缓存和依赖。

每个提交都必须保持对应模块单测通过；端到端首次可用点在第8项，第9项只在全部验收门槛通过后执行。

---

## 19. 最终完成定义

本计划完成时：

- Go PC Agent 是终端屏幕、历史、光标、模式、滚屏和 resize/reflow 的唯一权威。
- Android 不再通过 `TerminalEmulator.append()` 解释远端 ANSI。
- Android 使用 `RemoteTerminalModel` 显示 Go snapshot/patch。
- Android 可按需向上加载历史，并在新输出到来时保持用户锚点。
- Android 保留当前终端的 Canvas 视觉、字体、选择、复制、键盘、快捷栏和手势体验。
- `TerminalSessionRuntime`、网络、View/controller和viewport生命周期相互独立。
- Android生产依赖中不存在本地ANSI解析器、Legacy Adapter、双screen协议或兼容feature flag。
- Go通过语义输入、layout lease和终端effect完整拥有终端行为状态。
