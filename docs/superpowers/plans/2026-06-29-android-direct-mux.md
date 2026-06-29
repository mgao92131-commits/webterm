# Android 客户端直连 mux 单连接实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Android 直连终端从「每终端独立 `/ws/sessions/{id}` WS」改为「一条 `/ws/sessions` mux 连接复用多个终端通道」；中继路径维持现状（每终端独立 WS）。

**Architecture:** 新建 `MuxSession`（客户端角色，维护单条 WS，发 `ws-connect` 建通道、解码 tunnel frame 分发）。`WebTermProtocol` 加 tunnel frame 编解码。`TerminalConnection` 按 `isRelayDevice` 分流：direct 走 `MuxSession` 复用通道，relay 走旧每终端 WS。调用链透传 `isRelayDevice` 从 `MainActivity`/`SessionCommandController` 到 `TerminalConnection.connect`。

**Tech Stack:** Android Java，OkHttp `WebSocket`/`WebSocketListener`，`org.json`，纯 JUnit4 单测（无 Robolectric——WS 层靠端到端验证，纯逻辑层单测）。

**前置依赖：** go-core 计划（`docs/superpowers/plans/2026-06-29-go-core-mux-extraction.md`）已完成——direct `/ws/sessions` 已支持 `webterm.mux.v1` 子协议 mux 入口。

## Global Constraints

- Android 包名 `com.webterm.mobile`，源码 `app/src/main/java/com/webterm/mobile/`，单测 `app/src/test/java/com/webterm/mobile/`。
- 终端子协议统一 `webterm.binary.v1`，不引入 screen。
- 中继路径（`isRelayDevice == true`）维持现状：每终端独立 `/ws/sessions/{id}` WS，**不改**。
- direct 路径（`isRelayDevice == false`）走 `MuxSession` 单连接。
- tunnel frame 格式与 go-core `protocol/tunnel.go` 完全一致：`[0x01 | idLen | id | extraByte | payload]`，`extraByte` 0x01=text/0x02=binary。
- 终端通道 `tunnelId = "term:" + sessionId`；manager 通道第一阶段不打开。
- 现有 `MSG_*` 二进制帧解析逻辑（`TerminalConnection.handleServerMessage`）零改动，只是外面包一层 tunnel frame。
- 鉴权：mux 连接带 `Cookie` header + `Sec-WebSocket-Protocol: webterm.mux.v1`。
- 纯 JUnit4 单测，不依赖 Android 框架（`org.json` 是 Android 内置但 JUnit 测试也能用——确认 `app/build.gradle` testImplementation 含 `org.json`，否则用字符串断言）。
- 提交信息 conventional commits，结尾加 `Co-Authored-By: Claude <noreply@anthropic.com>`。

---

## File Structure

```
app/src/main/java/com/webterm/mobile/
  WebTermProtocol.java          # 加 tunnel frame 编解码常量与方法
  MuxSession.java               # 新建：客户端角色 mux，单 WS 多通道
  TerminalConnection.java       # connect 增 isRelayDevice；direct 走 MuxSession，relay 走旧 WS
  TerminalRuntimeState.java     # 加 isRelayDevice 字段 + setServerSession 透传
  TerminalLifecycleController.java # showTerminal 增 isRelayDevice 透传
  MainActivity.java             # openSession 传 server.isRelayDevice()；onOpenTerminal 回调接收并透传
  SessionCommandController.java # Listener.onOpenTerminal 增 isRelayDevice；新建会话路径传入

app/src/test/java/com/webterm/mobile/
  WebTermProtocolTest.java      # tunnel frame encode/decode 单测
  MuxSessionRoutingTest.java    # tunnelId 路由纯逻辑单测（不连真 WS）
```

---

## Task 1: `WebTermProtocol` 加 tunnel frame 编解码

**Files:**
- Modify: `app/src/main/java/com/webterm/mobile/WebTermProtocol.java`
- Create: `app/src/test/java/com/webterm/mobile/WebTermProtocolTest.java`

**Interfaces:**
- Produces:
  - 常量 `MSG_TYPE_WS_DATA = 0x01`、`WS_DATA_TEXT = 0x01`、`WS_DATA_BINARY = 0x02`
  - `static byte[] encodeTunnelFrame(String tunnelId, byte[] payload, boolean binary)` → `[0x01 | idLen | id | extraByte | payload]`
  - `static TunnelFrame decodeTunnelFrame(byte[] data)` → `(String tunnelId, byte extraByte, byte[] payload)`，非法返回 `null`
  - 静态嵌套类 `TunnelFrame { String tunnelId; byte extraByte; byte[] payload; }`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/webterm/mobile/WebTermProtocolTest.java`：

```java
package com.webterm.mobile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import okio.ByteString;
import org.junit.Test;

public class WebTermProtocolTest {

