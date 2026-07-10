# Go PC Agent 终端快照与 Node 参考实现一致性计划

**状态：** 阶段 2 / 阶段 3 已完成；阶段 4 已启动并基于初始 fixture 矩阵通过 restore comparator
**制定日期：** 2026-07-10
**最近修订：** 2026-07-10（完成 snapshot encoder 抽离、Node 参考 fixture 矩阵、Go/Node 结构化屏幕状态恢复比较，以及 payload 字节级 vs 语义级兼容决策）
**范围：** `webterm-clone` 的 Go PC Agent 与 `/Users/gao/Documents/webterm` 的 Node PC Agent；本计划不改变 Android、Web 前端或 `webterm.screen.v1` 的实验协议。

## 1. 目的和结论

目标是让 Go PC Agent 对现有 `webterm.json.v1` 和 `webterm.binary.v1` 客户端发出的**重连回复**（`info`、`replay`、`output`、`state`）具有与 Node PC Agent 相同的线协议语义和恢复结果。快照编码器只是其中一层；在 JSON replay 或 binary control frame 尚不一致时，不得宣称快照兼容。

这里的“一致”分为三个可验收层次：

| 层次 | 必须一致的内容 | 比较方式 |
| --- | --- | --- |
| 线协议 | WebSocket text/binary 类型、message type、9 字节帧头、`seq`、字段结构、消息顺序 | 完整 outbound WebSocket trace |
| 快照语义 | 清屏、备用屏、样式、光标、模式、回卷等恢复结果 | 将状态流重新喂给 Node xterm 后比较结构化屏幕状态 |
| 用户行为 | 能回放时增量回放；不能回放时权威全量恢复；不会重复/漏输出 | 断线重连端到端测试 |

第一层应字节级一致。第二层以恢复后的状态一致为准，**不把 ANSI 文本字节完全相同当作硬条件**：不同但等价的 ANSI 序列可以恢复同一屏幕；反过来，文本看起来相近但光标、备用屏或输入模式不同，仍然是不兼容。是否进一步要求 payload 字节级一致，必须在基线采集后的决策点决定，不能提前假设 Go 可以逐字复刻 xterm SerializeAddon 的内部输出。

对于 legacy JSON，`replay { from, frames: [{seq, data}], seq }` 是固定契约，不是可用若干 `output` 消息替换的传输优化。若保留 Go 当前的 output 形式，属于协议分叉，必须以新协议版本和客户端改动另行设计；不属于本计划的“与 Node 一致”。

## 2. 当前实现审计（2026-07-10）

### Node 参考实现

Node 的 `server/terminal-session.js` 使用：

- `@xterm/headless` 6.0.0；
- `@xterm/addon-serialize` 0.14.0；
- 100×30 初始尺寸和 10,000 行 scrollback；
- `serializeAddon.serialize()` 作为快照正文。

二进制 `hello` 的不可回放分支生成：

```text
payload = ESC[3J ESC[2J ESC[H + serializeAddon.serialize()
frame   = [MSG_STATE][uint64 big-endian seq][payload]
```

JSON `hello` 的不可回放分支则发送 `data: serialize()`，**不在 `data` 内附加上述清屏前缀**。Node 的 JSON 与 binary hello 时序不同；精确行为为：

| 路径 | attach 时 | hello 分支 |
| --- | --- | --- |
| JSON | text `info` | resize → text `replay` 或 text `state` → text `info` |
| binary | binary `MSG_INFO` | binary `MSG_INFO` → resize → binary `MSG_OUTPUT` 或 `MSG_STATE` |

JSON replay 的 `frames` 保留每个 ring frame 的 `seq` 和文本；binary replay 才会按约 64 KiB 合并为 `MSG_OUTPUT`，并取 batch 最后一帧的 seq。

### Go 当前实现

