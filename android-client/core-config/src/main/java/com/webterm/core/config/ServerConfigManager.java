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
		for (ServerConfig server : store.loadServers()) {
			if (server == null) continue;
			// Relay Device 是运行时生成的临时对象，不持久化。
			if (server.isRelayDevice()) continue;
			// 保留 Relay Master 与 Direct 设备。
			servers.add(server);
		}
    }

    public synchronized void save() {
        store.saveServers(servers);
    }

    public List<ServerConfig> servers() {
        return servers;
    }

    /** 添加一个 Direct 设备并立即持久化。 */
    public synchronized void addDirectDevice(ServerConfig config) {
        if (config == null) return;
        servers.add(config);
        store.saveServers(servers);
    }

    /** 按 configId 删除 Direct 设备（不会影响 Relay Master），返回是否删除成功。 */
    public synchronized boolean removeDirectDevice(String configId) {
        String id = safe(configId);
        if (id.isEmpty()) return false;
        boolean removed = false;
        for (int i = servers.size() - 1; i >= 0; i--) {
            ServerConfig server = servers.get(i);
            if (server.isDirectDevice() && id.equals(safe(server.getId()))) {
                servers.remove(i);
                removed = true;
            }
        }
        if (removed) store.saveServers(servers);
        return removed;
    }

    /** 判断是否已存在相同 URL + 账户的 Direct 设备，用于添加前去重。 */
    public synchronized boolean containsDirectDevice(String normalizedUrl, String username) {
        return containsDirectDevice(normalizedUrl, username, "");
    }

    /**
     * 去重判断，可排除某个 configId（编辑自身时使用，避免“只改密码”被误判为重复）。
     */
    public synchronized boolean containsDirectDevice(String normalizedUrl, String username,
                                                     String excludingConfigId) {
        String url = normalizeUrl(normalizedUrl);
        String user = safe(username).trim();
        String exclude = safe(excludingConfigId);
        for (ServerConfig server : servers) {
            if (!exclude.isEmpty() && exclude.equals(safe(server.getId()))) continue;
            if (server.isDirectDevice()
                && normalizeUrl(server.getUrl()).equals(url)
                && safe(server.getUsername()).trim().equals(user)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 原位更新 Direct 设备配置并持久化，保持 configId（从而 connectionKey）不变。
     * 地址变化时由 DeviceConnectionRegistry 负责重建连接。返回是否找到并更新成功。
     */
    public synchronized boolean updateDirectDevice(String configId, String url, String cookie,
                                                   String username, String password, String name) {
        String id = safe(configId);
        if (id.isEmpty()) return false;
        for (ServerConfig server : servers) {
            if (server.isDirectDevice() && id.equals(safe(server.getId()))) {
                server.setUrl(url);
                server.setCookie(cookie);
                server.setUsername(username);
                server.setPassword(password);
                if (name != null && !name.isEmpty()) server.setName(name);
                store.saveServers(servers);
                return true;
            }
        }
        return false;
    }

    /** 返回所有持久化的 Direct 设备（不含 Relay Master / Relay Device）。 */
    public synchronized List<ServerConfig> directDevices() {
        List<ServerConfig> result = new ArrayList<>();
        for (ServerConfig server : servers) {
            if (server.isDirectDevice()) result.add(server);
        }
        return result;
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

}
