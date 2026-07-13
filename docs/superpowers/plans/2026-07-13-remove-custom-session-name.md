# 移除自定义终端会话名称 Implementation Plan

**Goal:** 完整移除 WebTerm 自维护的自定义会话名称能力，让所有界面、通知和会话列表只使用终端通过 OSC 上报的 `termTitle`，空标题统一回退为 `Terminal`。

**Architecture:** 这是一次三端协调的会话信息契约收缩。Go 会话模型不再保存或输出 `name` / `displayTitle`，创建接口不再接收 `name`，`PATCH /api/sessions/{id}` 重命名接口被删除；Web 与 Android 同步删除名称编辑入口、参数传递和缓存字段。终端标题的唯一业务来源保持为 PTY/终端模拟器解析出的 `termTitle`。Agent hook 通知标题改为直接读取 `termTitle`，并保留空标题回退规则。

**Tech Stack:** Go 1.25、Vue 3 + TypeScript + Vitest、Android Java/Kotlin + Gradle/JUnit、Go/前端/Android 共享的终端消息契约。

## Global Constraints

- 不增加颜色、别名或其他替代性可编辑元数据。
- 不保留隐藏的重命名 API、空实现、兼容字段或废弃参数；仓库尚未发布，采用协调删除。
- `termTitle` 及其 OSC 解析、`titleChanged` 广播、Web 文档标题更新、Android 标题实时刷新必须继续工作。
- `name` 与 `displayTitle` 从会话 JSON 契约中一起删除；客户端展示统一使用 `termTitle.trim() || "Terminal"`。
- `POST /api/sessions` 仍允许携带 `cwd`，但不再解析 `name`；`PATCH /api/sessions/{id}` 删除后应返回 `405 Method Not Allowed`，而不是静默成功。
- Android 本地磁盘缓存中的旧 `sessionName` 字段采用宽松读取：新代码忽略未知旧字段，不做迁移，也不因旧缓存存在而恢复别名。
- `MSG_TITLE` 不属于本次功能替代方案：当前 Go 端对客户端发送的 `MsgTitle` 不执行更新。本次不扩展该协议，只验证 PTY OSC 标题链路。
- 协议字段删除必须在 Go、Web、Android、smoke 与快照 fixture 中同批完成，不允许只改某一端。
- 历史日期化 plan/spec 保留为历史记录；当前行为文档、设计系统和测试断言必须更新，新增计划作为此次契约变更依据。

## 删除后的契约

会话信息保留：

```json
{
  "id": "s1",
  "instanceId": "...",
  "termTitle": "Codex: 修复上传",
  "cwd": "/work/project",
  "status": "running"
}
```

标题规则：

1. 会话列表、终端顶栏、浏览器 `document.title`：使用非空 `termTitle`，否则使用 `Terminal`。
2. Agent hook 系统通知：使用非空 `termTitle`；为空时依次回退 `source`、事件类型。
3. Android 终端页不再显示会话名称副标题；状态指示不依赖该副标题 TextView。
4. 不再提供重命名入口或重命名网络请求。

---

## Task 1: 收缩 Go 会话模型与 HTTP 契约

**Files:**

- Modify: `go-core/internal/session/manager.go`
- Modify: `go-core/internal/session/terminal.go`
- Modify: `go-core/internal/application/session_router.go`
- Modify: `go-core/internal/direct/server.go`
- Test: `go-core/internal/session/manager_test.go`
- Test: `go-core/internal/session/client_node_compat_test.go`
- Test: `go-core/internal/direct/server_test.go`
- Test: `go-core/internal/relay/common_test.go`
- Modify: all Go test/smoke call sites of `Manager.Create(...)`

