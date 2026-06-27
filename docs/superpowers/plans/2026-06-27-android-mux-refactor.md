# Android 客户端对接 Go 直连服务器多路复用实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 `MuxWebSocket.java` 类作为物理多路复用连接层，改造 `TerminalConnection.java` 使用虚拟通道，让 Android 客户端通过单条 WebSocket 连接同时承载 manager 通道（会话列表推送）和多个终端通道。

**Architecture:** `MuxWebSocket` 管理到 `/ws/sessions` 的物理 WebSocket，负责 Tunnel 帧编解码和 `ws-connect`/`ws-connected` 控制握手。`TerminalConnection` 不再直接持有 OkHttp WebSocket，改为通过 `MuxWebSocket.openChannel()` 创建虚拟通道，收发终端协议数据。

**Tech Stack:** Java 11, OkHttp3 WebSocket, Android SDK (no new dependencies)

## Global Constraints

- 不引入新的第三方依赖
- 保持 `TerminalConnection.Listener` 接口不变（调用方 `MainActivity` 和 `TerminalLifecycleController` 不受影响）
- Tunnel 帧格式必须与 Go `direct.go` 和 `shared/tunnel-protocol.js` 一致
- 断线重连行为保持不变（指数退避 + 防抖）

---

## File Structure

| 文件 | 操作 | 职责 |
|------|------|------|
| `MuxWebSocket.java` | 新建 | 物理 WebSocket 管理、Tunnel 帧编解码、ws-connect/ws-connected 控制握手、manager 通道自动路由 |
| `TerminalConnection.java` | 改造 | 移除直接 WebSocket 管理，改用 MuxWebSocket 虚拟通道接口 |
| `MainActivity.java` | 小改 | 将 MuxWebSocket 实例传递给 TerminalConnection |

---

### Task 1: 新建 MuxWebSocket.java — 核心多路复用类

**Files:**
- Create: `android-client/app/src/main/java/com/webterm/mobile/MuxWebSocket.java`

**Interfaces:**
- Consumes: 无（纯新建）
- Produces:
  - `MuxWebSocket(OkHttpClient http, Handler mainHandler)` 构造器
  - `void connect(String baseUrl, String cookie)` 建立物理连接
  - `void openChannel(String id, String path, String[] protocols, ChannelCallback callback)` 注册通道，物理连接就绪后自动发 ws-connect，重连时自动重建
  - `void sendBinary(String id, byte[] data)` 编码为 Tunnel 帧后发送
  - `void sendText(String id, String text)` 编码为 Tunnel 帧后发送
  - `void closeChannel(String id)` 发送 ws-close 并移除通道
  - `void close(String reason)` 关闭物理 WebSocket，通知所有通道
  - `State getState()` 返回当前状态
  - `interface ChannelCallback { void onConnected(); void onBinaryMessage(byte[] data); void onTextMessage(String text); void onClosed(int code, String reason); }`
  - `interface ManagerListener { void onSessions(JSONObject message); }`
  - `interface StateListener { void onStateChanged(State state, int reconnectAttempts); }`
  - `enum State { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }`
  - 内部 `ChannelInfo` 类存储 `id, path, protocols, callback, connected` 用于重连

- [ ] **Step 1: 编写 MuxWebSocket 完整类（约 300 行）**

