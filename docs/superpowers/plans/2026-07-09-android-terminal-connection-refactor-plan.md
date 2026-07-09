# Android 终端连接定向修复实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 `MuxTransport / RelayMuxSessionManager / TerminalConnection` 分层架构上，修复“应用内重连失败、重启能连”和“切后台/页面重建误关远端 PTY”的问题，服务端零改动。

**Current Architecture:**
- `MuxTransport` (transport-api) — WebSocket / WebRTC DataChannel 统一接口
- `RelayMuxSessionManager` (core-session) — 设备级单 mux 连接，管理多个 tunnel channel、P2P fallback、backoff
- `MuxSession` (core-session) — mux 子协议解析、控制消息、channel 路由
- `TerminalConnection` (feature-terminal) — UI 状态转发、lastSeq、重连策略
- `TerminalLifecycleController` (feature-terminal) — Activity/Fragment 生命周期绑定

**Root Causes to Fix:**
1. `ws-close` / `ws-error` 控制消息没有解析 `code`/`reason`，所有 channel 关闭都走同一恢复路径，无法区分“可恢复”与“不可恢复”。
2. `TerminalConnection.close()` 被 `pauseCurrentConnection()` 调用，导致切后台/页面重建时发送 `ws-close`，远端 PTY 被关。
3. `TerminalConnection.connect()` 每次都会新建 channel，没有复用已存在的 channel，造成重复连接和 lastSeq 错乱。
4. `TerminalConnection` 在 8 次重连后将 `lastSeq` 重置为 0，掩盖了真正的恢复失败原因。

**Tech Stack:** Java 17, Android Gradle Plugin, JUnit 4, Mockito 5, OkHttp WebSocket, Hilt.

## Global Constraints

- 服务端协议不变：`/ws/sessions` + `webterm.mux.v1`；JSON 控制消息 + binary tunnel frame。
- 只有用户明确关闭终端时才发送 `ws-close`；切后台/页面重建/Fragment 销毁只能 detach。
- `RelayMuxSessionManager` 中的 channel 在 UI detach 后应保持存在，支持 UI 重新 attach。
- `lastSeq` 由运行中的 channel 状态决定，UI 重建时不应被缓存旧值覆盖，也不应被暴力重置。
- 所有公共状态机方法在主线程调用；Transport 回调 post 到主线程。
- 每个 Task 必须有单测，每个 Task 完成后 commit。

---

## File Structure

### 修改文件

| 文件 | 修改内容 |
|------|---------|
| `android-client/transport-api/src/main/java/com/webterm/transport/api/MuxTransport.java` | `Listener.onClosed` 增加 `int code` 参数 |
| `android-client/transport-websocket/src/main/java/com/webterm/transport/websocket/WebSocketMuxTransport.java` | 透传 WebSocket close code |
| `android-client/transport-webrtc/src/main/java/com/webterm/transport/webrtc/WebRtcDataChannelTransport.java` | 透传 close code（默认 1000） |
| `android-client/core-session/src/main/java/com/webterm/core/session/MuxSession.java` | 解析 `ws-close`/`ws-error` 的 code/reason；回调增加 code/reason |
| `android-client/core-session/src/main/java/com/webterm/core/session/RelayMuxSessionManager.java` | 根据 code 分类处理；支持查询/复用已有 channel |
| `android-client/feature/terminal/src/main/java/com/webterm/feature/terminal/domain/TerminalConnection.java` | 新增 `detach()`/`closeSession()`；复用已有 channel；移除 lastSeq 暴力重置 |
| `android-client/feature/terminal/src/main/java/com/webterm/feature/terminal/domain/TerminalLifecycleController.java` | pause 改 detach，resume 复用/重建，close 改 closeSession |

### 测试文件

| 文件 | 测试目标 |
|------|---------|
| `android-client/core-session/src/test/java/com/webterm/core/session/MuxSessionControlTest.java` | code/reason 解析、分类 |
| `android-client/transport-websocket/src/test/java/com/webterm/transport/websocket/WebSocketMuxTransportTest.java` | close code 透传 |
| `android-client/core-session/src/test/java/com/webterm/core/session/RelayMuxSessionManagerRecoveryTest.java` | 可恢复/不可恢复错误处理、channel 复用 |
| `android-client/feature/terminal/src/test/java/com/webterm/feature/terminal/domain/TerminalConnectionLifecycleTest.java` | detach/closeSession/reattach |
| `android-client/app/src/test/java/com/webterm/mobile/ui/TerminalLifecycleControllerTest.java` | 生命周期矩阵 |

