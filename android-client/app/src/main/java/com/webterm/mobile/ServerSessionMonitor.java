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
    private final Handler mainHandler;
    private final ServerConfig server;
    private final Listener listener;

    private WebSocket webSocket;
    private boolean connected;
    private boolean enabled;
    private int reconnectAttempts;

    ServerSessionMonitor(OkHttpClient http, Handler mainHandler, ServerConfig server, Listener listener) {
        this.http = http;
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

        Request request = new Request.Builder()
            .url(urlString)
            .header("Cookie", server.getCookie() != null ? server.getCookie() : "")
            .build();

        webSocket = http.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                if (!enabled) {
                    webSocket.close(1000, "stale manager socket");
                    return;
                }
                connected = true;
                reconnectAttempts = 0;
                listener.onMonitorConnected();
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                if (!enabled) return;
                try {
                    JSONObject msg = new JSONObject(text);
                    String type = msg.optString("type");
                    if ("sessions".equals(type)) {
                        JSONArray arr = msg.optJSONArray("data");
                        listener.onMonitorSessions(arr != null ? arr : new JSONArray());
                    } else if ("session".equals(type)) {
                        JSONObject sessionData = msg.optJSONObject("data");
                        if (sessionData != null) listener.onMonitorSession(sessionData);
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
                onDisconnected(webSocket);
            }
        });
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
        listener.onMonitorPollingFallback();
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!enabled) return;
        int attempt = ++reconnectAttempts;
        long delayMs = Math.min(1000L * attempt, 8000L);
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
