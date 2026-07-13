# headlessterm dirty 写路径全量审计

> 日期：2026-07-13
> 状态：已完成（只读审计，未修改代码）
> 用途：`go-android-terminal-performance-optimization-plan.md` §6.2 的前置交付物。引入行级 dirty tracking（`dirtyRows []bool` + `dirtyAll bool`）前，必须按本清单逐处落实标记，避免"改了不导出"的正确性回归。

## 1. Buffer 公共写方法清单（buffer.go）

| # | 方法 | file:line | 修改语义 | 当前 dirty 标记 | 行级 dirty 处置 |
|---|------|-----------|----------|----------------|------------------|
| 1 | `SetCell` | buffer.go:70-77 | 替换单个 cell | `cell.MarkDirty()` + `hasDirty` | 标 `dirtyRows[row]` |
| 2 | `MarkDirty` | buffer.go:81-87 | 不改内容，纯标记原语 | cell + `hasDirty` | 保留为标记原语，改为标 `dirtyRows[row]`（writeCluster 依赖它） |
| 3 | `ClearRow` | buffer.go:118-127 | 整行 Reset | Reset 后逐 cell MarkDirty | 标 `dirtyRows[row]` |
| 4 | `ClearRowRange` | buffer.go:130-145 | 行内 [start,end) Reset | 同上 | 标 `dirtyRows[row]` |
| 5 | `ClearAll` | buffer.go:148-152 | 全屏（循环调 ClearRow） | 全 cell + `hasDirty` | 置 `dirtyAll` |
| 6 | `ScrollUp` | buffer.go:157-198 | 行切片整体上移；top==0 时推 scrollback；底部新行 | 逐 cell MarkDirty | **置 `dirtyAll`**（不做索引平移） |
| 7 | `ScrollDown` | buffer.go:202-236 | 同上，反向 | 同上 | **置 `dirtyAll`** |
| 8 | `InsertLines` | buffer.go:240-245 | 委托 ScrollDown | 继承 | `dirtyAll`（继承） |
| 9 | `DeleteLines` | buffer.go:249-254 | 委托 ScrollUp | 继承 | `dirtyAll`（继承） |
| 10 | `InsertBlanks` | buffer.go:257-274 | 单行内右移 + 填空 | 逐 cell MarkDirty | 标 `dirtyRows[row]` |
| 11 | `DeleteChars` | buffer.go:277-296 | 单行内左移 + 尾部清空 | 逐 cell MarkDirty | 标 `dirtyRows[row]` |
| 12 | `Resize` | buffer.go:302-337 | 重建整个 cells/wrapped 二维数组 | 全 cell MarkDirty | 置 `dirtyAll`；**同时重建 `dirtyRows` 切片**（rows 变了） |
| 13 | `FillWithE` | buffer.go:383-392 | 全屏填 'E'（DECALN） | 逐 cell MarkDirty | 置 `dirtyAll` |
| 14 | `GrowRows` | buffer.go:485-511 | 追加新行（autoResize 模式） | 新 cell MarkDirty | 置 `dirtyAll`（总行数变化）；**扩展 `dirtyRows` 长度** |
| 15 | `GrowCols` | buffer.go:515-545 | 单行扩列，可能增大 `b.cols` | 新 cell MarkDirty | 标 `dirtyRows[row]`；cols 变化由 Terminal 层（terminal.go:632）处理 |
| 16 | `SetWrapped` | buffer.go:558-563 | 改 `wrapped[row]` | **无任何标记** ⚠️ | 标 `dirtyRows[row]`（wrapped 影响导出的 `Line.Wrapped`） |

不影响屏幕内容、无需 dirty：`SetTabStop`/`ClearTabStop`/`ClearAllTabStops`（buffer.go:340-358）、scrollback 配置方法（394-441）。

## 2. `Buffer.Cell()` 可变指针的全部生产调用方

生产调用方共 8 处，其中 3 处纯读、5 处写：

| file:line | 调用方 | 对指针做了什么 | 当前是否补 MarkDirty | 处置 |
|-----------|--------|----------------|----------------------|------|
| terminal.go:667-685 | `writeCluster` 主 cell | 写 Char/Fg/Bg/UnderlineColor/Flags/Hyperlink、宽字符标志 | ✅ 684 行 MarkDirty | 已覆盖；改为标 `dirtyRows[cursor.Row]` |
| terminal.go:692-699 | `writeCluster` 宽字符 spacer | `Reset()` 后写字段 | ✅ 699 行 MarkDirty | 已覆盖；同上 |
| handler.go:574-578 | `eraseCharsInternal`（ECH） | `cell.Reset()` | ❌ **漏标**；且 `Cell.Reset()` 会清掉已有 dirty | 标 `dirtyRows[cursor.Row]`；最小改法是改调 `ClearRowRange(row, col, col+n)` |
| handler.go:1753-1756 | `substituteInternal`（SUB） | `cell.Char = "?"` | ❌ **漏标** | 标 `dirtyRows[cursor.Row]` |
| handler.go:2139-2155 | `assignImageToCells`（kitty/sixel 放置） | 写 Char=占位符 + `cell.Image` | ✅ 2154 行（仅 cell 级） | 改为标 `dirtyRows[cellRow]` |
| terminal.go:435 → engine.go:150 → exporter.go:99 | 投影导出 | 纯读 | — | 只读，无需处理 |
| snapshot.go:232、286 | 快照导出 | 纯读 | — | 只读 |
| terminal.go:1099 | `GetSelectedText` | 纯读 | — | 只读 |

