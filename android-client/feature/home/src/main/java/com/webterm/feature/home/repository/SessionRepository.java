package com.webterm.feature.home.repository;

import android.os.Handler;

import com.webterm.core.api.WebTermApi;
import com.webterm.core.api.WebTermUrls;
import com.webterm.core.cache.CachedSessionMapper;
import com.webterm.core.cache.TerminalCacheCoordinator;
import com.webterm.core.cache.TerminalDiskCache;
import com.webterm.core.config.ServerConfig;
import com.webterm.core.api.SessionIds;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import javax.inject.Inject;
import javax.inject.Singleton;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/**
 * Single source of truth for a server's terminal session list.
 * Holds an in-memory cache, exposes LiveData observers, manages the WS manager
 * channel, and falls back to HTTP when the WS is disconnected.
 */
@Singleton
public final class SessionRepository {

    private static final long FALLBACK_INITIAL_DELAY_MS = 3000L;
    private static final long FALLBACK_MAX_DELAY_MS = 60000L;
    private static final long WS_GRACE_PERIOD_MS = 30000L;

    private final Api api;
    private final Cache cache;
    private final Executor executor;
    private final ServerSessionDataSource wsSource;
    private final SessionListCache sessionCache;
    private final TerminalCacheCoordinator terminalCache;
    private final Handler mainHandler;

    private final Map<String, ServerSubscription> subscriptions = new HashMap<>();
    private final MutableLiveData<ServerConfig> authEvents = new MutableLiveData<>();

    @Inject
    public SessionRepository(WebTermApi api,
                             TerminalCacheCoordinator terminalCache,
                             Executor executor,
                             ServerSessionDataSource wsSource,
                             SessionListCache sessionCache,
                             Handler mainHandler) {
        this(new WebTermApiAdapter(api),
            new TerminalCacheAdapter(terminalCache),
            executor,
            wsSource,
            sessionCache,
            terminalCache,
            mainHandler);
    }

    SessionRepository(Api api,
                      Cache cache,
                      Executor executor,
                      ServerSessionDataSource wsSource,
                      SessionListCache sessionCache,
                      TerminalCacheCoordinator terminalCache,
                      Handler mainHandler) {
        this.api = api;
        this.cache = cache;
        this.executor = executor;
        this.wsSource = wsSource;
        this.sessionCache = sessionCache;
        this.terminalCache = terminalCache;
        this.mainHandler = mainHandler;
    }

    /**
     * Observe the session list for a server. The returned LiveData emits the
     * latest cached value immediately, then keeps the value up to date via WS.
     * When the last observer goes inactive the WS channel is closed after a
     * short grace period.
     */
    public LiveData<SessionListResult> observeSessions(ServerConfig server) {
        return subscriptionFor(server).liveData;
    }

    /**
     * Observe cookie refresh events. Emits the server whose cookie changed.
     * Consumers should persist the updated server configuration.
     */
    public LiveData<ServerConfig> observeAuthEvents() {
        return authEvents;
    }

    /**
     * Trigger an explicit HTTP refresh for a server.
     */
    public void refresh(ServerConfig server) {
        loadHttp(server);
    }

    /**
     * Legacy callback-based load. Kept for one-shot HTTP loads during migration.
     */
    public void loadSessions(ServerConfig server, Callback callback) {
        if (server.getCookie() != null && !server.getCookie().isEmpty()) {
            fetchSessions(server, callback);
            return;
        }
        if (server.getPassword() != null && !server.getPassword().isEmpty()) {
            loginAndFetch(server, callback);
            return;
        }
        callback.onResult(Result.authRequired("需要登录"));
    }

    // ── Subscription / Lifecycle ─────────────────────────────────────

    private ServerSubscription subscriptionFor(ServerConfig server) {
        String key = key(server);
        ServerSubscription sub = subscriptions.get(key);
        if (sub == null) {
            sub = new ServerSubscription(server);
            subscriptions.put(key, sub);
        }
        return sub;
    }

    private final class ServerSubscription implements ServerSessionDataSource.Listener {
        final ServerConfig server;
        final ServerSessionsLiveData liveData = new ServerSessionsLiveData(this);
        final AtomicLong hydrateGeneration = new AtomicLong(0);
        int observerCount = 0;
        boolean wsStarted = false;
        long fallbackDelayMs = FALLBACK_INITIAL_DELAY_MS;
        final Runnable fallbackRunnable = this::runFallbackRefresh;

