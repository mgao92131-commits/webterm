package com.webterm.feature.home.repository;

import com.webterm.core.api.WebTermUrls;
import com.webterm.core.config.ServerConfig;

import org.json.JSONArray;

import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * In-memory cache for the latest session list of each server.
 */
@Singleton
public final class SessionListCache {

    @Inject
    public SessionListCache() {
    }

    public enum State {
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        AUTH_REQUIRED,
        ERROR
    }

    public static final class Snapshot {
        public final JSONArray sessions;
        public final long timestamp;
        public final State state;
        public final String errorMessage;

        public Snapshot(JSONArray sessions, long timestamp, State state, String errorMessage) {
            this.sessions = sessions;
            this.timestamp = timestamp;
            this.state = state;
            this.errorMessage = errorMessage;
        }
    }

    private final Map<String, Snapshot> memoryCache = new HashMap<>();

    public Snapshot get(ServerConfig server) {
        return memoryCache.get(key(server));
    }

    public void put(ServerConfig server, Snapshot snapshot) {
        memoryCache.put(key(server), snapshot);
    }

    public void clear(ServerConfig server) {
        memoryCache.remove(key(server));
    }

    private static String key(ServerConfig server) {
        if (server == null) return "";
        String base = WebTermUrls.normalizeBaseUrl(server.getUrl());
        String deviceId = server.getDeviceId() == null ? "" : server.getDeviceId();
        // A server/device only ever has one active account; the cookie is just
        // a rotating auth token, so it should not be part of the cache key.
        return base + "\n" + deviceId;
    }
}