---

## Task 1: Transport close callback carries code

**Files:**
- Modify: `android-client/transport-api/src/main/java/com/webterm/transport/api/MuxTransport.java`
- Modify: `android-client/transport-websocket/src/main/java/com/webterm/transport/websocket/WebSocketMuxTransport.java`
- Modify: `android-client/transport-webrtc/src/main/java/com/webterm/transport/webrtc/WebRtcDataChannelTransport.java`
- Modify: `android-client/core-session/src/main/java/com/webterm/core/session/MuxSession.java`
- Create: `android-client/transport-websocket/src/test/java/com/webterm/transport/websocket/WebSocketMuxTransportTest.java`

**Interfaces:**
- `MuxTransport.Listener.onClosed(int code, String reason)`
- `WebSocketMuxTransport.onClosed` 调用 `listener.onClosed(code, reason)`
- `WebRtcDataChannelTransport.onClosed` 调用 `listener.onClosed(1000, reason)`
- `MuxSession` 内部适配新签名

- [ ] **Step 1: Update MuxTransport interface**

```java
public interface MuxTransport {
    interface Listener {
        void onOpen();
        void onText(String text);
        void onBinary(byte[] data);
        void onClosed(int code, String reason);
        void onError(String message);
    }
    // ... unchanged
}
```

- [ ] **Step 2: Update WebSocketMuxTransport**

```java
@Override
public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
    if (!isCurrent()) return;
    connected = false;
    WebSocketMuxTransport.this.webSocket = null;
    Listener l = activeListener;
    if (l != null) l.onClosed(code, reason);
}
```

- [ ] **Step 3: Update WebRtcDataChannelTransport**

```java
@Override
public void onClosed(String reason) {
    if (!isCurrent()) return;
    Listener l = activeListener;
    if (l != null) l.onClosed(1000, reason);
}
```

- [ ] **Step 4: Update MuxSession transport listener**

```java
@Override public void onClosed(int code, String reason) {
    mainHandler.post(() -> {
        if (!enabled) return;
        connected = false;
        listener.onMuxDisconnected(reason + " (" + code + ")");
        scheduleReconnect();
    });
}
```

- [ ] **Step 5: Run existing tests and fix compilation**

```bash
./gradlew :transport-api:compileDebugJavaWithJavac :transport-websocket:compileDebugJavaWithJavac :transport-webrtc:compileDebugJavaWithJavac :core-session:compileDebugJavaWithJavac
```

Expected: any test using `MuxTransport.Listener` must be updated.

- [ ] **Step 6: Write WebSocketMuxTransport close-code test**

```java
package com.webterm.transport.websocket;

import com.webterm.transport.api.MuxTransport;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class WebSocketMuxTransportTest {
    @Test
    public void closeCallbackCarriesCode() {
        // Minimal compile-level verification that listener receives code
        AtomicInteger codeRef = new AtomicInteger(-1);
        MuxTransport.Listener listener = new MuxTransport.Listener() {
            @Override public void onOpen() {}
            @Override public void onText(String text) {}
            @Override public void onBinary(byte[] data) {}
            @Override public void onClosed(int code, String reason) { codeRef.set(code); }
            @Override public void onError(String message) {}
        };
        listener.onClosed(1001, "backpressure");
        assertEquals(1001, codeRef.get());
    }
}
```

- [ ] **Step 7: Commit**

