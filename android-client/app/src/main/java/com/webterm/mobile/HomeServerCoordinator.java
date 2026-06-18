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
    private final SessionListRenderer sessionListRenderer;
    private final ServerSessionsLoader serverSessionsLoader;
    private final HomeRefreshScheduler refreshScheduler;
    private final List<ServerGroupController> activeGroups = new ArrayList<>();
    private final Map<String, Boolean> serverCollapsed = new HashMap<>();

    private LinearLayout sessionList;
    private SessionRecyclerAdapter sessionAdapter;

    HomeServerCoordinator(
        Activity activity,
        OkHttpClient http,
        Handler mainHandler,
        WebTermApi api,
        TerminalCacheCoordinator terminalCache,
        Executor executor,
        SessionRowActions rowActions,
        Listener listener
    ) {
        this.activity = activity;
        this.http = http;
        this.mainHandler = mainHandler;
        this.api = api;
        this.terminalCache = terminalCache;
        this.executor = executor;
        this.listener = listener;
        sessionListRenderer = new SessionListRenderer(activity, rowActions);
        serverSessionsLoader = new ServerSessionsLoader(
            activity,
            api,
            terminalCache,
            executor,
            new ServerSessionsLoader.Listener() {
                @Override
                public void onAuthenticated(ServerConfig server) {
                    listener.onAuthenticated(server);
                }

                @Override
                public void onSessionsLoaded(ServerConfig server, JSONArray sessions) {
                    ServerGroupController holder = findHolderForServer(server);
                    if (holder != null) holder.setLastSessions(sessions);
                }

                @Override
                public void onRenderSessions(ServerConfig server, JSONArray sessions, LinearLayout subList) {
                    renderServerSessions(server, sessions, subList);
                }
            }
        );
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
                    if (group.subList != null) {
                        loadSessionsForServer(group.server, group.subList, group.status, null);
                    } else {
                        loadSessionsForAdapter(group.server, group.status, null);
                    }
                }
            }
        );
    }

    void attachSessionList(LinearLayout sessionList) {
        this.sessionList = sessionList;
    }

    void attachSessionAdapter(SessionRecyclerAdapter sessionAdapter) {
        this.sessionAdapter = sessionAdapter;
    }

    void load(List<ServerConfig> servers) {
        refreshScheduler.reset();
        if (sessionList == null) return;
        Map<String, JSONArray> tempInMemorySessions = collectInMemorySessions();
        stopAllGroups();
        activeGroups.clear();
        sessionList.removeAllViews();

        if (servers.isEmpty()) {
            sessionList.addView(HomeScreenBuilder.emptyState(activity), new LinearLayout.LayoutParams(-1, -2));
            return;
        }

        for (ServerConfig server : servers) {
            renderServerGroup(server, tempInMemorySessions);
        }
        refreshScheduler.scheduleInitial();
    }

    void loadDeviceSessions(ServerConfig server, StatusIndicatorView status) {
        if (sessionAdapter != null) {
            loadDeviceSessionsIntoAdapter(server, status);
            return;
        }
        refreshScheduler.reset();
        if (sessionList == null || server == null) return;
        Map<String, JSONArray> tempInMemorySessions = collectInMemorySessions();
        stopAllGroups();
        activeGroups.clear();
        sessionList.removeAllViews();

        ServerGroupController holder = new ServerGroupController(activity, http, mainHandler, server, sessionList, status, new ServerGroupController.Listener() {
            @Override
            public boolean isActive(ServerGroupController controller) {
                return isActiveManagerHolder(controller);
            }

            @Override
            public void onRenderSessions(ServerConfig server, JSONArray sessions, LinearLayout subList) {
                renderServerSessions(server, sessions, subList);
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
        loadSessionsForServer(server, sessionList, status, tempInMemorySessions);
        connectManagerWS(holder);
        refreshScheduler.scheduleInitial();
    }

    private void loadDeviceSessionsIntoAdapter(ServerConfig server, StatusIndicatorView status) {
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
                renderServerSessions(server, sessions, subList);
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
        sessionList = null;
        sessionAdapter = null;
    }

    private Map<String, JSONArray> collectInMemorySessions() {
        Map<String, JSONArray> tempInMemorySessions = new HashMap<>();
        for (ServerGroupController holder : activeGroups) {
            if (holder.getLastSessions() != null && holder.server != null && holder.server.url != null) {
                tempInMemorySessions.put(TerminalCacheScope.key(holder.server), holder.getLastSessions());
            }
        }
        return tempInMemorySessions;
    }

    private void renderServerGroup(ServerConfig server, Map<String, JSONArray> tempInMemorySessions) {
        boolean collapsed = serverCollapsed.containsKey(server.id) && Boolean.TRUE.equals(serverCollapsed.get(server.id));
        HomeScreenBuilder.ServerGroupResult group = HomeScreenBuilder.buildServerGroup(
            activity,
            server,
            collapsed,
            (nextCollapsed) -> serverCollapsed.put(server.id, nextCollapsed),
            () -> listener.onCreateSession(server),
            () -> listener.onEditServer(server),
            () -> listener.onRemoveServer(server)
        );
        sessionList.addView(group.group, new LinearLayout.LayoutParams(-1, -2));

        ServerGroupController holder = new ServerGroupController(activity, http, mainHandler, server, group.subList, group.status, new ServerGroupController.Listener() {
            @Override
            public boolean isActive(ServerGroupController controller) {
                return isActiveManagerHolder(controller);
            }

            @Override
            public void onRenderSessions(ServerConfig server, JSONArray sessions, LinearLayout subList) {
                renderServerSessions(server, sessions, subList);
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
        loadSessionsForServer(server, group.subList, group.status, tempInMemorySessions);
        connectManagerWS(holder);
    }

    private void loadSessionsForServer(ServerConfig server, LinearLayout subList, StatusIndicatorView status, Map<String, JSONArray> tempInMemorySessions) {
        serverSessionsLoader.load(server, subList, status, tempInMemorySessions);
    }

    private void loadSessionsForAdapter(ServerConfig server, StatusIndicatorView status, Map<String, JSONArray> tempInMemorySessions) {
        if (server.url.isEmpty() || sessionAdapter == null) return;
        status.setStatus(StatusIndicatorView.Status.CONNECTING);

        boolean loadedFromMemory = renderTemporarySessionsIntoAdapter(server, tempInMemorySessions);
        if (!loadedFromMemory && terminalCache != null) {
            prepopulateCachedSessionsIntoAdapter(server);
        }

        if (server.cookie != null && !server.cookie.isEmpty()) {
            fetchSessionsIntoAdapter(server, status);
        } else if (server.password != null && !server.password.isEmpty()) {
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
        renderServerSessions(server, sessions, null);
        return true;
    }

    private void prepopulateCachedSessionsIntoAdapter(ServerConfig server) {
        if (terminalCache == null) return;
        executor.execute(() -> {
            List<TerminalDiskCache.Metadata> cached = terminalCache.getCachedSessionsForServer(server);
            if (cached == null || cached.isEmpty()) return;
            activity.runOnUiThread(() -> {
                if (sessionAdapter != null && !sessionAdapter.hasSessionRows()) {
                    renderServerSessions(server, CachedSessionMapper.toSessions(cached), null);
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
                    renderServerSessions(server, sessions, null);
                });
            }

            @Override
            public void onError(int code, String message) {
                if (code == 401 && server.password != null && !server.password.isEmpty()) {
                    silentLoginAndFetchIntoAdapter(server, status);
                    return;
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
        api.login(server.url, server.username, server.password, new WebTermApi.LoginCallback() {
            @Override
            public void onReady(String baseUrl, String cookie) {
                server.cookie = cookie;
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
                renderServerSessions(server, CachedSessionMapper.toSessions(cachedMetadata), null);
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
            String termTitle = null;
            String sessionName = null;
            CachedTerminal memCached = memoryCaches.get(sessionId);
            if (memCached != null) {
                termTitle = memCached.termTitle;
                sessionName = memCached.sessionName;
            } else {
                TerminalDiskCache.Metadata diskCached = diskMap.get(sessionId);
                if (diskCached != null) {
                    termTitle = diskCached.termTitle;
                    sessionName = diskCached.sessionName;
                }
            }
            try {
                if (termTitle != null) session.put("termTitle", termTitle);
                if (sessionName != null) session.put("name", sessionName);
            } catch (org.json.JSONException ignored) {
            }
        }
    }

    private void renderServerSessions(ServerConfig server, JSONArray sessions, LinearLayout subList) {
        if (subList == null && sessionAdapter != null) {
            cleanupMissingCachedSessions(server, sessions);
            sessionAdapter.submitSessions(server, sessions);
            return;
        }
        sessionListRenderer.render(server, sessions, subList, listener::onRemoveMissingCachedSessionsForServer);
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
        if (!listener.isHomeActive() || !hasAttachedTarget() || holder.server.url.isEmpty()) {
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
        return sessionList != null || sessionAdapter != null;
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
        void onCreateSession(ServerConfig server);
        void onEditServer(ServerConfig server);
        void onRemoveServer(ServerConfig server);
        void onRemoveCachedTerminal(String baseUrl, String sessionId);
        void onRemoveMissingCachedSessionsForServer(ServerConfig server, Set<String> liveSessionIdentities);
    }
}
