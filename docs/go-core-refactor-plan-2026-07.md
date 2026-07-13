# Go Core 架构清理与重构计划（2026-07）

## 背景

本文档依据 2026-07-13 的架构审查结论制定。此前三轮重构（`go-core-pc-agent-refactor-plan.md`、`go-relay-server-refactor-plan.md`、`relay-unified-mux-refactor-plan.md`）的核心目标均已兑现：依赖图无环，agent 侧与 relay 服务端严格分离，`session.Socket` + `application.SessionRouter` 把 direct / mux / relay 三种传输统一到了一条路径上。

本轮不解决架构方向问题，只清扫三轮重构留下的残留和审查中暴露的质量洼地：

- 约 29MB 编译产物和运行日志被 git 跟踪（`session.test` 7.8MB、`webterm` 4.0MB、`webterm-relay-e2e-smoke` 17MB、`webterm-agent-direct.log`）。
- 三个 smoke 工具仍在说已被 `internal/application/session_router.go:91-97` 强制拒绝的 `webterm.binary.v1` 协议，旧协议代码在生产路径已成死代码。
- `session.TerminalSession`（730 行、24 字段）是 God struct，文件下载业务直接长在终端会话里。
- `terminalsession/runtime.go`（791 行，全仓最大非测试源文件）零直接测试。
- `mux ↔ application` 的循环依赖靠 4 个影子接口 + 2 个适配器硬绕开，而循环的唯一来源是 `mux/handler.go` 一个 38 行的文件。
- direct 模式的 session PATCH/DELETE 绕过共享路由重写了一遍，行为已分歧。
- 协议字面量、HTTP 助手函数在多包复制；`relaystore` 8 接口 1 实现；`relaycore` 是杂物包；装配层包命名混乱（`app` / `application` / `runtime` / `control` / `hook` / `agenthooks`）。

## 目标

```text
1. 仓库不再跟踪任何编译产物和运行日志
2. 死协议（webterm.binary.v1 / webterm.json.v1）代码从生产路径和 smoke 工具中清除
3. tunnel/terminal 协议常量确立 Go 侧单一权威源，并提供跨语言一致性校验
4. mux 装配层删除影子接口与适配器，direct/relay 两侧走同一种接法
5. direct 模式 HTTP API 与共享路由行为一致
6. TerminalSession 只保留终端职责，下载业务独立
7. terminalsession 核心路径有 actor 级单测
```

## 非目标

```text
不改变任何对外协议格式与 HTTP API
不重写 relay 服务端架构（relaycore/relaystore 只做收敛，不做重新设计）
不升级或替换 headlessterm，只修正其 module 身份与依赖边界
不做包改名之外的任何大规模重排（改名是独立可选项，见 Phase 2.4）
不引入新依赖
```

## Phase 0：工程卫生（0.5 人日）

### 0.1 清除已跟踪的编译产物

- `git rm --cached go-core/session.test go-core/webterm go-core/webterm-relay-e2e-smoke go-core/webterm-agent-direct.log`
- `go-core/.gitignore` 从一行扩为：

```gitignore
.gocache/
bin/
session.test
webterm
webterm-agent
webterm-relay
webterm-*
*.log
```

- 所有构建产物统一输出到 `go-core/bin/`（同步更新 `run-agent.sh` 与 `package.json` 的 `start` 脚本中的路径）。

### 验收

- `git ls-files go-core | grep -vE '\.(go|mod|sum)$|\.gitignore'` 只剩 `headlessterm` 的 LICENSE/README。
- `go build ./...` 通过。

## Phase 1：协议收敛与 smoke 修复（2-3 人日）

### 1.1 smoke 工具改说 screen 协议

现状：`cmd/webterm-flow-smoke/main.go:133`、`cmd/webterm-relay-e2e-smoke/main.go:481`、`cmd/webterm-relay-flow-smoke/main.go:138` 用 `protocol.BinarySubprotocol` + `MsgHello/MsgInput/MsgOutput` 探测终端，握手必被 `RouteOpen` 拒绝。

- 三个 smoke 的终端探测改为：握手 `webterm.screen.v1`，发 `Hello`，校验 protobuf `ScreenEnvelope`。
- `cmd/webterm-relay-storm-smoke` 若不涉及终端探测则不动，否则一并改。
- 同步检查 `scripts/smoke-go-relay-server.mjs`（薄包装，无需改）和 `scripts/smoke-web-go-relay-pc-agent.mjs`（其断言逻辑按 JS 侧审查结论迁往 `tests/e2e/`，不在本计划范围）。

### 1.2 删除死协议代码

在 1.1 完成、smoke 全绿之后：

