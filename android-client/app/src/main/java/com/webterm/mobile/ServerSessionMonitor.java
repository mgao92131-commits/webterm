package com.webterm.mobile;

import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

final class ServerSessionMonitor {
    private static final String TAG = "ServerSessionMonitor";

    private final OkHttpClient http;
    private final OkHttpClient monitorHttp;
    private final Handler mainHandler;
    private final ServerConfig server;
    private final Listener listener;

    private WebSocket webSocket;
    private boolean connected;
    private boolean enabled;
    private int reconnectAttempts;

    ServerSessionMonitor(OkHttpClient http, Handler mainHandler, ServerConfig server, Listener listener) {
        this.http = http;
        this.monitorHttp = http.newBuilder()
            .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
            .build();
        this.mainHandler = mainHandler;
        this.server = server;
        this.listener = listener;
    }

    void start() {
        if (server.getUrl().isEmpty()) {
            stop();
            return;
        }
        enabled = true;
        if (webSocket != null) return;

        String urlString = WebTermUrls.toWebSocketUrl(server.getUrl()) + "/ws/sessions";
        if (server.isRelayDevice() && server.getDeviceId() != null && !server.getDeviceId().isEmpty()) {
            urlString += "?deviceId=" + WebTermUrls.encodePath(server.getDeviceId());
        }
        Log.i(TAG, "TitleTrace manager start url=" + urlString + " relayDevice=" + server.isRelayDevice() + " deviceId=" + server.getDeviceId());

        Request request = new Request.Builder()
            .url(urlString)
            .header("Cookie", server.getCookie() != null ? server.getCookie() : "")
            .build();

        webSocket = monitorHttp.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                if (!enabled) {
                    webSocket.close(1000, "stale manager socket");
                    return;
                }
                connected = true;
                reconnectAttempts = 0;
                Log.i(TAG, "TitleTrace manager open code=" + response.code());
                listener.onMonitorConnected();
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                if (!enabled) return;
                dispatchMessage(text, listener, server.isRelayDevice() ? server.getDeviceId() : null);
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                if (response != null && response.code() == 401) {
                    connected = false;
                    if (webSocket == ServerSessionMonitor.this.webSocket) {
                        ServerSessionMonitor.this.webSocket = null;
                    }
                    listener.onMonitorError("401");
                    return;
                }
                Log.i(TAG, "TitleTrace manager failure response=" + (response != null ? response.code() : 0) + " error=" + t.getMessage());
                onDisconnected(webSocket);
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                if (code == 4001 || "Unauthorized".equalsIgnoreCase(reason)) {
                    connected = false;
                    if (webSocket == ServerSessionMonitor.this.webSocket) {
                        ServerSessionMonitor.this.webSocket = null;
                    }
                    listener.onMonitorError("401");
                    return;
                }
                Log.i(TAG, "TitleTrace manager closed code=" + code + " reason=" + reason);
                onDisconnected(webSocket);
            }
        });
    }

    static void dispatchMessage(@NonNull String text, @NonNull Listener listener) {
        dispatchMessage(text, listener, null);
    }

    static void dispatchMessage(@NonNull String text, @NonNull Listener listener, @Nullable String relayDeviceId) {
        try {
            JSONObject msg = new JSONObject(text);
            prefixRelaySessionIds(msg, relayDeviceId);
            String type = msg.optString("type");
            if ("sessions".equals(type)) {
                JSONArray arr = msg.optJSONArray("data");
                logSessionsTrace(arr);
                listener.onMonitorSessions(arr != null ? arr : new JSONArray());
            } else if ("session".equals(type)) {
                JSONObject sessionData = msg.optJSONObject("data");
                if (sessionData != null) {
                    Log.i(TAG, "TitleTrace manager message type=session id=" + sessionData.optString("id") + " termTitle=" + sessionData.optString("termTitle"));
                    listener.onMonitorSession(sessionData);
                }
            } else if ("session-closed".equals(type)) {
                String id = msg.optString("id");
                if (id != null) listener.onMonitorSessionClosed(id);
            } else if ("devices".equals(type)) {
                JSONArray arr = msg.optJSONArray("devices");
                listener.onMonitorDevices(arr != null ? arr : new JSONArray());
            } else if ("error".equals(type)) {
                String errorVal = msg.optString("message");
                listener.onMonitorError(errorVal);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse manager WS message", e);
        }
    }

    private static void prefixRelaySessionIds(JSONObject msg, @Nullable String relayDeviceId) throws JSONException {
        if (relayDeviceId == null || relayDeviceId.isEmpty()) return;
        String type = msg.optString("type");
        if ("sessions".equals(type)) {
            JSONArray sessions = msg.optJSONArray("data");
            if (sessions == null) return;
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject session = sessions.optJSONObject(i);
                prefixRelaySessionId(session, relayDeviceId);
            }
        } else if ("session".equals(type)) {
            prefixRelaySessionId(msg.optJSONObject("data"), relayDeviceId);
        } else if ("session-closed".equals(type)) {
            String id = prefixedSessionId(msg.optString("id"), relayDeviceId);
            if (!id.isEmpty()) msg.put("id", id);
        }
    }

    private static void prefixRelaySessionId(@Nullable JSONObject session, String relayDeviceId) throws JSONException {
        if (session == null) return;
        String id = prefixedSessionId(session.optString("id"), relayDeviceId);
        if (!id.isEmpty()) session.put("id", id);
    }

    private static String prefixedSessionId(String id, String relayDeviceId) {
        if (id == null || id.isEmpty() || id.contains(":")) return id == null ? "" : id;
        return relayDeviceId + ":" + id;
    }

    private static void logSessionsTrace(JSONArray sessions) {
        if (sessions == null) {
            Log.i(TAG, "TitleTrace manager message type=sessions count=0");
            return;
        }
        StringBuilder builder = new StringBuilder("TitleTrace manager message type=sessions count=")
            .append(sessions.length());
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session == null) continue;
            builder.append(" [")
                .append(session.optString("id"))
                .append(" title=")
                .append(session.optString("termTitle"))
                .append("]");
        }
        Log.i(TAG, builder.toString());
    }

    void connectDevice(String deviceId) {
        if (webSocket == null || !connected) return;
        JSONObject msg = new JSONObject();
        try {
            msg.put("type", "connect-device");
            msg.put("deviceId", deviceId);
            webSocket.send(msg.toString());
        } catch (JSONException ignored) {
        }
    }

    void requestDevicesList() {
        if (webSocket == null || !connected) return;
        JSONObject msg = new JSONObject();
        try {
            msg.put("type", "list-devices");
            webSocket.send(msg.toString());
        } catch (JSONException ignored) {
        }
    }

    void stop() {
        enabled = false;
        connected = false;
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            ws.close(1000, "closing page");
        }
    }

    boolean isConnected() {
        return connected;
    }

    boolean isEnabled() {
        return enabled;
    }

    private void onDisconnected(WebSocket socket) {
        connected = false;
        if (webSocket == socket) {
            webSocket = null;
        }
        Log.i(TAG, "TitleTrace manager disconnected fallback");
        listener.onMonitorPollingFallback();
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!enabled) return;
        int attempt = ++reconnectAttempts;
        long cap = Math.min(1000L * attempt, 8000L);
        long delayMs = Math.max(200L, (long) (Math.random() * cap));
        mainHandler.postDelayed(() -> {
            if (enabled) start();
        }, delayMs);
    }

    interface Listener {
        void onMonitorConnected();
        void onMonitorPollingFallback();
        void onMonitorSessions(JSONArray sessions);
        void onMonitorSession(JSONObject session);
        void onMonitorSessionClosed(String sessionId);
        void onMonitorDevices(JSONArray devices);
        void onMonitorError(String errorMsg);
    }
}