```java
package com.webterm.mobile;

import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Multiplexed WebSocket client — mirrors Go direct server's mux.ConnectionTransport.
 *
 * Physical connection: /ws/sessions
 * Control plane: text JSON frames (ws-connect, ws-connected, ws-close)
 * Data plane: binary tunnel frames  [0x01][idLen(1B)][id(N)][extraByte(1B)][payload]
 *
 * Virtual channels:
 *   - "manager" (auto-created by server) → ManagerListener.onSessions(...)
 *   - user-created (via ws-connect)       → ChannelCallback
 *
 * ChannelInfo stores per-channel metadata so that on physical reconnect,
 * all registered channels are automatically re-established via ws-connect.
 */
final class MuxWebSocket {

    private static final String TAG = "MuxWebSocket";
    private static final long RECONNECT_BASE_DELAY_MS = 200L;
    private static final long RECONNECT_CAP_MS = 15000L;

    private static final byte MSG_TYPE_WS_DATA = 0x01;
    private static final byte WS_DATA_TEXT = 0x01;
    private static final byte WS_DATA_BINARY = 0x02;

    enum State { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    // ── Channel callback interface ──────────────────────────────────

    interface ChannelCallback {
        void onConnected();
        void onBinaryMessage(byte[] data);
        void onTextMessage(String text);
        void onClosed(int code, String reason);
    }

    // ── Manager listener ────────────────────────────────────────────

    interface ManagerListener {
        void onSessions(JSONObject message);
    }

    // ── State listener ──────────────────────────────────────────────

    interface StateListener {
        void onStateChanged(State state, int reconnectAttempts);
    }

    // ── Channel metadata (survives reconnects) ──────────────────────

    private static class ChannelInfo {
        final String id;
        final String path;
        final String[] protocols;
        final ChannelCallback callback;
        boolean connected;

        ChannelInfo(String id, String path, String[] protocols, ChannelCallback callback) {
            this.id = id;
            this.path = path;
            this.protocols = protocols;
            this.callback = callback;
        }
    }

    // ── Instance fields ─────────────────────────────────────────────

    private final OkHttpClient http;
    private final OkHttpClient wsHttp;
    private final Handler mainHandler;
    private final Map<String, ChannelInfo> channels = new HashMap<>();

    private WebSocket physicalSocket;
    private volatile State state = State.DISCONNECTED;
    private int socketGeneration;
    private int reconnectAttempts;

    private String baseUrl;
    private String cookie;

    private StateListener stateListener;
    private ManagerListener managerListener;

    private final Runnable reconnectRunnable = this::connectNow;

    // ── Constructor ─────────────────────────────────────────────────

    MuxWebSocket(OkHttpClient http, Handler mainHandler) {
        this.http = http;
        this.wsHttp = http.newBuilder()
                .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        this.mainHandler = mainHandler;
    }

    // ── Public API ──────────────────────────────────────────────────

    void setStateListener(StateListener listener) { this.stateListener = listener; }
    void setManagerListener(ManagerListener listener) { this.managerListener = listener; }
    State getState() { return state; }

    void connect(String baseUrl, String cookie) {
        this.baseUrl = baseUrl;
        this.cookie = cookie;
        this.state = State.CONNECTING;
        this.reconnectAttempts = 0;
        connectNow();
    }

    void reconnectNow() {
        mainHandler.removeCallbacks(reconnectRunnable);
        this.state = State.CONNECTING;
        this.reconnectAttempts = 0;
        connectNow();
    }

    void openChannel(String id, String path, String[] protocols, ChannelCallback callback) {
        ChannelInfo info = new ChannelInfo(id, path, protocols, callback);
        channels.put(id, info);

        // If physical socket is connected, send ws-connect immediately
        if (state == State.CONNECTED && physicalSocket != null) {
            sendWSConnect(physicalSocket, id, path, protocols);
        }
        // Otherwise, ws-connect will be sent in onOpen after physical connect
    }

    void sendBinary(String id, byte[] data) {
        byte[] frame = encodeTunnelFrame(id, WS_DATA_BINARY, data);
        WebSocket ws = physicalSocket;
        if (ws != null && state == State.CONNECTED) {
            ws.send(ByteString.of(frame));
        }
    }

    void sendText(String id, String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        byte[] frame = encodeTunnelFrame(id, WS_DATA_TEXT, payload);
        WebSocket ws = physicalSocket;
        if (ws != null && state == State.CONNECTED) {
            ws.send(ByteString.of(frame));
        }
    }

    void closeChannel(String id) {
        channels.remove(id);
        WebSocket ws = physicalSocket;
        if (ws != null && state == State.CONNECTED) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "ws-close");
                msg.put("tunnelConnectionId", id);
                ws.send(msg.toString());
            } catch (JSONException e) {
                Log.w(TAG, "Failed to send ws-close", e);
            }
        }
    }

    void close(String reason) {
        this.state = State.DISCONNECTED;
        this.socketGeneration++;
        mainHandler.removeCallbacks(reconnectRunnable);
        for (ChannelInfo info : new ArrayList<>(channels.values())) {
            try { info.callback.onClosed(1000, reason); } catch (Exception ignored) {}
        }
        channels.clear();
        releaseSocket(reason);
    }

    // ── Internal connection ─────────────────────────────────────────

    private void connectNow() {
        if (baseUrl == null || cookie == null) return;
        releaseSocket("reconnecting");

        state = State.CONNECTING;
        if (stateListener != null) stateListener.onStateChanged(state, reconnectAttempts);

        int generation = ++socketGeneration;

        Request request = new Request.Builder()
                .url(WebTermUrls.toWebSocketUrl(baseUrl) + "/ws/sessions")
                .header("Cookie", cookie)
                .build();

        physicalSocket = wsHttp.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                if (generation != socketGeneration) {
                    webSocket.close(1000, "stale socket");
                    return;
                }
                Log.i(TAG, "physical socket open gen=" + generation + " code=" + response.code());

                mainHandler.post(() -> {
                    if (generation != socketGeneration) return;
                    state = State.CONNECTED;
                    reconnectAttempts = 0;
                    if (stateListener != null) stateListener.onStateChanged(state, reconnectAttempts);

                    // Re-establish all registered channels on reconnect.
                    // Manager channel ("manager") is auto-created by the server —
                    // no ws-connect needed. All other channels need a fresh ws-connect.
                    for (ChannelInfo info : new ArrayList<>(channels.values())) {
                        info.connected = false;
                        if (!"manager".equals(info.id)) {
                            sendWSConnect(webSocket, info.id, info.path, info.protocols);
                        }
                    }
                });
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                if (generation != socketGeneration) return;
                handleControlMessage(text);
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
                if (generation != socketGeneration) return;
                handleBinaryFrame(bytes.toByteArray());
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t,
                                  @Nullable Response response) {
                mainHandler.post(() -> {
                    if (generation != socketGeneration) return;
                    if (MuxWebSocket.this.physicalSocket == webSocket) {
                        releaseSocket("failed");
                    }
                    String reason = t.getClass().getSimpleName()
                            + (t.getMessage() != null ? ": " + t.getMessage() : "");
                    Log.e(TAG, "socket failure gen=" + generation + " " + reason, t);
                    if (state != State.DISCONNECTED) scheduleReconnect(reason);
                });
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                mainHandler.post(() -> {
                    if (generation != socketGeneration) return;
                    if (MuxWebSocket.this.physicalSocket == webSocket) {
                        releaseSocket("closed");
                    }
                    Log.w(TAG, "socket closed gen=" + generation + " code=" + code + " " + reason);
                    // Notify all channels of physical disconnection
                    for (ChannelInfo info : new ArrayList<>(channels.values())) {
                        try { info.callback.onClosed(code, reason); } catch (Exception ignored) {}
                    }
                    if (state != State.DISCONNECTED) scheduleReconnect(reason);
                });
            }
        });
    }

    private void handleControlMessage(String text) {
        try {
            JSONObject json = new JSONObject(text);
            String type = json.optString("type", "");

            if ("ws-connected".equals(type)) {
                String id = json.optString("tunnelConnectionId", "");
                ChannelInfo info = channels.get(id);
                if (info != null) {
                    info.connected = true;
                    info.callback.onConnected();
                }
                return;
            }

            if ("ws-error".equals(type)) {
                String id = json.optString("tunnelConnectionId", "");
                ChannelInfo info = channels.get(id);
                if (info != null) {
                    channels.remove(id);
                    info.callback.onClosed(json.optInt("code", 4500),
                            json.optString("message", "tunnel error"));
                }
                return;
            }

            Log.d(TAG, "unhandled control message: " + type);
        } catch (JSONException e) {
            Log.w(TAG, "Failed to parse control message", e);
        }
    }

    private void handleBinaryFrame(byte[] data) {
        TunnelFrame tf = decodeTunnelFrame(data);
        if (tf == null) return;

        // Manager channel: route to manager listener
        if ("manager".equals(tf.id) && tf.extraByte == WS_DATA_TEXT && managerListener != null) {
            try {
                JSONObject msg = new JSONObject(new String(tf.payload, StandardCharsets.UTF_8));
                managerListener.onSessions(msg);
            } catch (JSONException e) {
                Log.w(TAG, "Failed to parse manager message", e);
            }
            return;
        }

        ChannelInfo info = channels.get(tf.id);
        if (info == null) return;

        if (tf.extraByte == WS_DATA_BINARY) {
            info.callback.onBinaryMessage(tf.payload);
        } else if (tf.extraByte == WS_DATA_TEXT) {
            info.callback.onTextMessage(new String(tf.payload, StandardCharsets.UTF_8));
        }
    }

    // ── Reconnect ───────────────────────────────────────────────────

    private void scheduleReconnect(String reason) {
        if (state == State.DISCONNECTED || baseUrl == null || cookie == null) return;
        if (state == State.RECONNECTING) return;
        Log.i(TAG, "Connection lost, scheduling reconnect. Reason: " + reason);
        state = State.RECONNECTING;
        int attempt = ++reconnectAttempts;

        int shift = Math.min(attempt - 1, 15);
        long cap = Math.min(RECONNECT_CAP_MS, 1000L * (1L << shift));
        long delayMs = Math.max(RECONNECT_BASE_DELAY_MS, (long) (Math.random() * cap));

        if (stateListener != null) stateListener.onStateChanged(state, reconnectAttempts);
        mainHandler.postDelayed(reconnectRunnable, delayMs);
    }

    // ── Socket cleanup ──────────────────────────────────────────────

    private void releaseSocket(String reason) {
        WebSocket ws = physicalSocket;
        physicalSocket = null;
        if (ws != null) {
            try {
                ws.close(1000, reason);
            } catch (Exception e) {
                Log.w(TAG, "Failed to close socket: " + e.getMessage(), e);
            }
        }
    }

    // ── Tunnel frame encode/decode ──────────────────────────────────

    private byte[] encodeTunnelFrame(String id, byte extraByte, byte[] payload) {
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        int idLen = idBytes.length;
        byte[] frame = new byte[1 + 1 + idLen + 1 + payload.length];
        frame[0] = MSG_TYPE_WS_DATA;
        frame[1] = (byte) idLen;
        System.arraycopy(idBytes, 0, frame, 2, idLen);
        frame[2 + idLen] = extraByte;
        System.arraycopy(payload, 0, frame, 3 + idLen, payload.length);
        return frame;
    }

    private TunnelFrame decodeTunnelFrame(byte[] data) {
        if (data.length < 3) return null;
        if (data[0] != MSG_TYPE_WS_DATA) return null;
        int idLen = data[1] & 0xFF;
        if (data.length < 3 + idLen) return null;
        String id = new String(data, 2, idLen, StandardCharsets.UTF_8);
        byte extraByte = data[2 + idLen];
        int payloadLen = data.length - (3 + idLen);
        byte[] payload = new byte[payloadLen];
        System.arraycopy(data, 3 + idLen, payload, 0, payloadLen);
        return new TunnelFrame(id, extraByte, payload);
    }

    private static class TunnelFrame {
        final String id;
        final byte extraByte;
        final byte[] payload;

        TunnelFrame(String id, byte extraByte, byte[] payload) {
            this.id = id;
            this.extraByte = extraByte;
            this.payload = payload;
        }
    }

    // ── ws-connect helper ───────────────────────────────────────────

    private void sendWSConnect(WebSocket ws, String id, String path, String[] protocols) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "ws-connect");
            msg.put("tunnelConnectionId", id);
            msg.put("path", path);
            JSONArray arr = new JSONArray();
            if (protocols != null) {
                for (String p : protocols) arr.put(p);
            }
            msg.put("protocols", arr);
            ws.send(msg.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Failed to create ws-connect message", e);
        }
    }
}
```