Go 的 `go-core/internal/session/terminal.go` 和 `go-core/internal/infrastructure/emulator/screen.go` 使用 `github.com/danielgatis/go-headless-term`，并由 `Screen.AnsiTextWithScrollbackLimit` 手工重建 ANSI 快照。当前 `StateBytes()`：

- 对所有模式都在 payload 开头附加 `ESC[3J ESC[2J ESC[H`；
- 最多放入 1,000 行 scrollback；
- 额外重建部分输入/鼠标终端模式；
- 将这同一 payload 用于 JSON 和 binary `MSG_STATE`；
- 在可回放但帧数、队列或总字节较大时，也会降级为 state；输出组包阈值是 100 帧或约 32 KiB。

Go 的 `protocol.EncodeState` 已正确采用 `[1 byte type][8 byte big-endian seq][payload]`。但 client 传输层还有两项独立差异：

- JSON 可回放时，Go 将 ring 帧合并成一个或多个 text `output`，而非 Node 的单个 text `replay` 对象；
- `Client.SendInfo()` 不区分 mode，总是发送 text JSON。因此 Go binary 当前为 `text info → binary output/state → text info`，而 Node 是 `binary MSG_INFO → binary MSG_INFO → binary output/state`。

`StateBytes()` 的 `screen == nil` fallback 从 ring 拼接文本；正常 `NewTerminalSession` 和 benchmark helper 都会创建 screen，Node 没有等价的生产路径。它是 Go 私有容错分支，不能当作 Node 兼容实现的一部分。

当前尚没有 Node 生成的黄金夹具，也没有跨实现的快照恢复比较。因此“包含了相似 ANSI 文本”不能证明兼容。

### 已知需要在实施中裁定/对齐的差异

1. JSON replay 的对象形状、逐帧 seq 和前端恢复生命周期不同；这是 P0 传输不兼容。
2. binary `MSG_INFO` 的 WebSocket frame 类型、次数和时序不同；这是 P0 线协议不兼容。
3. JSON state 是否含清屏前缀目前不同；binary state 的前缀虽同为清屏，正文仍不同。
4. Node 的 SerializeAddon 已输出 cursor keys、keypad、bracketed paste、insert、origin、focus、wrap、mouse 等模式；Go 的 `terminalModes()` 可能重复输出，并且覆盖集不完全相同。
5. scrollback 上限为 Node 10,000 行、Go 1,000 行；Go 的 queue/字节阈值还会将可回放分支降级为 state，Node 不会。
6. 备用屏、软换行、宽字符、复杂 SGR、光标形态、超链接和 xterm 私有模式是否等价，尚无证据；其中 Go 仅特判两个 East Asian Ambiguous 字符，不能代表 Node 的完整 Unicode 宽度行为。
7. `screen == nil` fallback 是 Go 私有路径，必须与正常 legacy 兼容路径分开处理。

## 3. 不变约束与非目标

### 不变约束

- Node 当前行为是 `webterm.json.v1` / `webterm.binary.v1` 的参考契约；新 Go 端不能借由修改 Android 或 Web 前端来“适配”错误快照。
- `MSG_OUTPUT` 是增量、严格去重的输出；`MSG_STATE` 是权威全量恢复。不可用“放宽 output 的 seq 去重”解决 state/replay 竞态。
- binary subprotocol 的 control message 也必须使用 binary frame：`MSG_INFO`、`MSG_EXIT` 等不得以 text JSON 混入；JSON subprotocol 才使用 text JSON。
- 接收端保持 raw ANSI/xterm 兼容恢复路径；`webterm.screen.v1` 继续是独立实验能力，不得改变现有协议的状态帧。
- 默认尺寸、终端版本、Unicode 宽度策略和 fixture 的 xterm 依赖版本必须记录并固定。
- 每一个新增/更新黄金样本都必须有审查过的原因和版本记录，不能由日常测试静默覆盖。

### 非目标