- 删 `internal/protocol/terminal_binary.go` 及其测试。
- 删 `protocol` 包中 `BinarySubprotocol` / `JSONSubprotocol` 常量与 `MsgInput/MsgOutput/EncodeSequencedData/DecodeSequencedData`（先 grep 确认生产代码无引用，目前消费者只剩自身）。
- 删 `internal/mux/session.go:235` 的 `BinarySubprotocol` 死分支。
- 清理重构残留（同一 commit 或紧邻 commit）：`app.App.Run`（无调用方）、`session/event_ring.go`（包外无使用）、`config.NormalizeRelayProtocol` 与 `RelayProtocolV2`、`session.ClientMode` 死参数、`cmd/webterm-agent/main.go:68-70` 的 "runtime scaffold" 日志措辞。

### 1.3 协议常量单一权威源

- 权威源定为 `go-core/internal/protocol/constants.go`。
- mux 控制消息类型字符串（`"ws-connect"` 等，目前散落硬编码）集中到 `protocol` 包导出常量。
- relay 控制面 `"agent.register"` 字面量三处（`relaygateway/agent_gateway.go:19`、`relay/client_v2.go:21`、`control/connection_test_api.go:22`）收敛为 `relaycore` 一处导出。
- 编写 `scripts/generate-constants.mjs`：从 Go 常量文件生成 `shared/constants.js` 与 `frontend/src/types/shared-constants.d.ts`（替换手写镜像），CI 加"生成物与源一致"校验。Java 侧由安卓计划 Phase 1 消费同一来源。

### 1.4 跨语言编解码 fixture 测试

- 在 `tests/fixtures/` 放一组 tunnel 帧字节样例（覆盖各 MsgType、空 payload、最大 id 长度）。
- Go 侧加 `internal/protocol/tunnel_fixture_test.go` 读取 fixture 互验；前端 vitest 与安卓 JUnit 侧由各自计划接入同一 fixture。
- 目的：任一端改帧格式或常量，三端测试同时红。

### 验收

- 三个 smoke 工具对 direct 与 relay 链路全绿。
- `grep -rn "MsgInput\|MsgOutput\|BinarySubprotocol" go-core/internal go-core/cmd` 无生产代码命中。
- `grep -rn '"agent.register"' go-core` 只有一处定义。
- CI 中 constants 生成校验与 Go fixture 测试通过。

## Phase 2：装配层简化（2 人日）

### 2.1 消除 mux 影子接口

- 把 `internal/mux/handler.go`（38 行，循环依赖的唯一来源）挪到 wiring 层：可放入 `cmd/webterm-agent` 的装配代码，或新建 `internal/wiring` 包。
- 让 `internal/application` 直接 import `internal/mux`，删除 `session_router.go:22-41` 的 `MuxServeFunc/MuxSession/MuxVirtualSocket/MuxServeOpts` 四个影子接口、`mux.MuxServeAdapter`、`mux.OpenSessionOrManager`、`NewSessionRouterWithMux` 可变参数构造函数。
- 统一装配路径：direct（`direct/server.go:138` 直接调 `mux.Serve`）与 relay agent（`relay/client_v2.go:37` 走适配器）改为同一种接法。

### 2.2 direct 路由回归共享实现

- 删 `internal/direct/server.go:256-282` 的 `routeSession`，让 `/api/sessions/{id}` 走 `application.SessionRouter.RouteHTTP`。
- 顺带修复两侧行为分歧：`routeSession` 缺少的 `url.PathUnescape` 在共享实现中已有。
- 补一个含空格/转义字符 session id 的 PATCH/DELETE 测试，direct 与共享路由各跑一遍。

### 2.3 构造函数风格统一

- 统一为"一个 `New` + Options struct"：`relayrouter`（4 个变体，`stream_manager.go:41-62`）、`relayapp`（3 个）、`relaygateway`、`relaycontrol`、`relaymetrics` 逐一收敛。
- 删除 `ForTest` 变体，改用 unexported 构造或 Options 注入。
- `session.NewManager(defaults ...TerminalDefaults)` 与 `NewClient(..., logger ...*logs.Logger)` 的可变参数改为显式参数或 Options（当前传第二个 logger 被静默忽略）。

### 2.4 包命名（可选，独立 commit）

二选一：

- 保守方案：给 `app` / `application` / `runtime` / `control` / `hook` / `agenthooks` 六个包装配层补包级 doc comment，说明职责与依赖方向。
- 彻底方案：纯机械改名（`app→corestate`、`application→sessionapi`、`runtime→supervisor`、`hook→hooksocket`），单独一个 commit 完成，不与任何逻辑改动混合；注意 `runtime` 与 stdlib 冲突已迫使 `cmd/webterm-agent/main.go:18` 起别名 `agentruntime`。

### 验收

- `go vet ./...` 干净，`go test ./...` 全绿。
- `internal/application` 无 mux 影子类型；`grep -rn "MuxServeAdapter\|OpenSessionOrManager"` 无命中。
- 每个包对外构造函数 ≤ 2 个（`New` + 必要时 `NewForTest` 的 Options 替代形式）。
- 含转义字符的 session id 在 direct 模式下 PATCH/DELETE 正常。

