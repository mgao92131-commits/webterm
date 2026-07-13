# Go Agent 与 Android 权威终端性能优化计划

> 状态：待实施（2026-07-13 审查后修订）  
> 制定日期：2026-07-13  
> 修订记录：2026-07-13 对照代码完成实施前审查，补充 dirty 路径审计、跨边界选择 bug 前置修复、字节预算重校准等条目（见第 12 节）  
> 范围：`go-core` 与 `android-client` 远程终端主链路  
> 不包含：网页端、Legacy Termux 模拟器性能优化、协议无关的 UI 重构

## 1. 背景与目标

当前架构已经确定：

- Go PC Agent 是唯一权威终端状态源，负责 PTY、ANSI 解析、屏幕状态和 scrollback。
- Android 只维护远端投影模型，负责画面渲染、滚动、选择、复制和输入。
- Android 与 Go 之间使用 `screen-protocol` 传输 snapshot、patch、history page 和语义输入。

此前性能审查中的一部分问题已经修复，后续不能按旧报告从头实施。本计划只处理当前代码中仍存在且有明确收益的问题，并以可重复性能基线作为是否继续优化的依据。

总体目标：

1. Go 端单行变化不再扫描完整屏幕和完整历史窗口。
2. Go 端导出终端状态时不再逐 Cell 获取读锁。
3. Android 每追加一行历史时不再复制全部历史。
4. Android 选择、拖动和复制时不再复制整棵 `TreeMap`。
5. 高速输出时保持有界内存、稳定帧时间和最终状态一致性。
6. 所有优化不得破坏 resize、reflow、主/备用屏、历史分页、选择复制和 resync 行为。

## 2. 当前状态基线

### 2.1 已经修复，不再重复实施

Go：

- 遗留 `screen` 模拟器已经删除，PTY 输出只由权威 Runtime 解析一次。
- Legacy `ScreenDelta` 空载扫描路径已经删除。
- `pendingInput` 已改为 `[]byte`，不再为每个 rune 执行 `string(r)` 和 `StepString`。
- 同一 revision 的权威屏幕只导出一次，再共享给多个客户端。
- projection 已经使用 16ms 合并窗口。
- patch 只在线路上传输变化行，style/link 只传新增字典项。

Android：

- 普通 ASCII 已有基础 run 合批，空格 glyph 和 spacer 已跳过。
- selection 已在 render 入口统一归一化，不再逐 Cell 创建 Anchor。
- `updateSize` 已比较字体和 View 尺寸，不再每帧重复发送 resize。
- `historyBytes` 已改为增量记账。
- 屏幕消息使用最大 64 帧的有界 mailbox，溢出后通过 resync 收敛。
- 二进制 tunnel frame 已在 OkHttp 回调线程解码，不再先切到主线程。

### 2.2 当前仍需解决的核心问题

Go：

- `exportSnapshot` 每次仍导出完整屏幕和完整历史窗口。
- `diffToPatch` 每次仍比较完整屏幕，并为历史构建 LineID map。
- exporter 通过 `engine.Cell()` 逐格进入 `Terminal.Cell()`，每格一次 `RLock/RUnlock`。
- printable rune 虽已消除主要字符串分配，但仍然逐 rune 加锁。
- `screenprotocol.Handler` 仍在每条二进制消息到达时创建。

Android：

- history 变化时，`publishRenderSnapshot` 仍执行 `new ArrayList<>(historyCache.values())`。
- `RemoteTerminalView` 的选择、坐标转换和复制路径仍调用 `historyCache()`，每次复制完整 `TreeMap`。
- styled ASCII、短 run、Unicode 和宽字符仍主要使用逐 Cell 绘制；是否继续优化必须由 profile 决定。

## 3. 实施原则

1. 先测量，后重构。每个核心阶段必须有改造前后的同一组 benchmark。
2. Go 和 Android 两个核心数据结构重构不得同时展开。
3. 不向 UI 线程暴露任何可变终端集合。
4. 不使用兼容层长期保留新旧两套状态模型。
5. snapshot 是最终恢复手段；增量状态无法证明连续时必须退回权威 snapshot。
6. resize、buffer 切换、字典轮转和 epoch 变化可以走全量路径，不强求增量。
7. 每个阶段独立提交，禁止与认证、业务 UI 或无关清理混合。

## 4. 阶段 0：整理当前工作区

性能重构开始前，先独立提交现有 Android 正确性修复，避免认证、重连、历史请求和日志修复混入性能提交。

验收条件：

