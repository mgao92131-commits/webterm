package com.webterm.mobile;

import com.termux.terminal.TerminalSession;

import java.io.File;
import java.nio.charset.StandardCharsets;

final class TerminalCacheCoordinator {
    private static final int SNAPSHOT_MAX_LINES = 5000;

    private final TerminalDiskCache diskCache;
    private final java.util.Map<String, CachedTerminal> memoryCache = new java.util.HashMap<>();

    TerminalCacheCoordinator(File filesDir) {
        diskCache = new TerminalDiskCache(filesDir);
    }

    java.util.List<TerminalDiskCache.Metadata> getCachedSessionsForServer(ServerConfig server) {
        return diskCache.getCachedSessionsForServer(server);
    }

    java.util.Map<String, CachedTerminal> getMemorySessionsForServer(ServerConfig server) {
        java.util.Map<String, CachedTerminal> result = new java.util.HashMap<>();
        for (CachedTerminal cached : memoryCache.values()) {
            if (TerminalCacheScope.matches(server, cached.baseUrl, cached.sessionId)) {
                result.put(cached.sessionId, cached);
            }
        }
        return result;
    }

    CachedTerminal getMemory(String baseUrl, String sessionId, String instanceId, String createdAt) {
        String key = SessionIdentity.cacheKey(baseUrl, sessionId, instanceId, createdAt);
        return key.isEmpty() ? null : memoryCache.get(key);
    }

    TerminalDiskCache.RestoreResult restore(String baseUrl, String sessionId, String instanceId, String createdAt) {
        return diskCache.restore(baseUrl, sessionId, instanceId, createdAt);
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
        cached.columns = snapshot.columns;
        cached.rows = snapshot.rows;
        TerminalDiskCache.Metadata metadata = snapshot.diskMetadata;
        if (metadata != null) {
            diskCache.saveSnapshotBlocking(metadata, snapshotBytes(snapshot.terminalSession));
        }
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

    void removeMissingForServer(ServerConfig server, java.util.Set<String> liveSessionIdentities, TerminalSession currentSession) {
        java.util.List<String> staleKeys = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, CachedTerminal> entry : memoryCache.entrySet()) {
            CachedTerminal cached = entry.getValue();
            if (TerminalCacheScope.matches(server, cached.baseUrl, cached.sessionId)
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
        diskCache.clearMissingForServerAsync(server, liveSessionIdentities);
    }

    void removeServer(ServerConfig server, TerminalSession currentSession) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, CachedTerminal> entry : memoryCache.entrySet()) {
            CachedTerminal cached = entry.getValue();
            if (TerminalCacheScope.matches(server, cached.baseUrl, cached.sessionId)) {
                keys.add(entry.getKey());
            }
        }
        removeMemoryEntries(keys, currentSession);
        diskCache.clearServerAsync(server);
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
        int columns;
        int rows;
        TerminalDiskCache.Metadata diskMetadata;
    }

    private static byte[] snapshotBytes(TerminalSession terminalSession) {
        if (terminalSession == null || terminalSession.getEmulator() == null || terminalSession.getEmulator().getScreen() == null) {
            return new byte[0];
        }
        return terminalSession.getEmulator().getScreen().serialize();
    }
}
