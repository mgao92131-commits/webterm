# P2P 完整移除计划

## 1. 结论

本项目应收敛为单一的可靠传输架构：

```text
Web / Android
    |
    |  HTTPS + WebSocket (webterm.mux.v1)
    v
Go Relay
    |
    |  /ws/agent 上的 relay frame
    v
Go PC Agent
    |
    v
Session / Terminal / File Send / Agent Notification
```

P2P/WebRTC 不再是可选加速、fallback 分支或配置项，而是从客户端、服务端、协议、依赖、部署、测试、UI 和构建产物中全部删除。

本项目尚未发布，因此采用干净删除，不保留以下兼容层：

- 不保留 `enableP2P`、`WEBTERM_DISABLE_P2P` 等开关。
- 不保留 `/api/p2p/offer`、`/api/p2p/ice` 的 404/503 兼容处理器。
- 不保留 P2P frame type、stream kind 或 unavailable/fallback 消息。
- 不保留空的 WebRTC transport 接口、状态枚举、UI badge 或测试替身。
- 不迁移旧开发安装中的 `enable_p2p` SharedPreferences；开发设备通过清数据或重装验证。

## 2. 当前耦合面

当前 P2P 不是独立插件，而是跨越四条运行链路。

### 2.1 Web

- `frontend/src/lib/p2p.ts` 持有 `RTCPeerConnection`、DataChannel、信令和 mux transport。
- `frontend/src/lib/p2p-ws-mock.ts`、`p2p-utils.ts` 保留一套 P2P WebSocket mock 协议。
- `connection.service.ts` 在 P2P connected/disconnected 时重建设备 mux。
- `relay-mux-session-manager.ts` 支持动态 transport provider。
- `ManagerView.vue` 在设备进入时主动发起 P2P，并接收 ICE。
- `store.ts`、`TerminalPane.vue`、`DeviceDrawer.vue` 暴露 P2P 状态。
- `terminal-session-context.ts` 根据 P2P 状态在 JSON 与 binary terminal 协议间切换。

### 2.2 Android

- 独立模块 `:transport-webrtc` 包含 PeerConnection、DataChannel endpoint 和 transport adapter。
- App 直接依赖 `io.github.webrtc-sdk:android`，并将其原生库打入 APK。
- `TransportFactory` 同时制造 WebSocket 和 DataChannel transport。
- `RelayMuxSessionManager` 包含 P2P 尝试、超时、退避、fallback、transport 切换和状态查询。
- `AppFlowCoordinator` 监听 P2P 生命周期并触发整个设备 mux 重连。
- `ServerConfig`、`ServerConfigStore`、设置页和 ViewModel 持有 P2P 开关。
- Home/terminal 状态模型含 `CONNECTED_P2P`，影响列表缓存、monitor 和状态指示器。
- `WebTermApi` 仍暴露 offer/ICE HTTP API。

### 2.3 Go Relay 与 PC Agent

- Relay 暴露 `/api/p2p/offer`、`/api/p2p/ice`。
- relay protocol 定义四个 P2P frame type 和 `StreamKindP2P`。
- `relaygateway.P2PGateway` 负责信令 stream 生命周期。
- PC Agent 的 `P2PHandler` 使用 Pion WebRTC 建立 DataChannel，并在其上运行 mux。
- `go.mod` 引入 Pion WebRTC 及 ICE/DTLS/SCTP/STUN/TURN 依赖树。
- debug/metrics、测试和 smoke 脚本都包含 P2P 分支。

### 2.4 部署、文档和产物

- `deploy.sh`、`docker-compose.yml` 暴露 `WEBTERM_DISABLE_P2P`。
- 部署验证与 Web/Android smoke 支持 `--p2p`、`--expect-p2p`、fallback 模式。
- 两份 P2P 专用设计计划及多份集成/架构文档仍把 P2P 描述为当前能力。
- `web/` 是跟踪进 Git 的前端构建产物，必须在源代码清理后重建，不能手工删字符串。

## 3. 目标架构的不变量

完成后必须同时满足：