- [ ] 先修改测试，断言 `session.Info` JSON 不含 `name`、`displayTitle`，且 OSC 标题仍产生 `termTitle` 更新。
- [ ] 从 `session.Info` 删除 `Name`、`DisplayTitle`，从 `TerminalOptions` / `TerminalSession` 删除 `Name` / `name`。
- [ ] 将 `Manager.Create(name, cwd)` 收缩为 `Manager.Create(cwd)`；删除 `Manager.Rename`、`TerminalSession.Rename` 与 `displayTitle()`。
- [ ] 机械更新 control、hook、mux、upload、relay 与 session 测试中的 `Manager.Create(...)` 调用；原先只为命名使用的字符串参数直接删除，不改 cwd 与测试语义。
- [ ] `POST /api/sessions` 只读取 `cwd`；删除 direct 与 relay-agent `PATCH` 分支，保留 `DELETE`。
- [ ] 增加 API 回归测试：`PATCH /api/sessions/s1` 返回 405；创建与列表响应不再包含被删除字段。
- [ ] 保留并验证 OSC `termTitle` 更新会触发 manager `session` 增量广播。

**Targeted gate:**

```sh
cd go-core
GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go test ./internal/session/... ./internal/direct/... ./internal/application/... ./internal/relay/...
```

---

## Task 2: 更新 Agent hook 通知标题与 Go smoke

**Files:**

- Modify: `go-core/internal/hook/server.go`
- Modify: `go-core/internal/hook/server_test.go`
- Modify: `go-core/cmd/webterm-relay-e2e-smoke/main.go`
- Review/modify as needed: other `go-core/cmd/webterm-*-smoke/` session creation helpers
- Modify: `docs/agent-hooks.md`

- [ ] 将 hook 通知标题从 `Info().DisplayTitle` 改为 `strings.TrimSpace(Info().TermTitle)`，空值依次回退 `ev.Source`、`ev.Type`。
- [ ] 覆盖三种通知标题测试：有 `termTitle`、无标题但有 source、两者均空。
- [ ] 从 relay E2E smoke 删除 rename 步骤、`renameSession`/`expectSessionName` helper 及相关断言。
- [ ] smoke 创建会话时不再提交 `name`，但继续验证 session ID、cwd、列表和关闭链路。
- [ ] 更新 `docs/agent-hooks.md`，说明通知标题来自 `termTitle`，不再描述 `DisplayTitle`。

**Targeted gate:**

```sh
cd go-core
GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go test ./internal/hook/... ./cmd/...
```

---

## Task 3: 删除 Web 自定义名称 UI 与状态

**Files:**

- Modify: `frontend/src/components/TerminalPane.vue`
- Modify: `frontend/src/components/SessionGrid.vue`
- Modify: `frontend/src/lib/terminal-session-context.ts`
- Modify: `frontend/src/services/session.service.ts`
- Modify: `frontend/src/services/session.service.test.ts`
- Modify: `frontend/src/store.ts`
- Modify: `docs/design-system.md`
- Review/modify: `docs/mobile-terminal-keyboard-plan.md`（仅清除当前行为说明；历史步骤保留历史语义）

- [ ] 删除 `TerminalPane` 的 `sessionName` 输入框、focus/blur/Enter/Escape 提交逻辑和编辑状态。
- [ ] 顶栏改为只读、可截断的标题文本；展示 `termTitle || "Terminal"`，不引入新的交互控件。
- [ ] 从 `TerminalSessionState` 删除 `sessionName` / `sessionPlaceholder`，保留单一 `termTitle` 或等价的展示标题状态。
- [ ] 从 `TerminalSessionContext` 删除 `currentSessionName`、`titleBeforeEdit`、`commitTerminalTitle()` 和 `renameSession` import；`document.title` 直接由 `termTitle || "Terminal"` 生成。
- [ ] `SessionGrid` 删除 `displayTitle || name` fallback，只使用 `termTitle || "Terminal"`。
- [ ] 从 `Session` 类型删除 `name`、`displayTitle`；从 service 删除 `renameSession()` 及其测试。
- [ ] 更新设计系统中的终端顶栏描述，删除“标题输入框”交互规范。
- [ ] 新增/调整组件或 context 测试，验证空标题回退和 OSC/info 标题刷新。

**Targeted gate:**

```sh
npm run test:unit
npm run typecheck
```

---

## Task 4: 删除 Android 重命名入口与名称参数链

**Files:**

