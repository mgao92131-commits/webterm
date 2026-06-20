package com.webterm.mobile;

import android.app.Activity;
import android.os.Handler;
import android.widget.LinearLayout;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import okhttp3.OkHttpClient;

final class HomeServerCoordinator {
    private final Activity activity;
    private final OkHttpClient http;
    private final Handler mainHandler;
    private final WebTermApi api;
    private final TerminalCacheCoordinator terminalCache;
    private final Executor executor;
    private final Listener listener;
    private final HomeRefreshScheduler refreshScheduler;
    private final List<ServerGroupController> activeGroups = new ArrayList<>();
    private final Map<String, Boolean> directoryCollapsed = new HashMap<>();

    private SessionRecyclerAdapter sessionAdapter;

    HomeServerCoordinator(
        Activity activity,
        OkHttpClient http,
        Handler mainHandler,
        WebTermApi api,
        TerminalCacheCoordinator terminalCache,
        Executor executor,
        Listener listener
    ) {
        this.activity = activity;
        this.http = http;
        this.mainHandler = mainHandler;
        this.api = api;
        this.terminalCache = terminalCache;
        this.executor = executor;
        this.listener = listener;
        refreshScheduler = new HomeRefreshScheduler(
            mainHandler,
            new HomeRefreshScheduler.Listener() {
                @Override
                public boolean isHomeRefreshActive() {
                    return isRefreshActive();
                }

                @Override
                public List<ServerGroupController> activeGroups() {
                    return activeGroups;
                }

                @Override
                public void loadSessionsForGroup(ServerGroupController group) {
                    loadSessionsForAdapter(group.server, group.status, null);
                }
            }
        );
    }

    void attachSessionAdapter(SessionRecyclerAdapter sessionAdapter) {
        this.sessionAdapter = sessionAdapter;
        if (sessionAdapter != null) {
            sessionAdapter.setCollapseState(new SessionRecyclerAdapter.CollapseState() {
                @Override
                public boolean isCollapsed(String groupKey) {
                    return Boolean.TRUE.equals(directoryCollapsed.get(groupKey));
                }

                @Override
                public void setCollapsed(String groupKey, boolean collapsed) {
                    if (groupKey == null || groupKey.isEmpty()) return;
                    if (collapsed) {
                        directoryCollapsed.put(groupKey, true);
                    } else {
                        directoryCollapsed.remove(groupKey);
                    }
                }
            });
        }
    }

    void loadDeviceSessions(ServerConfig server, StatusIndicatorView status) {
        refreshScheduler.reset();
        if (sessionAdapter == null || server == null) return;
        Map<String, JSONArray> tempInMemorySessions = collectInMemorySessions();
        stopAllGroups();
        activeGroups.clear();

        ServerGroupController holder = new ServerGroupController(activity, http, mainHandler, server, null, status, new ServerGroupController.Listener() {
            @Override
            public boolean isActive(ServerGroupController controller) {
                return isActiveManagerHolder(controller);
            }

            @Override
            public void onRenderSessions(ServerConfig server, JSONArray sessions, LinearLayout subList) {
                hydrateCachedNames(server, sessions);
                renderServerSessions(server, sessions);
            }

            @Override
            public void onSessionClosed(String baseUrl, String sessionId) {
                listener.onRemoveCachedTerminal(baseUrl, sessionId);
            }

            @Override
            public void onScheduleFallbackRefresh() {
                refreshScheduler.scheduleInitial();
            }
        });
        activeGroups.add(holder);
        loadSessionsForAdapter(server, status, tempInMemorySessions);
        connectManagerWS(holder);
        refreshScheduler.scheduleInitial();
    }

    void resume() {
        for (ServerGroupController holder : activeGroups) {
            connectManagerWS(holder);
        }
        refreshScheduler.reset();
        refreshScheduler.scheduleInitial();
    }

    void pause() {
        refreshScheduler.cancel();
        stopAllGroups();
    }

    void destroy() {
        pause();
        activeGroups.clear();
        sessionAdapter = null;
    }

    private Map<String, JSONArray> collectInMemorySessions() {
        Map<String, JSONArray> tempInMemorySessions = new HashMap<>();
        for (ServerGroupController holder : activeGroups) {
            if (holder.getLastSessions() != null && holder.server != null && holder.server.getUrl() != null) {
                tempInMemorySessions.put(TerminalCacheScope.key(holder.server), holder.getLastSessions());
            }
        }
        return tempInMemorySessions;
    }