注意：`Terminal.Cell()`（terminal.go:432-436）返回 `&b.cells[row][col]`，是 dirty 语义上的永久写逃逸口。当前生产上只读使用；§6.2 实施后应将 exporter 改为只读访问方式（或至少注释禁止写）。

## 3. 直接访问 `b.cells` 内部切片的位置

`b.cells` 的所有访问都在 buffer.go 内部，包内其他文件与包外无一处直接触碰。除 scroll（buffer.go:181、219 搬移整行切片；175 行推入 scrollback）外：

- buffer.go:190/228 — ScrollUp/ScrollDown 用 `make([]Cell)` 换新行（随 scroll 置 `dirtyAll` 已覆盖）
- buffer.go:324/507 — Resize/GrowRows 整体替换 `b.cells`（dirtyAll 已覆盖）
- buffer.go:530 — GrowCols 替换单行切片（dirtyRows[row] 已覆盖）
- buffer.go:453、466-467 — `LineContent` 只读取址，无需处理

providers.go:195/210 的 `line.cells` 是 LineRing 内部字段，与 Buffer.cells 无关。

## 4. Terminal/handler 层写路径逐条核对

唯一内容入口：`Engine.Write`（engine.go:108）→ `Terminal.Write`（terminal.go:574）→ ANSI decoder → 各 handler；`Engine.Resize`（engine.go:114）→ `Terminal.Resize`。

| 入口 | file:line | 落地路径 | 覆盖情况 |
|------|-----------|----------|----------|
| 打印字符 | handler.go:678 → terminal.go:615 `writeCluster` | Cell() 写、`SetWrapped(true)`（terminal.go:639）、`GrowCols`、`InsertBlanks`、`scrollIfNeeded` | writeCluster 已覆盖；**经由它触发的 SetWrapped 漏标** |
| LineFeed | handler.go:777-791 | `SetWrapped(false)`（783）+ `scrollIfNeeded` | **SetWrapped 漏标** |
| scrollIfNeeded | terminal.go:744-762 | `GrowRows`/`ScrollUp`/`ScrollDown` | 已覆盖 |
| ScrollUp/Down handler | handler.go:1218-1241 | Buffer.ScrollUp/Down | 已覆盖 |
| ReverseIndex | handler.go:1168-1178 | ScrollDown | 已覆盖 |
| ClearLine（EL） | handler.go:334-347 | ClearRow/ClearRowRange | 已覆盖 |
| ClearScreen（ED） | handler.go:358-390 | ClearRowRange/ClearRow/ClearAll | 已覆盖 |
| ResetState（RIS） | handler.go:1097-1122 | ClearAll | 已覆盖 |
| Decaln | handler.go:484-490 | FillWithE | 已覆盖 |
| DeleteChars（DCH） | handler.go:501-507 | Buffer.DeleteChars | 已覆盖 |
| DeleteLines（DL） | handler.go:518-526 | DeleteLines→ScrollUp | 已覆盖 |
| InsertBlank（ICH） | handler.go:740-746 | Buffer.InsertBlanks | 已覆盖 |
| InsertBlankLines（IL） | handler.go:757-765 | InsertLines→ScrollDown | 已覆盖 |
| **EraseChars（ECH）** | handler.go:568-579 | Cell()+Reset() | ❌ **漏标** |
| **Substitute（SUB）** | handler.go:1748-1757 | Cell() 写 Char | ❌ **漏标** |
| 主备屏切换 | handler.go:1463-1476 | 切 `activeBuffer` + 新屏 ClearAll | 切出方向已覆盖；**切回主屏无任何写**——在 Terminal 层两个分支都置对应 Buffer 的 `dirtyAll`（dirty 按 Buffer 实例各自维护） |
| Resize | terminal.go:477-570 | ScrollUp、两屏 Buffer.Resize、scrollback Pop、ScrollDown、SetCell、SetWrapped | Buffer.Resize 自带 dirtyAll；SetWrapped 漏标同源 |
| kitty/sixel 图像放置 | handler.go:~140-235、2001-2062 → `assignImageToCells` | ScrollUp + Cell() 写 | 已覆盖 |
| DECAWM 自动换行 | terminal.go:637-645 | `SetWrapped(true)`（639） | **SetWrapped 漏标** |
| 图像删除 | kittyDelete handler.go:238-271、ClearImages terminal.go:1342 | 只动 ImageManager，不改任何 cell | Buffer dirty 范围外（遗留观察：删除 placement 后 `cell.Image` 残留引用，属既有行为） |