        ServerSubscription(ServerConfig server) {
            this.server = server;
        }

        void onObserverActive() {
            observerCount++;
            cancelGracefulStop();
            if (observerCount == 1) {
                startObserving();
            }
        }

        void onObserverInactive() {
            observerCount--;
            if (observerCount == 0) {
                scheduleGracefulStop();
            }
        }

        void startObserving() {
            SessionListCache.Snapshot snapshot = sessionCache.get(server);
            if (snapshot != null) {
                liveData.setValueIfNeeded(toResult(snapshot));
            } else {
                liveData.setValueIfNeeded(new SessionListResult(
                    new JSONArray(),
                    SessionListResult.State.CONNECTING,
                    null,
                    false
                ));
            }
            cancelFallback();
            if (!wsStarted) {
                wsStarted = true;
                wsSource.start(server, this);
            }
        }

        void stopObserving() {
            cancelFallback();
            if (wsStarted) {
                wsStarted = false;
                wsSource.stop(server);
            }
            hydrateGeneration.incrementAndGet();
        }

        private void scheduleGracefulStop() {
            cancelGracefulStop();
            mainHandler.postDelayed(gracefulStopRunnable, WS_GRACE_PERIOD_MS);
        }

        private void cancelGracefulStop() {
            mainHandler.removeCallbacks(gracefulStopRunnable);
        }

        private final Runnable gracefulStopRunnable = () -> {
            if (observerCount > 0) return;
            stopObserving();
            removeFromRegistry();
        };

        private void removeFromRegistry() {
            subscriptions.remove(key(server));
        }

        void setLoading(boolean loading) {
            SessionListResult current = liveData.getValue();
            if (current == null) return;
            if (current.isLoading == loading) return;
            liveData.setValue(new SessionListResult(
                current.sessions,
                current.state,
                current.errorMessage,
                loading
            ));
        }

        /**
         * Hydrates session names off the main thread (the disk cache may do IO)
         * and emits the result on the main thread. A generation counter drops
         * stale results when a newer update arrives or the subscription stops.
         */
        void hydrateAndEmit(JSONArray sessions, SessionListResult.State state, String errorMessage) {
            final long generation = hydrateGeneration.incrementAndGet();
            final JSONArray input = copySessions(sessions);
            if (executor == null) {
                emitIfCurrent(generation, hydrateCachedNames(server, input), state, errorMessage);
                return;
            }
            executor.execute(() -> {
                JSONArray hydrated = hydrateCachedNames(server, input);
                mainHandler.post(() -> emitIfCurrent(generation, hydrated, state, errorMessage));
            });
        }

        void emitIfCurrent(long generation, JSONArray sessions, SessionListResult.State state, String errorMessage) {
            if (generation != hydrateGeneration.get()) return;
            sessionCache.put(server, new SessionListCache.Snapshot(
                copySessions(sessions),
                System.currentTimeMillis(),
                toCacheState(state),
                errorMessage
            ));
            liveData.setValue(new SessionListResult(sessions, state, errorMessage, false));
        }

        // ── WS callbacks ─────────────────────────────────────────────

        @Override
        public void onConnected() {
            cancelFallback();
            updateState(SessionListResult.State.CONNECTED);
            // Sync with HTTP after reconnect to ensure we did not miss WS pushes
            // while the channel was reconnecting or the observer was inactive.
            SessionListCache.Snapshot snapshot = sessionCache.get(server);
            if (snapshot == null || System.currentTimeMillis() - snapshot.timestamp > 2000L) {
                loadHttp(server);
            }
        }

        @Override
        public void onConnecting() {
            updateState(SessionListResult.State.CONNECTING);
        }

        @Override
        public void onDisconnected(String reason) {
            if (reason != null && (reason.contains("401") || reason.contains("Unauthorized"))) {
                if (wsStarted) {
                    wsStarted = false;
                    wsSource.stop(server);
                }
                cancelFallback();
                updateState(SessionListResult.State.AUTH_REQUIRED);
                loadHttp(server);
            } else {
                updateState(SessionListResult.State.DISCONNECTED);
                scheduleFallback();
            }
        }