- Delete: `android-client/ui-common/src/main/java/com/webterm/ui/common/dialog/RenameSessionDialogHelper.java`
- Modify: `android-client/core-api/src/main/java/com/webterm/core/api/WebTermApi.java`
- Modify: `android-client/ui-common/src/main/java/com/webterm/ui/common/command/SessionCommandController.java`
- Modify: `android-client/feature/home/src/main/java/com/webterm/feature/home/SessionRowHelper.java`
- Modify: `android-client/feature/home/src/main/java/com/webterm/feature/home/SessionRowActions.java`
- Modify: `android-client/feature/home/src/main/java/com/webterm/feature/home/HomeHost.java`
- Modify: `android-client/feature/home/src/main/java/com/webterm/feature/home/DeviceSessionsFragment.java`
- Modify: `android-client/feature/home/src/main/java/com/webterm/feature/home/HomeViewModel.java`
- Modify: `android-client/app/src/main/java/com/webterm/mobile/ui/MainActivity.java`
- Modify: `android-client/app/src/main/java/com/webterm/mobile/ui/AppFlowCoordinator.java`

- [ ] 删除 `WebTermApi.renameSession()`、`SessionCommandController.showRenameDialog()` 及对 dialog helper 的依赖。
- [ ] 删除会话卡片副标题中的 `name` 展示和长按重命名监听；长按不再承担其他隐式动作。
- [ ] 将 `openSession/showTerminal/onOpenTerminal` 整条调用链的 `sessionName` 参数删除，避免仅传空字符串的兼容签名。
- [ ] 创建会话后的终端打开路径只传 `termTitle`，并继续正确处理 relay canonical session ID。
- [ ] 从 DiffUtil/UI content 比较中删除 `name`、`displayTitle`，确保 `termTitle` 更新仍会刷新目标行。

**Targeted gate:**

```sh
cd android-client
ANDROID_HOME=/Users/gao/Library/Android/sdk ./gradlew \
  :feature:home:testDebugUnitTest \
  :feature:terminal:testDebugUnitTest \
  :app:compileDebugJavaWithJavac \
  --no-daemon
```

实施时先用 `./gradlew projects` 核对模块任务；若模块没有独立 test variant，则运行 `:app:testDebugUnitTest`。

---

## Task 5: 删除 Android 缓存与终端页的 sessionName 状态

**Files:**

- Modify: `android-client/core-cache/src/main/java/com/webterm/core/cache/CachedTerminal.java`
- Modify: `android-client/core-cache/src/main/java/com/webterm/core/cache/TerminalCacheCoordinator.java`
- Modify: `android-client/core-cache/src/main/java/com/webterm/core/cache/TerminalDiskCache.java`
- Modify: `android-client/core-cache/src/main/java/com/webterm/core/cache/CachedSessionMapper.java`
- Modify: `android-client/feature/home/src/main/java/com/webterm/feature/home/repository/SessionRepository.java`
- Modify: `android-client/feature/terminal/src/main/java/com/webterm/feature/terminal/TerminalFragment.java`
- Modify: `android-client/feature/terminal/src/main/java/com/webterm/feature/terminal/TerminalScreenBuilder.java`
- Modify: `android-client/feature/terminal/src/main/java/com/webterm/feature/terminal/TerminalViewModel.java`
- Modify: `android-client/feature/terminal/src/main/java/com/webterm/feature/terminal/domain/TerminalLaunchState.java`
- Modify: `android-client/feature/terminal/src/main/java/com/webterm/feature/terminal/domain/TerminalLifecycleController.java`
- Modify: `android-client/feature/terminal/src/main/java/com/webterm/feature/terminal/domain/TerminalRuntime.java`
- Modify: `android-client/feature/terminal/src/main/java/com/webterm/feature/terminal/domain/TerminalRuntimeState.java`
- Test: related cache/repository/terminal launch-state tests

- [ ] 从内存缓存、snapshot 和磁盘 metadata 删除 `sessionName`；反序列化自然忽略旧 JSON 中的未知字段。
- [ ] 删除 `CachedSessionMapper` 与 `SessionRepository` 从旧缓存补回 `name` 的逻辑。
- [ ] 将 `TerminalLaunchState` 收缩为单一 `headerTitle`；回退链为缓存 `termTitle` → 请求 `termTitle` → `Terminal`。
- [ ] `TerminalScreenBuilder` 删除会话名称副标题；如果状态圆点需要容器，保留独立状态容器，不保留空名称 TextView。
- [ ] `TerminalLifecycleController.onInfo()` 只处理 `termTitle` 和身份字段；snapshot 只保存真正仍在使用的标题字段。
- [ ] 调整缓存与恢复测试，证明旧 metadata 含 `sessionName` 时可读取但不会恢复到 UI，新缓存不再写出该键。

