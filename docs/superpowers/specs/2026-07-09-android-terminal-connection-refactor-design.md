# Android 终端连接分层重构设计

- 日期：2026-07-09
- 范围：`android-client/` 终端连接子系统
- 目标：保留现有 mux/tunnel/terminal 二进制协议，仅重构 Android 端代码结构
- 策略：增量替换，服务端零改动

---

## 1. 背景

当前 Android 终端连接逻辑集中在 `TerminalConnection`、`MuxSession`、`RelayMuxSessionManager` 三个类中，职责边界模糊：

- `TerminalConnection` 同时管理 UI 状态、物理连接、mux channel、cookie、重连、lastSeq。
- `MuxSession` 和 `RelayMuxSessionManager` 交织物理重连、channel 复用、P2P fallback。
- 终端业务协议解析和 mux 帧解析混在一起，导致类过大且难以单测。

服务端协议本身已经符合“设备级单连接 + channel 复用”模型（`/ws/sessions` + `webterm.mux.v1`），无需重写协议。

---

## 2. 设计目标

1. 一条 `/ws/sessions` 物理连接由 `DeviceConnection` 单独管理。
2. 每个终端 session 由 `SessionChannel` 表示，封装 tunnel channel 全生命周期。
3. `TerminalConnection` 只负责 UI 状态转发，不再接触 WebSocket/mux/cookie/物理重连。
4. 拆出 `MuxCodec` 和 `TerminalCodec`，避免大类。
5. `DeviceConnection` 内部使用统一发送队列串行写 WebSocket。
6. 物理断开后所有 channel 统一失效，重连成功后按 `ws-connect → ws-connected → HELLO(lastSeq)` 流程恢复。
7. UI 层不拼接 `tunnelConnectionId`，该逻辑封装在 `SessionChannel` 或更底层。
8. 第一阶段不做 active/metadata 模式，协议保持完全兼容。
9. 严格区分 `detach`（UI 解绑，channel 保留）和 `closeSession`（关闭远端 PTY，发送 ws-close）。

### 2.1 生命周期语义：detach vs closeSession

| 操作 | 触发场景 | 是否发送 ws-close | 是否关闭服务端 PTY | SessionChannel 是否保留 |
|------|---------|------------------|-------------------|------------------------|
| `detach` | 页面离开、Fragment 销毁、切换到另一个终端 | 否 | 否 | 是，进入 `DETACHED` 或保持 `LIVE` |
| `closeSession` | 用户点击关闭终端、退出 session | 是 | 是 | 否，进入 `CLOSED` 后销毁 |

原则：
- 只有用户明确关闭终端时才关闭远端 session。
- UI 层销毁、导航切换、Activity 切后台都只能 detach，不能 close。
- `SessionChannel` 不因为 UI listener 离开而关闭，它独立管理自己的生命周期。

### 2.2 Android 生命周期状态矩阵（迁移关键）

当前实现里 `pauseCurrentConnection()` 会走 `closeTerminalConnection("activity paused")` → `TerminalConnection.close()` → `closeChannel()`，也就是说后台/切页现在会发 `ws-close`。这与 2.1 的 detach 语义冲突，迁移时必须逐个场景改清楚：

| 场景 | 当前行为 | 目标行为 | 是否发 ws-close | SessionChannel | TerminalSession/缓存 | 备注 |
|------|---------|---------|----------------|---------------|---------------------|------|
| `onPause()` / 切后台 | `closeTerminalConnection`（发 ws-close） | **detach UI**，channel 保持 | 否 | 保持 `LIVE`/`DETACHED` | 内存缓存 snapshot | 后台保活由 DeviceConnectionRegistry idle 策略决定 |
| `onResume()` / 回到前台 | `connectTerminal()` 重建 channel | **reattach** 已有 channel | 否 | 若仍为 `LIVE` 直接 attach；若为 `STALE` 则 recover | 用最新 lastSeq 恢复 | 不再重复建立新 channel |
| 切换到另一个终端 tab | `closeTerminalConnection` | **detach** 当前，打开新 channel | 否 | 原 channel 保留，新 channel 创建 | 各自独立缓存 | 多终端并行时需要多 SessionChannel |
| Fragment `onDestroyView()` | 视实现可能 close | **detach** | 否 | 保留 | 内存缓存保留 | 同 onPause |
| 用户点击关闭按钮 | `closeTerminal(true)` | `closeSession()` | 是 | `CLOSED` 后移除 | 清除缓存，finish session | 唯一发 ws-close 的入口 |
| Activity 被系统回收/进程退出 | 无保证 | 视策略：可发 ws-close 或依赖服务端超时 | 可选 | 销毁 | 磁盘缓存保留 lastSeq | 需在 `onDestroy()`/`Application.onTrimMemory()` 中显式处理 |
| 用户从 Home 列表删除 session | `closeSession()` / HTTP DELETE | `closeSession()` | 是 | 移除 | 清除缓存 | 与关闭按钮一致 |

关键改动：
- `pauseCurrentConnection()` 不再调用 `TerminalConnection.close()`，改为 `TerminalConnection.detach()`。
- `closeTerminalConnection()` 这个辅助方法要改名或删掉，避免“close”一词被误用成 detach。
- `connectTerminal()` 在 resume 时改为 `TerminalConnection.attach(existingChannel)`，只有 channel 不存在时才 `connect()`。
- 进程退出时若选择不发 ws-close，需文档化：服务端依赖 PTY idle/超时机制清理；若选择发，则需在 `onDestroy()` 中同步发送（可能超时）。

---

## 3. 总体架构

