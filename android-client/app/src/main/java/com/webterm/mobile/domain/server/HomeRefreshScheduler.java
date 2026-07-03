package com.webterm.mobile.domain.server;

import android.os.Handler;

public final class HomeRefreshScheduler {
    private static final long INITIAL_DELAY_MS = 3000L;
    private static final long MAX_DELAY_MS = 60000L;

    private final Handler mainHandler;
    private final Listener listener;
    private long delayMs = INITIAL_DELAY_MS;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!listener.isHomeRefreshActive()) return;
            boolean needsFallbackRefresh = false;
            for (ServerGroupController group : listener.activeGroups()) {
                if (!group.isConnected()) {
                    needsFallbackRefresh = true;
                    listener.loadSessionsForGroup(group);
                }
            }
            if (needsFallbackRefresh) {
                schedule(nextDelay());
            }
        }
    };

    public HomeRefreshScheduler(Handler mainHandler, Listener listener) {
        this.mainHandler = mainHandler;
        this.listener = listener;
    }

    void reset() {
        delayMs = INITIAL_DELAY_MS;
        cancel();
    }

    void cancel() {
        mainHandler.removeCallbacks(refreshRunnable);
    }

    void scheduleInitial() {
        schedule(INITIAL_DELAY_MS);
    }

    void schedule(long requestedDelayMs) {
        cancel();
        if (!listener.isHomeRefreshActive()) return;
        boolean needsFallbackRefresh = false;
        for (ServerGroupController group : listener.activeGroups()) {
            if (!group.isConnected()) {
                needsFallbackRefresh = true;
                break;
            }
        }
        if (needsFallbackRefresh) {
            mainHandler.postDelayed(refreshRunnable, Math.max(INITIAL_DELAY_MS, requestedDelayMs));
        }
    }

    private long nextDelay() {
        delayMs = Math.min(MAX_DELAY_MS, Math.max(INITIAL_DELAY_MS, delayMs * 2));
        return delayMs;
    }

    interface Listener {
        boolean isHomeRefreshActive();
        java.util.List<ServerGroupController> activeGroups();
        void loadSessionsForGroup(ServerGroupController group);
    }
}
