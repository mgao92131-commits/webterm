# webterm.json.v1 / webterm.binary.v1 重连快照兼容契约

**状态：** 已冻结（随计划实施前评审通过）
**制定日期：** 2026-07-10
**适用范围：** `webterm-clone` 的 Go PC Agent 与 `/Users/gao/Documents/webterm` 的 Node PC Agent。

本文件记录 Node 参考实现的精确行为，作为 Go 实现的验收基准。任何与本契约冲突的 Go 行为都属于不兼容，除非显式修订本契约。

## 1. 参考环境与版本

| 项目 | 值 |
|------|-----|
| Node 仓库路径 | `/Users/gao/Documents/webterm` |
| Node commit | `b629555952712c0a029e9f5fddb2c036376cf52e` |
| `package-lock.json` SHA-256 | `6169f7f7620237712a013d1904dddc1728e4c9adcb1a06dfafc4d3e63b591b9e` |
| `@xterm/headless` | `6.0.0` |
| `@xterm/addon-serialize` | `0.14.0` |
| Go 仓库 commit | `4fb5a17a3e21beee6832c55ad77952b42d875dcc` |
| `go-core/go.mod` SHA-256 | `5369c1b218c76fd7a2df06fc86a0b3f614a939b027c4abe85bd79807d7ab3f15` |
| `go-core/go.sum` SHA-256 | `56cf75240ad11a39970350f2a965912a7d9e5e730c4c7881593411eeb63eabb3` |

fixture 生成器必须在生成前校验上述 Node `package-lock.json` 哈希与已安装包的 `package.json` 版本。`package.json` 中的 `^` 不能单独作为版本依据；生成脚本应使用 `npm ci` 或显式校验 lock hash。

## 2. 终端初始化参数

| 参数 | Node | Go（目标） |
|------|------|------------|
| 默认 cols | 100 | 100 |
| 默认 rows | 30 | 30 |
| scrollback | 10,000 行 | 10,000 行 |
| 编码 | UTF-8 | UTF-8 |
| Unicode 宽度 | xterm.js 默认（含完整 East Asian Ambiguous 表） | 须与 Node 恢复结果一致 |
| `windowsMode` | 仅 `process.platform === 'win32'` | 不适用（macOS/Linux） |
| `allowProposedApi` | `true` | 无直接对应项，但需保证输出等价 |

## 3. 帧格式定义

### 3.1 公共消息类型

与 `shared/constants.js` 和 `go-core/internal/protocol/constants.go` 保持一致：

| 类型 | 字节值 |
|------|--------|
| `MSG_INPUT` | `0x01` |
| `MSG_OUTPUT` | `0x02` |
| `MSG_RESIZE` | `0x03` |
| `MSG_HELLO` | `0x04` |
| `MSG_INFO` | `0x05` |
| `MSG_EXIT` | `0x06` |
| `MSG_PING` | `0x07` |
| `MSG_PONG` | `0x08` |
| `MSG_TITLE` | `0x09` |
| `MSG_STATE` | `0x0a` |

### 3.2 定序数据帧（`MSG_OUTPUT` / `MSG_STATE`）

```text
[1 byte type][8 byte seq (big-endian uint64)][payload...]
```

- `seq` 为产生该数据时终端的 `latestSeq`。
- 对于 `MSG_OUTPUT`，`seq` 是 ring 中该 output frame 的序号；批量合并时取 batch 内最后一帧的 `seq`。
- 对于 `MSG_STATE`，`seq` 是快照生成时刻的 `latestSeq`。

### 3.3 JSON 控制帧（`MSG_INFO` / `MSG_EXIT` / `MSG_PONG`）

binary subprotocol 中：

```text
[1 byte type][UTF-8 JSON payload]
```

例如 `MSG_INFO` 的 payload 是直接的 `Info` JSON 对象字节：`{"id":"...","cols":100,...}`。它不包裹 JSON subprotocol 使用的 `{"type":"info","data":...}` 外层。

JSON subprotocol 中：所有控制消息都是 WebSocket text frame，内容为 JSON 对象，例如 `{"type":"info","data":{...}}`。

## 4. attach / hello 消息序列

### 4.1 JSON subprotocol (`webterm.json.v1`)

**attach 时（连接建立立即发送，不等待 hello）：**

```text
WebSocket text: {"type":"info","data":<Info对象>}
```

**收到 hello 后：**

1. 若 `hello.cols` / `hello.rows` 有效且与当前不同，执行 resize。
2. 计算 `latestSeq = ring.latestSeq()`。
3. 若 `lastSeq > 0 && lastSeq <= latestSeq && ring.canReplayFrom(lastSeq)`：
   - 发送一条 text `replay`：
     ```json
     {
       "type": "replay",
       "from": <lastSeq>,
       "frames": [
         {"seq": <frameSeq>, "data": <frameText>},
         ...
       ],
       "seq": <latestSeq>
     }
     ```
   - 必须保留每个 ring frame 的独立 `seq` 和 `data`；不允许合并为单个 `output` 消息。