    @Test
    public void encodeTunnelFrameBinaryMatchesGoCoreFormat() {
        byte[] idBytes = "term:s1".getBytes();
        byte[] payload = new byte[]{0x04, 0x01, 0x02, 0x03}; // MsgHello + dummy
        byte[] frame = WebTermProtocol.encodeTunnelFrame("term:s1", payload, true);

        // [0x01 | idLen | id... | extraByte(0x02 binary) | payload...]
        assertEquals(0x01, frame[0] & 0xff);
        assertEquals(idBytes.length, frame[1] & 0xff);
        for (int i = 0; i < idBytes.length; i++) {
            assertEquals(idBytes[i], frame[2 + i]);
        }
        assertEquals(WebTermProtocol.WS_DATA_BINARY, frame[2 + idBytes.length] & 0xff);
        for (int i = 0; i < payload.length; i++) {
            assertEquals(payload[i], frame[3 + idBytes.length + i]);
        }
    }

    @Test
    public void encodeTunnelFrameTextUsesTextExtraByte() {
        byte[] payload = "{\"type\":\"hello\"}".getBytes();
        byte[] frame = WebTermProtocol.encodeTunnelFrame("manager", payload, false);
        int idLen = "manager".length();
        assertEquals(WebTermProtocol.WS_DATA_TEXT, frame[2 + idLen] & 0xff);
    }

    @Test
    public void decodeTunnelFrameRoundTripsBinary() {
        byte[] payload = new byte[]{0x01, 0x02, 0x03};
        byte[] frame = WebTermProtocol.encodeTunnelFrame("term:s1", payload, true);
        WebTermProtocol.TunnelFrame decoded = WebTermProtocol.decodeTunnelFrame(frame);
        assertEquals("term:s1", decoded.tunnelId);
        assertEquals(WebTermProtocol.WS_DATA_BINARY, decoded.extraByte & 0xff);
        assertArrayEquals(payload, decoded.payload);
    }

    @Test
    public void decodeTunnelFrameReturnsNullForTooShort() {
        assertNull(WebTermProtocol.decodeTunnelFrame(new byte[]{0x01, 0x05}));
    }

