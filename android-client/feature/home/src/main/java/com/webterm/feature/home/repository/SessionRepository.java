package com.webterm.feature.home.repository;

import android.os.Handler;

import com.webterm.data.http.WebTermApi;
import com.webterm.core.api.AuthSessionCoordinator;
import com.webterm.core.api.WebTermUrls;
import com.webterm.core.cache.CachedSessionMapper;
import com.webterm.core.cache.TerminalCacheCoordinator;
import com.webterm.core.cache.TerminalDiskCache;
import com.webterm.core.config.ServerConfig;
import com.webterm.core.api.SessionIds;
import com.webterm.core.session.ChannelFailure;

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
    private final AuthSessionCoordinator authCoordinator;

    private final Map<String, ServerSubscription> subscriptions = new HashMap<>();
    private final MutableLiveData<ServerConfig> authEvents = new MutableLiveData<>();

    @Inject
    public SessionRepository(WebTermApi api,
                             AuthSessionCoordinator authCoordinator,
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
            mainHandler,
            authCoordinator);
    }

    SessionRepository(Api api,
                      Cache cache,
                      Executor executor,
                      ServerSessionDataSource wsSource,
                      SessionListCache sessionCache,
                      TerminalCacheCoordinator terminalCache,
                      Handler mainHandler) {
        this(api, cache, executor, wsSource, sessionCache, terminalCache, mainHandler, null);
    }

    SessionRepository(Api api,
                      Cache cache,
                      Executor executor,
                      ServerSessionDataSource wsSource,
                      SessionListCache sessionCache,
                      TerminalCacheCoordinator terminalCache,
                      Handler mainHandler,
                      AuthSessionCoordinator authCoordinator) {
        this.api = api;
        this.cache = cache;
        this.executor = executor;
        this.wsSource = wsSource;
        this.sessionCache = sessionCache;
        this.terminalCache = terminalCache;
        this.mainHandler = mainHandler;
        this.authCoordinator = authCoordinator;
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
     * Optimistically mirrors a screen-protocol cwd effect into the session list.
     * The next manager WS/HTTP update remains authoritative and may overwrite it.
     */
    public void updateSessionCwd(ServerConfig server, String sessionId, String cwd) {
        if (server == null || sessionId == null || sessionId.isEmpty()) return;
        String value = cwd == null ? "" : cwd;
        if (terminalCache != null) {
            terminalCache.updateSessionCwdAsync(server, sessionId, value);
        }
        mainHandler.post(() -> updateSessionCwdInMemory(server, sessionId, value));
    }

    private void updateSessionCwdInMemory(ServerConfig server, String sessionId, String cwd) {
        SessionListCache.Snapshot snapshot = sessionCache.get(server);
        if (snapshot == null || snapshot.sessions == null) return;
        String deviceId = server.getDeviceId() == null ? "" : server.getDeviceId();
        String localSessionId = SessionIds.local(sessionId, deviceId);
        JSONArray next = new JSONArray();
        boolean changed = false;
        for (int i = 0; i < snapshot.sessions.length(); i++) {
            JSONObject session = snapshot.sessions.optJSONObject(i);
            if (session == null) continue;
            if (!localSessionId.equals(SessionIds.local(session.optString("id"), deviceId))) {
                next.put(session);
                continue;
            }
            if (cwd.equals(session.optString("cwd", ""))) {
                next.put(session);
                continue;
            }
            try {
                JSONObject updated = new JSONObject(session.toString());
                updated.put("cwd", cwd);
                next.put(updated);
                changed = true;
            } catch (JSONException e) {
                next.put(session);
            }
        }
        if (!changed) return;

        sessionCache.put(server, new SessionListCache.Snapshot(
            copySessions(next),
            System.currentTimeMillis(),
            snapshot.state,
            snapshot.errorMessage
        ));
        ServerSubscription sub = subscriptions.get(key(server));
        if (sub != null) {
            SessionListResult current = sub.liveData.getValue();
            SessionListResult.State state = current != null && current.state != null
                ? current.state : toResultState(snapshot.state);
            String error = current != null ? current.errorMessage : snapshot.errorMessage;
            boolean loading = current != null && current.isLoading;
            sub.liveData.setValue(new SessionListResult(next, state, error, loading));
        }
    }

    /**
     * Legacy callback-based load. Kept for one-shot HTTP loads during migration.
     */
    public void loadSessions(ServerConfig server, Callback callback) {
        if (server.getCookie() != null && !server.getCookie().isEmpty()) {
            fetchSessions(server, callback);
            return;
        }
        if (authCoordinator != null) {
            recoverAndFetch(server, callback);
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
        /** AUTH_REQUIRED 恢复中：HTTP 链上的网络错误按临时错误退避重试。 */
        boolean authRecovery = false;
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
        public void onDisconnected(ChannelFailure failure) {
            switch (failure.kind) {
                case AUTH_REQUIRED:
                    // 鉴权失败：关闭失效 channel，进入 AUTH_REQUIRED，并交给统一
                    // 协调器执行一次 refresh/login；明确失败后等待用户更新凭据。
                    stopWebSocket();
                    cancelFallback();
                    authRecovery = true;
                    updateState(SessionListResult.State.AUTH_REQUIRED);
                    loadHttp(server);
                    break;
                case CHANNEL_NOT_FOUND:
                case REMOTE_CLOSED:
                    // channel 已被 mux 移除：同步 wsStarted 并释放；HTTP 确认 ONLINE
                    // 且 observer 仍活跃时由 applyHttpResult 重建 channel。
                    stopWebSocket();
                    updateState(SessionListResult.State.DISCONNECTED);
                    loadHttp(server);
                    scheduleFallback();
                    break;
                case CLIENT_CLOSED:
                    // 本地主动关闭：不自动恢复。
                    break;
                case MUX_TEMPORARY:
                case SERVER_TEMPORARY:
                default:
                    // 保留 channel 与 wsStarted，依赖 mux 自身重连；
                    // HTTP fallback 仅作会话列表补偿。
                    updateState(SessionListResult.State.DISCONNECTED);
                    scheduleFallback();
                    break;
            }
        }

        private void stopWebSocket() {
            if (wsStarted) {
                wsStarted = false;
                wsSource.stop(server);
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
            // 只移除待发任务，不重置 fallbackDelayMs：
            // runFallbackRefresh 翻倍后的退避必须保留到下一次调度。
            mainHandler.removeCallbacks(fallbackRunnable);
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
                    sub.authRecovery = false;
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
                // 凭据被服务明确拒绝：退出恢复流程，保持 AUTH_REQUIRED 等待用户。
                if (sub != null) sub.authRecovery = false;
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
            if (sub.authRecovery && sub.observerCount > 0) {
                // AUTH_REQUIRED 恢复期间的网络错误：按临时错误处理，
                // 使用现有 3s~60s 退避重试，不形成热循环。
                sub.scheduleFallback();
            }
        }
    }

    private void fetchSessions(ServerConfig server, Callback callback) {
        fetchSessions(server, callback, true);
    }

    private void fetchSessions(ServerConfig server, Callback callback, boolean authRetryAllowed) {
        api.fetchSessions(server, new WebTermApi.SessionsCallback() {
            @Override
            public void onReady(JSONArray sessions) {
                callback.onResult(Result.online(sessions));
            }

            @Override
            public void onError(int code, String message) {
                if (code == 401 || code == 403) {
                    if (authCoordinator != null && authRetryAllowed) {
                        recoverAndFetch(server, callback);
                        return;
                    }
                    callback.onResult(Result.authRequired(message));
                    return;
                }
                offlineFallback(server, code > 0 ? "HTTP " + code : message, callback);
            }

            @Override
            public void onParseError(String message) {
                callback.onResult(Result.parseError("JSON 解析错误"));
            }
        });
    }

    private void recoverAndFetch(ServerConfig server, Callback callback) {
        authCoordinator.recover(server, new AuthSessionCoordinator.Callback() {
            @Override public void onAuthenticated(ServerConfig canonical, String cookie) {
                server.setCookie(cookie);
                callback.onAuthenticated(canonical);
                fetchSessions(server, callback, false);
            }

            @Override public void onFailure(AuthSessionCoordinator.Failure failure) {
                if (failure.isAuthenticationRequired()) {
                    callback.onResult(Result.authRequired(failure.message));
                } else {
                    offlineFallback(server, failure.message, callback);
                }
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
        // 自定义会话名已删除；服务端返回的 OSC termTitle 是唯一标题来源。
        return sessions;
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