        @Override
        public void onSessions(JSONArray sessions) {
            JSONArray filtered = filterAndNormalize(server, sessions);
            hydrateAndEmit(filtered, SessionListResult.State.CONNECTED, null);
        }

        @Override
        public void onSession(JSONObject session) {
            if (!belongsToCurrentServer(server, session)) return;
            JSONObject normalized = normalizeSession(server, session);
            if (normalized == null) return;
            SessionListCache.Snapshot snapshot = sessionCache.get(server);
            JSONArray sessions = snapshot != null && snapshot.sessions != null
                ? copySessions(snapshot.sessions)
                : new JSONArray();
            upsertSession(sessions, normalized);
            hydrateAndEmit(sessions, SessionListResult.State.CONNECTED, null);
        }

        @Override
        public void onSessionClosed(String sessionId) {
            if (!belongsToCurrentServer(server, sessionId)) return;
            String normalizedId = normalizeSessionId(server, sessionId);
            SessionListCache.Snapshot snapshot = sessionCache.get(server);
            if (snapshot == null || snapshot.sessions == null) return;
            JSONArray next = new JSONArray();
            for (int i = 0; i < snapshot.sessions.length(); i++) {
                JSONObject s = snapshot.sessions.optJSONObject(i);
                if (s != null && !normalizedId.equals(s.optString("id"))) {
                    next.put(s);
                }
            }
            putSnapshot(next, SessionListResult.State.CONNECTED);
        }

        // ── Fallback ─────────────────────────────────────────────────

        private void scheduleFallback() {
            cancelFallback();
            if (observerCount <= 0) return;
            mainHandler.postDelayed(fallbackRunnable, fallbackDelayMs);
        }

        private void cancelFallback() {
            mainHandler.removeCallbacks(fallbackRunnable);
            fallbackDelayMs = FALLBACK_INITIAL_DELAY_MS;
        }

        private void runFallbackRefresh() {
            if (observerCount <= 0) return;
            loadHttp(server);
            fallbackDelayMs = Math.min(FALLBACK_MAX_DELAY_MS, Math.max(FALLBACK_INITIAL_DELAY_MS, fallbackDelayMs * 2));
            scheduleFallback();
        }

        private void putSnapshot(JSONArray sessions, SessionListResult.State state) {
            sessionCache.put(server, new SessionListCache.Snapshot(
                copySessions(sessions),
                System.currentTimeMillis(),
                toCacheState(state),
                null
            ));
            liveData.setValue(new SessionListResult(sessions, state, null, false));
        }

        private void updateState(SessionListResult.State state) {
            SessionListResult current = liveData.getValue();
            JSONArray sessions = current != null ? current.sessions : new JSONArray();
            String error = current != null ? current.errorMessage : null;
            sessionCache.put(server, new SessionListCache.Snapshot(
                copySessions(sessions),
                System.currentTimeMillis(),
                toCacheState(state),
                error
            ));
            liveData.setValue(new SessionListResult(sessions, state, error, false));
        }
    }

    private final class ServerSessionsLiveData extends MutableLiveData<SessionListResult> {
        private final ServerSubscription subscription;

        ServerSessionsLiveData(ServerSubscription subscription) {
            this.subscription = subscription;
        }

        @Override
        protected void onActive() {
            super.onActive();
            subscription.onObserverActive();
        }

        @Override
        protected void onInactive() {
            super.onInactive();
            subscription.onObserverInactive();
        }

        void setValueIfNeeded(SessionListResult value) {
            if (getValue() == null) {
                setValue(value);
            }
        }
    }

    // ── HTTP loading ─────────────────────────────────────────────────

    private void loadHttp(ServerConfig server) {
        ServerSubscription sub = subscriptions.get(key(server));
        if (sub != null) {
            sub.setLoading(true);
        }
        loadSessions(server, new Callback() {
            @Override
            public void onAuthenticated(ServerConfig server) {
                authEvents.postValue(server);
            }

            @Override
            public void onResult(Result result) {
                mainHandler.post(() -> applyHttpResult(server, result));
            }
        });
    }