    @Test
    public void decodeTunnelFrameReturnsNullForWrongMsgType() {
        byte[] payload = new byte[]{0x01};
        byte[] frame = new byte[3 + "x".length() + payload.length];
        frame[0] = 0x02; // wrong msg type
        frame[1] = 1;
        frame[2] = 'x';
        frame[3] = WebTermProtocol.WS_DATA_BINARY;
        frame[4] = 0x01;
        assertNull(WebTermProtocol.decodeTunnelFrame(frame));
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd android-client && ./gradlew :app:testDebugUnitTest --tests "com.webterm.mobile.WebTermProtocolTest" 2>&1 | tail -20`
Expected: 编译失败（`encodeTunnelFrame`/`decodeTunnelFrame`/常量/`TunnelFrame` 类未定义）。

- [ ] **Step 3: 实现 tunnel frame 编解码**

在 `app/src/main/java/com/webterm/mobile/WebTermProtocol.java` 末尾（类内）追加：

```java
    // ── Tunnel frame（与 go-core protocol/tunnel.go 一致）──
    // [MsgType=0x01 | idLen | id | extraByte | payload]
    static final byte MSG_TYPE_WS_DATA = 0x01;
    static final byte WS_DATA_TEXT = 0x01;
    static final byte WS_DATA_BINARY = 0x02;

    static final class TunnelFrame {
        final String tunnelId;
        final byte extraByte;
        final byte[] payload;

        TunnelFrame(String tunnelId, byte extraByte, byte[] payload) {
            this.tunnelId = tunnelId;
            this.extraByte = extraByte;
            this.payload = payload;
        }
    }

    static byte[] encodeTunnelFrame(String tunnelId, byte[] payload, boolean binary) {
        byte[] idBytes = tunnelId.getBytes(StandardCharsets.UTF_8);
        byte extraByte = binary ? WS_DATA_BINARY : WS_DATA_TEXT;
        byte[] frame = new byte[3 + idBytes.length + (payload == null ? 0 : payload.length)];
        frame[0] = MSG_TYPE_WS_DATA;
        frame[1] = (byte) idBytes.length;
        System.arraycopy(idBytes, 0, frame, 2, idBytes.length);
        frame[2 + idBytes.length] = extraByte;
        if (payload != null) {
            System.arraycopy(payload, 0, frame, 3 + idBytes.length, payload.length);
        }
        return frame;
    }

    static TunnelFrame decodeTunnelFrame(byte[] data) {
        if (data == null || data.length < 3) return null;
        if ((data[0] & 0xff) != MSG_TYPE_WS_DATA) return null;
        int idLen = data[1] & 0xff;
        if (data.length < 2 + idLen + 1) return null;
        String tunnelId = new String(data, 2, idLen, StandardCharsets.UTF_8);
        byte extraByte = data[2 + idLen];
        int payloadStart = 3 + idLen;
        byte[] payload = new byte[data.length - payloadStart];
        System.arraycopy(data, payloadStart, payload, 0, payload.length);
        return new TunnelFrame(tunnelId, extraByte, payload);
    }
```

- [ ] **Step 4: 确认 import**

`WebTermProtocol.java` 顶部需 `import java.nio.charset.StandardCharsets;`（已存在则跳过）。

- [ ] **Step 5: 运行测试，确认通过**

Run: `cd android-client && ./gradlew :app:testDebugUnitTest --tests "com.webterm.mobile.WebTermProtocolTest" 2>&1 | tail -20`
Expected: 5 个测试 PASS。

- [ ] **Step 6: Commit**

```bash
cd android-client && git add app/src/main/java/com/webterm/mobile/WebTermProtocol.java app/src/test/java/com/webterm/mobile/WebTermProtocolTest.java
git commit -m "feat(protocol): add tunnel frame encode/decode matching go-core format

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 2: `MuxSession` 客户端角色

**Files:**
- Create: `app/src/main/java/com/webterm/mobile/MuxSession.java`
- Create: `app/src/test/java/com/webterm/mobile/MuxSessionRoutingTest.java`

**Interfaces:**
- Consumes: `WebTermProtocol.encodeTunnelFrame`/`decodeTunnelFrame`/`MSG_*`；`okhttp3.WebSocket`/`WebSocketListener`/`OkHttpClient`。
- Produces:
  - `MuxSession(OkHttpClient http, Handler mainHandler, String wsUrl, String cookie, Listener listener)`
  - `interface Listener { void onMuxConnected(); void onMuxDisconnected(String reason); void onTunnelData(String tunnelId, byte[] payload, boolean binary); void onTunnelConnected(String tunnelId); void onTunnelError(String tunnelId, String message); }`
  - `void start()`
  - `void stop()`
  - `boolean isConnected()`
  - `boolean sendWsConnect(String tunnelId, String path, String[] protocols)`
  - `boolean sendWsClose(String tunnelId)`
  - `boolean sendTunnelFrame(String tunnelId, byte[] payload, boolean binary)`
  - `boolean sendTunnelFrameText(String tunnelId, String text)`

- [ ] **Step 1: 写路由纯逻辑单测（不连真 WS）**

`MuxSession` 的 WebSocket 连接层无法用纯 JUnit 测（需 Android/网络），但「收到 tunnel frame 后按 tunnelId 分发」可抽成纯方法测。创建 `app/src/test/java/com/webterm/mobile/MuxSessionRoutingTest.java`：

```java
package com.webterm.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class MuxSessionRoutingTest {

    static final class RecordingListener implements MuxSession.Listener {
        final List<String> tunnelData = new ArrayList<>();
        @Override public void onMuxConnected() {}
        @Override public void onMuxDisconnected(String reason) {}
        @Override public void onTunnelConnected(String tunnelId) {}
        @Override public void onTunnelError(String tunnelId, String message) {}
        @Override
        public void onTunnelData(String tunnelId, byte[] payload, boolean binary) {
            tunnelData.add(tunnelId + ":" + binary + ":" + new String(payload));
        }
    }

    @Test
    public void dispatchBinaryFrameRoutesByTunnelId() {
        RecordingListener listener = new RecordingListener();
        // term:s1 通道收到一帧 binary payload {MsgOutput=0x02}
        byte[] payload = new byte[]{0x02};
        byte[] frame = WebTermProtocol.encodeTunnelFrame("term:s1", payload, true);

        MuxSession.dispatchBinaryFrame(frame, listener);

        assertEquals(1, listener.tunnelData.size());
        assertEquals("term:s1:true:" + new String(payload), listener.tunnelData.get(0));
    }

    @Test
    public void dispatchBinaryFrameIgnoresInvalidFrame() {
        RecordingListener listener = new RecordingListener();
        MuxSession.dispatchBinaryFrame(new byte[]{0x01, 0x05}, listener); // too short
        assertTrue(listener.tunnelData.isEmpty());
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd android-client && ./gradlew :app:testDebugUnitTest --tests "com.webterm.mobile.MuxSessionRoutingTest" 2>&1 | tail -20`
Expected: 编译失败（`MuxSession` 类不存在）。

- [ ] **Step 3: 实现 MuxSession**

创建 `app/src/main/java/com/webterm/mobile/MuxSession.java`：

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
import java.util.HashMap;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 客户端角色 mux：一条 /ws/sessions 连接（webterm.mux.v1 子协议）复用多个终端通道。
 * 通道由 ws-connect 建立；数据经 tunnel frame 收发。第一阶段仅用于 direct 路径。
 */
final class MuxSession {
    private static final String TAG = "MuxSession";
    private static final String MUX_SUBPROTOCOL = "webterm.mux.v1";

    interface Listener {
        void onMuxConnected();
        void onMuxDisconnected(String reason);
        void onTunnelConnected(String tunnelId);
        void onTunnelError(String tunnelId, String message);
        void onTunnelData(String tunnelId, byte[] payload, boolean binary);
    }

    private final OkHttpClient http;
    private final OkHttpClient muxHttp;
    private final Handler mainHandler;
    private final String wsUrl;
    private final String cookie;
    private final Listener listener;

    private WebSocket webSocket;
    private boolean connected;
    private boolean enabled;
    private int reconnectAttempts;

    // 待发 ws-connect 后等待 ws-connected 的回调登记（仅记录已发 connect 的 tunnelId）。
    private final Map<String, Boolean> pendingConnects = new HashMap<>();

    MuxSession(OkHttpClient http, Handler mainHandler, String wsUrl, String cookie, Listener listener) {
        this.http = http;
        this.muxHttp = http.newBuilder()
            .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
            .build();
        this.mainHandler = mainHandler;
        this.wsUrl = wsUrl;
        this.cookie = cookie;
        this.listener = listener;
    }

    void start() {
        if (wsUrl == null || wsUrl.isEmpty()) {
            stop();
            return;
        }
        enabled = true;
        if (webSocket != null) return;
        connectNow();
    }

    void stop() {
        enabled = false;
        connected = false;
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            try { ws.close(1000, "closing mux"); } catch (Exception ignored) {}
        }
    }

    boolean isConnected() {
        return connected;
    }

    boolean sendWsConnect(String tunnelId, String path, String[] protocols) {
        if (!connected) return false;
        JSONObject msg = new JSONObject();
        try {
            msg.put("type", "ws-connect");
            msg.put("tunnelConnectionId", tunnelId);
            msg.put("path", path);
            if (protocols != null && protocols.length > 0) {
                JSONArray arr = new JSONArray();
                for (String p : protocols) arr.put(p);
                msg.put("protocols", arr);
            }
        } catch (JSONException ignored) {
            return false;
        }
        synchronized (pendingConnects) {
            pendingConnects.put(tunnelId, true);
        }
        return sendText(msg.toString());
    }

    boolean sendWsClose(String tunnelId) {
        if (!connected) return false;
        JSONObject msg = new JSONObject();
        try {
            msg.put("type", "ws-close");
            msg.put("tunnelConnectionId", tunnelId);
        } catch (JSONException ignored) {
            return false;
        }
        return sendText(msg.toString());
    }

    boolean sendTunnelFrame(String tunnelId, byte[] payload, boolean binary) {
        if (!connected) return false;
        WebSocket ws = webSocket;
        if (ws == null) return false;
        return ws.send(okio.ByteString.of(WebTermProtocol.encodeTunnelFrame(tunnelId, payload, binary)));
    }

    boolean sendTunnelFrameText(String tunnelId, String text) {
        return sendTunnelFrame(tunnelId, text.getBytes(StandardCharsets.UTF_8), false);
    }

    private boolean sendText(String text) {
        WebSocket ws = webSocket;
        if (ws == null || !connected) return false;
        return ws.send(text);
    }

    private void connectNow() {
        Request request = new Request.Builder()
            .url(wsUrl)
            .header("Cookie", cookie != null ? cookie : "")
            .header("Sec-WebSocket-Protocol", MUX_SUBPROTOCOL)
            .build();
        webSocket = muxHttp.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                if (!enabled) {
                    webSocket.close(1000, "stale mux socket");
                    return;
                }
                connected = true;
                reconnectAttempts = 0;
                Log.i(TAG, "mux open");
                mainHandler.post(() -> listener.onMuxConnected());
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                if (!enabled) return;
                handleControlMessage(text);
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull okio.ByteString bytes) {
                if (!enabled) return;
                dispatchBinaryFrame(bytes.toByteArray(), listener);
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                mainHandler.post(() -> {
                    if (!enabled) return;
                    connected = false;
                    MuxSession.this.webSocket = null;
                    Log.e(TAG, "mux failure: " + t.getMessage(), t);
                    listener.onMuxDisconnected(t.getMessage());
                    scheduleReconnect();
                });
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                mainHandler.post(() -> {
                    if (!enabled) return;
                    connected = false;
                    MuxSession.this.webSocket = null;
                    listener.onMuxDisconnected("closed: " + code);
                    scheduleReconnect();
                });
            }
        });
    }

    private void handleControlMessage(String text) {
        JSONObject msg;
        try {
            msg = new JSONObject(text);
        } catch (JSONException e) {
            return;
        }
        String type = msg.optString("type");
        String tunnelId = msg.optString("tunnelConnectionId");
        if ("ws-connected".equals(type)) {
            synchronized (pendingConnects) {
                pendingConnects.remove(tunnelId);
            }
            mainHandler.post(() -> listener.onTunnelConnected(tunnelId));
        } else if ("ws-error".equals(type)) {
            synchronized (pendingConnects) {
                pendingConnects.remove(tunnelId);
            }
            String message = msg.optString("message");
            mainHandler.post(() -> listener.onTunnelError(tunnelId, message));
        }
        // 其它控制消息（服务端角色不发送 ws-connect）忽略。
    }

    // dispatchBinaryFrame 是纯方法，单测覆盖（见 MuxSessionRoutingTest）。
    static void dispatchBinaryFrame(byte[] data, Listener listener) {
        WebTermProtocol.TunnelFrame frame = WebTermProtocol.decodeTunnelFrame(data);
        if (frame == null) return;
        boolean binary = (frame.extraByte & 0xff) == WebTermProtocol.WS_DATA_BINARY;
        listener.onTunnelData(frame.tunnelId, frame.payload, binary);
    }

    private void scheduleReconnect() {
        if (!enabled) return;
        int attempt = ++reconnectAttempts;
        long cap = Math.min(1000L * attempt, 8000L);
        long delayMs = Math.max(200L, (long) (Math.random() * cap));
        mainHandler.postDelayed(() -> {
            if (enabled) connectNow();
        }, delayMs);
    }
}
```

- [ ] **Step 4: 运行路由单测，确认通过**

Run: `cd android-client && ./gradlew :app:testDebugUnitTest --tests "com.webterm.mobile.MuxSessionRoutingTest" 2>&1 | tail -20`
Expected: 2 个测试 PASS。

- [ ] **Step 5: Commit**

```bash
cd android-client && git add app/src/main/java/com/webterm/mobile/MuxSession.java app/src/test/java/com/webterm/mobile/MuxSessionRoutingTest.java
git commit -m "feat(mux): add client-side MuxSession for direct single-connection multiplex

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 3: 调用链透传 `isRelayDevice`

