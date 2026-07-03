package com.webterm.mobile.transport;

import com.webterm.mobile.domain.session.MuxTransport;

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

    private WebSocket webSocket;
    private volatile boolean connected;
    private volatile boolean enabled;

    WebSocketMuxTransport(OkHttpClient http, String wsUrl, String cookie, String subprotocol) {
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
            return;
        }
        enabled = true;
        if (webSocket != null) return;
        Request request = new Request.Builder()
            .url(wsUrl)
            .header("Cookie", cookie != null ? cookie : "")
            .header("Sec-WebSocket-Protocol", subprotocol)
            .build();
        webSocket = muxHttp.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                if (!enabled) {
                    webSocket.close(1000, "stale mux socket");
                    return;
                }
                connected = true;
                listener.onOpen();
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                if (!enabled) return;
                listener.onText(text);
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull okio.ByteString bytes) {
                if (!enabled) return;
                listener.onBinary(bytes.toByteArray());
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                if (!enabled) return;
                connected = false;
                WebSocketMuxTransport.this.webSocket = null;
                listener.onError(t.getMessage());
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                if (!enabled) return;
                connected = false;
                WebSocketMuxTransport.this.webSocket = null;
                listener.onClosed("closed: " + code);
            }
        });
    }

    @Override
    public void close() {
        enabled = false;
        connected = false;
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