**Targeted gate:**

```sh
cd android-client
ANDROID_HOME=/Users/gao/Library/Android/sdk ./gradlew \
  :app:testDebugUnitTest \
  :app:compileDebugJavaWithJavac \
  --no-daemon
```

---

## Task 6: 更新快照契约并执行零残留审计

**Files:**

- Modify/regenerate: `go-core/internal/session/testdata/node_snapshot_v1/**/node-outbound-trace.json`
- Modify: snapshot compatibility tests only where expected JSON fields change
- Review: `shared/constants.js`, `shared/tunnel-protocol.js`, terminal binary protocol implementations（确认无需改帧类型）
- Review/update: 当前行为 README 与设计文档

- [ ] 使用仓库现有 fixture 生成/更新流程刷新初始 info JSON，移除 `name` / `displayTitle`，不要手工只改部分 fixture。
- [ ] 分别验证 JSON state 与 binary state；本次只收缩 info payload，不改变终端屏幕快照二进制结构和 relay/mux 帧版本。
- [ ] 审查 `MSG_TITLE`：确认未把它错误描述成自定义名称替代品；除非另立需求，不改变其当前行为。
- [ ] 对业务源码、测试、smoke、当前文档执行零残留搜索。允许历史日期化 plan/spec 中保留当时事实，但不得被当前文档引用为现行契约。

**Zero-residual searches:**

```sh
rg -n "renameSession|RenameSession|重命名会话|sessionName|DisplayTitle|displayTitle" \
  frontend go-core android-client shared scripts tests docs \
  -g '!docs/superpowers/plans/**' -g '!docs/superpowers/specs/**' \
  -g '!android-client/.superpowers/**' -g '!web/**' -g '!**/build/**'

rg -n 'json:"name"|optString\("name"|session\.name|body\.put\("name"' \
  frontend go-core android-client shared scripts tests \
  -g '!web/**' -g '!**/build/**'
```

预期：没有与终端会话自定义名称有关的命中；设备名、文件名、账号名等同名业务字段不属于本次范围，需逐条人工判定。

---

## Final Validation

按风险从窄到宽执行：

```sh
cd go-core
GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go test ./...

cd /Users/gao/Documents/webterm-clone
npm run test:unit
npm run typecheck
npm run build

cd android-client
ANDROID_HOME=/Users/gao/Library/Android/sdk ./gradlew \
  :app:testDebugUnitTest \
  :app:compileDebugJavaWithJavac \
  --no-daemon

cd /Users/gao/Documents/webterm-clone
git diff --check
```

手工/运行时验收：

- [ ] 新建终端后，Web 和 Android 初始显示 `Terminal` 或 shell 首次 OSC 标题。
- [ ] Agent/终端改变 OSC 标题后，首页会话卡片、终端顶栏和浏览器标题实时更新。
- [ ] Android 终端页离线缓存恢复后仍显示最近的 `termTitle`，不出现旧别名。
- [ ] Web 不存在可编辑标题输入框；Android 长按会话不再弹出重命名框。
- [ ] Agent hook 通知使用当前 `termTitle`，空标题回退 source/type。
- [ ] direct 与 relay 模式的创建、列表、打开、关闭会话链路均正常；重命名 PATCH 明确不可用。
- [ ] 构建产生的 `web/` 仅通过 `npm run build` 更新，不手工编辑。

## Recommended Implementation Order

1. Go 模型/API/通知与测试，先建立新的服务端契约。
2. Web 删除名称编辑与 fallback。
3. Android 删除 UI/API 参数链，再删除缓存字段。
4. 更新 fixtures、当前文档并做零残留审计。
5. 跑三端全量验证和 direct/relay 手工验收。
