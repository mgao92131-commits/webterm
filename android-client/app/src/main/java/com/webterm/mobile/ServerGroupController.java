package com.webterm.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import okhttp3.OkHttpClient;

final class ServerGroupController {
    final ServerConfig server;
    final LinearLayout subList;
    final StatusIndicatorView status;

    private final Activity activity;
    private final OkHttpClient http;
    private final Handler mainHandler;
    private final Listener listener;

    private ServerSessionMonitor monitor;
    private JSONArray lastSessions;

    ServerGroupController(Activity activity, OkHttpClient http, Handler mainHandler, ServerConfig server, LinearLayout subList, StatusIndicatorView status, Listener listener) {
        this.activity = activity;
        this.http = http;
        this.mainHandler = mainHandler;
        this.server = server;
        this.subList = subList;
        this.status = status;
        this.listener = listener;
    }

    void start() {
        if (server.getUrl().isEmpty()) {
            stop();
            return;
        }
        if (!listener.isActive(this)) return;
        if (monitor == null) {
            monitor = new ServerSessionMonitor(http, mainHandler, server, new ServerSessionMonitor.Listener() {
                @Override
                public void onMonitorConnected() {
                    listener.onScheduleFallbackRefresh();
                    activity.runOnUiThread(() -> {
                        if (listener.isActive(ServerGroupController.this)) markReady();
                    });
                }

                @Override
                public void onMonitorSessions(JSONArray sessions) {
                    if (!listener.isActive(ServerGroupController.this)) return;
                    lastSessions = filterSessionsForCurrentServer(sessions);
                    activity.runOnUiThread(() -> listener.onRenderSessions(server, lastSessions, subList));
                }

                @Override
                public void onMonitorSession(JSONObject session) {
                    if (listener.isActive(ServerGroupController.this) && belongsToCurrentServer(session)) {
                        upsertLocalSession(session);
                    }
                }

                @Override
                public void onMonitorSessionClosed(String sessionId) {
                    if (listener.isActive(ServerGroupController.this) && belongsToCurrentServer(sessionId)) {
                        removeLocalSession(sessionId);
                    }
                }

                @Override
                public void onMonitorDevices(JSONArray devices) {
                    // 临时的具体设备组不需要处理总的设备列表发现，留空。
                }

                @Override
                public void onMonitorError(String errorMsg) {
                    // 仅记录异常或不处理
                }

                @Override
                public void onMonitorPollingFallback() {
                    activity.runOnUiThread(() -> {
                        if (listener.isActive(ServerGroupController.this)) markPending();
                    });
                    listener.onScheduleFallbackRefresh();
                }
            });
        }
        monitor.start();
    }

    void stop() {
        if (monitor != null) {
            monitor.stop();
        }
    }

    boolean isConnected() {
        return monitor != null && monitor.isConnected();
    }

    boolean isEnabled() {
        return monitor != null && monitor.isEnabled();
    }

    JSONArray getLastSessions() {
        return lastSessions;
    }

    void setLastSessions(JSONArray sessions) {
        lastSessions = filterSessionsForCurrentServer(sessions);
    }

    private JSONArray filterSessionsForCurrentServer(JSONArray sessions) {
        JSONArray filtered = new JSONArray();
        if (sessions == null) return filtered;
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (belongsToCurrentServer(session)) {
                filtered.put(session);
            }
        }
        return filtered;
    }

    private boolean belongsToCurrentServer(JSONObject session) {
        if (session == null) return false;
        return belongsToCurrentServer(session.optString("id"));
    }

    private boolean belongsToCurrentServer(String sessionId) {
        return TerminalCacheScope.matches(server, server.getUrl(), sessionId);
    }

    private void upsertLocalSession(JSONObject newData) {
        if (lastSessions == null) {
            lastSessions = new JSONArray();
        }
        String id = newData.optString("id");
        if (id == null) return;

        boolean found = false;
        for (int i = 0; i < lastSessions.length(); i++) {
            JSONObject session = lastSessions.optJSONObject(i);
            if (session != null && id.equals(session.optString("id"))) {
                try {
                    lastSessions.put(i, newData);
                } catch (JSONException ignored) {
                }
                found = true;
                break;
            }
        }
        if (!found) {
            lastSessions.put(newData);
        }
        activity.runOnUiThread(() -> listener.onRenderSessions(server, lastSessions, subList));
    }

    private void removeLocalSession(String id) {
        listener.onSessionClosed(server.getUrl(), id);
        if (lastSessions == null) return;
        JSONArray nextSessions = new JSONArray();
        for (int i = 0; i < lastSessions.length(); i++) {
            JSONObject session = lastSessions.optJSONObject(i);
            if (session != null && !id.equals(session.optString("id"))) {
                nextSessions.put(session);
            }
        }
        lastSessions = nextSessions;
        activity.runOnUiThread(() -> listener.onRenderSessions(server, lastSessions, subList));
    }

    private void markReady() {
        status.setStatus(StatusIndicatorView.Status.CONNECTED);
    }

    private void markPending() {
        status.setStatus(StatusIndicatorView.Status.CONNECTING);
    }

    interface Listener {
        boolean isActive(ServerGroupController controller);
        void onRenderSessions(ServerConfig server, JSONArray sessions, LinearLayout subList);
        void onSessionClosed(String baseUrl, String sessionId);
        void onScheduleFallbackRefresh();
    }
}