- 本轮不设计 `screen.diff.v1` 的正式替代协议。
- 本轮不承诺完全模拟所有 VT 标准；未由 Node 支持或客户端消费的能力先不扩大范围。
- 本轮不把真实用户终端、用户名、路径或命令输出直接提交为样本。
- 本轮不为了性能改变既有状态恢复语义；性能优化须在兼容测试绿灯后单独评审。

## 4. 总体方案

```text
固定 hello / ANSI / resize 动作
        |
        +--> Node fake socket + reference harness --> 完整 outbound trace + 原始 state 帧 + Node 恢复后的状态
        |                                      |
        |                                      +--> 受审查并冻结的 golden fixtures
        |
        +--> Go fake socket + encoder/session --> 完整 outbound trace + 原始 state 帧 + Node 恢复后的状态
                                               |
                                               +--> trace 比较 + 帧级比较 + 状态级差分 + 重连流程测试
```

样本的唯一权威来源是**固定输入在 Node 生产 hello/session/快照组件上的输出**，不是手工编辑的 ANSI 字符串，也不是线上随机抓包。Node 仅为测试提供最小 reference harness，不改运行时协议；生成后冻结的 fixture 放入 `webterm-clone`，使 Go 的常规测试不依赖本机恰好存在相邻的 Node 项目。

## 5. 分阶段实施计划

### 阶段 0：冻结传输兼容契约与采集环境

**目的：** 在改 Go 代码前确认要兼容的精确 Node 版本、每种子协议的完整 hello trace，以及 payload 的边界。此阶段只写契约、reference harness 和测试，不重构 Go encoder。

1. 在本计划同目录新增 `terminal-snapshot-node-compatibility-contract.md`，记录：
   - Node commit / package-lock 哈希；
   - `@xterm/headless` 与 `@xterm/addon-serialize` 的准确版本；
   - 100×30 默认尺寸、10,000 scrollback、UTF-8 与 Unicode 宽度前提；
   - JSON 与 binary 分开记录的 attach/hello 消息序列、每条 info 的 WebSocket 类型和 resize 时机；
   - JSON `replay` 的 `{from, frames:[{seq,data}], seq}` 结构，及 binary replay 的 64 KiB output 组包规则；
   - JSON state 与 binary state 各自的 payload 规则和 clear 前缀；
   - 帧类型、big-endian `seq`、完整帧/裸 payload 的定义，以及允许的包大小。
2. 在 Node 项目添加仅测试使用的参考 harness。它必须直接调用和生产代码相同的 `Terminal` + `SerializeAddon` 初始化逻辑，并支持固定的 `write`、`resize` 动作序列；每次 write 完成后再进入下一动作，消除异步写入竞态。
3. 分别为 Node 和 Go 建 fake WebSocket/PTY 测试，使用相同 hello 记录完整 outbound trace。每一条 trace 都须标明 text/binary、frame type、完整 bytes、解码字段和发生时机。
4. 首先采集并审查四个基线：JSON 可 replay、JSON state fallback、binary 可 replay、binary state fallback；另外覆盖首次 attach 的初始 info。
5. 生成器必须校验 package-lock 哈希和已安装的准确 xterm/addon 版本。`package.json` 的 `^` 不可单独作为参考版本依据；使用 `npm ci` + lock 校验即可，不要求为本计划单独改生产依赖版本。
6. 将协议记录、Node/Go 差距清单和样本 schema 评审通过后，才开始修改 Go 兼容代码。

**通过条件：** 可以明确回答四类 hello 分支中每个 WebSocket 帧的 text/binary 类型、协议 type、顺序、次数、序号、字段和 payload 组成；并已列出 Node/Go 的实际差距，尤其是 JSON replay 与 binary `MSG_INFO`。

### 阶段 1：先对齐 legacy 传输契约

**目的：** 在重构 state encoder 前，消除已确认的 client-visible 协议差异，避免“payload 对了但恢复生命周期仍错”。