```
┌─────────────────────────────────────────┐
│  TerminalFragment / TerminalView        │
├─────────────────────────────────────────┤
│  TerminalConnection (UI State)          │
├─────────────────────────────────────────┤
│  SessionChannel (Tunnel Channel +       │
│  Terminal Protocol + lastSeq)           │
├─────────────────────────────────────────┤
│  DeviceConnection (Physical WS +        │
│  Mux Control + Routing + Send Queue)    │
├─────────────────────────────────────────┤
│  Transport (WebSocket / WebRTC)         │
└─────────────────────────────────────────┘
```

数据方向：

- 下行：`Transport` → `DeviceConnection` → `MuxCodec` → `SessionChannel` → `TerminalCodec` → `TerminalConnection` → UI
- 上行：UI → `TerminalConnection` → `SessionChannel` → `TerminalCodec` → `MuxCodec` → `DeviceConnection.sendQueue` → `Transport`

`TransportProvider`（4.1.1）位于 `DeviceConnection` 与 `Transport` 之间，负责选择 WebSocket 或 P2P、处理 fallback/backoff/cookie refresh；架构图中省略以简化层次。

---

## 4. 核心类设计

### 4.1 Transport

```java
public interface Transport {
    void start(Listener listener);
    void close();
    boolean isOpen();
    boolean sendText(String text);
    boolean sendBinary(byte[] data);
    boolean isP2P();

    interface Listener {
        void onOpen();
        void onText(String text);
        void onBinary(byte[] data);
        void onClosed(String reason);
        void onError(String message);
    }
}
```

职责：只负责一条物理连接的打开、关闭、收发。不感知 mux、session、终端业务。

实现：
- `WebSocketTransport`：连接 `/ws/sessions`，subprotocol `webterm.mux.v1`。
- `WebRtcDataChannelTransport`：P2P data channel 包装。

### 4.1.1 TransportProvider / TransportSelector

`DeviceConnection` 不直接决定用 WebSocket 还是 P2P，而是依赖一个专门的 `TransportProvider` 负责选择、fallback、backoff、cookie 更新后重连。这样把现有 `RelayMuxSessionManager` 中的 P2P 策略完整迁移出来，不散落在 `DeviceConnection` 里。

```java
public interface TransportProvider {
    // 获取当前最适合的 Transport；若返回 null 则 DeviceConnection 进入 FAILED
    Transport acquire(
        String baseUrl,
        String deviceId,
        String cookie,
        Transport.Listener listener,
        Callback callback
    );

    // cookie 更新后强制重新选择 transport
    void refreshCookie(String cookie);

    // 手动强制重新建立底层 transport（例如点击重试）
    void forceReconnect(String reason);

    interface Callback {
        void onP2PStateChange(boolean connected);
    }
}
```

默认实现 `RelayTransportProvider` 保留现有行为：

1. 每次 `acquire()` 时检查 `p2pBackoffUntil`。
2. 未 backoff 时先 `prepareDataChannel()`，再尝试 `createDataChannel()`。
3. P2P 成功 → 使用 `WebRtcDataChannelTransport`。
4. P2P 失败/超时时 → 5 秒后若仍未连接，则 backoff 30 秒并切回 WebSocket。
5. cookie 更新 → `refreshCookie()` → 触发底层 transport 重建。
6. 手动 `forceReconnect()` → 立即重建 transport，打破 stale connected 拦截。

`DeviceConnection` 只调用 `transportProvider.acquire(...)` 拿到一个 `Transport` 实例，然后监听它的 `onOpen/onClosed/onError`；P2P 相关的 timeout、backoff、fallback 全部由 `TransportProvider` 内部管理。

---

### 4.2 MuxCodec

无状态工具类，负责 mux 控制消息和 tunnel frame 编解码。

```java
public final class MuxCodec {
    // 控制消息编码
    public static String encodeWsConnect(String tunnelId, String path, String[] protocols);
    public static String encodeWsClose(String tunnelId);                       // client → server
    public static String encodeWsConnected(String tunnelId);                   // mostly server-side
    public static String encodeWsError(String tunnelId, int code, String message);

    // 控制消息解码
    public static MuxControlMessage decodeControlMessage(String text);

    // tunnel frame
    public static byte[] encodeTunnelFrame(String tunnelId, byte[] payload, boolean binary);
    public static TunnelFrame decodeTunnelFrame(byte[] data);
}
```

`MuxControlMessage` 是一个数据类，必须包含以下字段（兼容旧服务端/旧客户端缺失字段的情况）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | String | `ws-connect` / `ws-connected` / `ws-close` / `ws-error` |
| `tunnelId` | String | tunnelConnectionId |
| `path` | String | 仅 `ws-connect` 有 |
| `protocols` | String[] | 仅 `ws-connect` 有 |
| `code` | int | `ws-error` / `ws-close` 都有；旧消息缺失时默认 0 |
| `reason` | String | `ws-close` 有；旧消息缺失时默认 "" |
| `message` | String | `ws-error` 有 |

兼容性要求：
- 解码时必须使用 `optInt` / `optString`，不能因 `code`/`reason` 缺失抛异常。
- 单元测试必须覆盖“旧服务端只发 message 不发 code”和“新服务端发 code+reason”两种 JSON。服务端 `ws-close` 参考字段见 `go-core/internal/mux/virtual_socket.go:73`（`code`、`reason`）。

---

### 4.3 TerminalCodec

无状态工具类，负责 channel payload 内部的终端二进制协议。

```java
public final class TerminalCodec {
    public static byte[] encodeHello(long lastSeq, int cols, int rows);
    public static byte[] encodeInput(String data);
    public static byte[] encodeResize(int cols, int rows);
    public static byte[] encodeTitle(String title);
    public static byte[] encodeDownloadProgress(String downloadId, long current, long total);
    public static byte[] encodePing();

    public static TerminalMessage decode(byte[] payload);
}
```