4. 否则：
   - 发送一条 text `state`：
     ```json
     {"type":"state","seq":<latestSeq>,"data":<serializeAddon.serialize()>}
     ```
   - `data` 内**不得**包含 `ESC[3J ESC[2J ESC[H` 前缀。
5. 再发送一条 text `info`。
6. 标记 client ready，后续正常 `output` 开始增量下发。

### 4.2 Binary subprotocol (`webterm.binary.v1`)

**attach 时（连接建立立即发送，不等待 hello）：**

```text
WebSocket binary: [MSG_INFO][<Info对象> 的 UTF-8 JSON]
```

**收到 `MSG_HELLO` 后：**

1. 发送一条 binary `MSG_INFO`（与 attach 时结构相同，内容是最新 Info）。
2. 若 `hello.cols` / `hello.rows` 有效且与当前不同，执行 resize。
3. 计算 `latestSeq = ring.latestSeq()`。
4. 若 `lastSeq > 0 && lastSeq <= latestSeq && ring.canReplayFrom(lastSeq)`：
   - 取 `frames = ring.after(lastSeq)`。
   - 按顺序合并为 `MSG_OUTPUT` batch，batch 字节上限 **64 KiB**。
   - 每个 batch 的 `seq` 为该 batch 中最后一帧的 `seq`。
   - 每个 batch 的 payload 为合并后的原始字节（**不**加清屏前缀）。
5. 否则：
   - 生成 payload：
     ```text
     payload = ESC[3J ESC[2J ESC[H + serializeAddon.serialize()
     ```
   - 发送 binary `MSG_STATE`：
     ```text
     [MSG_STATE][uint64 big-endian seq=<latestSeq>][payload]
     ```
6. 标记 client ready，后续正常 `MSG_OUTPUT` 开始增量下发。

> 注意：binary hello **不**在 state/output 后再发一次 `MSG_INFO`；JSON hello 会在 state/replay 后再发一次 text `info`。

## 5. State payload 规则

### 5.1 JSON state payload

- payload = `serializeAddon.serialize()` 的返回值字符串。
- **不得** prepending `ESC[3J ESC[2J ESC[H`。
- WebSocket text frame 内字段为 `"data": <payload>`。

### 5.2 Binary state payload

- payload = `ESC[3J ESC[2J ESC[H` + `serializeAddon.serialize()`。
- 外层为 `MSG_STATE` 定序数据帧：`[0x0a][seq BE64][payload]`。

### 5.3 Clear 前缀语义

- `ESC[3J`：清除回卷缓冲（xterm 私有）。
- `ESC[2J`：擦除屏幕。
- `ESC[H`：光标归位。

该前缀只在 binary state 中使用；JSON state 由前端/客户端在收到 `state` 时自行清屏，因此不需要也不应包含此前缀。

## 6. Replay 规则

### 6.1 JSON replay

- 单条 text `replay` 消息，含完整 `frames` 数组。
- 每个元素必须恰好保留 Node JSON wire format 使用的 `seq` 和 `data`；`text`、`bytes` 仅是 Node 服务端内部 ring 字段，不属于 JSON wire payload。
- 不得将 replay 拆成多条 `output` 消息。

### 6.2 Binary replay

- 一条或多条 binary `MSG_OUTPUT`。
- 合并阈值：连续 frame 字节累计超过 **64 KiB** 时 flush 当前 batch。
- batch `seq` 为 batch 内最后一帧的 `seq`。
- 不允许为了性能将可回放分支降级为 `MSG_STATE`；降级只发生在 `lastSeq` 无法 replay 时。

## 7. 消息顺序矩阵（hello 分支）

| 场景 | JSON 顺序 | Binary 顺序 |
|------|-----------|-------------|
| attach | text `info` | binary `MSG_INFO` |
| 可 replay | resize → text `replay` → text `info` | binary `MSG_INFO` → resize → binary `MSG_OUTPUT`... |
| 不可 replay（state fallback） | resize → text `state` → text `info` | binary `MSG_INFO` → resize → binary `MSG_STATE` |

`seq` 规则：
- `replay.from` = hello 中的 `lastSeq`。
- `replay.seq` / `state seq` / 最后一个 `output seq` = 快照/回放时刻的 `latestSeq`。

## 8. Fixture schema

每个 fixture case 目录下至少包含：