    private void loadSessionsForAdapter(ServerConfig server, StatusIndicatorView status, Map<String, JSONArray> tempInMemorySessions) {
        if (server.getUrl().isEmpty() || sessionAdapter == null) return;
        status.setStatus(StatusIndicatorView.Status.CONNECTING);

        boolean loadedFromMemory = renderTemporarySessionsIntoAdapter(server, tempInMemorySessions);
        if (!loadedFromMemory && terminalCache != null) {
            prepopulateCachedSessionsIntoAdapter(server);
        }

        if (server.getCookie() != null && !server.getCookie().isEmpty()) {
            fetchSessionsIntoAdapter(server, status);
        } else if (server.getPassword() != null && !server.getPassword().isEmpty()) {
            silentLoginAndFetchIntoAdapter(server, status);
        } else {
            status.setStatus(StatusIndicatorView.Status.DISCONNECTED);
            sessionAdapter.showError("需要登录");
        }
    }

    private boolean renderTemporarySessionsIntoAdapter(ServerConfig server, Map<String, JSONArray> tempInMemorySessions) {
        if (tempInMemorySessions == null) return false;
        JSONArray sessions = tempInMemorySessions.get(TerminalCacheScope.key(server));
        if (sessions == null) return false;
        hydrateCachedNames(server, sessions);
        ServerGroupController holder = findHolderForServer(server);
        if (holder != null) holder.setLastSessions(sessions);
        renderServerSessions(server, sessions);
        return true;
    }

    private void prepopulateCachedSessionsIntoAdapter(ServerConfig server) {
        if (terminalCache == null) return;
        executor.execute(() -> {
            List<TerminalDiskCache.Metadata> cached = terminalCache.getCachedSessionsForServer(server);
            if (cached == null || cached.isEmpty()) return;
            activity.runOnUiThread(() -> {
                if (sessionAdapter != null && !sessionAdapter.hasSessionRows()) {
                    renderServerSessions(server, CachedSessionMapper.toSessions(cached));
                }
            });
        });
    }

    private void fetchSessionsIntoAdapter(ServerConfig server, StatusIndicatorView status) {
        api.fetchSessions(server, new WebTermApi.SessionsCallback() {
            @Override
            public void onReady(JSONArray sessions) {
                activity.runOnUiThread(() -> {
                    status.setStatus(StatusIndicatorView.Status.CONNECTED);
                    ServerGroupController holder = findHolderForServer(server);
                    if (holder != null) holder.setLastSessions(sessions);
                    hydrateCachedNames(server, sessions);
                    renderServerSessions(server, sessions);
                });
            }

            @Override
            public void onError(int code, String message) {
                if (code == 401) {
                    if (server.getCookie() != null && !server.getCookie().isEmpty()) {
                        api.refresh(server.getUrl(), server.getCookie(), new WebTermApi.LoginCallback() {
                            @Override
                            public void onReady(String baseUrl, String cookie) {
                                server.setCookie(cookie);
                                listener.onAuthenticated(server);
                                activity.runOnUiThread(() -> fetchSessionsIntoAdapter(server, status));
                            }

                            @Override
                            public void onError(String refreshError) {
                                silentLoginAndFetchIntoAdapter(server, status);
                            }
                        });
                        return;
                    } else if (server.getPassword() != null && !server.getPassword().isEmpty()) {
                        silentLoginAndFetchIntoAdapter(server, status);
                        return;
                    }
                }
                showOfflineCachedSessionsIntoAdapter(server, status, code > 0 ? "HTTP " + code : message);
            }

            @Override
            public void onParseError(String message) {
                activity.runOnUiThread(() -> {
                    status.setStatus(StatusIndicatorView.Status.DISCONNECTED);
                    if (sessionAdapter != null && !sessionAdapter.hasSessionRows()) {
                        sessionAdapter.showError("JSON 解析错误");
                    }
                });
            }
        });
    }

    private void silentLoginAndFetchIntoAdapter(ServerConfig server, StatusIndicatorView status) {
        if (server.getPassword() == null || server.getPassword().isEmpty()) {
            showOfflineCachedSessionsIntoAdapter(server, status, "登录失败: 密码为空");
            return;
        }
        api.login(server.getUrl(), server.getCookie(), server.getUsername(), server.getPassword(), new WebTermApi.LoginCallback() {
            @Override
            public void onReady(String baseUrl, String cookie) {
                server.setCookie(cookie);
                listener.onAuthenticated(server);
                activity.runOnUiThread(() -> fetchSessionsIntoAdapter(server, status));
            }

            @Override
            public void onError(String message) {
                showOfflineCachedSessionsIntoAdapter(server, status, "登录失败: " + message);
            }
        });
    }