- Android 全量单元测试通过。
- Release/R8 构建通过。
- 模拟器 connected tests 全部通过。
- `git diff --check` 通过。
- 性能改造从干净工作区和独立提交开始。

## 5. 阶段 1：建立性能基线

本阶段不改变生产架构，只增加可重复的压力测试、benchmark 和观测点。

### 5.1 Go benchmark

覆盖场景：

- 连续普通 ASCII 输出。
- 大量 ANSI 颜色和样式切换。
- 光标移动、擦除和局部重绘。
- Claude Code 风格全屏 TUI 更新。
- 持续产生 scrollback。
- 80×24、120×40、200×50 三种终端尺寸。
- 1、2、4 个 screen client。

记录指标：

- 每 MB PTY 输出 CPU 时间。
- `allocs/op`、`bytes/op`。
- ANSI 解析时间。
- `ExportState`、`exportSnapshot`、`diffToPatch` 时间。
- projection flush 的 P50、P95、P99。
- 活动屏幕与历史导出的独立分配量。

建议测试位置：

- `go-core/internal/headlessterm/*_benchmark_test.go`
- `go-core/internal/screenprojection/*_benchmark_test.go`
- `go-core/internal/terminalsession/*_benchmark_test.go`

现状备注：headlessterm 目前没有任何 benchmark，terminalsession 没有任何测试文件，screenprojection 仅有 `BenchmarkProjectorExportOnceFanout`。Go 侧基线基本从零建立；terminalsession 的 Runtime 行为只能依靠 session 包集成测试和 `go test -race` 兜底，阶段 2 重构前应考虑补最少量包内单测。

### 5.2 Android 性能测试

固定数据规模：

- 80×24 + 1000 行历史。
- 120×40 + 5000 行历史。
- 200×50 + 10000 行历史。
- 每 patch 追加一行历史。
- 每 patch 只修改一行活动屏幕。
- 持续输出时滚动、长按选择和拖动选择手柄。
- Styled ASCII、CJK、emoji、combining mark 和宽字符混合。

记录指标：

- `RemoteTerminalRenderer.render()` 时间。
- `RemoteTerminalModel.applyPatch()` 时间。
- `publishRenderSnapshot()` 时间。
- 每次 patch 的分配量。
- 主线程 dropped frames。
- mailbox 最大深度和溢出次数。
- Java heap、GC 次数和 GC pause。

现状备注：Android 侧目前没有任何性能测试基础设施（无 benchmark 模块、无 Robolectric，androidTest 仅有输入法相关测试），本节需要从零搭建，排期需单独预留。

### 5.3 优化准入标准

后续每项优化必须满足：

- 正确性测试不回退。
- 目标 benchmark 有可重复改善。
- 非目标场景没有明显退化。
- 内存上限不增加。
- 不引入跨线程共享的可变状态。

## 6. 阶段 2：Go 增量投影重构

这是 Go 端最高优先级工作。

### 6.1 原子只读投影接口

为 `headlessterm.Terminal` 增加一次锁保护的投影读取接口，代替 exporter 分别调用 `Rows()`、`Cols()`、`CursorPos()`、`Cell()` 和 `IsWrapped()`。

接口应返回一致且不可变的投影数据，例如：

```go
type ProjectionRead struct {
	Rows         int
	Cols         int
	Cursor       ProjectionCursor
	Modes        ProjectionModes
	DirtyRows    []ProjectionRow
	Full         bool
	ActiveBuffer BufferKind
	Title        string
	WorkingDir   string
}

func (t *Terminal) ReadProjection(full bool) ProjectionRead
```

约束：

- 一次 `RLock` 读取同一时刻的完整元数据和目标行。
- Cell/Row 必须复制为不可变值，不能暴露内部可变切片。
- resize、清屏、reflow、buffer 切换必须标记 full dirty。
- 光标移动时旧光标行和新光标行都必须标脏。
- palette、mode、title、cwd 变化独立记录。
- 只有 Projector 成功把 dirty 行合并进 projectedState 缓存后才能清除 dirty 标记；不得按客户端确认清除（mailbox 合并会丢弃中间帧）。

### 6.2 行级 dirty tracking

在 Buffer 中维护：

```go
dirtyRows []bool
dirtyAll  bool
```

以下写操作必须维护行级 dirty：

- `SetCell`
- `ClearRow`、`ClearRowRange`
- insert/delete character
- insert/delete line
- scroll
- erase display
- resize/reflow
- 主屏与备用屏切换

实施前必须先做一次 dirty 写路径全量审计（以 `Buffer.Cell()` 返回可变指针的全部调用方为 checklist）。当前已知会绕过或不标记的路径：