    private void applyHttpResult(ServerConfig server, Result result) {
        ServerSubscription sub = subscriptions.get(key(server));
        SessionListResult.State state;
        String errorMessage = null;

        switch (result.kind) {
            case ONLINE:
                state = SessionListResult.State.CONNECTED;
                JSONArray onlineSessions = result.sessions != null ? result.sessions : new JSONArray();
                if (sub != null) {
                    sub.hydrateAndEmit(onlineSessions, state, null);
                    if (sub.observerCount > 0 && !sub.wsStarted) {
                        sub.startObserving();
                    }
                } else {
                    sessionCache.put(server, new SessionListCache.Snapshot(
                        copySessions(onlineSessions),
                        System.currentTimeMillis(),
                        toCacheState(state),
                        null
                    ));
                }
                return;
            case OFFLINE_CACHE:
                state = SessionListResult.State.DISCONNECTED;
                break;
            case AUTH_REQUIRED:
                state = SessionListResult.State.AUTH_REQUIRED;
                errorMessage = result.message;
                break;
            case ERROR:
            case PARSE_ERROR:
            default:
                state = SessionListResult.State.ERROR;
                errorMessage = result.message;
                break;
        }

        SessionListCache.Snapshot current = sessionCache.get(server);
        JSONArray sessions = result.sessions;
        if ((sessions == null || sessions.length() == 0)
            && current != null && current.sessions != null && current.sessions.length() > 0
            && (state == SessionListResult.State.DISCONNECTED || state == SessionListResult.State.ERROR)) {
            sessions = current.sessions;
        }
        if (sessions == null) sessions = new JSONArray();

        sessionCache.put(server, new SessionListCache.Snapshot(
            copySessions(sessions),
            System.currentTimeMillis(),
            toCacheState(state),
            errorMessage
        ));
        if (sub != null) {
            sub.liveData.setValue(new SessionListResult(sessions, state, errorMessage, false));
        }
    }

    private void fetchSessions(ServerConfig server, Callback callback) {
        api.fetchSessions(server, new WebTermApi.SessionsCallback() {
            @Override
            public void onReady(JSONArray sessions) {
                callback.onResult(Result.online(sessions));
            }

            @Override
            public void onError(int code, String message) {
                if (code == 401) {
                    if (server.getCookie() != null && !server.getCookie().isEmpty()) {
                        refreshAndFetch(server, callback);
                        return;
                    }
                    if (server.getPassword() != null && !server.getPassword().isEmpty()) {
                        loginAndFetch(server, callback);
                        return;
                    }
                }
                offlineFallback(server, code > 0 ? "HTTP " + code : message, callback);
            }

            @Override
            public void onParseError(String message) {
                callback.onResult(Result.parseError("JSON 解析错误"));
            }
        });
    }

    private void refreshAndFetch(ServerConfig server, Callback callback) {
        api.refresh(server.getUrl(), server.getCookie(), new WebTermApi.LoginCallback() {
            @Override
            public void onReady(String baseUrl, String cookie) {
                server.setCookie(cookie);
                callback.onAuthenticated(server);
                fetchSessions(server, callback);
            }

            @Override
            public void onError(String refreshError) {
                loginAndFetch(server, callback);
            }
        });
    }

    private void loginAndFetch(ServerConfig server, Callback callback) {
        if (server.getPassword() == null || server.getPassword().isEmpty()) {
            offlineFallback(server, "登录失败: 密码为空", callback);
            return;
        }
        api.login(server.getUrl(), server.getCookie(), server.getUsername(), server.getPassword(), new WebTermApi.LoginCallback() {
            @Override
            public void onReady(String baseUrl, String cookie) {
                server.setCookie(cookie);
                callback.onAuthenticated(server);
                fetchSessions(server, callback);
            }

            @Override
            public void onError(String message) {
                offlineFallback(server, "登录失败: " + message, callback);
            }
        });
    }

    private void offlineFallback(ServerConfig server, String errorMessage, Callback callback) {
        if (cache == null || executor == null) {
            callback.onResult(Result.error(errorMessage));
            return;
        }
        executor.execute(() -> {
            List<TerminalDiskCache.Metadata> metadata = cache.cachedSessionsForServer(server);
            if (metadata != null && !metadata.isEmpty()) {
                callback.onResult(Result.offlineCache(CachedSessionMapper.toSessions(metadata), errorMessage));
            } else {
                callback.onResult(Result.error(errorMessage));
            }
        });
    }