1. 每个目标设备只有一个由 registry/manager 持有的物理 mux WebSocket。
2. manager、terminal、file-send、agent-notification 都复用同一设备 mux 的虚拟 channel/control plane。
3. UI attach/detach 只影响 listener，不关闭或替换设备 mux。
4. 网络断开、cookie 更新和服务端关闭可以重连物理 WebSocket，但不存在“传输类型切换”。
5. terminal 永远使用 `webterm.binary.v1`；不再按 transport 在 JSON/binary 间切换。
6. 逻辑 session identity、`lastSeq`、`TerminalProjection` 和恢复语义不因本次删除而改变。
7. 直连模式与 relay 模式共享 mux/channel 语义，差异只在 WebSocket URL 和是否带 `deviceId`。
8. Relay/Agent 协议只保留真实使用的 HTTP、WebSocket、terminal、ping/pong frame。

## 4. 实施阶段

### 阶段 0：冻结基线与删除边界

目标：先证明当前 relay-only 路径可用，并保存删除前的可比较数据。

工作：

- 确认工作树干净，记录当前 commit。
- 跑 Web typecheck、unit、build。
- 跑 Go 全量测试和 relay smoke，明确使用 relay-only 参数。
- 跑 Android Java compile、unit test 和 relay-only emulator smoke。
- 记录 release APK 的文件大小及其中 `libjingle_peerconnection_so.so` 的数量/体积。
- 保存 P2P 源文件、依赖和配置的扫描清单，作为最终反向验收列表。

门槛：任何与 relay-only 主路径相关的红灯先修复，不能把既有失败混进移除提交。

### 阶段 1：Web 收敛为唯一 mux WebSocket

目标：Web 不创建 PeerConnection，不根据信令或 DataChannel 事件重建 mux。

删除：

```text
frontend/src/lib/p2p.ts
frontend/src/lib/p2p-ws-mock.ts
frontend/src/lib/p2p-utils.ts
```

修改：

- `connection.service.ts`
  - 删除 P2P import、事件监听、connect、disconnect、ICE 和状态查询。
  - `initialize()` 只保留 session invalidation 等通用监听。
  - manager/terminal channel 始终交给 `relayMuxSessionManager`。
- `relay-mux-session-manager.ts`
  - 删除 optional transport provider 和 transport-change reconnect API。
  - factory 永远使用 `webSocketRelayMuxTransportFactory`。
- `terminal-session-context.ts`
  - 删除 `p2pActive` option。
  - terminal transport 固定为 binary mux，不再保留 JSON mock 分支。
- `useManagerConnection.ts`、`ManagerView.vue`
  - 从消息 union 和 handler 中删除 `p2p-ice`。
  - 删除进入设备时的 `connectToDevice()`。
- `store.ts`、`ManagerView.vue`、`TerminalPane.vue`、`DeviceDrawer.vue`
  - 删除 `p2pActive` 状态和 P2P/RELAY 二选一显示。
  - 如需链路标签，只显示通用“已连接/中转连接”，不再暴露 transport 类型。
- `config.ts`
  - 删除 STUN、P2P connect/disconnect/request/mock timeout。
- 更新或删除只验证 transport 切换/P2P ICE 的 unit tests。
- 新增 relay-only 回归测试：设备切换、manager 重连、terminal reopen、logout closeAll。

阶段验收：

- 浏览器运行时不访问 `/api/p2p/*`。
- 浏览器全局不存在 `RTCPeerConnection`、`RTCDataChannel`、`RTCIceCandidate` 业务引用。
- manager 与 terminal 共用一个设备 mux，异常 close 后仍按现有 backoff 恢复。
- terminal 输入、输出、resize、state restore 均只走 binary protocol。

### 阶段 2：Android 删除 WebRTC 模块和 transport 分支

目标：Android 只保留 `WebSocketMuxTransport`，并简化设备 mux 生命周期。

删除：