`TerminalMessage` 数据类包含 `type`（INPUT/OUTPUT/RESIZE/HELLO/INFO/EXIT/STATE/TITLE/PING/PONG/HOOK/DOWNLOAD_PROGRESS）、`seq`、`data`、JSON 字段等。

---

### 4.4 DeviceConnection

代表 `/ws/sessions` 这一条设备级物理连接。

#### 状态

```java
public enum State {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED
}
```

#### 核心字段

```java
public final class DeviceConnection {
    private final String baseUrl;
    private final String deviceId;
    private final AuthProvider authProvider;
    private final TransportProvider transportProvider;
    private final SendQueue sendQueue;
    private final Map<String, SessionChannel> channels = new LinkedHashMap<>();
    private final Handler mainHandler;

    private State state = State.DISCONNECTED;
    private Transport transport;
    private int generation;
}
```

#### 职责

- 创建/关闭 `Transport`。
- 通过 `AuthProvider` 获取 cookie，cookie 失效时刷新。
- 管理连接生命周期和重连退避。
- 维护统一发送队列，串行写出所有 mux 控制消息和 tunnel frame。
- 接收文本控制消息，解析后按 `tunnelId` 分发给 `SessionChannel`。
- 接收二进制 tunnel frame，按 `tunnelId` 分发给 `SessionChannel`。
- 物理断开后通知所有 `SessionChannel` 进入 `STALE`。
- 重连成功后通知需要恢复的 `SessionChannel` 重新 `ws-connect`。
- 当且仅当所有 `SessionChannel` 都已关闭，并且 `DeviceConnectionRegistry` 判定超过空闲超时时间后，才主动关闭自身以释放资源。

#### 关键方法

```java
public void start();
public void close();
public State getState();
public boolean hasChannels();

// 创建 channel（初始状态为 DETACHED），不绑定 listener
public SessionChannel openChannel(String sessionId, String[] protocols);

// 内部使用
void sendControl(String text);
void sendFrame(String tunnelId, byte[] payload, boolean binary);
void removeChannel(String tunnelId);
```

注意：
- `DeviceConnection` 不解析终端业务帧，不持有 lastSeq，不感知 UI。
- `DeviceConnection` 自身不启动 idle timer；空闲关闭策略由 `DeviceConnectionRegistry` 统一持有，通过 `hasChannels()` 判断是否可以释放。

---

### 4.5 SessionChannel

代表一个 tunnel channel，对应一个终端 session。`SessionChannel` 不绑定单个 UI listener，可以长期存在；UI 层通过 `attach(listener)` / `detach(listener)` 订阅或取消订阅。

#### 状态

```java
public enum State {
    DETACHED,      // 没有 UI 绑定，也未发送 ws-connect
    CONNECTING,    // 已发送 ws-connect，等待 ws-connected
    LIVE,          // ws-connected 已收到，已发送 HELLO，正常收发终端数据
    STALE,         // 物理连接断开或通道异常，可恢复
    CLOSED,        // 正常关闭或不可恢复关闭，channel 即将销毁
    ERROR          // 不可恢复错误
}
```

内部子状态（不暴露，但实现时必须遵守）：

```
CONNECTING
    ├── 已发 ws-connect，等待 ws-connected
    └── ws-connected 收到后 → 发送 HELLO → LIVE

LIVE
    ├── 已发 HELLO
    └── 可安全发送 INPUT / RESIZE / TITLE / PING
```

**关键规则**：在收到 `ws-connected` 之前，禁止发送 `HELLO/INPUT/RESIZE/TITLE/PING`；这些消息应暂存或返回失败。

#### 核心字段

```java
public final class SessionChannel {
    private final String sessionId;     // 原始 sessionId（可能带 deviceId: 前缀）
    private final String localSessionId; // 去掉 deviceId 前缀后的服务端 session id
    private final String tunnelId;       // term:<localSessionId>
    private final String path;           // /ws/sessions/<localSessionId>
    private final String[] protocols;    // ["webterm.binary.v1"]
    private final DeviceConnection deviceConnection;

    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Queue<PendingMessage> pendingBeforeLive = new ArrayDeque<>();

    private State state = State.DETACHED;
    private long lastSeq;       // SessionChannel 自己维护，网络层恢复的唯一真源
    private int columns;
    private int rows;
    private boolean helloSent;  // 内部子状态
}
```

`tunnelId`、`path` 由 `SessionChannel` 内部根据 `sessionId` 生成：
- `localSessionId` = `sessionId` 去掉 `deviceId:` 前缀（如果已有）。
- `tunnelId` = `"term:" + localSessionId`。
- `path` = `"/ws/sessions/" + localSessionId`。
- `protocols` = `["webterm.binary.v1"]`。

UI 层只传 `sessionId`，不拼接 tunnelId、path、protocols。

#### 职责

- 向 `DeviceConnection` 注册/注销。
- 发送 `ws-connect` / `ws-close`。
- 发送 `HELLO(lastSeq, cols, rows)`、`INPUT`、`RESIZE`、`TITLE`、`PING`。
- 接收 tunnel frame payload，用 `TerminalCodec` 解析。
- **自己维护 `lastSeq`**，UI 层日常不需要传递；仅在冷启动时通过 `setLastSeq(seed)` 注入缓存/磁盘中的初始值。
- 处理 `OUTPUT` / `STATE` / `INFO` / `TITLE` / `EXIT` / `HOOK` / `DOWNLOAD_PROGRESS`。
- 物理断开后进入 `STALE`；恢复后重新走 `ws-connect → HELLO` 流程。
- 把终端数据/状态/错误回调给所有已 attach 的 listener。

#### 关键方法

