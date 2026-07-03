package com.webterm.mobile;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

final class ServerSessionMonitor {
    private static final String TAG = "ServerSessionMonitor";

    private final RelayMuxSessionRegistry relayMuxRegistry;
    private final ServerConfig server;
    private final Listener listener;

    private RelayMuxSessionManager relayMuxSession;
    private boolean connected;
    private boolean enabled;
    private boolean channelOpened;

    ServerSessionMonitor(RelayMuxSessionRegistry relayMuxRegistry, ServerConfig server, Listener listener) {
        this.relayMuxRegistry = relayMuxRegistry;
        this.server = server;
        this.listener = listener;
    }

    void start() {
        if (server.getUrl().isEmpty()) {
            stop();
            return;
        }
        enabled = true;
        if (relayMuxSession == null) {
            relayMuxSession = relayMuxRegistry.forDevice(
                server.getUrl(),
                server.getCookie() != null ? server.getCookie() : "",
                server.getDeviceId()
            );
        }
        if (channelOpened) {
            relayMuxSession.start();
            return;
        }
        String managerId = managerChannelId();
        Log.i(TAG, "TitleTrace manager start deviceId=" + server.getDeviceId());
        relayMuxSession.openChannel(managerId, "/ws/sessions", null, new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) {
                if (!managerId.equals(channelId)) return;
                connected = true;
                Log.i(TAG, "TitleTrace manager open deviceId=" + server.getDeviceId());
                listener.onMonitorConnected();
            }

            @Override public void onError(String channelId, String message) {
                if (!managerId.equals(channelId)) return;
                connected = false;
                channelOpened = false;
                listener.onMonitorError(message);
                onMuxDisconnected(message);
            }

            @Override public void onData(String channelId, byte[] payload, boolean binary) {
                if (!managerId.equals(channelId) || binary) return;
                dispatchMessage(new String(payload, StandardCharsets.UTF_8), listener, server.getDeviceId());
            }

            @Override public void onMuxDisconnected(String reason) {
                ServerSessionMonitor.this.onMuxDisconnected(reason);
            }
        });
        channelOpened = true;
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

    void stop() {
        enabled = false;
        connected = false;
        channelOpened = false;
        if (relayMuxSession != null) {
            relayMuxSession.closeChannel(managerChannelId());
            relayMuxRegistry.releaseIfIdle(relayMuxSession);
            relayMuxSession = null;
        }
    }

    boolean isConnected() {
        return connected || (relayMuxSession != null && relayMuxSession.isConnected());
    }

    boolean isP2PConnected() {
        return relayMuxSession != null && relayMuxSession.isP2PConnected();
    }

    boolean isEnabled() {
        return enabled;
    }

    private void onMuxDisconnected(String reason) {
        if (!enabled) return;
        connected = false;
        Log.i(TAG, "TitleTrace mux manager disconnected reason=" + reason);
        listener.onMonitorPollingFallback();
    }

    private String managerChannelId() {
        String deviceId = server.getDeviceId() == null ? "" : server.getDeviceId();
        return "manager:" + deviceId;
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