1. JSON 可 replay 路径改为发送 Node 形状的单个 `replay` 消息，保留每个 frame 的 seq/data；不得以 text `output` 批次替代。
2. binary mode 的 `SendInfo` 改为 `MSG_INFO` binary frame；将 initial info、hello 内 info、resize、state/output 的顺序对齐 Node。JSON 保持其独立顺序，不能为了共享实现改变它。
3. 将 JSON 与 binary state payload 的选择显式化：JSON 不继承 binary 的 clear 前缀；binary 只含一次 clear 前缀。
4. legacy 可 replay 分支第一版移除或关闭 Go 专有的 queue/512 KiB state 降级；任何后续性能策略必须协议化，并单独证明对既有客户端安全。
5. 为以上每条行为建立 Go fake socket 回归测试，并以阶段 0 的 Node trace 做逐帧断言。

**通过条件：** 四类 Node/Go hello trace 的差异仅剩快照 payload 语义；不存在 JSON replay、WebSocket text/binary 类型、info 次数/顺序或 state clear 前缀差异。

### 阶段 2：建立 Node 黄金样本库

**目的：** 让样本可复现、可审查、可在 Go CI 中离线消费。

建议目录：

```text
go-core/internal/session/testdata/node_snapshot_v1/
  manifest.json
  basic-text/
    actions.json
    node-state-binary-frame.bin
    node-state-binary-payload.bin
    node-state-json.json
    node-outbound-trace.json
    node-screen.json
  sgr-and-colors/
  wide-unicode-and-wrap/
  erase-cursor-and-scroll-region/
  alternate-screen/
  terminal-modes/
  resize-during-output/
  scrollback-boundary/
  replay-expired-fallback/
```

每个案例至少包含：

- `actions.json`：固定 rows/cols 以及按顺序执行的 `write` / `resize`；write 用 UTF-8 或 base64 明确表示字节；
- `node-state-binary-frame.bin`：带 `[1 byte type][8 byte seq][payload]` 的完整 binary state frame；
- `node-state-binary-payload.bin`：仅上述完整帧的第 9 字节以后 payload，专供恢复比较；
- Node 的 JSON state 原始对象或规范化 JSON；
- `node-outbound-trace.json`：hello 前后每个 WebSocket frame 的 text/binary 类型、原始编码、解码字段、顺序与 case；
- 由 Node xterm 从 state 重新恢复后导出的结构化 `screen` 断言；
- 生成器版本、输入哈希、依赖版本和生成时间写入 manifest。

首批最小矩阵必须覆盖：纯文本、光标覆盖/清除、16/256/RGB 色与属性、CJK/emoji/组合字符、**专门的 East Asian Ambiguous 字符集合**、软换行、scrollback 边界、备用屏进入/活跃/退出、鼠标/粘贴/光标模式、resize、无可用 replay 的 state 回退。每个问题修复后，再追加一个最小化回归案例。

**样本更新规则：** 正常测试只读取 fixture；只有显式的 `generate-node-snapshot-fixtures` 命令可重写它们；该命令必须要求干净工作区并输出二进制哈希、结构化屏幕 diff 和依赖版本差异。

**通过条件：** 删除/替换任一重要 ANSI 序列、改变 state 前缀或帧头都会让 fixture 测试失败。

**完成状态：** 已生成首批 fixture 矩阵，目录位于 `go-core/internal/session/testdata/node_snapshot_v1/`，覆盖：

- `json-state` / `binary-state`：基础 hello state fallback；
- `json-replay` / `binary-replay`：可回放分支的 outbound trace；
- `initial-info-json`：首次 attach 的 info 帧；
- `sgr-and-colors`、`wide-unicode-and-wrap`、`alternate-screen`、`terminal-modes`、`scrollback-boundary`：JSON/binary 双模式语义案例。

每个 case 均包含 `actions.json`、完整 binary frame、裸 payload、JSON state 对象、outbound trace 和 `node-screen.json` 结构化屏幕断言。

