# Relay-first / P2P-upgrade 统一连接计划

## 目标

把 Web、Android 和 Go PC Agent 的连接策略统一成：

```text
先用 Relay 建立稳定 mux 连接。
业务先可用，terminal/session/manager 不等待 P2P。
通过 Relay 交换 P2P offer/answer/ice。
P2P DataChannel 真正 connected 后，再把 mux physical transport 切到 P2P。
P2P 失败、超时、断开或质量不达标时，继续使用或切回 Relay mux。
```

这个计划和 `docs/web-go-relay-pc-agent-integration-plan.md` 隔离：

```text
Go Relay 替换 Node、账号、设备、部署切流：看 web-go-relay-pc-agent-integration-plan.md。
Relay-first / P2P-upgrade 连接策略：看本计划。
```

## 职责边界

Relay 负责：

```text
账号登录和鉴权。
设备列表和 Agent 在线状态。
Relay mux 中转。
P2P signaling：offer / answer / ice。
P2P 不可用时的稳定 fallback。
必要的 metrics/debug。
```

Relay 不负责：

```text
terminal 业务逻辑。
session truth source。
pty 生命周期。
terminal replay / screen state。
规划摘要或 Agent 内部状态缓存。
```

Agent 负责：

```text
session 创建、关闭和列表。
terminal / pty / replay / screen state。
接收 P2P offer，生成 answer。
DataChannel open 后在 DataChannel 上运行同一套 webterm.mux.v1。
Relay 主连接断开时清理对应 P2P peer。
```

Web / Android 负责：

```text
先连接 Relay mux。
后台尝试 P2P。
P2P connected 后切换 mux physical transport。
P2P 失败或断开后回 Relay mux。
UI 显示当前 transport：RELAY / P2P / CONNECTING。
```

## 连接生命周期

```text
1. Client 登录 Relay。
2. Client 拉取设备列表，选择 online Agent。
3. Client 建立 Relay mux：
   /ws/sessions?deviceId=<agentDeviceId>
4. manager channel 通过 Relay mux 打开：
   ws-connect /ws/sessions
5. terminal channel 通过 Relay mux 打开：
   ws-connect /ws/sessions/{sessionId}
6. terminal 已经可用。
7. Client 后台发起 P2P offer。
8. Relay 把 offer 转给 Agent，并把 answer 返回 Client。
9. Client/Agent 通过 Relay 交换 ICE candidate。
10. DataChannel("tunnel") open。
11. Client 将 mux physical transport 从 Relay WS 切到 DataChannel。
12. active virtual channels 重新 open。
13. P2P disconnected / failed / timeout 时切回 Relay WS。
```

关键原则：

```text
P2P connecting 不能阻塞 terminal 打开。
P2P connected 前，所有业务流量都走 Relay。
P2P connected 后，只是底层 transport 变化，上层 channel/path/协议不变。
Relay WS 可以作为 fallback 保留，不能和 P2P 同时派发同一组 active channels。
```

## 统一状态机

建议 Web 和 Android 使用同一组状态命名：

```text
RELAY_CONNECTED
P2P_CONNECTING
P2P_CONNECTED
P2P_FAILED
P2P_DISCONNECTED
FALLING_BACK
```

状态转换：

```text
RELAY_CONNECTED -> P2P_CONNECTING
P2P_CONNECTING -> P2P_CONNECTED
P2P_CONNECTING -> P2P_FAILED -> RELAY_CONNECTED
P2P_CONNECTED -> P2P_DISCONNECTED -> FALLING_BACK -> RELAY_CONNECTED
P2P_CONNECTED -> RELAY_CONNECTED   手动关闭 P2P 或切换设备
```

超时建议：

```text
P2P offer HTTP timeout: 5-10 秒。
DataChannel open timeout: 3-5 秒。
ICE gathering 不阻塞 Relay terminal。
P2P disconnected grace: 5-8 秒。
fallback reconnect timeout: 5 秒。
```

## Transport 抽象

Web 和 Android 都应该收敛到同一概念：

```text
MuxSession
  -> MuxTransport
       -> RelayWebSocketTransport / WebSocketMuxTransport
       -> P2PDataChannelTransport / WebRtcDataChannelTransport
```

`MuxTransport` 只承载 mux text/binary：

```text
sendText(control JSON)
sendBinary(tunnel frame)
onText(control JSON)
onBinary(tunnel frame)
onOpen()
onClose()
onError()
```

禁止：

```text
在 P2P DataChannel 上恢复旧 http-request/ws mock 协议。
把 HTTP session API 偷偷改走 P2P。
让 Relay 缓存 session/terminal 业务状态。
```