```java
// UI 绑定/解绑
public void attach(Listener listener);
public void detach(Listener listener);

// 生命周期：connect 只传 cols/rows；lastSeq 由 SessionChannel 自己维护，
// 但首次创建时可通过 setLastSeq(seed) 从缓存/磁盘恢复初始值
public void connect(int cols, int rows);
public void setLastSeq(long seq);   // 仅用于冷启动 seed，connect 后不再由 UI 调用
public long getLastSeq();           // UI reattach 时取最新值

// 用户明确关闭终端，发送 ws-close、关闭远端 PTY，并通知 DeviceConnection 移除本 channel
public void closeSession();

// 发送终端业务消息
public void sendInput(String data);
public void sendResize(int cols, int rows);
public void sendTitle(String title);
public void sendDownloadProgress(String downloadId, long current, long total);

// DeviceConnection 回调
void onWsConnected();
void onWsError(int code, String message);
void onWsClose(int code, String reason);
void onTransportDisconnected();
void onTransportConnected();
void onTunnelData(byte[] payload, boolean binary);

public interface Listener {
    void onStateChange(State state, int reconnectAttempts);
    void onOutput(long seq, byte[] data);
    void onState(long seq, byte[] data);
    void onInfo(JSONObject info);
    void onTitle(String title);
    void onHook(JSONObject ev);
    void onDownloadHook(String downloadId, String fileName, long fileSize, String sessionId);
    void onExit(int code);
    void onProtocolError(String message);
}
```

#### ws-close / ws-error 分类处理

| 来源 | code / 原因 | 分类 | 状态 | 行为 |
|------|------------|------|------|------|
| 网络断开、transport 异常 | — | 可恢复 | `STALE` | `DeviceConnection` 自动重连，恢复后重新 `ws-connect` |
| 服务端背压关闭 channel | `ws-close` going-away | 可恢复 | `STALE` | 自动重新 `ws-connect` |
| session 不存在 | `ws-error` 404 | 不可恢复 | `ERROR` | 回调 `onProtocolError`，不再自动重连 |
| PTY 退出 / session 已关闭 | `ws-close` normal / 服务端主动 | 不可恢复 | `CLOSED` | 回调 `onExit`，不再重连 |
| 用户主动 `closeSession()` | — | 不可恢复 | `CLOSED` | 发送 `ws-close`，销毁 channel |
| 认证失败 | 401 / `ws-error` | 不可恢复 | `ERROR` | 触发 `AuthProvider` 刷新，DeviceConnection 重连后该 channel 再尝试一次，仍失败则 `ERROR` |

#### 消息暂存规则

在 `CONNECTING` 状态（已发 `ws-connect` 但未收到 `ws-connected`）期间：

- `INPUT`：入队，进入 `LIVE` 后按顺序发送。
- `RESIZE`：只保留最后一次，进入 `LIVE` 后发送。
- `TITLE`：入队。
- `PING`：直接丢弃；进入 `LIVE` 后由 keepalive 循环重新产生。
- `HELLO`：由 `SessionChannel` 在收到 `ws-connected` 后自动发送，不接收外部 HELLO。

在 `STALE` 状态期间：

- `INPUT`：返回失败或丢弃（UI 应显示未连接）。
- `RESIZE`：更新本地 `columns/rows`，恢复后自动发送最新值。
- `TITLE` / `PING`：丢弃。

#### lastSeq 所有权与同步

`SessionChannel` 是 **网络恢复时 lastSeq 的唯一真源**，解决旧代码中 `terminalState.lastSeq()` 被缓存旧值覆盖导致重复 replay 的问题。

| 时机 | 行为 | 数据来源 | 写入方 |
|------|------|---------|--------|
| 冷启动创建 channel | 用磁盘/内存缓存的 lastSeq 作为初始值 | `TerminalRuntimeState.lastSeq()` / `CachedTerminal.lastSeq` | `TerminalConnection.connect()` 在 `channel.connect()` 前调用 `channel.setLastSeq(seed)` |
| 运行中收到 OUTPUT/STATE | 更新 `lastSeq` | 服务端二进制帧 | `SessionChannel` 自己 |
| UI detach 期间 | channel 继续收数据，`lastSeq` 继续更新 | 服务端增量 | `SessionChannel` 自己 |
| UI reattach | 用 `channel.getLastSeq()` 而非 `terminalState.lastSeq()` | `SessionChannel` | `TerminalConnection` |
| 缓存 snapshot | 把最新 `lastSeq` 写回内存/磁盘缓存 | `SessionChannel.getLastSeq()` | `TerminalLifecycleController.cacheCurrentTerminal()` |
| 重置 lastSeq | 多次恢复失败后可重置为 0 | 本地策略 | `SessionChannel` 内部决策，不暴露给 UI |

原则：
- `TerminalConnection.connect()` 不再传 `lastSeq` 参数。
- 冷启动 seed 只发生一次；一旦 `SessionChannel` 开始运行，所有后续恢复都直接用它内部维护的最新值。
- `TerminalRuntimeState` 仍保留 `lastSeq` 用于缓存 snapshot 和磁盘元数据，但**不作为网络恢复输入**。

#### 连接恢复流程

```java
void recover() {
    if (state != STALE) return;
    state = CONNECTING;
    helloSent = false;
    deviceConnection.sendControl(
        MuxCodec.encodeWsConnect(tunnelId, path, protocols)
    );
}

void onWsConnected() {
    if (state != CONNECTING) return;
    state = LIVE;
    sendHello(lastSeq, columns, rows);
    helloSent = true;
    flushPendingMessages();
}
```

服务端收到 HELLO 后：
- 如果 `lastSeq` 有效且缓存足够 → 补发 `OUTPUT` 增量。
- 如果缓存丢失 → 发送 `STATE` 全量快照。

---

### 4.6 TerminalConnection