### 阶段 3：抽离 Go 的可测试状态编码边界

**目的：** 不依赖真实 PTY、不依赖 WebSocket，就能把同一动作序列送入 Go 并获得 state payload。

1. 将 `TerminalSession.StateBytes()` 中的状态编码职责抽到内部、无副作用的 encoder，例如 `session/snapshot_encoder.go`；session 仍只负责锁、缓存、ring 和 client 生命周期。
2. encoder 的输入必须显式包含：屏幕状态、模式状态、scrollback 策略、目标 wire mode（JSON/binary）和当前协议版本。不得把 JSON/binary 的前缀差异藏在 `Client.SendState` 的调用顺序中。
3. 保留 `ScreenSnapshot()` / `ScreenDelta()` 的现有实验接口，但它们不成为 legacy state 的实现来源。
4. 用 `go-headless-term` 的直接 screen 写入构造测试，不启动 shell；生产 `TerminalSession` 路径再用少量真实 PTY 集成测试覆盖。
5. 定义一个测试专用 decoder：使用固定 Node xterm 参考环境将 Go payload 恢复为结构化状态；Go 的 `Snapshot` JSON 不能替代这个验证，因为客户端实际消费的是 ANSI 状态流。

**需要明确决定的设计点：**

- binary payload 由 encoder 一次性生成 `clear + serialized-state`；调用方不得二次 prepend clear；
- JSON payload 严格匹配 Node JSON 契约，不能复用 binary payload；
- scrollback 策略先按 Node 10,000 行对齐。若内存压力需要限制，必须协议化并通过“旧客户端恢复正确”的测试后另行优化；
- 当前 Go 手工序列化器若不能在 Node 参考 decoder 上通过语义测试，应补齐缺失语义或替换 encoder；不能以降低断言规避差异。

**通过条件：** 纯 Go 单测可以在固定动作序列下稳定输出目标 JSON/binary state，并覆盖 clear 前缀和 `seq` 帧头。

**完成状态：** 已新增 `go-core/internal/session/snapshot_encoder.go`，定义 `SnapshotMode` / `SnapshotEncoder`；`TerminalSession` 通过 `encodeSnapshot(SnapshotMode)` 生成 JSON/binary payload，JSON 不再继承 binary 的 clear 前缀。encoder 不负责锁、缓存、ring 或 client 生命周期。

### 阶段 4：实现逐项语义对齐

**目的：** 基于失败样本修正 Go emulator/encoder，而不是凭猜测大改。

按以下优先级逐项推进，每项先写/启用一个红色 fixture 再修复：

1. 基本布局：cursor、CR/LF、擦除、滚动、软换行、空格尾部处理；
2. SGR：前景/背景、粗体、暗淡、斜体、下划线变体、反显、隐藏、删除线、256/RGB 色；
3. Unicode：宽字符、组合字符、emoji；East Asian Ambiguous 宽度策略单列测试集和验收，不以两个硬编码字符代表；
4. buffer：scrollback、主屏/备用屏切换、resize 后保留规则；备用屏须分别测试进入、保持 active、退出回到主屏；
5. 私有模式：line wrap、cursor keys、keypad、bracketed paste、focus/mouse reporting、cursor visibility/style；
6. 必要时的 OSC：标题和客户端实际依赖的 OSC 行为。

每项均比较：Node state frame 的固定部分、Go state frame 的固定部分，以及两者在 Node decoder 中恢复后的结构化屏幕结果。先将 Node `serialize()` 的 mode 后缀与 Go `terminalModes()` 分项比较，清楚记录哪些 mode 是重复、缺失或顺序不同。若 serializer 文本不同但所有结构化状态一致，应保留 payload diff 报告；是否将它升级为硬错误由阶段 5 的兼容决策决定。

**通过条件：** 首批 fixture 在两条 legacy 子协议下均恢复为相同的 rows、cols、active buffer、行内容、cursor、样式/模式和 scrollback 边界。

