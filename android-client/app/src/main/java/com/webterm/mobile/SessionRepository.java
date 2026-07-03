package com.webterm.mobile;

import org.json.JSONArray;

import java.util.List;
import java.util.concurrent.Executor;

final class SessionRepository {
    private final Api api;
    private final Cache cache;
    private final Executor executor;

    SessionRepository(WebTermApi api, TerminalCacheCoordinator terminalCache, Executor executor) {
        this(new WebTermApiAdapter(api), new TerminalCacheAdapter(terminalCache), executor);
    }

    SessionRepository(Api api, Cache cache, Executor executor) {
        this.api = api;
        this.cache = cache;
        this.executor = executor;
    }

    void loadSessions(ServerConfig server, Callback callback) {
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

    interface Api {
        void fetchSessions(ServerConfig server, WebTermApi.SessionsCallback callback);
        void refresh(String baseUrl, String cookie, WebTermApi.LoginCallback callback);
        void login(String baseUrl, String cookie, String username, String password, WebTermApi.LoginCallback callback);
    }

    interface Cache {
        List<TerminalDiskCache.Metadata> cachedSessionsForServer(ServerConfig server);
    }

    interface Callback {
        void onAuthenticated(ServerConfig server);
        void onResult(Result result);
    }

    static final class Result {
        enum Kind {
            ONLINE,
            OFFLINE_CACHE,
            ERROR,
            AUTH_REQUIRED,
            PARSE_ERROR
        }

        final Kind kind;
        final JSONArray sessions;
        final String message;

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