只负责终端 UI 状态和事件转发。`TerminalConnection` 不拥有 `SessionChannel`，只通过 `attach`/`detach` 监听它。

#### 状态

```java
public enum State {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    CLOSED,
    ERROR
}
```

#### 核心字段

```java
public final class TerminalConnection {
    private final DeviceConnectionRegistry registry;
    private final String baseUrl;
    private final String deviceId;
    private final AuthProvider authProvider;
    private final TransportProvider transportProvider;
    private final Listener uiListener;

    private SessionChannel channel;
}
```

server/device 相关参数（`baseUrl`、`deviceId`、`cookie` 来源等）由 `TerminalConnection.Factory` 在创建时注入；`TerminalConnection` 自身不再从 `ServerConfig` 读取。

#### 职责

- 通过 `DeviceConnectionRegistry` 获取/创建 `DeviceConnection`。
- `connect(sessionId, cols, rows, initialLastSeq)`：找到/创建 `SessionChannel`；若为新 channel 则用 `initialLastSeq` seed；然后 `attach(channel)`，再调用 `channel.connect(cols, rows)`。
- `reattach()`：当 Activity 回到前台、Fragment 重建时，直接 `attach(channel)`，不复位 lastSeq；此时用 `channel.getLastSeq()` 取 detach 期间持续更新的最新值。
- `attach(SessionChannel)` / `detach()`：订阅/取消订阅 channel 的输出和状态；`detach()` 不关闭 channel。
- `sendInput(String)` / `sendResize(cols, rows)`：转发给 `SessionChannel`。
- 监听 `SessionChannel` 状态变化，更新 UI。
- 把 `OUTPUT` / `STATE` 数据转给 `TerminalView` 渲染。
- 处理 `EXIT` / `ERROR` 等 UI 事件。
- 用户点击关闭时，调用 `SessionChannel.closeSession()`。

#### 关键方法

```java
// 冷启动：用 initialLastSeq seed SessionChannel，然后 attach + connect
public void connect(String sessionId, int cols, int rows, long initialLastSeq);

// 重新 attach 已有 channel（resume / Fragment 重建），不 seed lastSeq
public void reattach(SessionChannel channel);

// UI 绑定/解绑，不会关闭 channel
public void attach(SessionChannel channel);
public void detach();

// 手动触发重连（例如点击重试按钮）
public void reconnectNow();

// 用户明确关闭终端
public void closeSession();

public void sendInput(String data);
public void sendResize(int cols, int rows);
public void sendTitle(String title);
public void sendDownloadProgress(String downloadId, long current, long total);

// 更新本地尺寸，debounce 后通过 SessionChannel 发送 RESIZE
public void updateSize(int cols, int rows);
```

`TerminalConnection` 不再：
- 直接操作 WebSocket
- 处理 mux 控制消息
- 管理 cookie
- 做物理重连
- 拼接 tunnel ID
- 维护 `lastSeq`

---

### 4.7 DeviceConnectionRegistry

```java
@Singleton
public final class DeviceConnectionRegistry {
    private final Map<String, DeviceConnection> connections = new LinkedHashMap<>();

    public synchronized DeviceConnection getOrCreate(
        String baseUrl,
        String deviceId,
        AuthProvider authProvider,
        TransportProvider transportProvider
    );

    public synchronized void releaseIfIdle(DeviceConnection connection);
    public synchronized void reconnectDevice(String baseUrl, String deviceId, String reason);
    public synchronized void shutdown();
}
```

Key：`baseUrl + "\n" + deviceId`（cookie 不参与 key，因为 cookie 会轮换）。

---

### 4.8 AuthProvider

```java
public interface AuthProvider {
    String currentCookie();
    void refreshCookie(Consumer<String> onCookie, Consumer<String> onError);
}
```

`DeviceConnection` 重连时发现 cookie 失效（例如 WebSocket 握手 401 或收到认证错误），调用 `refreshCookie` 刷新后再重试。正常重连时直接使用 `currentCookie()`。

实现可以由上层注入，例如从 `ServerConfig` 读取，或在 401 时调 `/api/auth/refresh`。

---

### 4.9 线程模型

- `Transport` 回调（`onOpen/onText/onBinary/onClosed/onError`）发生在 OkHttp / WebRTC 后台线程。
- `DeviceConnection` 把这些回调 post 到 `mainHandler` 后再处理，保证后续状态机和 UI 回调都在主线程。
- `SendQueue` 使用独立工作线程消费队列，但入队操作可在任意线程调用。
- `SessionChannel` 和 `TerminalConnection` 的所有公共方法都应在主线程调用。

这样设计避免在 `DeviceConnection` 和 `SessionChannel` 内部加锁，降低并发 bug 风险。

## 5. 状态流转

### 5.1 DeviceConnection

```
DISCONNECTED
    │
    ▼ start()
CONNECTING
    │
    │ onOpen()
    ▼
CONNECTED ◄─────────────────────┐
    │                           │
    │ transport.onClosed/Error  │
    ▼                           │
RECONNECTING                    │
    │                           │
    │ onOpen()                  │
    └───► notify channels STALE │
              ▲                 │
              │ recover()       │
              └─────────────────┘

FAILED  ←──── auth failure / max retries
```

### 5.2 SessionChannel

```
DETACHED
    │
    ▼ connect()
CONNECTING  ──ws-connect──► 等待 ws-connected
    │                          │
    │ ws-connected             │
    ▼                          │
   LIVE  ──HELLO──► 收发数据   │
    │                          │
    │ DeviceConnection 断开    │
    ▼                          │
  STALE  ──recover──► CONNECTING
    │
    │ ws-error / 多次失败
    ▼
  ERROR
    │
    │ close()
    ▼
  CLOSED
```

---

## 6. 重连恢复流程

### 6.1 物理连接断开