**Files:**
- Modify: `app/src/main/java/com/webterm/mobile/TerminalRuntimeState.java`
- Modify: `app/src/main/java/com/webterm/mobile/TerminalLifecycleController.java`
- Modify: `app/src/main/java/com/webterm/mobile/MainActivity.java`
- Modify: `app/src/main/java/com/webterm/mobile/SessionCommandController.java`

**Interfaces:**
- Produces: `isRelayDevice` 布尔从入口透传到 `TerminalConnection.connect`。`TerminalRuntimeState` 新增 `boolean isRelayDevice()`、`setServerSession(baseUrl, cookie, sessionId, isRelayDevice)`。

- [ ] **Step 1: TerminalRuntimeState 加字段**

读 `app/src/main/java/com/webterm/mobile/TerminalRuntimeState.java`，在 `sessionId` 字段后加 `private boolean relayDevice;`，并改 `setServerSession` 与 `clearServerSession`，加 getter：

```java
    private String baseUrl;
    private String cookie;
    private String sessionId;
    private boolean relayDevice;
```

把 `setServerSession` 改为：
```java
    void setServerSession(String baseUrl, String cookie, String sessionId, boolean relayDevice) {
        this.baseUrl = baseUrl;
        this.cookie = cookie;
        this.sessionId = sessionId;
        this.relayDevice = relayDevice;
    }
```

