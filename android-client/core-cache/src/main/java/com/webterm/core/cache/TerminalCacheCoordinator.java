package com.webterm.core.cache;

import com.webterm.core.config.ServerConfig;

import java.io.File;
import java.util.List;
import java.util.Set;

/** Metadata-only cache for remote, Go-authoritative terminal sessions. */
public final class TerminalCacheCoordinator {
    private final TerminalDiskCache diskCache;

    public TerminalCacheCoordinator(File filesDir) {
        diskCache = new TerminalDiskCache(filesDir);
    }

    public List<TerminalDiskCache.Metadata> getCachedSessionsForServer(ServerConfig server) {
        return diskCache.getCachedSessionsForServer(server);
    }

    public TerminalDiskCache.RestoreResult restore(String baseUrl, String sessionId,
                                                    String instanceId, String createdAt) {
        return diskCache.restore(baseUrl, sessionId, instanceId, createdAt);
    }

    public void saveMetadata(TerminalDiskCache.Metadata metadata) {
        diskCache.saveMetadataBlocking(metadata);
    }

    public void removeTerminal(String baseUrl, String sessionId) {
        diskCache.clearAsync(baseUrl, sessionId);
    }

    public void removeMissingForServer(ServerConfig server, Set<String> liveSessionIdentities) {
        diskCache.clearMissingForServerAsync(server, liveSessionIdentities);
    }

    public void removeServer(ServerConfig server) {
        diskCache.clearServerAsync(server);
    }

    public void shutdown() {
        diskCache.shutdown();
    }
}