```
Transport.onClosed("network lost")
    ↓
DeviceConnection.state = RECONNECTING
    ↓
for each SessionChannel:
    channel.onTransportDisconnected()
    channel.state = STALE
    ↓
TerminalConnection.onConnectionStatus(RECONNECTING)
```

### 6.2 物理连接恢复

```
DeviceConnection.reconnect() success
Transport.onOpen()
    ↓
DeviceConnection.state = CONNECTED
    ↓
for each SessionChannel where state == STALE:
    channel.onTransportConnected()
    channel.recover()   // send ws-connect
```

### 6.3 每个 SessionChannel 恢复

```java
void recover() {
    if (state != STALE) return;
    state = CONNECTING;
    helloSent = false;
    deviceConnection.sendControl(
        MuxCodec.encodeWsConnect(tunnelId, path, protocols)
    );
}

void onWsConnected() {
    if (state != CONNECTING) return;
    state = LIVE;
    helloSent = true;
    sendHello(lastSeq, columns, rows);  // lastSeq 由 SessionChannel 自己维护
    flushPendingMessages();
}
```

服务端收到 HELLO 后：
- 如果 `lastSeq` 有效且缓存足够 → 补发 `OUTPUT` 增量。
- 如果缓存丢失 → 发送 `STATE` 全量快照。

注意：`TerminalConnection.connect()` 日常只传 `sessionId/cols/rows`，但**冷启动时额外传 `initialLastSeq` 用于 seed**。seed 完成后 `lastSeq` 由 `SessionChannel` 自己维护；`reattach()` 不复位 lastSeq。

---

## 7. 数据流

### 7.1 下行：服务端 → UI

```
Transport.onBinary(byte[] data)
    ↓
MuxCodec.decodeTunnelFrame(data)
    ↓
DeviceConnection.route(tunnelId, payload, binary)
    ↓
SessionChannel channel = channels.get(tunnelId)
channel.onTunnelData(payload, binary)
    ↓
TerminalMessage msg = TerminalCodec.decode(payload)
    ↓
switch msg.type:
    OUTPUT → update lastSeq → listener.onOutput(seq, data)
    STATE  → update lastSeq → listener.onState(seq, data)
    INFO   → listener.onInfo(info)
    EXIT   → listener.onExit(code)
    HOOK   → listener.onHook(hook)
    DOWNLOAD_PROGRESS → listener.onDownloadProgress(...)
```

### 7.2 上行：UI → 服务端

```
TerminalView 输入
    ↓
TerminalConnection.sendInput(text)
    ↓
SessionChannel.sendInput(text)
    ↓
byte[] payload = TerminalCodec.encodeInput(text)
byte[] frame = MuxCodec.encodeTunnelFrame(tunnelId, payload, true)
    ↓
DeviceConnection.sendFrame(frame)
    ↓
SendQueue 按 FIFO 串行写出（RESIZE 合并）
    ↓
Transport.sendBinary(frame)
```

UI 绑定/解绑流程（冷启动）：

```
TerminalFragment.onViewCreated()
    ↓
TerminalConnection.connect(sessionId, cols, rows, initialLastSeq)
    ↓
DeviceConnectionRegistry.getOrCreate(...) → DeviceConnection
    ↓
DeviceConnection.openChannel(sessionId, ...) → SessionChannel
    ↓
SessionChannel.setLastSeq(initialLastSeq)   // 仅冷启动 seed
    ↓
TerminalConnection.attach(channel)
    ↓
SessionChannel.attach(listener)
    ↓
SessionChannel.connect(cols, rows)   // 发送 ws-connect
```

UI 重新绑定流程（resume / Fragment 重建）：

```
TerminalFragment.onViewCreated() / onResume()
    ↓
TerminalConnection.reattach(existingChannel)
    ↓
SessionChannel.attach(listener)      // 直接监听，不复位 lastSeq
    ↓
（若 channel 为 STALE 则内部自动 recover）
```

UI 解绑流程（切后台 / Fragment 销毁）：

```
TerminalFragment.onDestroyView() / onPause()
    ↓
TerminalConnection.detach()
    ↓
SessionChannel.detach(listener)
    ↓
（channel 保持打开，不发送 ws-close；lastSeq 继续随服务端增量更新）
```

用户关闭终端：

```
用户点击关闭
    ↓
TerminalConnection.closeSession()
    ↓
SessionChannel.closeSession()
    ↓
发送 ws-close
    ↓
SessionChannel.state = CLOSED
    ↓
DeviceConnection.removeChannel(tunnelId)
```

---

## 8. 发送队列设计（第一阶段保守实现）

第一阶段不引入跨 channel 优先级抢占，只做**单线程 FIFO writer + RESIZE 合并**，降低引入乱序的风险。

### 8.1 设计目标

- 避免多个 `SessionChannel` 并发写同一条 WebSocket。
- 保证同一 channel 的终端消息按入队顺序发出。
- 连续多次 `RESIZE` 只发最后一次。
- 物理连接断开时，控制消息（`ws-connect` / `ws-close` / `HELLO`）可暂存并在恢复后发送；用户输入返回失败。

### 8.2 队列结构

```java
public final class SendQueue {
    private final BlockingQueue<QueueEntry> queue;
    private final Thread worker;

    void start();
    void stop();
    boolean offer(QueueEntry entry);
    boolean remove(Predicate<QueueEntry> predicate);
}

public final class QueueEntry {
    public final String tunnelId;   // 用于 RESIZE 合并和调试
    public final MessageType messageType; // CONNECT / CLOSE / HELLO / INPUT / RESIZE / TITLE / PING
    public final Message message;
    public final long sequence;
}

public sealed class Message {
    public record Control(String text) implements Message {}
    public record Binary(byte[] data) implements Message {}
}
```