- [ ] **Step 2: 验证编译**

Run: `cd android-client && ./gradlew :app:compileDebugJavaWithJavac 2>&1 | tail -20`

- [ ] **Step 3: Commit**

```bash
git add android-client/app/src/main/java/com/webterm/mobile/MuxWebSocket.java
git commit -m "feat: add MuxWebSocket for multiplexed WebSocket connection"
```

---

### Task 2: 改造 TerminalConnection.java 使用 MuxWebSocket

**Files:**
- Modify: `android-client/app/src/main/java/com/webterm/mobile/TerminalConnection.java`

**Interfaces:**
- Consumes: `MuxWebSocket.connect()`, `MuxWebSocket.openChannel()`, `MuxWebSocket.sendBinary()`, `MuxWebSocket.closeChannel()`, `MuxWebSocket.getState()`, `MuxWebSocket.State`
- Produces: 保持 `TerminalConnection.Listener` 不变，公开 `getMuxWebSocket()` 供外部获取

- [ ] **Step 1: 重构 TerminalConnection — 移除直接 WebSocket，引入 MuxWebSocket**

改动点：
1. 构造函数新增 `MuxWebSocket` 参数
2. `connectNow()` 不再创建 WebSocket，改为调用 `muxSocket.openChannel()`
3. `sendBinary()` 改为 `muxSocket.sendBinary(channelId, data)`
4. `close()` 改为 `muxSocket.closeChannel(channelId)`（不关闭物理连接）
5. 移除 `terminalHttp`、`webSocket`、`releaseSocket()`、`CELL_SUBPROTOCOL` 等
6. 新增 `channelId` 字段（格式 `"terminal-" + sessionId`）