    private void showOfflineCachedSessionsIntoAdapter(ServerConfig server, StatusIndicatorView status, String errorMsg) {
        List<TerminalDiskCache.Metadata> cachedMetadata = terminalCache == null
            ? null
            : terminalCache.getCachedSessionsForServer(server);

        activity.runOnUiThread(() -> {
            status.setStatus(StatusIndicatorView.Status.DISCONNECTED);
            if (sessionAdapter != null && sessionAdapter.hasSessionRows()) return;

            if (cachedMetadata != null && !cachedMetadata.isEmpty()) {
                renderServerSessions(server, CachedSessionMapper.toSessions(cachedMetadata));
            } else if (sessionAdapter != null) {
                sessionAdapter.showError(errorMsg);
            }
        });
    }

    private void hydrateCachedNames(ServerConfig server, JSONArray sessions) {
        if (terminalCache == null || sessions == null) return;
        Map<String, CachedTerminal> memoryCaches = terminalCache.getMemorySessionsForServer(server);
        List<TerminalDiskCache.Metadata> diskCaches = terminalCache.getCachedSessionsForServer(server);
        Map<String, TerminalDiskCache.Metadata> diskMap = new HashMap<>();
        if (diskCaches != null) {
            for (TerminalDiskCache.Metadata meta : diskCaches) {
                if (meta.sessionId != null) diskMap.put(meta.sessionId, meta);
            }
        }

        for (int i = 0; i < sessions.length(); i++) {
            org.json.JSONObject session = sessions.optJSONObject(i);
            if (session == null) continue;
            String sessionId = session.optString("id");
            String sessionName = null;
            CachedTerminal memCached = memoryCaches.get(sessionId);
            if (memCached != null) {
                sessionName = memCached.sessionName;
            } else {
                TerminalDiskCache.Metadata diskCached = diskMap.get(sessionId);
                if (diskCached != null) {
                    sessionName = diskCached.sessionName;
                }
            }
            try {
                if (sessionName != null && session.optString("name", "").trim().isEmpty()) {
                    session.put("name", sessionName);
                }
            } catch (org.json.JSONException ignored) {
            }
        }
    }

    private void renderServerSessions(ServerConfig server, JSONArray sessions) {
        if (sessionAdapter == null) return;
        cleanupMissingCachedSessions(server, sessions);
        sessionAdapter.submitSessions(server, sessions);
    }

    private void cleanupMissingCachedSessions(ServerConfig server, JSONArray sessions) {
        Set<String> liveIdentities = new java.util.HashSet<>();
        for (int i = 0; sessions != null && i < sessions.length(); i++) {
            org.json.JSONObject session = sessions.optJSONObject(i);
            if (session == null) continue;
            String identity = SessionIdentity.value(
                session.optString("id"),
                session.optString("instanceId", ""),
                session.optString("createdAt", "")
            );
            if (!identity.isEmpty()) liveIdentities.add(identity);
        }
        listener.onRemoveMissingCachedSessionsForServer(server, liveIdentities);
    }

    private void connectManagerWS(ServerGroupController holder) {
        if (!listener.isHomeActive() || !hasAttachedTarget() || holder.server.getUrl().isEmpty()) {
            closeManagerWS(holder);
            return;
        }
        if (!activeGroups.contains(holder)) return;
        holder.start();
    }

    private void closeManagerWS(ServerGroupController holder) {
        holder.stop();
    }

    private void stopAllGroups() {
        for (ServerGroupController holder : activeGroups) {
            closeManagerWS(holder);
        }
    }

    private boolean isRefreshActive() {
        return listener.isHomeActive() && hasAttachedTarget();
    }

    private boolean isActiveManagerHolder(ServerGroupController holder) {
        return listener.isHomeActive()
            && holder.isEnabled()
            && hasAttachedTarget()
            && activeGroups.contains(holder);
    }

    private boolean hasAttachedTarget() {
        return sessionAdapter != null;
    }

    private ServerGroupController findHolderForServer(ServerConfig server) {
        for (ServerGroupController holder : activeGroups) {
            if (holder.server == server) return holder;
        }
        return null;
    }

    interface Listener {
        boolean isHomeActive();
        void onAuthenticated(ServerConfig server);
        void onRemoveCachedTerminal(String baseUrl, String sessionId);
        void onRemoveMissingCachedSessionsForServer(ServerConfig server, Set<String> liveSessionIdentities);
    }
}