### 8.3 入队规则

- **FIFO**：所有消息按入队顺序进入队列，worker 单线程消费。
- **RESIZE 合并**：入队前若队列中已有同 `tunnelId` 的 `RESIZE` 待发送，移除旧条目，只保留最新尺寸。
- **PING**：若队列中已有同 `tunnelId` 的 `PING`，丢弃旧的。
- **队列满**：非关键消息返回失败；关键消息（`ws-connect` / `ws-close` / `HELLO`）允许入队并带发送超时，超时未发出则触发重连。

### 8.4 连接状态影响

- `DeviceConnection.state == CONNECTED`：worker 正常消费队列。
- `DeviceConnection.state != CONNECTED`：
  - `ws-connect` / `ws-close` / `HELLO`：入队暂存，恢复后发送。
  - `INPUT` / `TITLE`：返回失败。
  - `RESIZE`：更新本地缓存，恢复后发送最新值。
  - `PING`：丢弃。

### 8.5 未来扩展

第二阶段在单测/集成测试证明不乱序后，可引入优先级：

```java
public enum Priority {
    CRITICAL,   // ws-connect / ws-close / HELLO
    NORMAL,     // INPUT / TITLE
    STATE,      // RESIZE（合并）
    KEEPALIVE   // PING（可丢弃）
}
```

注意：即使引入优先级，也要保证**同一 channel 内部严格保序**，不同 channel 的 CRITICAL 消息按入队顺序发送。

---

## 9. 错误处理

错误分类必须基于 `MuxControlMessage` 中的真实 `code` / `reason` 字段（见 4.2），不能只看 `type`。

| 场景 | 真实字段 | 分类 | 处理 |
|------|---------|------|------|
| WebSocket 握手 401 | HTTP 401 | 不可恢复（认证） | `DeviceConnection` 进入 `FAILED`，调用 `AuthProvider.refreshCookie()`，刷新成功后重连 |
| `ws-error` code=404 / session 不存在 | `code=404` | 不可恢复 | `SessionChannel` 进入 `ERROR`，回调 `onProtocolError`，不自动重连 |
| `ws-error` code=401 / 认证失败 | `code=401` | 不可恢复（认证） | 触发 `AuthProvider` 刷新，`DeviceConnection` 重连后该 channel 再尝试一次，仍失败则 `ERROR` |
| 服务端发 `ws-close` code=1001 / going-away / 背压关闭 | `code=1001` 或 reason 含背压标识 | 可恢复 | `SessionChannel` 进入 `STALE`，自动重新 `ws-connect` |
| 服务端发 `ws-close` code=1000 / normal / PTY 已退出 | `code=1000` 或 reason 含 exit | 不可恢复 | `SessionChannel` 进入 `CLOSED`，回调 `onExit`，不自动重连 |
| 用户主动 `closeSession()` | — | 不可恢复 | `SessionChannel` 进入 `CLOSED`，发送 `ws-close`，销毁 channel |
| 物理连接反复失败 | transport onClosed/onError | 可恢复但可能最终失败 | `DeviceConnection` 指数退避重试，超过阈值进入 `FAILED` |
| tunnel frame 路由不到 channel | — | 内部异常 | `DeviceConnection` 记录 warn，丢弃 |
| 终端协议解析错误 | — | 内部异常 | `SessionChannel` 记录 warn，继续运行 |
| 发送队列满（非关键消息） | — | 可恢复 | 返回失败，由 `SessionChannel` 决定是否重试 |
| 发送队列满（关键消息超时） | — | 严重异常 | 进入 `ERROR`，触发 `DeviceConnection` 重连 |

兼容性：Android 旧代码对 `ws-error` 只读 `message`、`ws-close` 不读 `code/reason`（见 `MuxSession.java:187`）。重构后的 `MuxCodec` 必须先用 `optInt`/`optString` 读取，再按 code 分类；同时保留对旧服务端 JSON 的兜底（code 缺失时按 message 关键词分类）。

---

## 10. 迁移步骤

### Step 1：新增 Codec

- 创建 `MuxCodec` 和 `TerminalCodec`。
- 把 `WebTermProtocol` 中的编码/解码逻辑迁移过去。
- 保持 `WebTermProtocol` 暂时不删除，作为兼容层。

### Step 2：重构 Transport + TransportProvider

- 定义 `Transport` 接口；`WebSocketMuxTransport` / `WebRtcDataChannelTransport` 实现接口。
- 新增 `TransportProvider` / `RelayTransportProvider`，把 P2P fallback（5 秒 timeout、30 秒 backoff、cookie 更新后重连）从 `RelayMuxSessionManager` 迁移进来。
- `DeviceConnection` 通过 `TransportProvider.acquire()` 获取当前 `Transport`。
- 移除或适配旧的 `MuxTransport` 接口。

### Step 3：创建 DeviceConnection

- 实现 `DeviceConnection`、`DeviceConnectionRegistry`、`AuthProvider`。
- 内部使用 `MuxCodec` 解析控制消息；`MuxControlMessage` 必须覆盖 `code` / `reason`。
- 实现第一阶段 `SendQueue`：单线程 FIFO + RESIZE 合并。
- 先和旧代码并行存在。

### Step 4：创建 SessionChannel

- 实现 `SessionChannel` 状态机和业务协议。
- 使用 `TerminalCodec` 解析终端帧。
- 处理 `ws-connect/ws-connected/ws-close/ws-error`。
- 维护 `lastSeq`。

### Step 5：重构 TerminalConnection