```text
android-client/transport-webrtc/
android-client/app/src/test/java/com/webterm/mobile/di/DefaultTransportFactoryTest.java 中仅针对 P2P 的用例
其他仅验证 CONNECTED_P2P、开关或 DataChannel fallback 的测试
```

Gradle 清理：

- 从 `settings.gradle.kts` 删除 `:transport-webrtc`。
- 从 App dependencies 删除 `project(":transport-webrtc")`。
- 删除 `io.github.webrtc-sdk:android`。
- 删除只服务于 WebRTC SDK 的 ProGuard 规则。

transport/session 清理：

- `TransportFactory` 只保留 `createWebSocket(...)`；必要时改名为更准确的 `MuxTransportFactory`。
- `DefaultTransportFactory` 删除 P2P manager、config store、executor 和 DataChannel API。
- `MuxTransport` 删除 `isP2P()`。
- `MuxSession` 删除 `isP2PTransport()`。
- `RelayMuxSessionManager` 删除：
  - P2P timeout/backoff 常量与字段；
  - `prepareDataChannel()`/`createDataChannel()`；
  - `skipInitialP2P`/`skipNextP2P`；
  - fallback runnable；
  - `isP2PConnected()`；
  - 带 `skipP2POnce` 的强制重连重载。
- 保留并强化 generation、channel reopen、cookie refresh 和 reconnect backoff，这些是 relay-only 仍需要的恢复机制。

API/config/UI 清理：

- 从 `WebTermApi` 删除 `postP2POffer()`、`postP2PIce()` 和 callback。
- 从 `ServerConfig` JSON contract 删除 `enableP2P`。
- 从 `ServerConfigStore` 删除 `enable_p2p` 读写。
- 从 `RelayService` 的动态设备配置复制中删除 P2P 字段。
- 从设置页、Settings/Terminal ViewModel 和 host interface 删除 P2P 开关。
- 从 `AppFlowCoordinator` 删除 P2P listener、manager getter、disabled-reason 特判和 mux 重连触发。
- 将 `CONNECTED_P2P` 从 Home repository/UI/cache/monitor/terminal status 全链路删除，只保留 connected/connecting/disconnected/polling 等业务状态。

测试替换原则：

- 删除“P2P 优先、超时 fallback、断开切回”的测试。
- 保留并补强“单设备单 mux”“UI detach 不关 session”“channel reopen”“cookie 更新”“lastSeq/MSG_STATE 恢复”测试。
- release APK 中不得再包含 `libjingle_peerconnection_so.so`。

### 阶段 3：Go 删除信令、DataChannel 和协议能力

目标：Relay 和 PC Agent 不认识 P2P 请求或 frame，Go module graph 不含 Pion。

Relay server：

- 删除 `go-core/internal/relaygateway/p2p_gateway.go`。
- 从 `relayapp/app.go` 删除 P2P gateway 创建和两个 HTTP route。
- 从 debug/metrics capability 输出删除 P2P endpoint/stream。
- 删除 `p2p_gateway_test.go`，以“未知路由不暴露能力”的通用路由测试替代（如确有必要）。

Relay protocol：

- 删除 `relaycore/p2p.go`。
- 删除 `FrameTypeP2POffer`、`FrameTypeP2PAnswer`、`FrameTypeP2PIce`、`FrameTypeP2PUnavailable`。
- 删除 `StreamKindP2P`。
- 不复用已删除的 frame type 数值，避免未来调试时产生歧义。

PC Agent：

- 删除 `relay/client_v2_p2p.go`。
- 从 `V2Client` 删除 `p2p` 字段、构造、frame dispatch 和 CloseAll。
- 删除 `client_v2_test.go` 中 Pion PeerConnection/DataChannel 集成用例和辅助函数。
- 检查 `application/session_router.go` 与 `shared/tunnel-transport.js`，删除仅为 DataChannel adapter 存在的注释或接口，不删除 mux 自身的通用 socket abstraction。

依赖：

- 从 `go.mod` 删除 `github.com/pion/webrtc/v4`。
- 执行 `go mod tidy`，确认 datachannel、ICE、DTLS、SCTP、SDP、STUN、TURN 等间接依赖全部退出 `go.sum`。
- 用 `go mod graph` 和 `go list -deps` 反查，不只检查 `go.mod` 文本。