- `eraseCharsInternal`（ECH，`handler.go:573-578`）：`cell.Reset()` 不标 dirty，且 `Cell.Reset()` 本身会清除已有 dirty 标记。
- `substituteInternal`（`handler.go:1753-1756`）：不标 dirty。
- `SetWrapped`（`buffer.go:558-563`）：修改 wrapped 标志无任何标记，但 wrapped 影响导出的 `Line.Wrapped`。
- scroll 一律置 `dirtyAll`：`ScrollUp/ScrollDown` 直接搬移行切片（`buffer.go:180-186`、`218-224`），不维护 dirtyRows 索引平移逻辑。
- Buffer 现有的 `hasDirty`/`CellFlagDirty` 基础设施无生产使用方，直接替换为行级 dirty，不并存两套语义。
- 主备屏切换在 Terminal 层（`handler.go:1467-1472`），置 `dirtyAll`。

必须测试：

- 单 Cell 修改只导出一行。
- 光标移动导出旧行和新行。
- scroll 正确更新屏幕并追加历史。
- resize、alternate buffer 切换和字典轮转强制全量 snapshot。

### 6.3 Projector 保存不可变行缓存

Projector 保存最近一次权威投影：

```go
type projectedState struct {
	screenRows []terminalengine.Line
	history    projectedHistory
	metadata   projectedMetadata
}
```

每次 flush：

1. 从 Engine 取得 dirty projection。
2. 只重新导出 dirty rows。
3. 未变化行复用旧不可变 `Line`。
4. 更新光标、模式、标题和其他元数据。
5. 生成一次新的权威 Frame。
6. 各客户端只根据自己的 baseline 派生 snapshot/patch。

禁止每个客户端分别读取 Engine。

### 6.4 历史按 LineID 增量导出

为 TrackedScrollback 增加类似接口：

```go
func (s *TrackedScrollback) LinesAfter(lastLineID uint64, limit int) HistoryDelta
func (s *TrackedScrollback) Window(limit int) HistoryWindow
```

普通 flush 只取得 `LastIncludedLineID` 之后的新行，不再构建旧历史 ID map。

必须处理：

- 一次输出滚出多行。
- scrollback 行数/字节预算驱逐。
- 客户端 baseline 已落后于当前窗口。
- resize reflow 后 epoch/LineID 变化。
- 主备用屏切换。
- resync 和字典轮转。

连续性无法证明时直接发送 snapshot。

### 6.5 简化 `diffToPatch`

删除每帧历史 map：

```go
oldHistoryIDs := make(map[uint64]bool)
```

改为连续范围判断：

- LastID 相同：没有历史追加。
- New LastID 更大：携带新增连续范围。
- baseline 已被 trim：发送 HistoryTrim 或 snapshot。
- epoch、instance、字典世代或 LineID 不连续：发送 snapshot。

### 6.6 Go 阶段验收

正确性：

- screenprojection、terminalsession、terminalengine 测试全部通过。
- snapshot/patch 协议兼容测试通过。
- Claude Code 重绘、resize、滚动、分页和备用屏行为不回退。
- 多客户端 baseline 独立且最终一致。
- `go test -race` 通过。

性能：

- 单行变化不再扫描完整屏幕和历史窗口。
- Cell 锁从 O(rows×cols) 降为一次快照锁或 O(dirty rows)。
- 单行更新的 projection 分配量显著下降。
- 增加客户端数量不增加 Engine 导出次数。

建议拆分提交：

1. `headlessterm` 原子读取和行级 dirty。
2. Projector 脏行缓存。
3. 历史 LineID 增量投影和 diff 简化。

## 7. 阶段 3：Android 分段不可变历史模型

这是 Android 端最高优先级工作。在 Go 增量投影稳定后再开始。

### 7.0 前置：修复跨边界选择复制 bug

现有代码中 `selectedText` → `appendHistoryRange`（`RemoteTerminalView.java:610-625`）对"起点在历史、终点在屏幕"的跨边界选择复制结果为空（`end.historyLineId == 0` 时第一轮循环即 break），且选择/复制路径目前没有任何测试覆盖。

迁移开始前先提交一个 failing test 复现该行为，再修复并独立提交。否则迁移后无法区分新结构回归与既有缺陷，`7.5` 的"选择可以跨越 history/screen 边界"验收项也无法验证。

### 7.1 新建独立历史结构

新增分段不可变结构，例如：

```java
public final class TerminalHistory {
    private static final int CHUNK_SIZE = 128;
    private final List<HistoryChunk> chunks;
    private final int size;
    private final long estimatedBytes;
}
```

