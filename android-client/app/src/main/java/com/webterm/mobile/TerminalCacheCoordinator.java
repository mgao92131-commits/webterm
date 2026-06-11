package com.webterm.mobile;

import com.termux.terminal.TerminalSession;

import android.os.Handler;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

final class TerminalCacheCoordinator {
    private final TerminalDiskCache diskCache;
    private final java.util.Map<String, CachedTerminal> memoryCache = new java.util.HashMap<>();

    TerminalCacheCoordinator(File filesDir) {
        diskCache = new TerminalDiskCache(filesDir);
    }

    java.util.List<TerminalDiskCache.Metadata> getCachedSessionsForServer(String baseUrl) {
        return diskCache.getCachedSessionsForServer(baseUrl);
    }

    CachedTerminal getMemory(String baseUrl, String sessionId, String instanceId, String createdAt) {
        String key = SessionIdentity.cacheKey(baseUrl, sessionId, instanceId, createdAt);
        return key.isEmpty() ? null : memoryCache.get(key);
    }

    TerminalDiskCache.RestoreResult restore(String baseUrl, String sessionId, String instanceId, String createdAt, TerminalDiskCache.FrameVisitor callback) {
        return diskCache.restore(baseUrl, sessionId, instanceId, createdAt, callback);
    }

    void restoreAsyncCombined(
        java.util.concurrent.Executor executor,
        Handler mainHandler,
        String baseUrl,
        String sessionId,
        String instanceId,
        String createdAt,
        RestoreBytesCallback callback
    ) {
        executor.execute(() -> {
            ByteArrayOutputStream combined = new ByteArrayOutputStream();
            diskCache.restore(baseUrl, sessionId, instanceId, createdAt, (seq, bytes) -> {
                try {
                    combined.write(bytes);
                } catch (IOException ignored) {
                }
            });
            byte[] allBytes = combined.toByteArray();
            if (allBytes.length > 0) {
                mainHandler.post(() -> callback.onRestored(allBytes));
            }
        });
    }

    void saveCurrent(Snapshot snapshot) {
        if (snapshot.baseUrl == null || snapshot.sessionId == null || snapshot.terminalSession == null) return;
        String key = SessionIdentity.cacheKey(snapshot.baseUrl, snapshot.sessionId, snapshot.instanceId, snapshot.createdAt);
        if (key.isEmpty()) return;
        CachedTerminal cached = memoryCache.get(key);
        if (cached == null) {
            cached = new CachedTerminal(
                snapshot.baseUrl,
                snapshot.cookie,
                snapshot.sessionId,
                snapshot.instanceId,
                snapshot.termTitle,
                snapshot.sessionName,
                snapshot.createdAt,
                snapshot.terminalSession
            );
            memoryCache.put(key, cached);
        }
        cached.cookie = snapshot.cookie;
        cached.instanceId = snapshot.instanceId;
        cached.termTitle = snapshot.termTitle;
        cached.sessionName = snapshot.sessionName;
        cached.createdAt = snapshot.createdAt;
        cached.terminalSession = snapshot.terminalSession;
        cached.lastSeq = snapshot.lastSeq;
        cached.persistedSeq = snapshot.persistedSeq;
        cached.pendingDiskFrames.clear();
        cached.pendingDiskFrames.addAll(snapshot.pendingDiskFrames);
        cached.columns = snapshot.columns;
        cached.rows = snapshot.rows;
    }

    long appendFramesBlocking(TerminalDiskCache.Metadata metadata, java.util.List<TerminalDiskCache.Frame> frames) {
        if (metadata == null || frames == null || frames.isEmpty()) return 0;
        return diskCache.appendFramesBlocking(metadata, frames);
    }