`clearServerSession`（约 line 91）加 `relayDevice = false;`。

加 getter：
```java
    boolean isRelayDevice() {
        return relayDevice;
    }
```

> 实现者注意：检查 `TerminalRuntimeState` 是否有 `snapshot`/序列化（约 line 109-128），若有需把 `relayDevice` 也带上或确认不影响。`relayDevice` 是运行时状态、非持久化缓存键，snapshot 里可不带（缓存键用 baseUrl/sessionId/instanceId/createdAt，不含 relayDevice）。

- [ ] **Step 2: TerminalLifecycleController.showTerminal 透传**

读 `app/src/main/java/com/webterm/mobile/TerminalLifecycleController.java` 的 `showTerminal` 签名（约 line 92），加 `boolean relayDevice` 参数，透传给 `terminalState.setServerSession`（约 line 110）：

签名改为：
```java
    void showTerminal(
        String baseUrl, String cookie, String sessionId,
        String termTitle, String sessionName, String createdAt, String instanceId,
        boolean relayDevice,
        WebTermTerminalViewClient.Host viewClientHost,
        WebTermTerminalSessionClient sessionClient,
        Runnable onBack
    ) {
```

`setServerSession` 调用改为：
```java
        terminalState.setServerSession(baseUrl, cookie, sessionId, relayDevice);
```

- [ ] **Step 3: 找所有 showTerminal 调用方，补参数**

Run: `cd android-client && grep -rn "showTerminal(" app/src/main/java/com/webterm/mobile/ | grep -v "void showTerminal"`
找出每个调用点，在 `instanceId` 之后、`viewClientHost` 之前插入 `relayDevice` 实参。

- [ ] **Step 4: MainActivity.openSession 传 server.isRelayDevice()**

读 `app/src/main/java/com/webterm/mobile/MainActivity.java:460` 的 `openSession`：
```java
    @Override
    public void openSession(ServerConfig server, String sessionId, ...) {
        mSelectedServer = server;
        showTerminal(server.getUrl(), server.getCookie(), sessionId, termTitle, sessionName, createdAt, instanceId, server.isRelayDevice(), ...);
    }
```
在 `showTerminal(...)` 调用里加 `server.isRelayDevice()`（位置对应 Step 2 签名）。

- [ ] **Step 5: SessionCommandController.Listener.onOpenTerminal 增 isRelayDevice**

