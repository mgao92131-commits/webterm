package com.webterm.mobile.domain.server;

import android.app.Activity;
import android.os.Handler;
import android.widget.LinearLayout;

import com.webterm.core.api.WebTermApi;
import com.webterm.core.api.WebTermUrls;
import com.webterm.mobile.data.cache.CachedSessionMapper;
import com.webterm.mobile.data.cache.CachedTerminal;
import com.webterm.mobile.data.cache.TerminalCacheCoordinator;
import com.webterm.mobile.data.cache.TerminalCacheScope;
import com.webterm.mobile.data.cache.TerminalDiskCache;
import com.webterm.core.config.ServerConfig;
import com.webterm.mobile.data.repository.SessionRepository;
import com.webterm.mobile.domain.session.RelayMuxSessionManager;
import com.webterm.mobile.domain.session.RelayMuxSessionRegistry;
import com.webterm.mobile.domain.session.SessionIdentity;
import com.webterm.mobile.ui.home.SessionRecyclerAdapter;
import com.webterm.mobile.ui.home.StatusIndicatorView;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

public final class HomeServerCoordinator {
    private final Activity activity;
    private final RelayMuxSessionRegistry relayMuxRegistry;
    private final TerminalCacheCoordinator terminalCache;
    private final Executor executor;
    private final SessionRepository sessionRepository;
    private final Listener listener;
    private final HomeRefreshScheduler refreshScheduler;
    private final List<ServerGroupController> activeGroups = new ArrayList<>();
    private final Map<String, Boolean> directoryCollapsed = new HashMap<>();

    private SessionRecyclerAdapter sessionAdapter;
    private volatile int sessionLoadGeneration;