## 切换规则

P2P connected 后：

```text
1. 标记新 transport generation。
2. 暂停旧 Relay WS mux 的 channel 派发。
3. 关闭或降级旧 physical transport。
4. 使用 P2P DataChannel transport 启动 MuxSession。
5. 重新 open active manager/terminal virtual channels。
6. terminal/session 上层对象不感知 transport 类型。
```

P2P failed / disconnected 后：

```text
1. 标记 P2P transport generation 失效。
2. 丢弃旧 P2P 回调。
3. 重新建立 Relay WS mux。
4. reopen active channels。
5. UI 显示 RELAY 或 RECONNECTING。
6. 不弹阻断式错误。
```

约束：

```text
同一 device 同一时刻只能有一个 active mux transport 派发 channel 消息。
旧 transport 回调必须按 generation 丢弃。
virtual channel close 不等于关闭整个 P2P peer。
设备切换、logout、页面退出必须关闭对应 P2P peer。
```

## Web 落地

当前状态：

```text
Web 已有 P2PDataChannelTransport。
RelayMuxSessionManager 已能在 P2P connected 后优先选择 P2P transport。
P2P unavailable fallback smoke 已通过。
P2P success smoke 已通过。
HTTP session API 保持走 Relay 透明代理。
```

后续只需要按本计划持续校验：

```text
P2P connecting 不阻塞 Relay terminal。
P2P connected 后 active mux transport 唯一。
P2P disconnected 后快速回 Relay。
UI 状态与实际 transport 一致。
```

## Android 落地

当前状态：

```text
Android 已支持 Relay mux WebSocket。
Android 已支持 P2P DataChannel mux。
RelayMuxSessionManager 当前按 deviceId 复用一条 active physical mux transport，可在 Relay WS 和 P2P DataChannel 间切换。
MuxSession 已通过 MuxTransport 抽象底层 physical transport。
TerminalConnection / ServerSessionMonitor 已经通过 RelayMuxSessionManager 打开 virtual channel。
```

### Android A：抽象 MuxTransport

目标：先把 `MuxSession` 从“直接持有 OkHttp WebSocket”改为“持有 MuxTransport”，不改变当前 Relay 行为。

当前状态：已完成。

新增或修改：

```text
android-client/app/src/main/java/com/webterm/mobile/MuxTransport.java
android-client/app/src/main/java/com/webterm/mobile/WebSocketMuxTransport.java
android-client/app/src/main/java/com/webterm/mobile/MuxSession.java
android-client/app/src/main/java/com/webterm/mobile/RelayMuxSessionManager.java
```

已完成：

```text
新增 MuxTransport 接口。
新增 WebSocketMuxTransport，把 OkHttp WebSocket physical transport 从 MuxSession 拆出。
MuxSession 现在只负责 webterm.mux.v1 control message、tunnel frame、pendingConnects 和 reconnect 调度。
旧构造函数仍保留，现有 Relay mux 调用方无需改动。
```

接口建议：

```java
interface MuxTransport {
    void start();
    void close();
    boolean isConnected();
    boolean sendText(String text);
    boolean sendBinary(byte[] data);

    interface Listener {
        void onOpen();
        void onText(String text);
        void onBinary(byte[] data);
        void onClosed(String reason);
        void onError(String message);
    }
}
```

验收：

```text
Android relay mux 行为不变。
同一设备仍只有一条 /ws/sessions?deviceId=... physical WS。
manager/terminal virtual channel 不受影响。
```

已验证：

```text
cd android-client && ANDROID_HOME=/Users/gao/Library/Android/sdk ./gradlew :app:compileDebugJavaWithJavac --no-daemon
DEVICE_NAME='Android E2E Agent' scripts/smoke-go-relay-android-emulator.sh --terminal --mux
```

### Android B：P2P signaling

目标：Android 能对指定 Agent 发起 offer，并通过 Relay 拿到 answer。

当前状态：已完成，并已通过 Android D 接入 transport 自动切换；UI 动态状态仍在 Android E 后续增强。

新增或修改：

```text
android-client/app/build.gradle.kts
android-client/app/src/main/java/com/webterm/mobile/P2PConnectionManager.java
android-client/app/src/main/java/com/webterm/mobile/WebTermApi.java
```

已完成：

```text
WebTermApi.postP2POffer 已支持 POST /api/p2p/offer 并解析 answer SDP。
WebTermApi.postP2PIce 已支持 POST /api/p2p/ice。
P2PConnectionManager 已能初始化 PeerConnectionFactory。
P2PConnectionManager 已能创建 PeerConnection 和 DataChannel("tunnel")。
P2PConnectionManager 已能 createOffer / setLocalDescription / setRemoteDescription(answer)。
P2PConnectionManager 已能把本地 ICE candidate 通过 Relay 发送给 Agent。
```