每个 `HistoryChunk`：

- 内容不可变。
- LineID 升序排列。
- 保存 first/last LineID。
- 支持按索引访问。
- 支持 LineID 二分定位。
- 支持局部 append、prepend 和 trim。

目标复杂度：

| 操作 | 目标复杂度 |
|---|---:|
| 追加一行 | 均摊 O(1) |
| prepend 一页 | O(page size) |
| 删除完整 chunk | O(chunks) 或更低 |
| 按可见索引取行 | O(1) |
| 按 LineID 定位 | O(log chunks + log chunk size) |
| 发布 RenderSnapshot | O(chunks)，不复制全部行 |
| 获取历史字节数 | O(1) |

### 7.2 RemoteTerminalModel 迁移

使用新结构替换：

- `putHistoryLine`
- `evictHistoryIfNeeded`
- `evictHistoryAfterPrepend`
- `trimHistory`
- `historyCache`
- `publishRenderSnapshot` 中的历史全量复制

必须保留：

- soft/hard 行数和字节双预算。
- prepend 时保护可见锚点。
- 历史 LineID 连续范围。
- alternate buffer 不显示 main scrollback。
- resize/snapshot 可以原子替换完整历史。

迁移后必须重校准 `estimateHistoryLineBytes`：现有 112B 基线包含 `TreeMap.Entry` 与 `Long` key 的对象开销（`RemoteTerminalModel.java:436-438`），chunk 结构下每行实际开销下降；不重校准会导致字节预算系统性高估、缓存保留行数变少。

`RemoteTerminalModelHistoryBudgetTest` 是 prepend 驱逐双方向语义与锚点保护的主要安全网，迁移过程中必须保持全绿。

### 7.3 RenderSnapshot 发布不可变历史视图

RenderSnapshot 改为持有：

```java
public final TerminalHistorySnapshot history;
```

至少提供：

```java
int size();
TerminalLine lineAt(int index);
int findLineIndex(long lineId);
long firstLineId();
long lastLineId();
```

Renderer 只读取本帧 snapshot，不读取可变 Model 集合。

### 7.4 View 移除 `historyCache()`

以下路径全部改用已发布的 RenderSnapshot：

- `pointToAnchor`
- `anchorToPoint`
- `selectedText`
- 长按选择
- 拖动选择手柄
- 最大滚动范围计算
- 可见历史定位
- `model.screen()` 的全部调用点（`RemoteTerminalView.java` 共 7 处，每次调用克隆整个屏幕数组）

同一个手势事件尽量固定使用同一个 snapshot，避免模型更新导致 Anchor 与画面错位。

顺带修复：`model.activeBuffer` 是无同步的 public 字段且主线程直读（`RemoteTerminalView.java:297`），迁移时改为从 snapshot 读取，消除可见性瑕疵。

### 7.5 Android 阶段验收

正确性：

- prepend 后视口不跳动。
- 滚动到顶部时第一行完整显示。
- follow-tail 与非 follow-tail 的钉住行为不变。
- 选择可以跨越 history/screen 边界。
- CJK、emoji、宽字符和空行复制正确。
- trim 后失效 Anchor 能安全钳制。
- resize、reconnect 后旧 snapshot 不污染新 epoch。

性能：

- 追加一行不复制全部历史。
- 选择拖动不创建 TreeMap。
- 10000 行历史持续输出时 apply/publish 时间保持稳定。
- GC 次数和 pause 明显下降。
- Renderer/View 不读取可变 Model 集合。

建议拆分提交：

1. 新增 `TerminalHistory` 和纯单元测试。
2. RemoteTerminalModel 迁移。
3. Renderer/View 迁移并删除生产 `historyCache()`。

## 8. 阶段 4：依据 Profile 处理局部热点

只有阶段 2、3 完成并重新采样后，才实施本阶段。

### 8.1 Go Screen Handler 复用

在 `Client` 构造时创建一次 `screenprotocol.Handler` 并保存到字段，`handleScreenBinary` 只负责调用已存在的 Handler。

验收：

- 所有 screen 消息类型路由测试通过。
- Client 关闭后 Handler 不持有失效资源。
- 单消息 allocation 明显下降。

### 8.2 Go printable 输入批处理

扩展 printable 处理路径，使连续 printable span 一次提交给 Terminal。

依赖备注：ANSI decoder 是外部模块 `github.com/danielgatis/go-ansicode v1.0.14`，当前没有 replace 指令。优先方案是不修改 decoder，在 `Terminal.Input` 侧缓冲连续 printable span 后批量提交（`pendingInput` 已有字节缓冲雏形）；只有 profile 证明缓冲层不足时才考虑 fork/replace decoder。