- `actions.json`：固定的 `rows`/`cols` 初始值，以及按顺序执行的 `write` / `resize` 动作；`write` 可用 `bytes`（base64）或 `text`（UTF-8）表示。
- `node-state-binary-frame.bin`：完整 binary state 帧（含 9 字节头部）。
- `node-state-binary-payload.bin`：仅 `node-state-binary-frame.bin` 的第 9 字节之后的 payload。
- `node-state-json.json`：JSON state 的 text 消息对象（`{"type":"state","seq":N,"data":"..."}`）。
- `node-outbound-trace.json`：attach 及 hello 分支中每个 WebSocket frame 的 `text/binary`、原始 bytes（base64）、解码字段、顺序、场景。
- `node-screen.json`：从 state payload 恢复后导出的结构化屏幕状态，供 restore comparator 使用。
- `manifest.json`：生成器版本、输入哈希、依赖版本、生成时间。

## 9. 允许大小与限制

- Binary replay batch 上限：**64 KiB** 合并字节。
- Event ring 上限由 Node `server/event-ring.js` 决定：`maxFrames = 20000`，`maxBytes = 5 MiB`。Go 须对齐或记录差异。
- 默认终端尺寸限制：cols ∈ [10, 500]，rows ∈ [5, 200]。
- State scrollback：本契约要求 10,000 行；任何缩减必须协议化并单独验证旧客户端安全。

## 10. 修改流程

1. 更新本契约需同步更新 Node commit / lock hash / xterm 版本。
2. fixture 更新必须通过显式 `generate-node-snapshot-fixtures` 命令，且要求干净工作区。
3. 每次 fixture 更新须在 manifest 中记录原因、新旧屏幕 diff 和依赖版本差异。
4. Go 实现若因技术原因无法完全字节对齐 payload，须在阶段 4 完成首批 fixture 后召开决策评审，记录为契约附录，不得在未记录的情况下放宽断言。
5. restore comparator 使用仓库内的 `testdata/node_snapshot_v1/restore-reference.mjs`；CI 必须设置 `WEBTERM_NODE_ROOT` 到已执行 `npm ci` 且 lock hash 匹配的 Node reference checkout，缺失该环境应失败而不是跳过测试。

## 11. 附录 A：payload 字节级兼容 vs 语义级兼容决策

**决策日期：** 2026-07-10
**决策：** 当前以 *restore comparator*（恢复后的结构化屏幕状态）作为 `MSG_STATE` payload 的验收标准；在 wire 固定部分（frame type、seq、9 字节头、clear 前缀有无、JSON/binary 字段结构）严格一致的前提下，**不强制要求 payload 字节逐字相同**。

### 决策依据

- `@xterm/addon-serialize` 不承诺序列化字节格式稳定；不同 patch 版本可能输出等价但字面上不同的 ANSI 序列。
- 客户端实际消费的是恢复后的屏幕状态（行内容、光标、样式、模式、buffer 边界），而不是 payload 的原始字节。
- Go 使用的 `go-headless-term` 与 Node xterm 内部状态机不同，强行逐字节复刻 SerializeAddon 输出会引入大量脆弱耦合，且收益不明确。

### 比较方法

- **wire comparator**：严格比较 frame type、`seq`、帧长、JSON/binary 字段、消息顺序、binary state 的 clear 前缀有无。
- **restore comparator**：将 Node payload 与 Go payload 分别喂入同一 Node xterm 参考环境，比较导出的 `rows`/`cols`、buffer、每行 `text`、cursor、`wrapped` 标记、样式 runs 和相关模式。

### 已知的语义兼容缺口

| 缺口 | 根因 | 当前影响 | 处理状态 |
| --- | --- | --- | --- |
| Dingbats 字符宽度不一致 | Go 依赖的 `github.com/unilibs/uniwidth`（`go-headless-term` 使用）将 Dingbats 区块字符（如 `✔` U+2714、`❯` U+276F）计为宽字符（width=2），而 xterm/Node 在默认中性环境下计为单宽（width=1）。 | 含这些字符的行在 Go 生成 payload 并恢复到 xterm 后可能出现多余空格，导致 `lines[].text` 与 Node 参考状态不一致。 | 已记录；现有 fixture 改用真正的 East Asian Ambiguous 字符（如 `±` U+00B1、`½` U+00BD）验证 Ambiguous 宽度策略，因此当前所有 fixture 测试通过。Dingbat 宽度差异作为遗留缺口保留，待后续协议化或修复。 |

### 约束与后续触发条件

- 若发现任何既有客户端或 relay 依赖 payload 字节模式（例如对 state payload 做哈希校验、文本拼接或长度断言），须立即重新评估本决策，并评估移植/复用 Node SerializeAddon 或要求字节级对齐的成本。
- 新增 fixture 若触发 restore comparator 失败，必须先尝试修复 Go emulator/encoder；不得以“字节不同但看起来一样”为由降低断言，除非经本附录流程再次评审并记录。