依赖评估：

```kotlin
implementation("io.github.webrtc-sdk:android:144.7559.09")
```

说明：

```text
org.webrtc:google-webrtc:1.0.32006 在 Maven Central 和 Google Maven 均为 404。
io.github.webrtc-sdk:android:144.7559.09 已在 Maven Central 元数据中确认存在。
如果后续 ABI 或体积不合适，再评估：
本地 AAR
项目已有可用 WebRTC 封装
```

信令接口：

```text
POST /api/p2p/offer
POST /api/p2p/ice
```

实现步骤：

```text
1. 初始化 PeerConnectionFactory。
2. 创建 PeerConnection。
3. 创建 DataChannel("tunnel")。
4. createOffer / setLocalDescription。
5. POST /api/p2p/offer。
6. setRemoteDescription(answer)。
7. onIceCandidate POST /api/p2p/ice。
8. DataChannel open 后通知 RelayMuxSessionManager 切 transport。
```

后续增强：

```text
增加 Android UI 手动开关。
增强 --expect-p2p smoke，使其断言 DataChannel 上的 terminal payload，而不仅是 DataChannel open。
```

### Android C：WebRtcDataChannelTransport

目标：把 Android WebRTC DataChannel 适配成 `MuxTransport`。

当前状态：已完成，并已通过 Android D 接入切换路径和 P2P smoke。

新增：

```text
android-client/app/src/main/java/com/webterm/mobile/WebRtcDataChannelTransport.java
```

已完成：

```text
DataChannel.State.OPEN 映射到 MuxTransport.Listener.onOpen。
DataChannel text message 映射到 onText。
DataChannel binary message 映射到 onBinary。
sendText 使用 DataChannel.Buffer(text, binary=false)。
sendBinary 使用 DataChannel.Buffer(bytes, binary=true)。
```

约束：

```text
DataChannel label 必须是 tunnel。
DataChannel binary payload 原样承载 mux tunnel frame。
DataChannel text payload 原样承载 mux control JSON。
不实现旧 P2P http-request/ws mock。
```

已验证：

```text
cd android-client && ANDROID_HOME=/Users/gao/Library/Android/sdk ./gradlew :app:compileDebugJavaWithJavac --no-daemon
DEVICE_NAME='Android E2E Agent' scripts/smoke-go-relay-android-emulator.sh --terminal --mux
```

### Android D：Relay-first / P2P-upgrade 切换

目标：Android terminal 先走 Relay；P2P connected 后再切 P2P；P2P 失败或断开后回 Relay。

当前状态：已完成基础接入，并通过默认 Relay、P2P fallback、P2P success smoke。

修改：

```text
android-client/app/src/main/java/com/webterm/mobile/RelayMuxSessionManager.java
android-client/app/src/main/java/com/webterm/mobile/TerminalConnection.java
android-client/app/src/main/java/com/webterm/mobile/ServerSessionMonitor.java
```

已完成：

```text
RelayMuxSessionManager 已支持 TransportProvider。
RelayMuxSessionManager.reconnectDevice 可在 P2P connected/disconnected 时切换 mux physical transport。
MuxSession generation 已用于丢弃旧 transport 回调。
MainActivity 已在 relay terminal 打开后后台启动 P2P。
P2P connected 后 reconnect 到 WebRtcDataChannelTransport。
P2P unavailable 或 disabled 时继续使用 Relay mux。
P2P disconnect 已防止递归关闭导致 native crash。
MuxSession.start 已支持底层 transport 已经 open 的 DataChannel，确保切换后会注册 listener 并 reopen channels。
--expect-p2p smoke 已断言 DataChannel mux binary in/out，证明 terminal payload 经过 P2P transport。
```

要求：

```text
P2P connecting 不阻塞当前 Relay mux。
P2P active 且 deviceId 匹配时才切 DataChannel transport。
reconnectTransport(reason) 不清空业务 channel。
selected device 切换、logout、页面退出时关闭对应 P2P peer。
```

已验证：

```text
cd android-client && ANDROID_HOME=/Users/gao/Library/Android/sdk ./gradlew :app:compileDebugJavaWithJavac --no-daemon
DEVICE_NAME='Android E2E Agent' scripts/smoke-go-relay-android-emulator.sh --terminal --mux
DEVICE_NAME='Android E2E Agent' scripts/smoke-go-relay-android-emulator.sh --terminal --mux --p2p --expect-fallback
DEVICE_NAME='Android E2E Agent' scripts/smoke-go-relay-android-emulator.sh --terminal --mux --p2p --expect-p2p
```

