package com.webterm.transport.websocket;

import com.webterm.transport.api.MuxTransport;
import com.webterm.core.contract.diagnostics.Diagnostics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class WebSocketMuxTransport implements MuxTransport {
    private final OkHttpClient muxHttp;
    private final String wsUrl;
    private final String cookie;
    private final String subprotocol;

    /** 一次物理 WebSocket 连接尝试；OkHttp 回调只能推进所属 Attempt。 */
    private static final class Attempt {
        final Listener listener;
        final int number;
        final long startedAtNanos;
        volatile WebSocket socket;
        volatile boolean connected;

        Attempt(Listener listener, int number) {
            this.listener = listener;
            this.number = number;
            this.startedAtNanos = System.nanoTime();
        }
    }

    private Attempt currentAttempt;
    private int nextAttemptNumber;
    private TrafficAccumulator trafficAccumulator;

    public WebSocketMuxTransport(OkHttpClient http, String wsUrl, String cookie, String subprotocol) {
        this.muxHttp = http.newBuilder()
            .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
            .build();
        this.wsUrl = wsUrl;
        this.cookie = cookie;
        this.subprotocol = subprotocol;
        this.trafficAccumulator = new TrafficAccumulator();
    }

    @Override
    public void setTrafficAccumulator(TrafficAccumulator accumulator) {
        this.trafficAccumulator = accumulator != null ? accumulator : new TrafficAccumulator();
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
            attempt = new Attempt(listener, ++nextAttemptNumber);
            currentAttempt = attempt;
        }
        Diagnostics.info("ws", "ws_connect_start", Map.of(
            "attempt", attempt.number,
            "host", safeHost(wsUrl)));
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
                Diagnostics.info("ws", "ws_open", Map.of(
                    "attempt", attempt.number,
                    "responseCode", response.code()));
                attempt.listener.onOpen();
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                if (!isCurrent(attempt, webSocket)) return;
                if (trafficAccumulator != null) {
                    trafficAccumulator.recordRx(text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length);
                }
                attempt.listener.onText(text);
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull okio.ByteString bytes) {
                if (!isCurrent(attempt, webSocket)) return;
                byte[] payload = bytes.toByteArray();
                if (trafficAccumulator != null) {
                    trafficAccumulator.recordRx(payload.length);
                }
                attempt.listener.onBinary(payload);
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                if (!finish(attempt, webSocket)) {
                    if (response != null && response.body() != null) response.close();
                    return;
                }
                int code = response != null ? response.code() : 0;
                Diagnostics.warn("ws", "ws_failure", Map.of(
                    "attempt", attempt.number,
                    "responseCode", code,
                    "exceptionType", t.getClass().getSimpleName(),
                    "connectedDurationMs", elapsedMs(attempt)));
                if (response != null && response.body() != null) response.close();
                attempt.listener.onError(code, t.getMessage());
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                if (!finish(attempt, webSocket)) return;
                Diagnostics.info("ws", "ws_closed", Map.of(
                    "attempt", attempt.number,
                    "closeCode", code,
                    "connectedDurationMs", elapsedMs(attempt)));
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
    public TrafficSnapshot trafficSnapshot() {
        return trafficAccumulator != null ? trafficAccumulator.snapshot() : TrafficSnapshot.ZERO;
    }

    @Override
    public boolean sendText(String text) {
        Attempt attempt = connectedAttempt();
        int bytes = text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
        if (attempt == null) {
            logSendRejected(bytes);
            return false;
        }
        boolean accepted = attempt.socket.send(text);
        if (accepted && trafficAccumulator != null) {
            trafficAccumulator.recordTx(bytes);
        } else if (!accepted) {
            logSendRejected(bytes);
        }
        return accepted;
    }

    @Override
    public boolean sendBinary(byte[] data) {
        Attempt attempt = connectedAttempt();
        int bytes = data == null ? 0 : data.length;
        if (attempt == null) {
            logSendRejected(bytes);
            return false;
        }
        boolean accepted = attempt.socket.send(okio.ByteString.of(data));
        if (accepted && trafficAccumulator != null) {
            trafficAccumulator.recordTx(bytes);
        } else if (!accepted) {
            logSendRejected(bytes);
        }
        return accepted;
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

    private void logSendRejected(int payloadBytes) {
        Attempt attempt;
        synchronized (this) {
            attempt = currentAttempt;
        }
        Diagnostics.warn("ws", "ws_send_rejected", Map.of(
            "attempt", attempt == null ? 0 : attempt.number,
            "payloadBytes", payloadBytes));
    }

    private static long elapsedMs(Attempt attempt) {
        return Math.max(0L, (System.nanoTime() - attempt.startedAtNanos) / 1_000_000L);
    }

    private static String safeHost(String url) {
        try {
            return okhttp3.HttpUrl.get(url).host();
        } catch (RuntimeException ignored) {
            return "invalid";
        }
    }
}