    boolean removeTerminal(String baseUrl, String sessionId, String currentBaseUrl, String currentSessionId, TerminalSession currentSession) {
        boolean removedCurrent = sameServerSession(baseUrl, sessionId, currentBaseUrl, currentSessionId);
        java.util.List<String> keys = new java.util.ArrayList<>();
        String normalizedBaseUrl = WebTermUrls.normalizeBaseUrl(baseUrl);
        for (java.util.Map.Entry<String, CachedTerminal> entry : memoryCache.entrySet()) {
            CachedTerminal cached = entry.getValue();
            if (normalizedBaseUrl.equals(WebTermUrls.normalizeBaseUrl(cached.baseUrl)) && String.valueOf(sessionId).equals(cached.sessionId)) {
                keys.add(entry.getKey());
            }
        }
        removeMemoryEntries(keys, currentSession);
        diskCache.clearAsync(baseUrl, sessionId);
        return removedCurrent;
    }

    void removeMissingForServer(String baseUrl, java.util.Set<String> liveSessionIdentities, TerminalSession currentSession) {
        String normalizedBaseUrl = WebTermUrls.normalizeBaseUrl(baseUrl);
        java.util.List<String> staleKeys = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, CachedTerminal> entry : memoryCache.entrySet()) {
            CachedTerminal cached = entry.getValue();
            if (normalizedBaseUrl.equals(WebTermUrls.normalizeBaseUrl(cached.baseUrl))
                && !liveSessionIdentities.contains(SessionIdentity.value(cached.sessionId, cached.instanceId, cached.createdAt))) {
                staleKeys.add(entry.getKey());
            }
        }
        for (String key : staleKeys) {
            CachedTerminal cached = memoryCache.remove(key);
            finishIfInactive(cached, currentSession);
            if (cached != null) {
                diskCache.clearAsync(cached.baseUrl, cached.sessionId);
            }
        }
        diskCache.clearMissingForServerAsync(baseUrl, liveSessionIdentities);
    }

    void removeServer(String baseUrl, TerminalSession currentSession) {
        String normalizedBaseUrl = WebTermUrls.normalizeBaseUrl(baseUrl);
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, CachedTerminal> entry : memoryCache.entrySet()) {
            CachedTerminal cached = entry.getValue();
            if (normalizedBaseUrl.equals(WebTermUrls.normalizeBaseUrl(cached.baseUrl))) {
                keys.add(entry.getKey());
            }
        }
        removeMemoryEntries(keys, currentSession);
        diskCache.clearServerAsync(baseUrl);
    }

    void shutdown(TerminalSession currentSession) {
        for (CachedTerminal cached : memoryCache.values()) {
            finishIfInactive(cached, currentSession);
        }
        memoryCache.clear();
        diskCache.shutdown();
    }

    private void removeMemoryEntries(java.util.List<String> keys, TerminalSession currentSession) {
        for (String key : keys) {
            CachedTerminal cached = memoryCache.remove(key);
            finishIfInactive(cached, currentSession);
        }
    }

    private void finishIfInactive(CachedTerminal cached, TerminalSession currentSession) {
        if (cached != null && cached.terminalSession != null && cached.terminalSession != currentSession) {
            cached.terminalSession.finishIfRunning();
        }
    }

    private static boolean sameServerSession(String baseUrlA, String sessionIdA, String baseUrlB, String sessionIdB) {
        return WebTermUrls.normalizeBaseUrl(baseUrlA).equals(WebTermUrls.normalizeBaseUrl(baseUrlB))
            && String.valueOf(sessionIdA == null ? "" : sessionIdA).equals(String.valueOf(sessionIdB == null ? "" : sessionIdB));
    }

    static final class Snapshot {
        String baseUrl;
        String cookie;
        String sessionId;
        String instanceId;
        String termTitle;
        String sessionName;
        String createdAt;
        TerminalSession terminalSession;
        long lastSeq;
        long persistedSeq;
        int columns;
        int rows;
        final java.util.List<TerminalDiskCache.Frame> pendingDiskFrames = new java.util.ArrayList<>();
    }

    interface RestoreBytesCallback {
        void onRestored(byte[] bytes);
    }
}