- 删除 WebSocket/mux/cookie/物理重连/lastSeq 维护逻辑。
- `TerminalConnection` 只通过 `attach(SessionChannel)` / `detach()` 订阅 channel。
- `connect(sessionId, cols, rows, initialLastSeq)` 负责找到/创建 `SessionChannel`，seed lastSeq，`attach(channel)` 后再调用 `channel.connect(cols, rows)`。
- 新增 `reattach(SessionChannel)` 用于 resume / Fragment 重建，不复位 lastSeq。
- 页面离开时只 `detach()`，不关闭 channel。
- 用户点击关闭时调用 `SessionChannel.closeSession()`。
- 转发输入/resize，监听状态和数据回调。

### Step 6：接入生命周期（参考 2.2 状态矩阵）

- `TerminalLifecycleController.pauseCurrentConnection()`：不再调用 `closeTerminalConnection()`，改为 `TerminalConnection.detach()` + `cacheCurrentTerminal()`。
- `TerminalLifecycleController.connectTerminal()`：
  - 若已有 `SessionChannel` 且未关闭 → `TerminalConnection.reattach(channel)`。
  - 若 channel 不存在 → 从缓存取 `initialLastSeq`，调用 `TerminalConnection.connect(...)`。
- `TerminalLifecycleController.detachTerminalView()` 只 detach UI listener，不发送 ws-close。
- `TerminalLifecycleController.closeTerminal(true)` 调用 `SessionChannel.closeSession()`。
- `AppFlowCoordinator` 使用 `DeviceConnectionRegistry`。
- 处理 pause/resume/background 场景：background 时 detach UI，但 channel 保持；进程退出策略单独处理（见 2.2）。

### Step 7：移除旧代码

- 删除 `RelayMuxSessionManager`、`MuxSession`（或保留到测试完成）。
- 删除旧的 `WebTermProtocol` 冗余方法。
- 跑通单元测试和端到端测试。

---

## 11. 关键设计决策

| 决策 | 说明 |
|------|------|
| 保留现有 mux 协议 | 服务端零改动，降低风险 |
| 新增 `TransportProvider` | 把 WebSocket/P2P 选择、fallback、backoff、cookie refresh 策略集中管理 |
| `DeviceConnection` 持有发送队列 | 避免多终端并发写同一条 WebSocket |
| 第一阶段发送队列：单线程 FIFO + RESIZE 合并 | 不引入跨 channel 优先级抢占，先验证不乱序 |
| `SessionChannel` 持有 lastSeq | 业务状态下沉；冷启动时从缓存 seed |
| UI 层不拼接 tunnelId | `term:<localSessionId>` 只在 `SessionChannel`/更下层生成 |
| 物理断开后所有 channel 变 STALE | 物理连接重建后统一恢复，避免半连接状态 |
| 恢复流程：ws-connect → ws-connected → HELLO | 和服务端现有 `handleHello` 完全兼容 |
| 严格区分 detach 和 closeSession | detach 只解绑 UI listener；closeSession 才发送 ws-close 关闭 PTY |
| 按 2.2 状态矩阵处理 Android 生命周期 | 避免 pause/switch/close 语义混淆 |
| `SessionChannel` 支持多 listener attach/detach | channel 长期存在，UI 重建后重新 attach 即可 |
| ws-close/ws-error 按真实 code/reason 分类 | `MuxControlMessage` 覆盖 code/reason，兼容旧服务端缺失字段 |
| 第一阶段不做 active/metadata | 避免扩大范围，协议保持兼容 |
| `AuthProvider` 抽象 cookie 刷新 | `DeviceConnection` 不依赖 `ServerConfig` 具体实现 |

---

## 12. 测试策略

- **Codec 单测**：
  - `MuxCodec` / `TerminalCodec` 编解码 round-trip。
  - `MuxCodec` 兼容性：旧服务端 `ws-error` 缺 `code`、`ws-close` 缺 `code/reason` 时不抛异常。
- **TransportProvider 单测**：mock `TransportFactory`，验证 P2P → fallback → WebSocket 切换、5 秒 timeout、30 秒 backoff、cookie refresh 触发重建。
- **DeviceConnection 单测**：mock `TransportProvider`/`Transport`，验证重连、控制消息分发、tunnel frame 路由、发送队列 FIFO 顺序、RESIZE 合并。
- **SessionChannel 单测**：mock `DeviceConnection`，验证状态机、ws-connect/close、HELLO/INPUT/RESIZE 编码、lastSeq 更新、reattach 不复位 lastSeq。
- **TerminalConnection 单测**：mock `SessionChannel`，验证 UI 状态转换、事件转发、detach 不 close、closeSession 才发 ws-close。
- **集成测试**：真实 WebSocket 到本地 go-core，验证断网重连、多终端切换、lastSeq 恢复、后台保活。

---

## 13. 风险与回滚

| 风险 | 缓解 |
|------|------|
| 重构期间功能回归 | 增量替换，每个阶段结束后保留旧实现作为 fallback |
| 发送队列引入延迟/乱序 | 第一阶段用单线程 FIFO + RESIZE 合并；加测后再考虑优先级 |
| cookie 刷新时序复杂 | `AuthProvider` / `TransportProvider.refreshCookie` 接口单一，单测覆盖 |
| P2P fallback 行为变化 | `RelayTransportProvider` 完整保留现有 5s timeout / 30s backoff / cookie refresh 逻辑 |
| detach/close 生命周期混淆 | 按 2.2 状态矩阵逐个场景改造，保留当前 `closeTerminalConnection` 仅用于真正关闭 |

---

## 14. 后续可扩展

架构稳定后，可在不改动核心分层的前提下添加：

- **active/metadata 订阅模式**：在 `SessionChannel` 的 `connect()` 中增加 `mode` 参数。
- **后台保活策略**：在 `DeviceConnectionRegistry` 中增加 idle timeout。
- **连接质量监控**：在 `DeviceConnection` 中统计重连次数、延迟。
