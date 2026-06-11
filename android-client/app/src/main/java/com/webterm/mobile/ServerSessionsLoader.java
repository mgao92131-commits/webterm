package com.webterm.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;

final class ServerSessionsLoader {
    private final Activity activity;
    private final WebTermApi api;
    private final TerminalCacheCoordinator terminalCache;
    private final java.util.concurrent.Executor executor;
    private final Listener listener;

    ServerSessionsLoader(
        Activity activity,
        WebTermApi api,
        TerminalCacheCoordinator terminalCache,
        java.util.concurrent.Executor executor,
        Listener listener
    ) {
        this.activity = activity;
        this.api = api;
        this.terminalCache = terminalCache;
        this.executor = executor;
        this.listener = listener;
    }

    void load(ServerConfig server, LinearLayout subList, StatusIndicatorView status, java.util.Map<String, JSONArray> tempInMemorySessions) {
        if (server.url.isEmpty()) return;
        markPending(status);

        boolean loadedFromMemory = renderTemporarySessions(server, subList, tempInMemorySessions);
        if (!loadedFromMemory && terminalCache != null) {
            prepopulateCachedSessions(server, subList);
        }

        if (server.cookie != null && !server.cookie.isEmpty()) {
            fetch(server, subList, status);
        } else if (server.password != null && !server.password.isEmpty()) {
            silentLoginAndFetch(server, subList, status);
        } else {
            markError(status);
            renderErrorItem(server, subList, status, "需要登录");
        }
    }

    private boolean renderTemporarySessions(ServerConfig server, LinearLayout subList, java.util.Map<String, JSONArray> tempInMemorySessions) {
        if (tempInMemorySessions == null) return false;
        JSONArray sessions = tempInMemorySessions.get(WebTermUrls.normalizeBaseUrl(server.url));
        if (sessions == null) return false;
        listener.onSessionsLoaded(server, sessions);
        listener.onRenderSessions(server, sessions, subList);
        subList.setTag("cached_list");
        return true;
    }

    private void prepopulateCachedSessions(ServerConfig server, LinearLayout subList) {
        executor.execute(() -> {
            java.util.List<TerminalDiskCache.Metadata> cached = terminalCache.getCachedSessionsForServer(server.url);
            if (cached == null || cached.isEmpty()) return;
            activity.runOnUiThread(() -> {
                if (subList.getChildCount() == 0) {
                    listener.onRenderSessions(server, CachedSessionMapper.toSessions(cached), subList);
                    subList.setTag("cached_list");
                }
            });
        });
    }

    private void fetch(ServerConfig server, LinearLayout subList, StatusIndicatorView status) {
        api.fetchSessions(server, new WebTermApi.SessionsCallback() {
            @Override
            public void onReady(JSONArray sessions) {
                activity.runOnUiThread(() -> {
                    markReady(status);
                    listener.onSessionsLoaded(server, sessions);
                    listener.onRenderSessions(server, sessions, subList);
                });
            }

            @Override
            public void onError(int code, String message) {
                if (code == 401 && server.password != null && !server.password.isEmpty()) {
                    silentLoginAndFetch(server, subList, status);
                    return;
                }
                showOfflineCachedSessions(server, subList, status, code > 0 ? "HTTP " + code : message);
            }

            @Override
            public void onParseError(String message) {
                activity.runOnUiThread(() -> {
                    markError(status);
                    if (!shouldKeepExistingList(subList)) {
                        renderErrorItem(server, subList, status, "JSON 解析错误");
                    }
                });
            }
        });
    }

    private void silentLoginAndFetch(ServerConfig server, LinearLayout subList, StatusIndicatorView status) {
        api.login(server.url, server.username, server.password, new WebTermApi.LoginCallback() {
            @Override
            public void onReady(String baseUrl, String cookie) {
                server.cookie = cookie;
                listener.onAuthenticated(server);
                activity.runOnUiThread(() -> fetch(server, subList, status));
            }

            @Override
            public void onError(String message) {
                showOfflineCachedSessions(server, subList, status, "登录失败: " + message);
            }
        });
    }

    private void showOfflineCachedSessions(ServerConfig server, LinearLayout subList, StatusIndicatorView status, String errorMsg) {
        java.util.List<TerminalDiskCache.Metadata> cachedMetadata = terminalCache == null
            ? null
            : terminalCache.getCachedSessionsForServer(server.url);

        activity.runOnUiThread(() -> {
            markError(status);
            if (shouldKeepExistingList(subList)) return;

            if (cachedMetadata != null && !cachedMetadata.isEmpty()) {
                listener.onRenderSessions(server, CachedSessionMapper.toSessions(cachedMetadata), subList);
                subList.setTag("cached_list");
            } else {
                renderErrorItem(server, subList, status, errorMsg);
            }
        });
    }

    private boolean shouldKeepExistingList(LinearLayout subList) {
        if (subList == null || subList.getChildCount() == 0) return false;
        View firstChild = subList.getChildAt(0);
        Object tag = firstChild.getTag();
        return tag == null || (!"error_item".equals(tag) && !"empty_item".equals(tag));
    }

    private void renderErrorItem(ServerConfig server, LinearLayout subList, StatusIndicatorView status, String error) {
        subList.removeAllViews();
        subList.addView(
            SessionListItemViews.errorItem(activity, error, () -> load(server, subList, status, null)),
            new LinearLayout.LayoutParams(-1, -2)
        );
    }

    private void markPending(StatusIndicatorView status) {
        status.setStatus(StatusIndicatorView.Status.CONNECTING);
    }

    private void markReady(StatusIndicatorView status) {
        status.setStatus(StatusIndicatorView.Status.CONNECTED);
    }

    private void markError(StatusIndicatorView status) {
        status.setStatus(StatusIndicatorView.Status.DISCONNECTED);
    }

    interface Listener {
        void onAuthenticated(ServerConfig server);
        void onSessionsLoaded(ServerConfig server, JSONArray sessions);
        void onRenderSessions(ServerConfig server, JSONArray sessions, LinearLayout subList);
    }
}