部署行为：

- `/api/p2p/offer` 和 `/api/p2p/ice` 自然返回 404，不提供 deprecated handler。
- 旧客户端/旧 Agent 不作为兼容目标；Relay、Web、Android、Agent 必须作为同一未发布版本部署。

### 阶段 4：部署、烟测、文档和构建产物清理

部署配置：

- 从 `docker-compose.yml`、`deploy.sh` 删除 `WEBTERM_DISABLE_P2P` 的读取、传递、帮助和输出。
- 更新 `validate-go-relay-deploy-config.mjs`，改为断言该变量不存在。

smoke：

- `smoke-web-go-relay-pc-agent.mjs` 删除 `--p2p`、`--expect-p2p`、`--expect-fallback`。
- 保留一个默认 relay mux payload smoke，必须验证 manager 和 terminal 的真实双向数据，不只验证 WebSocket open。
- `smoke-go-relay-android-emulator.sh` 删除 P2P 参数、SharedPreferences 注入、logcat DataChannel 断言。
- 保留 Android relay-only mux、terminal payload、重连/恢复断言。
- `deploy.sh` 最后的 smoke 示例只展示 relay-only 命令。

文档：

- 删除已失效的专用方案：
  - `docs/relay-first-p2p-upgrade-plan.md`
  - `docs/webrtc-relay-transport-plan.md`
- 更新仍作为当前事实来源的集成、部署、Android 架构和 file-send 文档，将传输描述改为单一 relay mux。
- `docs/superpowers/specs`、`docs/superpowers/plans` 属于历史设计记录：实现完成后要么整体移到明确的历史归档目录，要么删除已被替代的文件；不能继续让它们看起来像当前设计。
- 本计划在完成验收后改写为不含旧能力名称的“relay-only architecture”文档，或在 commit 历史已保留的前提下删除，保证最终工作树零残留。

构建产物：

- 删除旧 `web/assets/*`，通过 `npm run build` 重新生成 `web/`。
- 检查新 bundle 不含 P2P/WebRTC/ICE/DataChannel 字符串或代码。
- 重新生成 Android debug/release APK，检查 native libs 和最终体积。

### 阶段 5：端到端验收与零残留审计

#### 静态零残留

以下可执行范围扫描必须为 0：

```bash
rg -n -i \
  --glob '!node_modules/**' \
  --glob '!frontend/node_modules/**' \
  --glob '!android-client/.gradle/**' \
  --glob '!android-client/**/build/**' \
  --glob '!.git/**' \
  '(p2p|peer[-_ ]?to[-_ ]?peer|webrtc|datachannel|rtcpeer|icecandidate|stun:|turn:|enable_p2p|WEBTERM_DISABLE_P2P)' \
  frontend android-client go-core scripts shared deploy.sh docker-compose.yml web
```

依赖扫描必须为 0：

```bash
cd go-core
go mod graph | rg -i 'pion|webrtc|datachannel|ice|stun|turn|dtls|sctp'

cd ../android-client
./gradlew :app:dependencies --configuration releaseRuntimeClasspath | rg -i 'webrtc|jingle|peerconnection'
```

第二条扫描只接受与 P2P 无关且能说明来源的普通单词误报；不接受相关库仍留在依赖图中。

#### Web gate

```bash
npm run typecheck
npm run test:unit
npm run build
npm run validate:deploy
```

手工/Playwright 场景：

- 登录、拉取设备、切换设备。
- manager 实时 sessions/session/session-closed 推送。
- 新建、进入、切换、返回、重进多个 terminal。
- terminal input/output/resize/title/state replay。
- 网络短断后自动恢复，旧 transport callback 不污染新 generation。
- logout 后所有 mux 关闭且 store 清空。

#### Go gate

```bash
cd go-core
GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go test ./...
```

再跑 relay server 与 Web PC Agent 的真实 payload smoke；确认 sessions、terminal、file-send 和通知控制消息都通过 relay mux。

