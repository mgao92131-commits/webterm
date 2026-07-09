package com.webterm.transport.websocket;

import com.webterm.transport.api.MuxTransport;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicInteger;

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

    private volatile WebSocket webSocket;
    private volatile boolean connected;
    private volatile boolean enabled;
    private final AtomicInteger listenerGeneration = new AtomicInteger(0);
    private volatile Listener activeListener;

    public WebSocketMuxTransport(OkHttpClient http, String wsUrl, String cookie, String subprotocol) {
        this.muxHttp = http.newBuilder()
            .pingInterval(5, java.util.concurrent.TimeUnit.SECONDS)
            .build();
        this.wsUrl = wsUrl;
        this.cookie = cookie;
        this.subprotocol = subprotocol;
    }

    @Override
    public void start(Listener listener) {
        if (wsUrl == null || wsUrl.isEmpty()) {
            close();
            return;
        }
        enabled = true;
        int generation = listenerGeneration.incrementAndGet();
        activeListener = listener;
        WebSocket oldSocket = webSocket;
        webSocket = null;
        connected = false;
        if (oldSocket != null) {
            try { oldSocket.close(1001, "stale socket cleanup"); } catch (Exception ignored) {}
        }
        Request request = new Request.Builder()
            .url(wsUrl)
            .header("Cookie", cookie != null ? cookie : "")
            .header("Sec-WebSocket-Protocol", subprotocol)
            .build();
        webSocket = muxHttp.newWebSocket(request, new WebSocketListener() {
            private boolean isCurrent() {
                return enabled && generation == listenerGeneration.get();
            }

            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                if (!isCurrent()) {
                    try { webSocket.close(1000, "stale listener"); } catch (Exception ignored) {}
                    return;
                }
                connected = true;
                Listener l = activeListener;
                if (l != null) l.onOpen();
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                if (!isCurrent()) return;
                Listener l = activeListener;
                if (l != null) l.onText(text);
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull okio.ByteString bytes) {
                if (!isCurrent()) return;
                Listener l = activeListener;
                if (l != null) l.onBinary(bytes.toByteArray());
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                if (!isCurrent()) return;
                connected = false;
                WebSocketMuxTransport.this.webSocket = null;
                Listener l = activeListener;
                if (l != null) l.onError(t.getMessage());
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                if (!isCurrent()) return;
                connected = false;
                WebSocketMuxTransport.this.webSocket = null;
                Listener l = activeListener;
                if (l != null) l.onClosed(code, reason);
            }
        });
    }

    @Override
    public void close() {
        enabled = false;
        connected = false;
        listenerGeneration.incrementAndGet();
        activeListener = null;
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            try { ws.close(1000, "closing mux"); } catch (Exception ignored) {}
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public boolean sendText(String text) {
        WebSocket ws = webSocket;
        if (ws == null || !connected) return false;
        return ws.send(text);
    }

    @Override
    public boolean sendBinary(byte[] data) {
        WebSocket ws = webSocket;
        if (ws == null || !connected) return false;
        return ws.send(okio.ByteString.of(data));
    }
}
