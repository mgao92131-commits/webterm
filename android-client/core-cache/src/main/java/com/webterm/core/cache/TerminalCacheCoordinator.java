package com.webterm.core.cache;

import com.termux.terminal.TerminalSession;
import com.webterm.core.api.WebTermUrls;
import com.webterm.core.config.ServerConfig;
import com.webterm.core.api.SessionIdentity;

import java.io.File;

public final class TerminalCacheCoordinator {
    private final TerminalDiskCache diskCache;
    private final java.util.Map<String, CachedTerminal> memoryCache = new java.util.HashMap<>();

    public TerminalCacheCoordinator(File filesDir) {
        diskCache = new TerminalDiskCache(filesDir);
    }

    public java.util.List<TerminalDiskCache.Metadata> getCachedSessionsForServer(ServerConfig server) {
        return diskCache.getCachedSessionsForServer(server);
    }

    public java.util.Map<String, CachedTerminal> getMemorySessionsForServer(ServerConfig server) {
        java.util.Map<String, CachedTerminal> result = new java.util.HashMap<>();
        for (CachedTerminal cached : memoryCache.values()) {
            if (TerminalCacheScope.matches(server, cached.baseUrl, cached.sessionId)) {
                result.put(cached.sessionId, cached);
            }
        }
        return result;
    }

    public CachedTerminal getMemory(String baseUrl, String sessionId, String instanceId, String createdAt) {
        String key = SessionIdentity.cacheKey(baseUrl, sessionId, instanceId, createdAt);
        return key.isEmpty() ? null : memoryCache.get(key);
    }

    public TerminalDiskCache.RestoreResult restore(String baseUrl, String sessionId, String instanceId, String createdAt) {
        return diskCache.restore(baseUrl, sessionId, instanceId, createdAt);
    }

    public void saveCurrent(Snapshot snapshot) {
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
                snapshot.cwd,
                snapshot.createdAt,
                snapshot.terminalSession
            );
            memoryCache.put(key, cached);
        }
        cached.cookie = snapshot.cookie;
        cached.instanceId = snapshot.instanceId;
        cached.termTitle = snapshot.termTitle;
        cached.sessionName = snapshot.sessionName;
        cached.cwd = snapshot.cwd;
        cached.createdAt = snapshot.createdAt;
        cached.terminalSession = snapshot.terminalSession;
        cached.lastSeq = snapshot.lastSeq;
        cached.columns = snapshot.columns;
        cached.rows = snapshot.rows;
        TerminalDiskCache.Metadata metadata = snapshot.diskMetadata;
        if (metadata != null) {
            diskCache.saveMetadataBlocking(metadata);
        }
    }

    public boolean removeTerminal(String baseUrl, String sessionId, String currentBaseUrl, String currentSessionId, TerminalSession currentSession) {
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

    public void removeMissingForServer(ServerConfig server, java.util.Set<String> liveSessionIdentities, TerminalSession currentSession) {
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

    public void removeServer(ServerConfig server, TerminalSession currentSession) {
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

    public void shutdown(TerminalSession currentSession) {
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

    public static final class Snapshot {
        public String baseUrl;
        public String cookie;
        public String sessionId;
        public String instanceId;
        public String termTitle;
        public String sessionName;
        public String cwd;
        public String createdAt;
        public TerminalSession terminalSession;
        public long lastSeq;
        public int columns;
        public int rows;
        public TerminalDiskCache.Metadata diskMetadata;
    }

}