读 `app/src/main/java/com/webterm/mobile/SessionCommandController.java:139` 的 `Listener` 接口，给 `onOpenTerminal` 加 `boolean isRelayDevice` 参数：
```java
        void onOpenTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String sessionName, boolean isRelayDevice);
```

找到 `SessionCommandController` 内部调用 `listener.onOpenTerminal(...)` 的地方（新建会话成功后），传入 `isRelayDevice`。需确认新建会话路径能否拿到 `isRelayDevice`——它从 `ServerConfig` 来。读 `SessionCommandController` 构造/字段确认有 `ServerConfig` 或能取到 `isRelayDevice`；若无，给 `onOpenTerminal` 调用前的逻辑补上从对应 `ServerConfig` 取 `isRelayDevice`。

- [ ] **Step 6: MainActivity 实现 onOpenTerminal 回调接收并透传**

读 `MainActivity` 里实现 `SessionCommandController.Listener.onOpenTerminal` 的方法，加 `boolean isRelayDevice` 参数，透传给 `showTerminal(...)`。

- [ ] **Step 7: 编译验证**

Run: `cd android-client && ./gradlew :app:compileDebugJavaWithJavac 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL。若有 `showTerminal`/`onOpenTerminal` 调用点漏补参数，编译会报错——按报错补齐。

- [ ] **Step 8: Commit**

```bash
cd android-client && git add app/src/main/java/com/webterm/mobile/
git commit -m "refactor(terminal): thread isRelayDevice through terminal launch chain

TerminalConnection.connect 需依据 isRelayDevice 分流 direct mux / relay 旧 WS。

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 4: `TerminalConnection` direct 路径走 MuxSession

**Files:**
- Modify: `app/src/main/java/com/webterm/mobile/TerminalConnection.java`

**Interfaces:**
- Consumes: Task 2 的 `MuxSession`；`TerminalRuntimeState.isRelayDevice()`（Task 3）；`WebTermProtocol.encodeTunnelFrame`。
- Produces: `connect(baseUrl, cookie, sessionId, lastSeq, isRelayDevice)`——direct 走 MuxSession，relay 走旧每终端 WS。

- [ ] **Step 1: 改 connect 签名与分流**

读 `app/src/main/java/com/webterm/mobile/TerminalConnection.java`。`connect`（line 73）改为增 `isRelayDevice`：

```java
    private boolean relayDevice;
    private MuxSession muxSession;

    void connect(String baseUrl, String cookie, String sessionId, long lastSeq, boolean isRelayDevice) {
        this.baseUrl = baseUrl;
        this.cookie = cookie;
        this.sessionId = sessionId;
        this.lastSeq = lastSeq;
        this.relayDevice = isRelayDevice;
        this.state = State.CONNECTING;
        this.reconnectAttempts = 0;
        connectNow();
    }
```

- [ ] **Step 2: connectNow 分流**

`connectNow`（line 132）开头加分流。direct 走 mux，relay 走旧逻辑（现有 `webSocket = terminalHttp.newWebSocket(...)` 整段保留为 relay 分支）：

```java
    private void connectNow() {
        if (sessionId == null || baseUrl == null || cookie == null) return;
        if (relayDevice) {
            connectRelayLegacy();   // 现有每终端独立 WS 逻辑，原样移入
            return;
        }
        connectDirectMux();          // 新：mux 单连接
    }

    private void connectRelayLegacy() {
        if (webSocket != null) {
            releaseSocket("reconnecting");
        }
        state = State.CONNECTING;
        listener.onConnectionStatus(state, reconnectAttempts);
        int generation = ++socketGeneration;
        String encodedId = WebTermUrls.encodePath(sessionId);
        Request request = new Request.Builder()
            .url(WebTermUrls.toWebSocketUrl(baseUrl) + "/ws/sessions/" + encodedId)
            .header("Cookie", cookie)
            .header("Sec-WebSocket-Protocol", BINARY_SUBPROTOCOL)
            .build();
        webSocket = terminalHttp.newWebSocket(request, new WebSocketListener() {
            // ... 原 onOpen/onMessage/onFailure/onClosed 全部保留 ...
        });
    }
```

> 实现者：把现有 `connectNow` 里 `webSocket = terminalHttp.newWebSocket(...)` 那一整段（含 `WebSocketListener` 匿名类，line 146-201）原样移入 `connectRelayLegacy()`，不动任何逻辑。

- [ ] **Step 3: 实现 connectDirectMux**

新增 `connectDirectMux`，建 `MuxSession`（若未建），发 `ws-connect` 建 `term:{sessionId}` 通道，等 `onTunnelConnected` 后发 `MSG_HELLO`：

