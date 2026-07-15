package com.webterm.transport.websocket;

import com.webterm.transport.api.MuxTransport;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public final class WebSocketMuxTransport implements MuxTransport {
    private final OkHttpClient muxHttp;
    private final String wsUrl;
    private final String cookie;
    private final String subprotocol;

    /** 一次物理 WebSocket 连接尝试；OkHttp 回调只能推进所属 Attempt。 */
    private static final class Attempt {
        final Listener listener;
        volatile WebSocket socket;
        volatile boolean connected;

        Attempt(Listener listener) {
            this.listener = listener;
        }
    }

    private Attempt currentAttempt;

    public WebSocketMuxTransport(OkHttpClient http, String wsUrl, String cookie, String subprotocol) {
        this.muxHttp = http.newBuilder()
            .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
            .build();
        this.wsUrl = wsUrl;
        this.cookie = cookie;
        this.subprotocol = subprotocol;
    }

    @Override
    public void start(Listener listener) {
        if (wsUrl == null || wsUrl.isEmpty()) {
            close();
            listener.onError("empty websocket url");
            return;
        }
        final Attempt attempt;
        synchronized (this) {
            if (currentAttempt != null) return;
            attempt = new Attempt(listener);
            currentAttempt = attempt;
        }
        Request request = new Request.Builder()
            .url(wsUrl)
            .header("Cookie", cookie != null ? cookie : "")
            .header("Sec-WebSocket-Protocol", subprotocol)
            .build();
        WebSocket socket = muxHttp.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                if (!markOpen(attempt, webSocket)) {
                    webSocket.close(1000, "stale mux socket");
                    return;
                }
                attempt.listener.onOpen();
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                if (!isCurrent(attempt, webSocket)) return;
                attempt.listener.onText(text);
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull okio.ByteString bytes) {
                if (!isCurrent(attempt, webSocket)) return;
                attempt.listener.onBinary(bytes.toByteArray());
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                if (!finish(attempt, webSocket)) {
                    if (response != null && response.body() != null) response.close();
                    return;
                }
                int code = response != null ? response.code() : 0;
                if (response != null && response.body() != null) response.close();
                attempt.listener.onError(code, t.getMessage());
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                if (!finish(attempt, webSocket)) return;
                attempt.listener.onClosed(code, reason);
            }
        });
        boolean stale;
        synchronized (this) {
            stale = currentAttempt != attempt;
            if (!stale && attempt.socket == null) attempt.socket = socket;
        }
        // close() 可能与 newWebSocket() 返回并发；不能遗留一个无人持有的连接。
        if (stale) socket.close(1000, "stale mux socket");
    }

    @Override
    public void close() {
        WebSocket ws;
        synchronized (this) {
            Attempt attempt = currentAttempt;
            currentAttempt = null;
            if (attempt == null) return;
            attempt.connected = false;
            ws = attempt.socket;
        }
        if (ws != null) {
            try { ws.close(1000, "closing mux"); } catch (Exception ignored) {}
        }
    }

    @Override
    public synchronized boolean isConnected() {
        return currentAttempt != null && currentAttempt.connected;
    }

    @Override
    public boolean sendText(String text) {
        Attempt attempt = connectedAttempt();
        return attempt != null && attempt.socket.send(text);
    }

    @Override
    public boolean sendBinary(byte[] data) {
        Attempt attempt = connectedAttempt();
        return attempt != null && attempt.socket.send(okio.ByteString.of(data));
    }

    private synchronized Attempt connectedAttempt() {
        Attempt attempt = currentAttempt;
        return attempt != null && attempt.connected && attempt.socket != null ? attempt : null;
    }

    private synchronized boolean markOpen(Attempt attempt, WebSocket socket) {
        if (currentAttempt != attempt) return false;
        if (attempt.socket != null && attempt.socket != socket) return false;
        attempt.socket = socket;
        attempt.connected = true;
        return true;
    }

    private synchronized boolean isCurrent(Attempt attempt, WebSocket socket) {
        return currentAttempt == attempt && attempt.socket == socket && attempt.connected;
    }

    private synchronized boolean finish(Attempt attempt, WebSocket socket) {
        if (currentAttempt != attempt) return false;
        if (attempt.socket != null && attempt.socket != socket) return false;
        attempt.socket = socket;
        attempt.connected = false;
        currentAttempt = null;
        return true;
    }
}