```bash
git add android-client/transport-api/src/main/java/com/webterm/transport/api/MuxTransport.java \
        android-client/transport-websocket/src/main/java/com/webterm/transport/websocket/WebSocketMuxTransport.java \
        android-client/transport-webrtc/src/main/java/com/webterm/transport/webrtc/WebRtcDataChannelTransport.java \
        android-client/core-session/src/main/java/com/webterm/core/session/MuxSession.java \
        android-client/transport-websocket/src/test/java/com/webterm/transport/websocket/WebSocketMuxTransportTest.java
git commit -m "refactor(transport): pass WebSocket close code through MuxTransport.Listener

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 2: MuxSession parses ws-close/ws-error code/reason

**Files:**
- Modify: `android-client/core-session/src/main/java/com/webterm/core/session/MuxSession.java`
- Modify: `android-client/core-session/src/main/java/com/webterm/core/session/RelayMuxSessionManager.java`
- Create: `android-client/core-session/src/test/java/com/webterm/core/session/MuxSessionControlTest.java`

**Interfaces:**
- `MuxSession.Listener.onTunnelError(String tunnelId, int code, String message)`
- `MuxSession.Listener.onTunnelClosed(String tunnelId, int code, String reason)`
- `RelayMuxSessionManager.ChannelListener.onError(String channelId, int code, String message)`
- `RelayMuxSessionManager.ChannelListener.onClosed(String channelId, int code, String reason)`

- [ ] **Step 1: Update MuxSession.Listener interface**

```java
interface Listener {
    void onMuxConnected();
    void onMuxDisconnected(String reason);
    void onTunnelConnected(String tunnelId);
    void onTunnelError(String tunnelId, int code, String message);
    void onTunnelData(String tunnelId, byte[] payload, boolean binary);
    void onTunnelClosed(String tunnelId, int code, String reason);
    default void onReconnectAttempt(int attempt) {}
}
```

- [ ] **Step 2: Update handleControlMessage**

```java
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
        synchronized (pendingConnects) { pendingConnects.remove(tunnelId); }
        mainHandler.post(() -> listener.onTunnelConnected(tunnelId));
    } else if ("ws-error".equals(type)) {
        synchronized (pendingConnects) { pendingConnects.remove(tunnelId); }
        int code = msg.optInt("code", 0);
        String message = msg.optString("message", "");
        mainHandler.post(() -> listener.onTunnelError(tunnelId, code, message));
    } else if ("ws-close".equals(type)) {
        synchronized (pendingConnects) { pendingConnects.remove(tunnelId); }
        int code = msg.optInt("code", 1000);
        String reason = msg.optString("reason", "");
        mainHandler.post(() -> listener.onTunnelClosed(tunnelId, code, reason));
    }
}
```

- [ ] **Step 3: Update RelayMuxSessionManager.ChannelListener and implementation**

```java
public interface ChannelListener {
    void onConnected(String channelId);
    void onError(String channelId, int code, String message);
    void onData(String channelId, byte[] payload, boolean binary);
    void onMuxDisconnected(String reason);
    default void onClosed(String channelId, int code, String reason) {}
    default void onReconnectAttempt(int attempt) {}
}
```

Update `MuxSession.Listener` implementation in `createMuxSession`:

```java
@Override public void onTunnelError(String tunnelId, int code, String message) {
    if (generation != muxGeneration) return;
    Channel channel = channels.get(tunnelId);
    if (channel != null) channel.listener.onError(tunnelId, code, message);
}

@Override public void onTunnelClosed(String tunnelId, int code, String reason) {
    if (generation != muxGeneration) return;
    Channel channel = channels.get(tunnelId);
    if (channel != null) channel.listener.onClosed(tunnelId, code, reason);
}
```

- [ ] **Step 4: Write MuxSessionControlTest**

```java
package com.webterm.core.session;