#### Android gate

```bash
cd android-client
ANDROID_HOME=/Users/gao/Library/Android/sdk \
  ./gradlew :app:compileDebugJavaWithJavac :app:testDebugUnitTest --no-daemon

ANDROID_HOME=/Users/gao/Library/Android/sdk \
  ./gradlew :app:assembleRelease --no-daemon
```

设备/emulator 场景：

- relay 登录、设备列表、session 列表。
- 多终端切换、后台/前台、Fragment detach/reattach。
- `MSG_STATE + lastSeq + TerminalProjection` 恢复。
- 输入连续性、title/notification 实时更新。
- file-send 与 agent notification 控制消息。
- 飞行模式/网络切换后的单 mux 重连。
- APK 内没有 WebRTC native library。

#### 通用质量门槛

```bash
git diff --check
git status --short
```

还必须确认：

- 没有空接口、永远为 false 的 P2P 字段或“暂时禁用”分支。
- 没有把 relay-only 实现伪装成仍支持多 transport 的抽象。
- 没有通过设置环境变量来“关闭”旧能力；旧能力的代码和依赖必须真实消失。
- 最终运行日志不再出现 offer/answer/ICE/DataChannel/fallback/transport switch。

## 5. 建议提交顺序

为了便于审查和定位回归，建议拆成以下原子提交，但最终作为同一未发布版本交付：

1. `test: lock relay-only web and android recovery behavior`
2. `refactor(web): remove p2p and use relay mux exclusively`
3. `refactor(android): remove webrtc transport and p2p state`
4. `refactor(go): remove p2p signaling protocol and pion runtime`
5. `chore(deploy): remove p2p flags and simplify relay smoke`
6. `docs: replace p2p plans with relay-only architecture`
7. `build: regenerate web assets and verify apk dependency cleanup`

每个提交都必须自行通过对应子系统的 compile/unit gate；第 7 个提交后再跑全量 gate 和端到端 smoke。

## 6. 主要风险与控制

### 风险 A：删除 transport 切换时误伤 mux 重连

控制：只删除“传输类型选择/切换”，保留 `MuxSession` generation、WebSocket reconnect、channel reopen 和 listener detach 语义；用网络短断和多终端切换回归覆盖。

### 风险 B：terminal 协议仍隐含依赖 P2P JSON mock

控制：Web terminal 固定 binary protocol，并覆盖 hello/input/output/resize/state/exit 全消息集；不只验证页面能打开。

### 风险 C：Android 状态枚举删改造成列表或缓存回归

控制：从 repository 到 UI 一次性收敛状态模型，更新缓存和 observe tests，不保留 enum alias。

### 风险 D：协议常量删除后老进程混跑

控制：项目未发布，采用 Relay、Agent、Web、Android 同版本部署；实施期间不做新旧版本互通承诺。

### 风险 E：Pion 或 Android WebRTC 通过间接依赖残留

控制：分别用 `go mod graph`、Gradle runtimeClasspath、APK unzip 三层验证，不能只看直接依赖声明。

### 风险 F：历史文档和构建产物造成“代码已删但仓库仍有 P2P”

控制：源代码完成后再统一处理当前文档和生成物，最后运行全仓扫描；本计划本身也必须在完成后转为 relay-only 架构文档或删除。

## 7. 完成定义

只有同时满足以下条件才算“全部移除”：

- Web、Android、Go 中无 P2P/WebRTC/ICE/DataChannel 运行时代码。
- Relay 不再提供信令 API，Agent 不再处理相关 frame。
- Go/Android 依赖树和 APK 中无相关库。
- 配置、UI、状态模型、日志、metrics、smoke 参数无相关概念。
- Web/Android 只通过单一 relay/direct WebSocket mux 工作。
- terminal 恢复、设备切换、文件发送、通知和网络重连通过端到端验收。
- 当前架构文档与实际代码一致，生成产物已重建。
- 全仓零残留审计通过，除 Git 历史外不再存在旧能力实现或说明。