    @AssistedInject
    public HomeServerCoordinator(
        @Assisted Activity activity,
        Handler mainHandler,
        RelayMuxSessionRegistry relayMuxRegistry,
        WebTermApi api,
        TerminalCacheCoordinator terminalCache,
        Executor executor,
        @Assisted Listener listener
    ) {
        this.activity = activity;
        this.relayMuxRegistry = relayMuxRegistry;
        this.terminalCache = terminalCache;
        this.executor = executor;
        this.sessionRepository = new SessionRepository(api, terminalCache, executor);
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
                    loadSessionsForAdapter(group.server, group.status(), null);
                }
            }
        );
    }

    @AssistedFactory
    public interface Factory {
        HomeServerCoordinator create(Activity activity, Listener listener);
    }

    public void attachSessionAdapter(SessionRecyclerAdapter sessionAdapter) {
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

    public void loadDeviceSessions(ServerConfig server, StatusIndicatorView status) {
        refreshScheduler.reset();
        if (sessionAdapter == null || server == null) return;
        Map<String, JSONArray> tempInMemorySessions = collectInMemorySessions();
        ServerGroupController holder = findHolderForServer(server);
        if (holder != null) {
            holder.attachStatus(status);
            loadSessionsForAdapter(server, status, tempInMemorySessions);
            connectManagerWS(holder);
            refreshScheduler.scheduleInitial();
            return;
        }

        detachGroups();

        holder = new ServerGroupController(activity, relayMuxRegistry, server, null, status, new ServerGroupController.Listener() {
            @Override
            public boolean isActive(ServerGroupController controller) {
                return isServerContextActive(controller);
            }

            @Override
            public boolean isRenderActive(ServerGroupController controller) {
                return isRenderTargetActive(controller.server);
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
            public void onSessionCwdChanged(ServerConfig server, String sessionId, String cwd) {
                listener.onSessionCwdChanged(server, sessionId, cwd);
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

    public void resume() {
        for (ServerGroupController holder : activeGroups) {
            connectManagerWS(holder);
        }
        refreshScheduler.reset();
        refreshScheduler.scheduleInitial();
    }

    public void pauseUi() {
        sessionLoadGeneration++;
        refreshScheduler.cancel();
    }

    public void detach() {
        pauseUi();
        detachGroups();
    }

    public void destroy() {
        detach();
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
        int loadGeneration = ++sessionLoadGeneration;
        status.setStatus(StatusIndicatorView.Status.CONNECTING);

        boolean loadedFromMemory = renderTemporarySessionsIntoAdapter(server, tempInMemorySessions);
        if (!loadedFromMemory && terminalCache != null) {
            prepopulateCachedSessionsIntoAdapter(server, loadGeneration);
        }

        loadRepositorySessionsIntoAdapter(server, status, loadGeneration);
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

    private void prepopulateCachedSessionsIntoAdapter(ServerConfig server, int loadGeneration) {
        if (terminalCache == null) return;
        executor.execute(() -> {
            List<TerminalDiskCache.Metadata> cached = terminalCache.getCachedSessionsForServer(server);
            if (cached == null || cached.isEmpty()) return;
            activity.runOnUiThread(() -> {
                if (isLoadActive(server, loadGeneration) && sessionAdapter != null && !sessionAdapter.hasSessionRows()) {
                    renderServerSessions(server, CachedSessionMapper.toSessions(cached));
                }
            });
        });
    }

    private void loadRepositorySessionsIntoAdapter(ServerConfig server, StatusIndicatorView status, int loadGeneration) {
        sessionRepository.loadSessions(server, new SessionRepository.Callback() {
            @Override
            public void onAuthenticated(ServerConfig server) {
                if (isLoadActive(server, loadGeneration)) {
                    listener.onAuthenticated(server);
                }
            }

            @Override
            public void onResult(SessionRepository.Result result) {
                activity.runOnUiThread(() -> {
                    if (isLoadActive(server, loadGeneration)) {
                        applySessionResult(server, status, result);
                    }
                });
            }
        });
    }

    private void applySessionResult(ServerConfig server, StatusIndicatorView status, SessionRepository.Result result) {
        if (result == null) return;
        if (result.kind == SessionRepository.Result.Kind.ONLINE) {
            ServerGroupController holder = findHolderForServer(server);
            boolean isP2P = holder != null && holder.isP2PConnected();
            status.setStatus(isP2P ? StatusIndicatorView.Status.CONNECTED_P2P : StatusIndicatorView.Status.CONNECTED);
            if (holder != null) holder.setLastSessions(result.sessions);
            hydrateCachedNames(server, result.sessions);
            renderServerSessions(server, result.sessions);
            return;
        }

        status.setStatus(StatusIndicatorView.Status.DISCONNECTED);
        if (sessionAdapter != null && sessionAdapter.hasSessionRows()) return;

        if (result.kind == SessionRepository.Result.Kind.OFFLINE_CACHE) {
            renderServerSessions(server, result.sessions);
        } else if (sessionAdapter != null) {
            sessionAdapter.showError(result.message);
        }
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
        if (!isRenderTargetActive(server)) return;
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
        if (!isServerContextActive(holder) || holder.server.getUrl().isEmpty()) {
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

    private void detachGroups() {
        stopAllGroups();
        activeGroups.clear();
    }

    private boolean isRefreshActive() {
        return listener.isHomeActive() && hasAttachedTarget();
    }

    private boolean isServerContextActive(ServerGroupController holder) {
        return holder != null
            && activeGroups.contains(holder)
            && listener.isServerContextActive(holder.server);
    }

    private boolean isRenderTargetActive(ServerConfig server) {
        return listener.isHomeActive()
            && hasAttachedTarget()
            && findHolderForServer(server) != null;
    }

    private boolean isLoadActive(ServerConfig server, int loadGeneration) {
        return sessionLoadGeneration == loadGeneration
            && isRenderTargetActive(server);
    }

    private boolean hasAttachedTarget() {
        return sessionAdapter != null;
    }

    private ServerGroupController findHolderForServer(ServerConfig server) {
        for (ServerGroupController holder : activeGroups) {
            if (sameMuxTarget(holder.server, server)) return holder;
        }
        return null;
    }

    private static boolean sameMuxTarget(ServerConfig a, ServerConfig b) {
        if (a == null || b == null) return false;
        return WebTermUrls.normalizeBaseUrl(a.getUrl()).equals(WebTermUrls.normalizeBaseUrl(b.getUrl()))
            && RelayMuxSessionManager.safeEquals(a.getCookie(), b.getCookie())
            && RelayMuxSessionManager.safeEquals(a.getDeviceId(), b.getDeviceId());
    }

    public interface Listener {
        boolean isHomeActive();
        boolean isServerContextActive(ServerConfig server);
        void onAuthenticated(ServerConfig server);
        void onRemoveCachedTerminal(String baseUrl, String sessionId);
        void onRemoveMissingCachedSessionsForServer(ServerConfig server, Set<String> liveSessionIdentities);
        void onSessionCwdChanged(ServerConfig server, String sessionId, String cwd);
    }
}
