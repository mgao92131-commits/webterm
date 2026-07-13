package com.webterm.core.config;

import java.util.ArrayList;
import java.util.List;

public final class ServerConfigManager {
    private final ServerConfigStore store;
    private final List<ServerConfig> servers = new ArrayList<>();

    public ServerConfigManager(ServerConfigStore store) {
        this.store = store;
    }

    public synchronized void load() {
        servers.clear();
        servers.addAll(store.loadServers());
    }

    public synchronized void save() {
        store.saveServers(servers);
    }

    public List<ServerConfig> servers() {
        return servers;
    }

    /**
     * Resolve a navigation/API copy to the canonical persisted configuration.
     * Relay-device rows are transient; their credentials belong to the relay
     * master with the same URL.
     */
    public synchronized ServerConfig credentialOwner(ServerConfig hint) {
        if (hint == null) return null;
        String url = normalizeUrl(hint.getUrl());
        if (hint.isRelayDevice()) {
            for (ServerConfig server : servers) {
                if (server.isRelayMaster() && normalizeUrl(server.getUrl()).equals(url)) {
                    return server;
                }
            }
        }
        String id = safe(hint.getId());
        if (!id.isEmpty()) {
            for (ServerConfig server : servers) {
                if (id.equals(safe(server.getId()))) return server;
            }
        }
        String deviceId = safe(hint.getDeviceId());
        ServerConfig onlyUrlMatch = null;
        int urlMatches = 0;
        for (ServerConfig server : servers) {
            if (!normalizeUrl(server.getUrl()).equals(url)) continue;
            if (safe(server.getDeviceId()).equals(deviceId)) return server;
            urlMatches++;
            onlyUrlMatch = server;
        }
        return urlMatches == 1 ? onlyUrlMatch : hint;
    }

    /** Update the canonical credentials and persist them before reconnecting. */
    public synchronized ServerConfig updateCookie(ServerConfig source, String cookie) {
        if (source == null) return null;
        String value = cookie == null ? "" : cookie;
        source.setCookie(value);
        ServerConfig owner = credentialOwner(source);
        if (owner != null) owner.setCookie(value);
        // Keep currently materialized relay rows coherent for the rest of this process.
        for (ServerConfig server : servers) {
            if (normalizeUrl(server.getUrl()).equals(normalizeUrl(source.getUrl()))
                && (server == owner || server.isRelayMaster())) {
                server.setCookie(value);
            }
        }
        store.saveServers(servers);
        return owner != null ? owner : source;
    }

    private static String normalizeUrl(String value) {
        String result = safe(value).trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public void addOrUpdate(ServerConfig existingServer, String name, String url, String cookie, String username, String password) {
        if (existingServer == null) {
            servers.add(new ServerConfig(
                "srv_" + System.currentTimeMillis(),
                name,
                url,
                cookie,
                username,
                password
            ));
            return;
        }

        existingServer.setName(name);
        existingServer.setUrl(url);
        existingServer.setCookie(cookie);
        existingServer.setUsername(username);
        existingServer.setPassword(password);
    }

    public void remove(ServerConfig server) {
        servers.remove(server);
    }
}