    // ── Session helpers ──────────────────────────────────────────────

    private static JSONArray filterAndNormalize(ServerConfig server, JSONArray sessions) {
        JSONArray normalized = new JSONArray();
        if (sessions == null) return normalized;
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session == null) continue;
            if (!belongsToCurrentServer(server, session)) continue;
            JSONObject normalizedSession = normalizeSession(server, session);
            if (normalizedSession != null) {
                normalized.put(normalizedSession);
            }
        }
        return normalized;
    }

    private static boolean belongsToCurrentServer(ServerConfig server, JSONObject session) {
        if (session == null) return false;
        return belongsToCurrentServer(server, session.optString("id"));
    }

    private static boolean belongsToCurrentServer(ServerConfig server, String sessionId) {
        return com.webterm.core.cache.TerminalCacheScope.matches(server, server.getUrl(), sessionId);
    }

    private static JSONObject normalizeSession(ServerConfig server, JSONObject session) {
        if (session == null) return null;
        String id = session.optString("id");
        String normalizedId = normalizeSessionId(server, id);
        if (normalizedId.equals(id)) return session;
        try {
            JSONObject copy = new JSONObject(session.toString());
            copy.put("id", normalizedId);
            return copy;
        } catch (JSONException e) {
            return session;
        }
    }

    private static String normalizeSessionId(ServerConfig server, String sessionId) {
        return SessionIds.local(sessionId, server.getDeviceId());
    }

    private static void upsertSession(JSONArray sessions, JSONObject normalizedData) {
        String id = normalizedData.optString("id");
        if (id.isEmpty()) return;
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session != null && id.equals(session.optString("id"))) {
                try {
                    sessions.put(i, normalizedData);
                } catch (JSONException ignored) {
                }
                return;
            }
        }
        sessions.put(normalizedData);
    }

    private JSONArray hydrateCachedNames(ServerConfig server, JSONArray sessions) {
        if (terminalCache == null || sessions == null) return sessions;
        List<TerminalDiskCache.Metadata> diskCaches = terminalCache.getCachedSessionsForServer(server);
        Map<String, TerminalDiskCache.Metadata> diskMap = new HashMap<>();
        if (diskCaches != null) {
            for (TerminalDiskCache.Metadata meta : diskCaches) {
                if (meta.sessionId != null) diskMap.put(meta.sessionId, meta);
            }
        }

        JSONArray result = new JSONArray();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session == null) continue;
            String sessionId = session.optString("id");
            String sessionName = null;
            TerminalDiskCache.Metadata diskCached = diskMap.get(sessionId);
            if (diskCached != null) {
                sessionName = diskCached.sessionName;
            }
            if (sessionName != null && session.optString("name", "").trim().isEmpty()) {
                try {
                    JSONObject copy = new JSONObject(session.toString());
                    copy.put("name", sessionName);
                    result.put(copy);
                } catch (JSONException ignored) {
                    result.put(session);
                }
            } else {
                result.put(session);
            }
        }
        return result;
    }

    // ── Cache utilities ──────────────────────────────────────────────

    private static JSONArray copySessions(JSONArray sessions) {
        JSONArray copy = new JSONArray();
        if (sessions != null) {
            for (int i = 0; i < sessions.length(); i++) {
                copy.put(sessions.optJSONObject(i));
            }
        }
        return copy;
    }

    private static String key(ServerConfig server) {
        if (server == null) return "";
        // Cookie is a rotating auth token for the same account/device; the
        // transport and cache should be keyed by the device, not the token.
        return WebTermUrls.normalizeBaseUrl(server.getUrl())
            + "\n" + (server.getDeviceId() == null ? "" : server.getDeviceId());
    }

    private static SessionListCache.State toCacheState(SessionListResult.State state) {
        switch (state) {
            case CONNECTING:
                return SessionListCache.State.CONNECTING;
            case CONNECTED:
                return SessionListCache.State.CONNECTED;
            case DISCONNECTED:
                return SessionListCache.State.DISCONNECTED;
            case AUTH_REQUIRED:
                return SessionListCache.State.AUTH_REQUIRED;
            case ERROR:
            default:
                return SessionListCache.State.ERROR;
        }
    }

    private static SessionListResult.State toResultState(SessionListCache.State state) {
        if (state == null) return SessionListResult.State.DISCONNECTED;
        switch (state) {
            case CONNECTING:
                return SessionListResult.State.CONNECTING;
            case CONNECTED:
                return SessionListResult.State.CONNECTED;
            case DISCONNECTED:
                return SessionListResult.State.DISCONNECTED;
            case AUTH_REQUIRED:
                return SessionListResult.State.AUTH_REQUIRED;
            case ERROR:
            default:
                return SessionListResult.State.ERROR;
        }
    }

    private static SessionListResult toResult(SessionListCache.Snapshot snapshot) {
        return new SessionListResult(
            snapshot.sessions != null ? copySessions(snapshot.sessions) : new JSONArray(),
            toResultState(snapshot.state),
            snapshot.errorMessage,
            false
        );
    }

    // ── Public result / callback types ───────────────────────────────

    public static final class SessionListResult {
        public enum State {
            CONNECTING,
            CONNECTED,
            DISCONNECTED,
            AUTH_REQUIRED,
            ERROR
        }

        public final JSONArray sessions;
        public final State state;
        public final String errorMessage;
        public final boolean isLoading;

        public SessionListResult(JSONArray sessions, State state, String errorMessage, boolean isLoading) {
            this.sessions = sessions;
            this.state = state;
            this.errorMessage = errorMessage;
            this.isLoading = isLoading;
        }
    }

    public interface Callback {
        void onAuthenticated(ServerConfig server);
        void onResult(Result result);
    }

    // ── Legacy internal types ────────────────────────────────────────

    interface Api {
        void fetchSessions(ServerConfig server, WebTermApi.SessionsCallback callback);
        void refresh(String baseUrl, String cookie, WebTermApi.LoginCallback callback);
        void login(String baseUrl, String cookie, String username, String password, WebTermApi.LoginCallback callback);
    }

    interface Cache {
        List<TerminalDiskCache.Metadata> cachedSessionsForServer(ServerConfig server);
    }

    public static final class Result {
        public enum Kind {
            ONLINE,
            OFFLINE_CACHE,
            ERROR,
            AUTH_REQUIRED,
            PARSE_ERROR
        }

        public final Kind kind;
        public final JSONArray sessions;
        public final String message;

        private Result(Kind kind, JSONArray sessions, String message) {
            this.kind = kind;
            this.sessions = sessions;
            this.message = message;
        }

        static Result online(JSONArray sessions) {
            return new Result(Kind.ONLINE, sessions, "");
        }

        static Result offlineCache(JSONArray sessions, String message) {
            return new Result(Kind.OFFLINE_CACHE, sessions, message);
        }

        static Result error(String message) {
            return new Result(Kind.ERROR, null, message);
        }

        static Result authRequired(String message) {
            return new Result(Kind.AUTH_REQUIRED, null, message);
        }

        static Result parseError(String message) {
            return new Result(Kind.PARSE_ERROR, null, message);
        }
    }

    private static final class WebTermApiAdapter implements Api {
        private final WebTermApi api;

        WebTermApiAdapter(WebTermApi api) {
            this.api = api;
        }

        @Override
        public void fetchSessions(ServerConfig server, WebTermApi.SessionsCallback callback) {
            api.fetchSessions(server, callback);
        }

        @Override
        public void refresh(String baseUrl, String cookie, WebTermApi.LoginCallback callback) {
            api.refresh(baseUrl, cookie, callback);
        }

        @Override
        public void login(String baseUrl, String cookie, String username, String password, WebTermApi.LoginCallback callback) {
            api.login(baseUrl, cookie, username, password, callback);
        }
    }

    private static final class TerminalCacheAdapter implements Cache {
        private final TerminalCacheCoordinator terminalCache;

        TerminalCacheAdapter(TerminalCacheCoordinator terminalCache) {
            this.terminalCache = terminalCache;
        }

        @Override
        public List<TerminalDiskCache.Metadata> cachedSessionsForServer(ServerConfig server) {
            return terminalCache == null ? null : terminalCache.getCachedSessionsForServer(server);
        }
    }
}