要求：

- ANSI 控制序列前 flush printable span。
- UTF-8 rune 和 grapheme cluster 可以跨 PTY chunk。
- emoji ZWJ、combining mark、variation selector 不得拆错。
- 批量提交只持锁一次。

### 8.3 Android styled ASCII run 合批

只有 Renderer 仍是主线程主要热点时才扩展。

Run key 至少包含：

- styleId
- selection 状态
- cursor 覆盖
- cell width
- reverse video
- hidden
- underline/strike
- 是否需要 glyph 横向缩放

emoji、CJK、combining sequence 和特殊缩放继续保留逐 Cell 路径。

### 8.4 鼠标 Move 合并

- 同一 frame/window 内只保留最新 MOVE 坐标。
- press、release、wheel 不合并、不重排。
- 断线时丢弃尚未发送的 MOVE。

## 9. 延期与不处理项

以下项目暂不实施，除非新的性能数据证明它们进入主要热点：

- EventRing 零拷贝和共享所有权重构。
- Okio/ByteBuffer 端到端零拷贝。
- `ps` 父进程链批量查询。
- `Manager.Create` 锁外启动 PTY。
- 通用 Unicode run 合批。
- 磁盘缓存全面异步重构。
- `backpressure.go` 等死代码清理。

Legacy Termux 模拟器不做性能优化。后续应单独执行生产引用审计；确认没有使用方后直接删除。

## 10. 总体执行顺序

1. 提交当前 Android 正确性修复。
2. 建立 Go 与 Android 性能基线（Android 侧测试基础设施从零搭建）。
3. Go dirty 写路径全量审计，随后实现原子投影读取和行级 dirty。
4. 实现 Go Projector 脏行增量导出。
5. 实现 Go 历史 LineID 增量导出。
6. 重新运行 Go 正确性测试、race 和 benchmark。
7. 提交跨边界选择复制 failing test 并修复该 bug（见 7.0）。
8. 新建 Android `TerminalHistory` 分段结构。
9. 迁移 Android Model（含字节估算重校准）。
10. 迁移 Android View/Renderer，删除生产 `historyCache()` 与 `screen()` 克隆路径。
11. 运行 Android 单测、Release/R8、模拟器测试和性能测试。
12. 对比改造前后基线。
13. 根据 profile 决定是否实施 Handler、rune、styled run 和输入合并等局部优化。

## 11. 最终完成定义

只有同时满足以下条件，本计划才算完成：

- Go 单行更新走脏行增量投影，不再全屏/全历史扫描。
- Go exporter 不再逐 Cell 获取 Terminal 读锁。
- Go 历史 patch 不再每帧构建旧历史 ID map。
- Android 历史追加不再复制全部历史行引用。
- Android 选择、拖动、复制不再复制 TreeMap。
- Go race、全量测试和 Android 全量测试全部通过。
- Android Release/R8 和模拟器交互测试通过。
- Claude Code、普通 shell、全屏 TUI、滚动、resize、历史分页和选择复制均无行为回退。
- 改造后的 benchmark 能证明目标热点得到改善。

## 12. 审查补充（2026-07-13）

本计划在实施前已对照代码完成审查。文档中对现状的描述经核实全部属实（含第 2.1 节"已修复"项），阶段划分与先后顺序维持不变。本节记录审查中新增或修订的条目，出处已就地写入对应章节：

- 第 5 节：补充 Go/Android 两侧基线基础设施的现状空白说明。
- 第 6.1 节：明确 dirty 清除语义（对 Projector 缓存清除，不按客户端确认）。
- 第 6.2 节：补充 dirty 写路径审计要求、已知绕过路径清单、scroll 一律 `dirtyAll`、废弃 `hasDirty`/`CellFlagDirty` 的处理。
- 第 7.0 节：新增跨边界选择复制 bug（`RemoteTerminalView.java:610-625`）的前置修复。
- 第 7.2 节：新增字节估算重校准要求。
- 第 7.4 节：补充 `model.screen()` 克隆路径迁移与 `activeBuffer` 可见性修复。
- 第 8.2 节：补充 decoder 外部依赖约束，优先 Terminal 侧缓冲方案。
- 第 10 节：执行顺序插入审计与 bug 前置修复两步。

审查中确认但不改变计划的事实：reflow 尚未实现（`ResetForReflow` 仅有测试调用方），`backpressure.go` 确为无生产引用方的死代码，与第 9 节延期判断一致。