```java
    private void connectDirectMux() {
        if (muxSession == null || !muxSession.isConnected()) {
            if (muxSession != null) muxSession.stop();
            String wsUrl = WebTermUrls.toWebSocketUrl(baseUrl) + "/ws/sessions";
            muxSession = new MuxSession(http, mainHandler, wsUrl, cookie, new MuxSession.Listener() {
                @Override public void onMuxConnected() {
                    openTerminalChannel();
                }
                @Override public void onMuxDisconnected(String reason) {
                    if (state != State.DISCONNECTED) scheduleReconnect(reason);
                }
                @Override public void onTunnelConnected(String tunnelId) {
                    if (!tunnelId.equals(channelId())) return;
                    state = State.CONNECTED;
                    reconnectAttempts = 0;
                    sentColumns = 0;
                    sentRows = 0;
                    listener.onConnectionStatus(state, reconnectAttempts);
                    sendHello();
                    sendResizeNow();
                }
                @Override public void onTunnelError(String tunnelId, String message) {
                    listener.onProtocolError("tunnel error: " + message);
                }
                @Override public void onTunnelData(String tunnelId, byte[] payload, boolean binary) {
                    if (!tunnelId.equals(channelId())) return;
                    handleServerMessage(payload);
                }
            });
            muxSession.start();
        } else {
            openTerminalChannel();
        }
    }

    private String channelId() {
        return "term:" + sessionId;
    }

    private void openTerminalChannel() {
        if (muxSession == null || !muxSession.isConnected()) return;
        muxSession.sendWsConnect(channelId(), "/ws/sessions/" + WebTermUrls.encodePath(sessionId),
            new String[]{BINARY_SUBPROTOCOL});
    }

    private void sendHello() {
        JSONObject hello = WebTermProtocol.put(WebTermProtocol.json(), "lastSeq", lastSeq);
        if (columns > 0 && rows > 0) {
            WebTermProtocol.put(hello, "cols", columns);
            WebTermProtocol.put(hello, "rows", rows);
            sentColumns = columns;
            sentRows = rows;
        }
        sendBinary(WebTermProtocol.MSG_HELLO, hello.toString().getBytes(StandardCharsets.UTF_8));
    }
```

- [ ] **Step 4: 改 sendBinary / sendInput / sendResize 走 mux 分流**

现有 `sendBinary`（line 283）只写 `webSocket`。改为按 `relayDevice` 分流：

```java
    private void sendBinary(byte type, byte[] payload) {
        if (relayDevice) {
            WebSocket ws = webSocket;
            if (ws == null || state != State.CONNECTED) return;
            ws.send(WebTermProtocol.frame(type, payload));
            return;
        }
        // direct mux：包成 tunnel frame
        if (muxSession == null || !muxSession.isConnected() || state != State.CONNECTED) return;
        muxSession.sendTunnelFrame(channelId(), WebTermProtocol.frame(type, payload), true);
    }
```

`sendInput`/`sendTitle`/`sendResizeNow` 调 `sendBinary`，无需改（已分流）。

- [ ] **Step 5: close 释放 muxSession**

`close`（line 102）加分流：
```java
    void close(String reason) {
        this.state = State.DISCONNECTED;
        this.socketGeneration++;
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(sendResizeRunnable);
        if (relayDevice) {
            releaseSocket(reason);
        } else if (muxSession != null) {
            muxSession.sendWsClose(channelId());
            // 注：不关整个 muxSession——它由 TerminalConnection 生命周期管理。
            // 单终端场景离开终端页时停掉 muxSession：
            muxSession.stop();
            muxSession = null;
        }
    }
```

> 实现者注意：第一阶段终端页一次一个终端，离开终端页直接 `muxSession.stop()` 关整条连接。多终端复用是未来工作。

- [ ] **Step 6: scheduleReconnect 分流**

`scheduleReconnect`（line 204）目前检查 `baseUrl/cookie` 等。mux 路径重连由 `MuxSession` 自己负责（`onMuxDisconnected` → `scheduleReconnect`）。`TerminalConnection.scheduleReconnect` 只需对 relay 路径生效。在 `scheduleReconnect` 开头加：
```java
    private void scheduleReconnect(String reason) {
        if (state == State.DISCONNECTED || sessionId == null || cookie == null || baseUrl == null) return;
        if (state == State.RECONNECTING) return;
        // mux 路径：重连由 MuxSession 内部负责，这里只处理 relay 旧 WS
        if (!relayDevice) return;
        // ... 原 relay 重连逻辑保留 ...
    }
```

> 注意：mux 路径下 `onMuxDisconnected` 调 `scheduleReconnect` 会因 `!relayDevice` 提前返回——这是对的，mux 重连在 `MuxSession.scheduleReconnect` 内部，重连后 `onMuxConnected` → `openTerminalChannel` 重建通道。但需确保 `TerminalConnection` 在 mux 重连后重新发 `ws-connect`：`onMuxConnected` 调 `openTerminalChannel()` 已覆盖。

- [ ] **Step 7: 编译验证**

Run: `cd android-client && ./gradlew :app:compileDebugJavaWithJavac 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 8: 更新 connect 调用点**

Run: `cd android-client && grep -rn "terminalConnection.connect(" app/src/main/java/com/webterm/mobile/`
找到 `TerminalLifecycleController.java:190` 的 `connectTerminal`，改为传 `terminalState.isRelayDevice()`：
```java
        terminalConnection.connect(terminalState.baseUrl(), terminalState.cookie(), terminalState.sessionId(), terminalState.lastSeq(), terminalState.isRelayDevice());