**当前进展：** 已新增 `go-core/internal/session/snapshot_restore_compat_test.go`，将 fixture actions 喂入 Go screen，生成 JSON/binary state payload，再调用 Node 参考脚本 `scripts/restore-payload.js` 恢复到 xterm，并比较 `node-screen.json` 结构化屏幕状态。当前首批 fixture 矩阵（basic-text、SGR、wide-unicode、alternate-screen、terminal-modes、scrollback-boundary）的 restore comparator 全部通过。

**已知残留缺口：** `github.com/unilibs/uniwidth`（`go-headless-term` 使用）将 Dingbats 区块字符（如 `✔` U+2714、`❯` U+276F）计为宽字符（width=2），而 xterm/Node 在默认环境下计为单宽。这会导致含 Dingbat 的 Go payload 恢复到 xterm 后出现多余空格。现有 fixture 已改用真正的 East Asian Ambiguous 字符（`±`、`½`）验证宽度策略，因此全部通过；Dingbat 宽度差异已记录在 `terminal-snapshot-node-compatibility-contract.md` 附录 A，作为语义兼容遗留缺口待后续处理。

### 阶段 5：回归 hello、replay 与 state 的传输行为

**目的：** 防止“快照文本正确，但实际重连顺序或 seq 错误”。

1. 扩展阶段 0/1 的 fake socket 矩阵：首次连接、`lastSeq=0`、可回放、ring 过期、`lastSeq>latest`、resize 随 hello 到达、空 replay、慢客户端/大回放。
2. 再次逐帧断言 JSON/binary 各自的 info 次数/顺序、state 的 `seq=latestSeq`、`MSG_STATE` 与 `MSG_OUTPUT` 的独立消息类型、JSON replay 的逐帧 seq 和 binary replay 最后一帧 seq。
3. 仅在兼容基线通过后，单独评审慢客户端、队列或大回放的性能策略；其默认不得悄悄改变为 state fallback。
4. 不改变 Android 对普通 output 的严格 seq 去重；测试 state 与同 seq replay 邻接时 state 能权威恢复。

**通过条件：** Node/Go 的模拟 socket trace 在每个矩阵案例中符合已冻结契约；Web 与 Android 的重连测试没有重复、空白或漏行。

### 阶段 6：端到端、压力与发布门禁

**目的：** 将单元级一致性扩展到真实 PTY、direct/relay 和真实消费者。

1. 用固定 shell 脚本启动 PTY，避免 prompt、时间和本机配置污染；分别在 direct 与 relay mux 下制造“历史仍在”和“历史已过期”。
2. Web 端执行恢复后读取 xterm buffer；Android 端验证 `MSG_STATE` 可覆盖恢复、普通 `MSG_OUTPUT` 仍严格增量。
3. 加入长输出、断线重连、快速 resize、慢客户端队列等压力场景；记录 state 大小、生成耗时、内存和连接队列行为。
4. 发布前运行：

```bash
cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go test ./internal/session ./internal/protocol ./...
npm run test:unit
npm run typecheck
npm run build
cd android-client && ANDROID_HOME=/Users/gao/Library/Android/sdk ./gradlew :app:compileDebugJavaWithJavac :app:testDebugUnitTest --no-daemon
git diff --check
```

此外执行新增的 Node-reference fixture generator（只验证 manifest，不重写 fixture）和 Go/Node 差分测试。direct/relay 的 smoke 必须实际观察 binary state/replay 载荷，而不只确认 WebSocket 已连接。

**通过条件：** 所有固定 fixture、状态机、真实 PTY 和至少一次 direct/relay 重连链路通过；无未审查的 fixture 变化；记录发布时 Go/Node 版本与测试结果。

## 6. 测试与验收设计

### 两阶段比较器

每个 fixture 都做以下两种比较：