### Android E：UI 和配置

新增配置：

```text
ServerConfig.enableP2P: boolean default true
```

当前状态：已完成基础配置、用户开关和动态 transport 状态显示。

已完成：

```text
ServerConfig 已新增 enableP2P，历史配置默认 true。
ServerConfigStore 已新增全局 enable_p2p，历史配置默认 true。
设置页已增加用户可点击的 中转 P2P 开关。
Relay master 注入配置可设置 enableP2P。
Relay device 会继承 relay master 的 enableP2P。
Android smoke 默认 enableP2P=false，显式 --p2p 才启用。
设备卡片对 relay device 显示 中转设备 · P2P 或 中转设备 · Relay。
Terminal 页面副标题动态显示 RELAY / CONNECTING / P2P。
```

UI：

```text
设备列表当前设备旁显示 P2P / RELAY。
Terminal 页面状态栏显示 P2P / RELAY。
P2P connecting 不遮挡 terminal 使用。
P2P fallback 不弹阻断式错误，只记录日志或轻提示。
```

剩余可选 UI 增强：

```text
P2P connecting/fallback 可选轻提示。
```

## 测试门禁

Web：

```text
npm run smoke:web-go-relay-pc-agent -- --p2p --expect-fallback --timeout 120000
npm run smoke:web-go-relay-pc-agent -- --p2p --expect-p2p --timeout 120000
```

Android：

```text
cd android-client && ANDROID_HOME=/Users/gao/Library/Android/sdk ./gradlew :app:compileDebugJavaWithJavac --no-daemon
DEVICE_NAME='Android E2E Agent' scripts/smoke-go-relay-android-emulator.sh --terminal --mux
DEVICE_NAME='Android E2E Agent' scripts/smoke-go-relay-android-emulator.sh --terminal --mux --p2p --expect-fallback
DEVICE_NAME='Android E2E Agent' scripts/smoke-go-relay-android-emulator.sh --terminal --mux --p2p --expect-p2p
```

Go：

```text
cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go test ./internal/relaycore ./internal/relayrouter ./internal/relaygateway ./internal/relayapp ./internal/relay ./cmd/webterm-relay
```

当前已验证：

```text
npm run smoke:web-go-relay-pc-agent -- --p2p --expect-fallback --timeout 120000
npm run smoke:web-go-relay-pc-agent -- --p2p --expect-p2p --timeout 120000

cd android-client && ANDROID_HOME=/Users/gao/Library/Android/sdk ./gradlew :app:compileDebugJavaWithJavac --no-daemon
DEVICE_NAME='Android E2E Agent' scripts/smoke-go-relay-android-emulator.sh --terminal --mux
DEVICE_NAME='Android E2E Agent' scripts/smoke-go-relay-android-emulator.sh --terminal --mux --p2p --expect-fallback
DEVICE_NAME='Android E2E Agent' scripts/smoke-go-relay-android-emulator.sh --terminal --mux --p2p --expect-p2p

cd go-core && GOCACHE=/Users/gao/Documents/webterm-clone/go-core/.gocache go test ./internal/relaycore ./internal/relayrouter ./internal/relaygateway ./internal/relayapp ./internal/relay ./cmd/webterm-relay
```

## 风险

```text
风险：P2P 尝试拖慢 terminal 打开。
处理：Relay-first，terminal 可用后再后台 P2P。

风险：切换时 manager sessions 重复派发。
处理：同一 device 只允许一个 active mux transport；旧 transport 回调用 generation 丢弃。

风险：移动网络 NAT 下 P2P 成功率不稳定。
处理：Relay mux 永远是 fallback；P2P 失败不影响 terminal。

风险：Android WebRTC 依赖体积或 ABI 兼容问题。
处理：先验证 Gradle artifact 和 emulator smoke；必要时改本地 AAR 或维护中的 SDK。

风险：DataChannel 上误走旧 mock 协议。
处理：P2P transport 只承载 webterm.mux.v1 text/binary。
```

## 完成定义

```text
Web 和 Android 都先通过 Relay mux 打开 terminal。
P2P 尝试失败时，terminal 不受影响。
P2P connected 后，Web 和 Android 都能通过 DataChannel mux 打开 manager 和 terminal。
P2P 断开后，Web 和 Android 都能自动回 Relay mux。
Go Relay 不增加 terminal/session 业务状态。
Go Agent 仍是 session/terminal truth source。
Web、Android、Go Agent 共享同一套 mux 语义。
```