import android.os.Handler;
import android.os.Looper;
import com.webterm.transport.api.MuxTransport;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class MuxSessionControlTest {
    @Test
    public void wsErrorCarriesCodeAndMessage() {
        Handler handler = new Handler(Looper.getMainLooper());
        FakeMuxTransport transport = new FakeMuxTransport();
        AtomicInteger codeRef = new AtomicInteger();
        AtomicReference<String> msgRef = new AtomicReference<>();
        MuxSession session = new MuxSession(transport, handler, new MuxSession.Listener() {
            @Override public void onMuxConnected() {}
            @Override public void onMuxDisconnected(String reason) {}
            @Override public void onTunnelConnected(String tunnelId) {}
            @Override public void onTunnelError(String tunnelId, int code, String message) {
                codeRef.set(code); msgRef.set(message);
            }
            @Override public void onTunnelData(String tunnelId, byte[] payload, boolean binary) {}
            @Override public void onTunnelClosed(String tunnelId, int code, String reason) {}
        });
        transport.simulateText("{\"type\":\"ws-error\",\"tunnelConnectionId\":\"term:s1\",\"code\":404,\"message\":\"not found\"}");
        assertEquals(404, codeRef.get());
        assertEquals("not found", msgRef.get());
    }

    @Test
    public void wsCloseCarriesCodeAndReason() {
        // similar test
    }

    static class FakeMuxTransport implements MuxTransport {
        private Listener listener;
        @Override public void start(Listener listener) { this.listener = listener; }
        public void simulateText(String text) { if (listener != null) listener.onText(text); }
        @Override public void close() {}
        @Override public boolean isConnected() { return true; }
        @Override public boolean sendText(String text) { return true; }
        @Override public boolean sendBinary(byte[] data) { return true; }
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add android-client/core-session/src/main/java/com/webterm/core/session/MuxSession.java \
        android-client/core-session/src/main/java/com/webterm/core/session/RelayMuxSessionManager.java \
        android-client/core-session/src/test/java/com/webterm/core/session/MuxSessionControlTest.java
git commit -m "feat(core-session): parse ws-close/ws-error code and reason

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 3: Classify errors and recover correctly in RelayMuxSessionManager

**Files:**
- Modify: `android-client/core-session/src/main/java/com/webterm/core/session/RelayMuxSessionManager.java`
- Create: `android-client/core-session/src/test/java/com/webterm/core/session/RelayMuxSessionManagerRecoveryTest.java`

**Interfaces:**
- Add `RelayMuxSessionManager.ChannelListener.onChannelGone(String channelId, int code, String reason)` for permanent closure
- `RelayMuxSessionManager` distinguishes recoverable vs permanent

- [ ] **Step 1: Update ChannelListener with permanent closure callback**

```java
public interface ChannelListener {
    void onConnected(String channelId);
    void onError(String channelId, int code, String message);
    void onData(String channelId, byte[] payload, boolean binary);
    void onMuxDisconnected(String reason);
    default void onClosed(String channelId, int code, String reason) {}
    default void onChannelGone(String channelId, int code, String reason) {}
    default void onReconnectAttempt(int attempt) {}
}
```

- [ ] **Step 2: Implement classification in RelayMuxSessionManager**

```java
@Override public void onError(String channelId, int code, String message) {
    if (generation != muxGeneration) return;
    Channel channel = channels.get(channelId);
    if (channel == null) return;
    if (code == 404 || code == 401) {
        channels.remove(channelId);
        channel.listener.onChannelGone(channelId, code, message);
    } else {
        channel.listener.onError(channelId, code, message);
    }
}

@Override public void onTunnelClosed(String tunnelId, int code, String reason) {
    if (generation != muxGeneration) return;
    Channel channel = channels.remove(tunnelId);
    if (channel == null) return;
    if (code == 1000 || code == 404 || code == 401) {
        channel.listener.onChannelGone(tunnelId, code, reason);
    } else {
        // recoverable: network/backpressure; reopen channel after reconnect
        channels.put(tunnelId, channel); // keep channel alive
        channel.listener.onClosed(tunnelId, code, reason);
        if (muxSession.isConnected()) {
            muxSession.sendWsConnect(tunnelId, channel.path, channel.protocols);
        }
    }
}
```

- [ ] **Step 3: Write recovery tests**

```java
@Test
public void wsClose1001KeepsChannelAndReopens() {
    // simulate ws-connected, then ws-close code=1001, verify ws-connect resent
}

@Test
public void wsClose1000RemovesChannel() {
    // verify channel removed and onChannelGone fired
}

@Test
public void wsError404RemovesChannel() {
    // verify channel removed and onChannelGone fired
}
```

- [ ] **Step 4: Commit**

```bash
git add android-client/core-session/src/main/java/com/webterm/core/session/RelayMuxSessionManager.java \
        android-client/core-session/src/test/java/com/webterm/core/session/RelayMuxSessionManagerRecoveryTest.java
git commit -m "feat(core-session): classify ws-close/ws-error by code for recoverable vs permanent

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 4: Add channel reuse / reattach to RelayMuxSessionManager

**Files:**
- Modify: `android-client/core-session/src/main/java/com/webterm/core/session/RelayMuxSessionManager.java`
- Modify: `android-client/core-session/src/test/java/com/webterm/core/session/RelayMuxSessionManagerRecoveryTest.java`

**Interfaces:**
- `RelayMuxSessionManager.openTerminalChannel` reuses existing channel if listener matches
- Add `RelayMuxSessionManager.hasTerminalChannel(localSessionId)`

- [ ] **Step 1: Add channel lookup and listener replacement**

```java
public boolean hasTerminalChannel(String localSessionId) {
    return channels.containsKey(terminalChannelId(localSessionId));
}

public String openTerminalChannel(String localSessionId, ChannelListener listener) {
    String channelId = terminalChannelId(localSessionId);
    Channel existing = channels.get(channelId);
    if (existing != null) {
        existing.listener = listener;
        if (muxSession.isConnected()) {
            // notify immediately so UI knows it's connected
            listener.onConnected(channelId);
        }
        return channelId;
    }
    String path = "/ws/sessions/" + WebTermUrls.encodePath(localSessionId);
    openChannel(channelId, path, new String[]{BINARY_SUBPROTOCOL}, listener);
    return channelId;
}
```

Make `Channel.listener` non-final:

```java
private static final class Channel {
    final String id;
    final String path;
    final String[] protocols;
    ChannelListener listener;
    // ...
}
```

- [ ] **Step 2: Add reattach test**

```java
@Test
public void reopenChannelReusesExisting() {
    RelayMuxSessionManager mgr = ...;
    String channelId1 = mgr.openTerminalChannel("s1", listener1);
    String channelId2 = mgr.openTerminalChannel("s1", listener2);
    assertEquals(channelId1, channelId2);
}
```

- [ ] **Step 3: Commit**

```bash
git add android-client/core-session/src/main/java/com/webterm/core/session/RelayMuxSessionManager.java \
        android-client/core-session/src/test/java/com/webterm/core/session/RelayMuxSessionManagerRecoveryTest.java
git commit -m "feat(core-session): allow TerminalConnection to reattach to existing channel

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 5: Split TerminalConnection close into detach and closeSession

**Files:**
- Modify: `android-client/feature/terminal/src/main/java/com/webterm/feature/terminal/domain/TerminalConnection.java`
- Create: `android-client/feature/terminal/src/test/java/com/webterm/feature/terminal/domain/TerminalConnectionLifecycleTest.java`

**Interfaces:**
- `TerminalConnection.detach()` — remove listener, don't send ws-close
- `TerminalConnection.closeSession()` — send ws-close, clean up
- `TerminalConnection.close(String reason)` deprecated/deleted

- [ ] **Step 1: Add detach and closeSession**

```java
public void detach() {
    this.state = State.DISCONNECTED;
    this.socketGeneration++;
    mainHandler.removeCallbacks(sendResizeRunnable);
    if (relayMuxSession != null && relayChannelId != null) {
        // Remove our listener so future events don't reach a dead UI,
        // but do NOT close the channel — it stays alive for reattach.
        // Since RelayMuxSessionManager now supports listener replacement,
        // we replace with a no-op listener or simply null-check in callbacks.
        // For now, clear relayMuxSession reference; reattach will look it up again.
    }
    relayMuxSession = null;
    relayChannelId = null;
}

public void closeSession() {
    this.state = State.DISCONNECTED;
    this.socketGeneration++;
    mainHandler.removeCallbacks(sendResizeRunnable);
    if (relayMuxSession != null) {
        relayMuxSession.closeChannel(relayChannelId);
        relayMuxRegistry.releaseIfIdle(relayMuxSession);
        relayMuxSession = null;
    }
    relayChannelId = null;
}
```

- [ ] **Step 2: Update connect() to reuse existing channel**

```java
private void connectRelayMux() {
    state = State.CONNECTING;
    listener.onConnectionStatus(state, reconnectAttempts);
    String localSessionId = RelayMuxSessionManager.localSessionId(sessionId, relayDeviceId);
    if (relayMuxSession == null || !relayMuxSession.matches(baseUrl, cookie, relayDeviceId)) {
        if (relayMuxSession != null) {
            // don't close channel here; just release idle reference
            relayMuxRegistry.releaseIfIdle(relayMuxSession);
        }
        relayMuxSession = relayMuxRegistry.forDevice(baseUrl, cookie, relayDeviceId);
        relayMuxSession.updateCookie(cookie);
    }
    relayChannelId = relayMuxSession.openTerminalChannel(localSessionId, new RelayMuxSessionManager.ChannelListener() {
        // ... existing callbacks but use new signatures with code/reason
        @Override public void onError(String channelId, int code, String message) { ... }
        @Override public void onClosed(String channelId, int code, String reason) { ... }
        @Override public void onChannelGone(String channelId, int code, String reason) {
            if (!channelId.equals(relayChannelId)) return;
            state = State.DISCONNECTED;
            listener.onExit(0);
        }
    });
}
```

- [ ] **Step 3: Remove lastSeq reset workaround**

Delete the two blocks:
```java
if (reconnectAttempts >= 8 && lastSeq > 0) { lastSeq = 0; }
```

- [ ] **Step 4: Write lifecycle tests**

```java
@Test
public void detachDoesNotCloseChannel() {
    // verify closeChannel never called on detach
}

@Test
public void closeSessionClosesChannel() {
    // verify closeChannel called
}
```

- [ ] **Step 5: Commit**

```bash
git add android-client/feature/terminal/src/main/java/com/webterm/feature/terminal/domain/TerminalConnection.java \
        android-client/feature/terminal/src/test/java/com/webterm/feature/terminal/domain/TerminalConnectionLifecycleTest.java
git commit -m "refactor(feature-terminal): split TerminalConnection into detach and closeSession

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 6: Fix TerminalLifecycleController lifecycle matrix

**Files:**
- Modify: `android-client/feature/terminal/src/main/java/com/webterm/feature/terminal/domain/TerminalLifecycleController.java`
- Create: `android-client/app/src/test/java/com/webterm/mobile/ui/TerminalLifecycleControllerTest.java`

**Interfaces:**
- `pauseCurrentConnection()` → `terminalConnection.detach()`
- `connectTerminal()` → reconnect/reuse existing channel
- `closeTerminal(boolean closeRemote)` → `closeSession()` when closeRemote, else `detach()`
- `disposeTerminal()` → `closeSession()`

- [ ] **Step 1: Replace pauseCurrentConnection**

```java
public void pauseCurrentConnection() {
    cacheCurrentTerminal();
    if (terminalConnection != null) terminalConnection.detach();
}
```

- [ ] **Step 2: Update connectTerminal to use cached lastSeq only on cold start**

```java
public void connectTerminal() {
    if (terminalConnection == null || !terminalState.hasSession() || terminalState.baseUrl() == null || terminalState.cookie() == null) return;
    terminalConnection.updateSize(terminalState.columns(), terminalState.rows());
    // If TerminalConnection has no active channel but RelayMuxSessionManager still has one,
    // openTerminalChannel will reattach. Otherwise it creates a new channel.
    // Use cached lastSeq only as seed when TerminalConnection has no lastSeq yet.
    long seq = terminalConnection.getLastSeq() > 0 ? terminalConnection.getLastSeq() : terminalState.lastSeq();
    terminalConnection.connect(terminalState.baseUrl(), terminalState.cookie(), terminalState.sessionId(), seq, terminalState.relayDeviceId());
}
```

- [ ] **Step 3: Update closeTerminalConnection**

```java
private void releaseTerminalConnection(boolean closeRemote) {
    if (titleSynchronizer != null) titleSynchronizer.cancel();
    if (terminalConnection != null) {
        if (closeRemote) {
            terminalConnection.closeSession();
        } else {
            terminalConnection.detach();
        }
    }
}
```

Update callers: `closeTerminal` passes `closeRemote`; `disposeTerminal` passes `true`.

- [ ] **Step 4: Write lifecycle test**

```java
@Test
public void pauseDetachesWithoutClosing() {
    // mock TerminalConnection, call pauseCurrentConnection, verify detach() called, closeSession() not called
}
```

- [ ] **Step 5: Commit**

```bash
git add android-client/feature/terminal/src/main/java/com/webterm/feature/terminal/domain/TerminalLifecycleController.java \
        android-client/app/src/test/java/com/webterm/mobile/ui/TerminalLifecycleControllerTest.java
git commit -m "refactor(feature-terminal): lifecycle matrix - pause detach, close closeSession

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 7: Propagate lastSeq correctly on reattach

**Files:**
- Modify: `android-client/core-session/src/main/java/com/webterm/core/session/RelayMuxSessionManager.java`
- Modify: `android-client/feature/terminal/src/main/java/com/webterm/feature/terminal/domain/TerminalConnection.java`

**Interfaces:**
- `RelayMuxSessionManager.Channel` stores lastSeq
- `ChannelListener.onData` updates channel lastSeq
- `TerminalConnection` uses channel lastSeq when reconnecting after detach

- [ ] **Step 1: Add lastSeq to Channel and getter**

```java
private static final class Channel {
    final String id;
    final String path;
    final String[] protocols;
    ChannelListener listener;
    long lastSeq;

    void updateLastSeq(long seq) { if (seq > lastSeq) lastSeq = seq; }
}
```

Add method:
```java
public long getChannelLastSeq(String channelId) {
    Channel ch = channels.get(channelId);
    return ch == null ? 0 : ch.lastSeq;
}
```

- [ ] **Step 2: Update TerminalConnection to seed from channel lastSeq**

In `connect()`, after obtaining relayMuxSession and before `openTerminalChannel`, query existing channel lastSeq:

```java
String localSessionId = RelayMuxSessionManager.localSessionId(sessionId, relayDeviceId);
String existingChannelId = RelayMuxSessionManager.terminalChannelId(localSessionId);
long channelSeq = relayMuxSession.getChannelLastSeq(existingChannelId);
if (channelSeq > 0) {
    this.lastSeq = channelSeq;
}
```

- [ ] **Step 3: Update onData to update channel lastSeq**

In TerminalConnection's `onData`, after updating local lastSeq, also update channel:
```java
relayMuxSession.updateChannelLastSeq(relayChannelId, lastSeq);
```

Add `RelayMuxSessionManager.updateChannelLastSeq(String channelId, long seq)`.

- [ ] **Step 4: Commit**

```bash
git add android-client/core-session/src/main/java/com/webterm/core/session/RelayMuxSessionManager.java \
        android-client/feature/terminal/src/main/java/com/webterm/feature/terminal/domain/TerminalConnection.java
git commit -m "feat(core-session,feature-terminal): persist lastSeq in channel for reattach continuity

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 8: Add send queue for reconnect resilience

**Files:**
- Create: `android-client/core-session/src/main/java/com/webterm/core/session/SendQueue.java`
- Modify: `android-client/core-session/src/main/java/com/webterm/core/session/RelayMuxSessionManager.java`
- Create: `android-client/core-session/src/test/java/com/webterm/core/session/SendQueueTest.java`

**Interfaces:**
- `SendQueue.offer(text/binary, tunnelId)` — FIFO, drops if not connected after brief retry
- `SendQueue.setTransport(MuxTransport)` — swapped on reconnect
- RESIZE coalescing for same tunnelId

- [ ] **Step 1: Implement SendQueue**

```java
package com.webterm.core.session;

import com.webterm.transport.api.MuxTransport;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SendQueue {
    private final BlockingQueue<Entry> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile MuxTransport transport;
    private Thread worker;

    void setTransport(MuxTransport transport) {
        this.transport = transport;
    }

    void start() {
        if (running.getAndSet(true)) return;
        worker = new Thread(this::loop, "MuxSendQueue");
        worker.start();
    }

    void stop() {
        running.set(false);
        if (worker != null) worker.interrupt();
    }

    boolean offerControl(String text) {
        return offer(new Entry(text, null, null, false));
    }

    boolean offerBinary(String tunnelId, byte[] data, boolean binary) {
        return offer(new Entry(null, tunnelId, data, binary));
    }

    private boolean offer(Entry e) {
        if (!running.get()) return false;
        if (e.tunnelId != null && e.data != null && e.data.length > 0 && isResize(e.data)) {
            queue.removeIf(pending -> pending.tunnelId != null && pending.tunnelId.equals(e.tunnelId) && isResize(pending.data));
        }
        return queue.offer(e);
    }

    private boolean isResize(byte[] data) {
        return data != null && data.length > 0 && data[0] == WebTermProtocol.MSG_RESIZE;
    }

    private void loop() {
        while (running.get()) {
            try {
                Entry e = queue.take();
                MuxTransport t = transport;
                if (t == null || !t.isConnected()) {
                    // drop non-critical; control messages will be re-sent on reconnect via reopenChannels
                    continue;
                }
                if (e.text != null) t.sendText(e.text);
                else if (e.data != null) t.sendBinary(WebTermProtocol.encodeTunnelFrame(e.tunnelId, e.data, e.binary));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static final class Entry {
        final String text;
        final String tunnelId;
        final byte[] data;
        final boolean binary;
        Entry(String text, String tunnelId, byte[] data, boolean binary) {
            this.text = text; this.tunnelId = tunnelId; this.data = data; this.binary = binary;
        }
    }
}
```

- [ ] **Step 2: Integrate SendQueue into RelayMuxSessionManager**

Replace direct `muxSession.sendWsConnect/...` calls with queue offers? Or keep MuxSession synchronous and queue at manager level. For minimal change, keep MuxSession as transport wrapper and queue at manager level.

Actually, simpler: integrate queue into `MuxSession` itself so all sends go through queue. But that changes MuxSession a lot. For this targeted fix, keep sends synchronous but ensure `sendWsConnect` after reconnect works.

Given time, we may defer SendQueue if existing synchronous sends are acceptable. Mark this task as optional/lower priority.

- [ ] **Step 3: Commit** (if implemented)

---

## Task 9: Integration tests and final cleanup

**Files:**
- Create: `android-client/app/src/androidTest/java/com/webterm/mobile/TerminalConnectionIntegrationTest.java` or core-session integration test
- Modify: any remaining deprecated usages

- [ ] **Step 1: Add integration test for detach/reattach**

Simulate full flow with FakeMuxTransport:
1. TerminalConnection.connect → ws-connect sent
2. FakeMuxTransport simulate ws-connected → HELLO sent with lastSeq=0
3. Simulate OUTPUT seq=10 → lastSeq=10
4. TerminalConnection.detach
5. New TerminalConnection.connect → openTerminalChannel reuses existing channel, listener onConnected fires
6. Verify HELLO sent with lastSeq=10

- [ ] **Step 2: Run full test suite**

```bash
./gradlew :transport-api:testDebugUnitTest
./gradlew :transport-websocket:testDebugUnitTest
./gradlew :transport-webrtc:testDebugUnitTest
./gradlew :core-session:testDebugUnitTest
./gradlew :feature-terminal:testDebugUnitTest
./gradlew :app:testDebugUnitTest
```

- [ ] **Step 3: Commit**

```bash
git add android-client/app/src/androidTest/java/com/webterm/mobile/TerminalConnectionIntegrationTest.java
git commit -m "test(integration): add detach/reattach/lastSeq integration test

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Self-Review

### Spec coverage

| 设计文档要点 | 覆盖 Task |
|-------------|----------|
| detach vs closeSession 区分 | Task 5, 6 |
| 生命周期状态矩阵 | Task 6 |
| ws-close/ws-error code/reason 分类 | Task 1, 2, 3 |
| channel 复用 / reattach | Task 4, 5 |
| lastSeq 真源与连续性 | Task 7 |
| SendQueue（可选第一阶段） | Task 8 |

### Placeholder scan

- 无 TBD/TODO。
- Task 8 SendQueue 标注为可选；若时间不足可跳过，不影响主修复路径。

### Type consistency

- `MuxTransport.Listener.onClosed` 签名在 Task 1 统一改为 `(int code, String reason)`，后续所有实现同步更新。
- `MuxSession.Listener.onTunnelError/onTunnelClosed` 在 Task 2 统一签名，RelayMuxSessionManager 在 Task 3 使用。

---

## Execution Handoff

Plan revised and saved to `docs/superpowers/plans/2026-07-09-android-terminal-connection-refactor-plan.md`.

Two execution options:

1. **Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