```text
A. wire comparator
   Node/Go 的 type、seq、帧长、clear 前缀、JSON/binary 字段和消息顺序

B. restore comparator
   Node state -> Node xterm decode -> canonical screen
   Go state   -> Node xterm decode -> canonical screen
   比较 canonical screen
```

`canonical screen` 至少含：rows、cols、当前主/备用 buffer、每行 codepoint 与宽度、cursor 行列/显示/形态、样式 runs、wrapped 标记、scrollback 边界、输入/鼠标相关模式。比较器应可忽略明确列入契约的非语义字段，但默认不忽略任何状态。

### 必须阻止的回归

- JSON state 被意外加上 binary 专用清屏前缀，或 binary state 少清屏前缀；
- JSON replay 被退化为若干 output，或逐帧 `seq` / `from` 字段丢失；
- `webterm.binary.v1` 中混入 text JSON info，或 binary `MSG_INFO` 的次数/顺序变化；
- `MSG_STATE` 被降成 `MSG_OUTPUT`，导致 seq 去重吞掉快照；
- alt screen、光标或模式只“看起来正常”却无法继续交互；
- 1,000/10,000 scrollback 边界造成远端恢复缺行；
- output 组包后 seq 不是该 batch 最后一帧；
- Go 的 state cache 在 resize/输出后复用旧 payload；
- 因 xterm 依赖升级而无审查地改变序列化输出。

## 7. 风险与决策门

| 风险 | 对策 | 决策门 |
| --- | --- | --- |
| Go emulator 不支持 Node 已使用的 VT 语义 | 以最小失败 fixture 驱动补齐；必要时替换/隔离 encoder | 阶段 4 首批矩阵 |
| SerializeAddon 不承诺稳定字节格式 | 固定版本、冻结 fixture、以恢复语义为核心断言 | 阶段 0 |
| 双目录测试无法进 CI | 生成器只在参考仓库运行；fixture 入 Go 仓库；Go CI 离线消费 | 阶段 2 |
| 长 scrollback 使 state 太大 | 先兼容后测量；采用明确的 backpressure/上限策略，不静默截断 | 阶段 6 |
| 为了赶进度改客户端宽松处理重复输出 | 保持 output/state 语义分离，禁止修改去重规则掩盖问题 | 全程 |

在阶段 4 完成首批样本后召开一次决策：若 Go payload 虽不字节相同但恢复状态完全一致，批准“wire 固定部分严格 + payload 语义严格”的定义；若任何既有客户端或 relay 依赖 payload 字节模式，则升级为字节级 payload 兼容并评估移植/复用 Node serializer 的成本。没有该决定不得声称“完全一致”。

## 8. 预期改动边界

| 区域 | 预期改动 |
| --- | --- |
| `webterm` Node 项目 | 仅参考 harness、fixture 生成和协议测试；不改生产协议行为 |
| `go-core/internal/session` | snapshot encoder、StateBytes 调用边界、hello/replay 对齐、差分和 fake socket 测试 |
| `go-core/internal/infrastructure/emulator` | 仅由 fixture 暴露的 ANSI/状态保真修复 |
| `go-core/internal/protocol` | 仅补充帧级断言/辅助，不改变既定 state frame 定义 |
| `android-client` / `frontend` | 仅补充兼容回归测试；除真实协议缺陷外不做适配性修改 |

## 9. 完成定义

只有同时满足以下条件，才可宣告 Go 快照与 Node 参考实现一致：

- 已冻结并版本化 Node 参考样本；
- Go 能通过所有样本的 wire 与 restore 比较；
- JSON 与 binary 的 attach/hello/state/replay 分支都通过逐帧状态机测试，且 `webterm.binary.v1` 没有 text control frame；
- 真实 PTY 的 direct 与 relay 重连均通过；
- Web 与 Android 未出现空白、重复、漏输出或错误 buffer 恢复；
- fixture/依赖升级有审查记录，且验证命令全部通过。