完整替换后的 `TerminalConnection.java`：

```java
package com.webterm.mobile;

import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import okhttp3.OkHttpClient;
import okio.ByteString;

final class TerminalConnection {
    private static final String TAG = "TerminalConnection";
    private static final long RESIZE_DEBOUNCE_MS = 100L;
    private static final String CELL_SUBPROTOCOL = "webterm.cell.v1";

    enum State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING
    }

    private final OkHttpClient http;
    private final Handler mainHandler;
    private final Listener listener;
    private final MuxWebSocket muxSocket;

    private volatile State state = State.DISCONNECTED;
    private int reconnectAttempts;

    private String baseUrl;
    private String cookie;
    private String sessionId;
    private String channelId;
    private long lastSeq;
    private int columns;
    private int rows;
    private int sentColumns;
    private int sentRows;

    private final Runnable reconnectRunnable = this::connectNow;
    private final Runnable sendResizeRunnable = this::sendResizeNow;

    TerminalConnection(OkHttpClient http, Handler mainHandler, Listener listener,
                       MuxWebSocket muxSocket) {
        this.http = http;
        this.mainHandler = mainHandler;
        this.listener = listener;
        this.muxSocket = muxSocket;
    }

    State getState() {
        return state;
    }

    void connect(String baseUrl, String cookie, String sessionId, long lastSeq) {
        this.baseUrl = baseUrl;
        this.cookie = cookie;
        this.sessionId = sessionId;
        this.channelId = "terminal-" + sessionId;
        this.lastSeq = lastSeq;
        this.state = State.CONNECTING;
        this.reconnectAttempts = 0;
        listener.onConnectionStatus(state, reconnectAttempts);
        connectNow();
    }

    void reconnectNow() {
        mainHandler.removeCallbacks(reconnectRunnable);
        this.state = State.CONNECTING;
        this.reconnectAttempts = 0;
        listener.onConnectionStatus(state, reconnectAttempts);
        connectNow();
    }

    void close(String reason) {
        this.state = State.DISCONNECTED;
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(sendResizeRunnable);
        if (channelId != null) {
            muxSocket.closeChannel(channelId);
        }
    }

    boolean isConnected() {
        return state == State.CONNECTED;
    }

    void updateLastSeq(long lastSeq) {
        this.lastSeq = lastSeq;
    }

    void updateSize(int columns, int rows) {
        this.columns = columns;
        this.rows = rows;
        scheduleResize();
    }

    void sendInput(String data) {
        sendBinary(WebTermProtocol.MSG_INPUT, data.getBytes(StandardCharsets.UTF_8));
    }

    void sendTitle(String title) {
        sendBinary(WebTermProtocol.MSG_TITLE, title.getBytes(StandardCharsets.UTF_8));
    }

    private void connectNow() {
        if (sessionId == null || baseUrl == null || cookie == null || channelId == null) return;

        state = State.CONNECTING;
        listener.onConnectionStatus(state, reconnectAttempts);

        muxSocket.openChannel(channelId, "/ws/sessions/" + sessionId,
                new String[]{CELL_SUBPROTOCOL},
                new MuxWebSocket.ChannelCallback() {

                    @Override
                    public void onConnected() {
                        state = State.CONNECTED;
                        reconnectAttempts = 0;
                        sentColumns = 0;
                        sentRows = 0;
                        Log.i(TAG, "tunnel connected channel=" + channelId);
                        listener.onConnectionStatus(state, reconnectAttempts);

                        JSONObject hello = WebTermProtocol.put(WebTermProtocol.json(), "lastSeq", lastSeq);
                        if (columns > 0 && rows > 0) {
                            WebTermProtocol.put(hello, "cols", columns);
                            WebTermProtocol.put(hello, "rows", rows);
                            sentColumns = columns;
                            sentRows = rows;
                        }
                        sendBinary(WebTermProtocol.MSG_HELLO,
                                hello.toString().getBytes(StandardCharsets.UTF_8));
                        sendResizeNow();
                    }

                    @Override
                    public void onBinaryMessage(byte[] data) {
                        handleServerMessage(data);
                    }

                    @Override
                    public void onTextMessage(String text) {
                        // Terminal data is always binary; text on terminal channel
                        // is unexpected but handle gracefully.
                        Log.w(TAG, "unexpected text on terminal channel: "
                                + (text.length() > 100 ? text.substring(0, 100) : text));
                    }

                    @Override
                    public void onClosed(int code, String reason) {
                        mainHandler.post(() -> {
                            String description = "Channel closed: " + code
                                    + (reason.isEmpty() ? "" : " " + reason);
                            Log.w(TAG, "channel closed channel=" + channelId + " " + description);
                            if (state != State.DISCONNECTED) {
                                scheduleReconnect(description);
                            }
                        });
                    }
                });
    }

    private void scheduleReconnect(String reason) {
        if (state == State.DISCONNECTED || sessionId == null || cookie == null || baseUrl == null) return;
        if (state == State.RECONNECTING) return;
        Log.i(TAG, "Connection lost, scheduling reconnect. Reason: " + reason);
        state = State.RECONNECTING;
        int attempt = ++reconnectAttempts;

        int shift = Math.min(attempt - 1, 15);
        long cap = Math.min(15000L, 1000L * (1L << shift));
        long delayMs = Math.max(200L, (long) (Math.random() * cap));

        listener.onConnectionStatus(state, reconnectAttempts);
        mainHandler.postDelayed(reconnectRunnable, delayMs);
    }

    private void handleServerMessage(byte[] frame) {
        if (frame.length == 0) return;
        byte type = frame[0];
        byte[] payload = Arrays.copyOfRange(frame, 1, frame.length);
        if (type == WebTermProtocol.MSG_SNAPSHOT) {
            listener.onSnapshot(payload);
            return;
        }
        if (type == WebTermProtocol.MSG_PATCH) {
            listener.onPatch(payload);
            return;
        }
        if (type == WebTermProtocol.MSG_SCROLLBACK) {
            listener.onScrollback(payload);
            return;
        }
        if (type == WebTermProtocol.MSG_OUTPUT) {
            if (payload.length >= 8) {
                long seq = WebTermProtocol.readUint64(payload, 0);
                if (seq <= lastSeq) return;
                lastSeq = seq;
                listener.onOutput(seq, Arrays.copyOfRange(payload, 8, payload.length));
            } else {
                listener.onOutput(0, payload);
            }
            return;
        }
        if (type == WebTermProtocol.MSG_STATE) {
            if (payload.length >= 8) {
                long seq = WebTermProtocol.readUint64(payload, 0);
                if (seq < lastSeq) return;
                lastSeq = seq;
                listener.onState(seq, Arrays.copyOfRange(payload, 8, payload.length));
            }
            return;
        }
        if (type == WebTermProtocol.MSG_INFO) {
            try {
                listener.onInfo(WebTermProtocol.controlPayload(payload));
            } catch (JSONException ignored) {
            }
            return;
        }
        if (type == WebTermProtocol.MSG_PONG) {
            return;
        }
        try {
            JSONObject msg = WebTermProtocol.controlPayload(payload);
            if (type == WebTermProtocol.MSG_EXIT) {
                listener.onExit(msg.optInt("code", 0));
            }
        } catch (JSONException e) {
            listener.onProtocolError("Bad message: " + e.getMessage());
        }
    }

    private void scheduleResize() {
        mainHandler.removeCallbacks(sendResizeRunnable);
        if (state != State.CONNECTED) return;
        mainHandler.postDelayed(sendResizeRunnable, RESIZE_DEBOUNCE_MS);
    }

    private void sendResizeNow() {
        mainHandler.removeCallbacks(sendResizeRunnable);
        if (columns <= 0 || rows <= 0) return;
        if (columns == sentColumns && rows == sentRows) return;
        JSONObject resize = WebTermProtocol.json();
        WebTermProtocol.put(resize, "cols", columns);
        WebTermProtocol.put(resize, "rows", rows);
        sendBinary(WebTermProtocol.MSG_RESIZE, resize.toString().getBytes(StandardCharsets.UTF_8));
        if (state == State.CONNECTED) {
            sentColumns = columns;
            sentRows = rows;
        }
    }

    private void sendBinary(byte type, byte[] payload) {
        if (state != State.CONNECTED || channelId == null) return;
        muxSocket.sendBinary(channelId, WebTermProtocol.frame(type, payload).toByteArray());
    }

    // ── Listener interface (unchanged) ──────────────────────────────

    interface Listener {
        void onConnectionStatus(State state, int reconnectAttempts);
        void onOutput(long seq, byte[] data);
        void onState(long seq, byte[] data);
        void onInfo(JSONObject info);
        void onExit(int code);
        void onProtocolError(String message);
        void onSnapshot(byte[] data);
        void onPatch(byte[] data);
        void onScrollback(byte[] data);
    }
}
```

