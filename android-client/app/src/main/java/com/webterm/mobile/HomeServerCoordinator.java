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
    private final Listener listener;
    private final SessionListRenderer sessionListRenderer;
    private final ServerSessionsLoader serverSessionsLoader;
    private final HomeRefreshScheduler refreshScheduler;
    private final List<ServerGroupController> activeGroups = new ArrayList<>();
    private final Map<String, Boolean> serverCollapsed = new HashMap<>();

    private LinearLayout sessionList;

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
                    loadSessionsForServer(group.server, group.subList, group.status, null);
                }
            }
        );
    }

    void attachSessionList(LinearLayout sessionList) {
        this.sessionList = sessionList;
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
    }

    private Map<String, JSONArray> collectInMemorySessions() {
        Map<String, JSONArray> tempInMemorySessions = new HashMap<>();
        for (ServerGroupController holder : activeGroups) {
            if (holder.getLastSessions() != null && holder.server != null && holder.server.url != null) {
                tempInMemorySessions.put(WebTermUrls.normalizeBaseUrl(holder.server.url), holder.getLastSessions());
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

    private void renderServerSessions(ServerConfig server, JSONArray sessions, LinearLayout subList) {
        sessionListRenderer.render(server, sessions, subList, listener::onRemoveMissingCachedSessionsForServer);
    }

    private void connectManagerWS(ServerGroupController holder) {
        if (!listener.isHomeActive() || sessionList == null || holder.server.url.isEmpty()) {
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
        return listener.isHomeActive() && sessionList != null;
    }

    private boolean isActiveManagerHolder(ServerGroupController holder) {
        return listener.isHomeActive()
            && holder.isEnabled()
            && sessionList != null
            && activeGroups.contains(holder);
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
        void onRemoveMissingCachedSessionsForServer(String baseUrl, Set<String> liveSessionIdentities);
    }
}