纯光标/状态类 handler（Goto、Move*、Tab、Backspace、CarriageReturn、SetScrollingRegion、SGR、tab stop、title、hyperlink）不改屏幕内容，无需 dirty。

## 5. `Cell.Reset()` 的全部调用方

| file:line | 上下文 | Reset 后是否必写新内容并标 dirty | 判定 |
|-----------|--------|----------------------------------|------|
| terminal.go:694 | writeCluster spacer | 是：随即写字段 + MarkDirty | ✅ |
| handler.go:576 | eraseCharsInternal | **否：Reset 即终点** | ❌ 漏标 |
| buffer.go:123/141/270/291/386 | ClearRow/ClearRowRange/InsertBlanks/DeleteChars/FillWithE | 是：随即 MarkDirty | ✅ |

行级 dirty 落地后 `Cell.Reset()` 清 cell 级 dirty 的危害自然消失（cell 级 flag 将被移除），前提是每个 Reset 调用点都标了行。

## 6. 历史（scrollback）写入路径（供阶段 2c 参考）

唯一推入机制：`Buffer.ScrollUp` 中 `b.scrollback.Push`（buffer.go:173-177），条件 `top==0 && provider 启用`。TrackedScrollback 是唯一 provider（terminalengine/tracked_scrollback.go:22，Push 在 :96）。入口（均经 Buffer.ScrollUp）：

| 入口 | file:line | 说明 |
|------|-----------|------|
| `scrollIfNeeded` | terminal.go:754 | 最常见路径：LineFeed 触底、writeCluster 换行触底 |
| `scrollUpInternal`（SU） | handler.go:1240 | CSI S |
| `deleteLinesInternal`（DL） | handler.go:524 | 经 DeleteLines→ScrollUp |
| `Terminal.Resize` 收缩 | terminal.go:494 | 为保光标可见把顶部行推入历史 |
| `assignImageToCells` | handler.go:2087 | 图像超高滚动 |

反向：`scrollback.Pop`（terminal.go:523，Resize 增高时拉回历史行）。备用屏无 scrollback（NoopScrollback）。

## 审计结论

**写路径统计**（生产代码）：Buffer 层变更方法 15 个 + 标记原语 1 个（全部在 buffer.go）；`Cell()` 可变指针生产调用 8 处（写 5 读 3）；Terminal/handler 层内容变更入口约 20 个，全部经 §1/§2 落地，无独立绕过；历史推入 1 个机制 + 5 个入口 + 1 个 Pop。

**当前漏标 3 处**（复核确认）：

1. `eraseCharsInternal` handler.go:573-578 — Reset 后无任何标记；
2. `substituteInternal` handler.go:1753-1756 — 写 Char 无标记；
3. `SetWrapped` buffer.go:558-563 — 改 wrapped 无标记（被 writeCluster:639、lineFeed:783、Resize:544 三条路径依赖）。

**引入 `dirtyRows`/`dirtyAll` 的确切改动清单**：

- `buffer.go`：结构体删 `hasDirty`，加 `dirtyRows []bool` + `dirtyAll bool`；`NewBufferWithStorage` 分配 dirtyRows；按 §1 表逐方法加标记（Resize/GrowRows 需重建/扩展 dirtyRows）；删除 cell 级设施 `CellFlagDirty`、`Cell.MarkDirty/ClearDirty/IsDirty`、`DirtyCells/ClearAllDirty/HasDirty` 及 Terminal 包装（terminal.go:988-1005）、doc.go:158-162 文档示例；新增消费 API（如 `TakeDirty() (rows []bool, all bool)`，读后清零）。
- `handler.go`：`eraseCharsInternal` 补行标记（或改调 `ClearRowRange`）；`substituteInternal` 补行标记；主备屏切换（1465-1476）两个分支各置对应 Buffer 的 `dirtyAll`。
- `terminal.go`：`writeCluster`（684、699）的 `MarkDirty` 调用随原语改造保留（语义变为行标记）；`Resize` 依赖 Buffer.Resize 的 dirtyAll，两屏各自 dirtyAll 自然满足。
- `screenprojection/exporter.go`（:99 起）：投影导出改为按 dirtyRows 增量导出，dirtyAll 时全量。

**额外观察**（不阻塞 §6.2）：`Terminal.Cell()` 是永久写逃逸口，目前生产上只读使用；图像 placement 删除路径不清除 `cell.Image` 残留引用，属既有行为。