## Phase 3：领域拆分与关键测试（3-4 人日）

### 3.1 拆分 TerminalSession 的下载职责

- 把 `internal/session/terminal.go:532-658` 的 `handleDownloadCommand`（127 行：文件 stat/open、任务注册、10 分钟超时状态机）与 `Manager` 中的 download task 表抽到独立类型 `session.Downloads`（或并入 `fsops`，同时解决 `fsops` 全包一个函数的问题）。
- 解除 `Manager ↔ TerminalSession` 双向引用：TerminalSession 不持有 `manager` 指针，改为通过事件回调或窄接口上报。
- 顺带把 `manager.go:353-383` 的平台相关 PID/TTY 解析（`getParentPID/getTTYPathByPID`）移到 `internal/infrastructure/pty` 或独立文件，Manager 只持有接口。

### 3.2 terminalsession 补 actor 级单测

`internal/terminalsession/` 当前 0 测试文件。按回归风险优先级补：

- `LeaseManager`（layout_lease.go）：租约获取/续期/过期/抢占。
- history 请求与裁剪（runtime.go 中 history 分页与驱逐逻辑，最近三个 commit 都在改这里）。
- resize epoch 切换：投递 resize event，验证快照与 epoch 变化。
- 测试方式：直接构造 Runtime、投递 event、断言效果，不经客户端层。

### 3.3 小型结构修复

- `config → terminalengine` 反向依赖（`config.go:8,25-26` 为复用两个 scrollback 默认值）：常量下沉到 `config`，`terminalengine` 引用 `config`。
- `runtime.Supervisor` 启动判断（`supervisor.go:141-151` 的 100ms 竞态窗口）：Runner 接口增加显式 ready 信号（如 `ListenAndServe(ctx, ready chan<- error)`）。
- `relaystore` 8 个接口收敛为 1 个（唯一实现是 `MemoryStore`，测试也用真实现，抽象无消费者）；等出现第二个实现再抽象。
- `relaycore` 按"帧协议 / 领域模型 / 事件总线"拆分为明确文件归属或子包，认证助手（`shared.go` 的 `BearerToken/AuthCookieName`）随 1.3 的常量收敛一并归位。
- HTTP 助手收敛：`writeJSON` 四份（`direct/server.go:302`、`control/server.go:286`、`relaycontrol/server.go:91`、`relaymetrics/debug_handlers.go:139`）收进一个 `internal/httputil` 小文件；`stringValue`、`cleanPath`、`randomID` 的重复同理处理。

### 3.4 headlessterm 身份与边界

- `internal/headlessterm/go.mod` 的 module 名从伪装的 `github.com/danielgatis/go-headless-term` 改为真实路径（如 `webterm/go-core/internal/headlessterm`），移除父 go.mod 的 `replace` 伪装，让"这是本地改过的 fork"可见（已有 3 个本地修改 commit）。
- 删除 fork 中 WebTerm 用不到的 `examples/` 与 kitty 图片协议代码。
- `internal/screenprojection/exporter.go:6` 改为只消费 `terminalengine` 类型，不直接 import headlessterm，恢复 terminalengine 作为唯一边界。

### 验收

- `session/terminal.go` 降至 400 行以内，TerminalSession 不再含下载与 manager 反向引用字段。
- `internal/terminalsession/` 新增 ≥ 3 个测试文件，覆盖上述三条路径。
- `grep -rn "headlessterm" internal/screenprojection` 无命中；headlessterm 以真实 module 路径被引用。
- `go test ./...` 全绿，测试包数从 23 上升。

## 风险与回滚

- Phase 1.1 的 smoke 改造可能暴露 screen 链路的真实回归——这正是目的，但需预留修复时间；smoke 未绿之前不启动 1.2 的删除。
- Phase 1.2 / 2.1 / 2.4 都是删除与改名类改动，每个子任务独立 commit，均可单独 revert；2.4 改名必须纯机械、不夹带逻辑。
- Phase 3.1 触及终端核心，需先有 3.2 的部分测试垫底，或至少先补下载链路的集成测试再动手。
- 跨语言 fixture（1.4）需要安卓侧 JUnit 能读同一目录，与安卓计划 Phase 1 同步落地，避免一端先改格式。

## 与安卓计划的联动点

- 协议常量单一源（1.3）与 fixture 测试（1.4）由本计划产出源头，安卓计划 Phase 1 消费；两端应同批次进入 CI。
- mux 控制消息类型常量化后，安卓 `MuxSession.java:83,104,204-216` 的硬编码字符串才能安全替换。

## 工作量汇总

```text
Phase 0  工程卫生              0.5 人日
Phase 1  协议收敛与 smoke       2-3 人日
Phase 2  装配层简化             2   人日
Phase 3  领域拆分与测试         3-4 人日
合计                            7.5-9.5 人日
```