- [ ] **Step 2: 验证编译**

Run: `cd android-client && ./gradlew :app:compileDebugJavaWithJavac 2>&1 | tail -20`

- [ ] **Step 3: Commit**

```bash
git add android-client/app/src/main/java/com/webterm/mobile/TerminalConnection.java
git commit -m "refactor: TerminalConnection uses MuxWebSocket virtual channels"
```

---

### Task 3: 集成 — MainActivity 传递 MuxWebSocket 实例

**Files:**
- Modify: `android-client/app/src/main/java/com/webterm/mobile/MainActivity.java`

**Interfaces:**
- Consumes: `MuxWebSocket` 构造器
- Produces: 创建 `MuxWebSocket` 实例，传给 `TerminalConnection`

- [ ] **Step 1: 在 MainActivity 中添加 MuxWebSocket 字段并初始化**

在 `MainActivity.java` 中添加字段：

```java
// 新增字段
private MuxWebSocket mMuxSocket;
```

在 `onCreate()` 中，将：
```java
mTerminalConnection = new TerminalConnection(mHttp, mMainHandler, this);
```
替换为：
```java
mMuxSocket = new MuxWebSocket(mHttp, mMainHandler);
mTerminalConnection = new TerminalConnection(mHttp, mMainHandler, this, mMuxSocket);
```

