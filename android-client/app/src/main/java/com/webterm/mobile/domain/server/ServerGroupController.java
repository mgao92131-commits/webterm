package com.webterm.mobile.domain.server;

import android.app.Activity;
import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.webterm.core.api.WebTermUrls;
import com.webterm.mobile.data.cache.TerminalCacheScope;
import com.webterm.core.config.ServerConfig;
import com.webterm.mobile.domain.session.RelayMuxSessionRegistry;
import com.webterm.mobile.ui.home.SessionRowActions;
import com.webterm.mobile.ui.home.SessionRowHelper;
import com.webterm.mobile.ui.home.StatusIndicatorView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

public final class ServerGroupController {
    private static final String TAG = "ServerGroupController";

    final ServerConfig server;
    final LinearLayout subList;
    private StatusIndicatorView status;

    private final Activity activity;
    private final RelayMuxSessionRegistry relayMuxRegistry;
    private final Listener listener;
    private String muxIdentity;

    private ServerSessionMonitor monitor;
    private volatile JSONArray lastSessions;

    @AssistedInject
    public ServerGroupController(
        @Assisted Activity activity,
        RelayMuxSessionRegistry relayMuxRegistry,
        @Assisted ServerConfig server,
        @Assisted LinearLayout subList,
        @Assisted StatusIndicatorView status,
        @Assisted Listener listener
    ) {
        this.activity = activity;
        this.relayMuxRegistry = relayMuxRegistry;
        this.server = server;
        this.subList = subList;
        this.status = status;
        this.listener = listener;
    }

    @AssistedFactory
    interface Factory {
        ServerGroupController create(
            Activity activity,
            ServerConfig server,
            LinearLayout subList,
            StatusIndicatorView status,
            Listener listener
        );
    }

    void start() {
        if (server.getUrl().isEmpty()) {
            stop();
            return;
        }
        if (!listener.isActive(this)) return;
        String currentIdentity = muxIdentity(server);
        if (monitor != null && !currentIdentity.equals(muxIdentity)) {
            stop();
        }
        if (monitor == null) {
            monitor = new ServerSessionMonitor(relayMuxRegistry, server, new ServerSessionMonitor.Listener() {
                @Override
                public void onMonitorConnected() {
                    listener.onScheduleFallbackRefresh();
                    activity.runOnUiThread(() -> {
                        if (listener.isRenderActive(ServerGroupController.this)) markReady();
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
                    if (!listener.isActive(ServerGroupController.this)) return;
                    boolean belongs = belongsToCurrentServer(session);
                    Log.i(TAG, "TitleTrace monitor session id=" + session.optString("id") + " termTitle=" + session.optString("termTitle") + " belongs=" + belongs);
                    if (belongs) {
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
                        if (listener.isRenderActive(ServerGroupController.this)) markPending();
                    });
                    listener.onScheduleFallbackRefresh();
                }
            });
            muxIdentity = currentIdentity;
        }
        monitor.start();
    }

    void attachStatus(StatusIndicatorView status) {
        this.status = status;
    }

    StatusIndicatorView status() {
        return status;
    }

    void stop() {
        if (monitor != null) {
            monitor.stop();
            monitor = null;
        }
        muxIdentity = "";
    }

    boolean isConnected() {
        return monitor != null && monitor.isConnected();
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

        JSONObject oldSessionForCwd = null;
        String newCwd = normalizedCwd(newData);
        boolean found = false;
        for (int i = 0; i < lastSessions.length(); i++) {
            JSONObject session = lastSessions.optJSONObject(i);
            if (session != null && id.equals(session.optString("id"))) {
                oldSessionForCwd = session;
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
        final boolean insertedNew = !found;
        final boolean cwdChanged = shouldRerenderForCwd(oldSessionForCwd, newData);

        final String rawTermTitle = newData.optString("termTitle", "").trim();
        final String termTitle = rawTermTitle.isEmpty() ? "Terminal" : rawTermTitle;
        final String nameText = newData.optString("name", "").trim();
        Log.i(TAG, "TitleTrace upsert row id=" + id + " termTitle=" + termTitle + " name=" + nameText + " active=" + listener.isActive(this));
        if (cwdChanged) {
            listener.onSessionCwdChanged(server, id, newCwd);
        }
        activity.runOnUiThread(() -> {
            if (cwdChanged && listener.isRenderActive(this)) {
                listener.onRenderSessions(server, lastSessions, subList);
                return;
            }
            View rowView = activity.findViewById(android.R.id.content).findViewWithTag(id);
            if (rowView != null) {
                if (activity instanceof SessionRowActions) {
                    SessionRowHelper.updateSessionRow((SessionRowActions) activity, rowView, newData, server);
                }
            } else if (insertedNew && listener.isRenderActive(this)) {
                listener.onRenderSessions(server, lastSessions, subList);
            }
        });
    }

    static boolean shouldRerenderForCwd(JSONObject oldSession, JSONObject newData) {
        if (oldSession == null || newData == null) return false;
        String id = newData.optString("id");
        if (id.isEmpty() || !id.equals(oldSession.optString("id"))) return false;
        return !normalizedCwd(oldSession).equals(normalizedCwd(newData));
    }

    private static String normalizedCwd(JSONObject session) {
        return session == null ? "" : session.optString("cwd", "").trim();
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
        if (status == null) return;
        status.setStatus(isP2PConnected() ? StatusIndicatorView.Status.CONNECTED_P2P : StatusIndicatorView.Status.CONNECTED);
    }

    boolean isP2PConnected() {
        return monitor != null && monitor.isP2PConnected();
    }

    private void markPending() {
        if (status == null) return;
        status.setStatus(StatusIndicatorView.Status.CONNECTING);
    }

    interface Listener {
        boolean isActive(ServerGroupController controller);
        boolean isRenderActive(ServerGroupController controller);
        void onRenderSessions(ServerConfig server, JSONArray sessions, LinearLayout subList);
        void onSessionClosed(String baseUrl, String sessionId);
        void onSessionCwdChanged(ServerConfig server, String sessionId, String cwd);
        void onScheduleFallbackRefresh();
    }

    private static String muxIdentity(ServerConfig server) {
        if (server == null) return "";
        return WebTermUrls.normalizeBaseUrl(server.getUrl())
            + "\n" + (server.getCookie() == null ? "" : server.getCookie())
            + "\n" + (server.getDeviceId() == null ? "" : server.getDeviceId());
    }
}