```

- [ ] **Step 9: 再编译**

Run: `cd android-client && ./gradlew :app:compileDebugJavaWithJavac 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 10: Commit**

```bash
cd android-client && git add app/src/main/java/com/webterm/mobile/TerminalConnection.java app/src/main/java/com/webterm/mobile/TerminalLifecycleController.java
git commit -m "feat(terminal): direct path uses MuxSession, relay keeps legacy per-terminal WS

按 isRelayDevice 分流：direct 走 /ws/sessions mux 单连接复用终端通道，
relay 维持每终端独立 /ws/sessions/{id} WS 不变。

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 5: 全量单测与端到端验证

**Files:** 无新增。

- [ ] **Step 1: 全量单测**

Run: `cd android-client && ./gradlew :app:testDebugUnitTest 2>&1 | tail -20`
Expected: 所有单测 PASS（含现有 `ServerSessionMonitorTest` + 新 `WebTermProtocolTest` + `MuxSessionRoutingTest`）。

- [ ] **Step 2: 编译 debug APK**

Run: `cd android-client && ./gradlew :app:assembleDebug 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: direct 端到端（真机/模拟器）**

前置：go-core direct server 已部署 mux 入口（go-core 计划完成）。
1. 起 go-core direct server：`cd go-core && WEBTERM_PASSWORD=pw WEBTERM_ADDR=0.0.0.0:8080 go run ./cmd/webterm-agent -mode direct`
2. Android 配置该 server（直连，非 relay）。
3. 打开一个终端会话 → 验证终端输出正常、能输入。
4. 切换到另一个终端会话 → 验证同样正常。
5. 杀掉 server 再重启 → 验证 Android 自动重连恢复（`lastSeq` 增量）。
6. 用 `adb logcat -s MuxSession TerminalConnection` 观察日志，确认走 mux 路径（看到 `mux open` 而非旧 `/ws/sessions/{id}`）。

Expected: 直连终端完全可用，重连恢复正常。

- [ ] **Step 4: relay 回归（中继维持旧路径）**

1. 起 relay server + go-core agent（agent 已用 mux.Serve，但这是 agent 侧，Android 中继客户端不受影响）。
2. Android 配置 relay server（relay device）。
3. 打开终端 → 验证中继终端仍走旧每终端 WS（`adb logcat` 不应出现 `mux open`，应出现 `/ws/sessions/{id}`）。
4. 验证中继终端输入输出正常。

Expected: 中继路径行为与改造前完全一致。

- [ ] **Step 5: 最终 commit（若有 lint/小修）**

```bash
cd android-client && git add -A
git commit -m "chore: cleanup after direct mux migration

Co-Authored-By: Claude <noreply@anthropic.com>" || echo "nothing to commit"
```

---

## Self-Review 记录

**1. Spec 覆盖**：
- §4.1 MuxSession → Task 2 ✅
- §4.2 WebTermProtocol tunnel frame → Task 1 ✅
- §4.3 TerminalConnection direct 复用通道 + 等 ws-connected 后发 HELLO → Task 4（`onTunnelConnected` 调 `sendHello`）✅
- §4.4 manager 通道第一阶段不开 → Task 4 未开 manager 通道 ✅
- §4.5 终端子协议 binary → Task 4 `sendWsConnect(..., new String[]{BINARY_SUBPROTOCOL})` ✅
- §4.6 归属/生命周期 → Task 4 close 时 stop muxSession ✅
- §4.6.1 调用链 isRelayDevice → Task 3 ✅
- §4.7 sessionId 前缀（direct 无前缀）→ Task 4 `"/ws/sessions/" + encodePath(sessionId)` ✅
- §6.2 Android 测试（tunnel frame/顺序/重连/调用链）→ Task 1/2/5 ✅

**2. 占位符**：Task 3 Step 1 有「实现者注意 snapshot」提示，但给出了具体处理（relayDevice 不入缓存键），非占位符。Task 4 Step 2 标注「原样移入」并指明了行号范围——实现者需读原代码搬移，这是必要的上下文引用，非占位符。无 TBD/TODO。

**3. 类型一致性**：`MuxSession.Listener` 5 个回调在 Task 2 定义、Task 4 实现一致；`channelId()` = `"term:" + sessionId` 在 Task 4 内部一致；`connect(..., isRelayDevice)` 签名在 Task 3/4 一致；`TerminalRuntimeState.isRelayDevice()` 在 Task 3 定义、Task 4 调用一致。

**4. 已知风险**：
- Task 3 调用链改动面广（5 文件），编译验证（Step 7/9）是关键守门。
- Task 4 mux 重连与 `TerminalConnection` 状态机交互需端到端验证（Task 5 Step 3）。
- 中继路径必须回归（Task 5 Step 4）——确认未被波及。