- [ ] **Step 2: 在 onDestroy 中关闭 MuxWebSocket**

在 `onDestroy()` 中，在 `mTerminalConnection.close(...)` 之后添加：
```java
if (mMuxSocket != null) mMuxSocket.close("activity closed");
```

- [ ] **Step 3: 修改 TerminalConnection.connect() 确保物理连接先建立**

修改 `TerminalConnection.connect()` 方法开头，在 `connectNow()` 之前确保 MuxWebSocket 物理层已连接：

```java
void connect(String baseUrl, String cookie, String sessionId, long lastSeq) {
    this.baseUrl = baseUrl;
    this.cookie = cookie;
    this.sessionId = sessionId;
    this.channelId = "terminal-" + sessionId;
    this.lastSeq = lastSeq;
    this.state = State.CONNECTING;
    this.reconnectAttempts = 0;
    listener.onConnectionStatus(state, reconnectAttempts);

    // Ensure physical MuxWebSocket is connected before opening channels
    if (muxSocket.getState() == MuxWebSocket.State.DISCONNECTED) {
        muxSocket.connect(baseUrl, cookie);
    }
    connectNow();
}
```

- [ ] **Step 4: 验证编译**

Run: `cd android-client && ./gradlew :app:compileDebugJavaWithJavac 2>&1 | tail -20`

- [ ] **Step 5: Commit**

```bash
git add android-client/app/src/main/java/com/webterm/mobile/MuxWebSocket.java \
        android-client/app/src/main/java/com/webterm/mobile/TerminalConnection.java \
        android-client/app/src/main/java/com/webterm/mobile/MainActivity.java
git commit -m "feat: integrate MuxWebSocket into MainActivity and TerminalConnection"
```

---

### Task 4: 验证 — 编译和代码审查

**Files:**
- Verify: 所有修改的文件

- [ ] **Step 1: 完整编译**

Run: `cd android-client && ./gradlew :app:assembleDebug 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 检查 Termux 相关代码路径是否受影响**

Run:
```bash
find android-client/terminal-emulator android-client/terminal-view -name "*.java" | head
```
确认 `TerminalConnection` 的改造不影响 Termux 终端模拟层（不直接依赖，无变更）。

- [ ] **Step 3: 运行 code-review skill 审查改动**

Run: Skill `code-review` on the diff.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "chore: final verification and fixes for mux refactor"
```
