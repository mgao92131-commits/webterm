package com.webterm.core.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ServerConfigManager {
    private final ServerConfigStore store;
    private final List<ServerConfig> servers = new ArrayList<>();
    private final Map<String, Long> credentialGenerations = new LinkedHashMap<>();
    private long nextCredentialGeneration;

    public static final class CredentialUpdate {
        public final ServerConfig server;
        public final CredentialSnapshot snapshot;
        public final boolean applied;

        CredentialUpdate(ServerConfig server, CredentialSnapshot snapshot, boolean applied) {
            this.server = server;
            this.snapshot = snapshot;
            this.applied = applied;
        }
    }

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
            ensureCredentialGeneration(server);
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
        ensureCredentialGeneration(config);
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
                String credentialKey = credentialIdentity(server);
                server.setUrl(url);
                server.setCookie(cookie);
                server.setUsername(username);
                server.setPassword(password);
                if (name != null && !name.isEmpty()) server.setName(name);
                credentialGenerations.put(credentialKey, ++nextCredentialGeneration);
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
        return updateCookieIfGeneration(source, cookie, -1L).server;
    }

    /**
     * 仅当 expectedGeneration 仍为当前版本时提交认证结果；-1 表示调用方明确
     * 不做版本比较。旧异步 refresh/login 因此不能覆盖用户刚保存的新凭据。
     */
    public synchronized CredentialUpdate updateCookieIfGeneration(
            ServerConfig source, String cookie, long expectedGeneration) {
        if (source == null) return null;
        ServerConfig owner = credentialOwner(source);
        ServerConfig canonical = owner != null ? owner : source;
        String key = credentialIdentity(canonical);
        long currentGeneration = ensureCredentialGeneration(canonical);
        if (expectedGeneration >= 0L && expectedGeneration != currentGeneration) {
            return new CredentialUpdate(canonical,
                new CredentialSnapshot(canonical.getCookie(), currentGeneration), false);
        }
        String value = cookie == null ? "" : cookie;
        source.setCookie(value);
        if (owner != null) owner.setCookie(value);
        // Keep currently materialized relay rows coherent for the rest of this process.
        for (ServerConfig server : servers) {
            if (normalizeUrl(server.getUrl()).equals(normalizeUrl(source.getUrl()))
                && (server == owner || server.isRelayMaster())) {
                server.setCookie(value);
            }
        }
        store.saveServers(servers);
        long generation = ++nextCredentialGeneration;
        credentialGenerations.put(key, generation);
        return new CredentialUpdate(canonical,
            new CredentialSnapshot(value, generation), true);
    }

    /** 原子读取当前权威 Cookie 与 generation。 */
    public synchronized CredentialSnapshot credentialSnapshot(ServerConfig source) {
        if (source == null) return new CredentialSnapshot("", 0L);
        ServerConfig owner = credentialOwner(source);
        ServerConfig canonical = owner != null ? owner : source;
        return new CredentialSnapshot(canonical.getCookie(),
            ensureCredentialGeneration(canonical));
    }

    private long ensureCredentialGeneration(ServerConfig server) {
        String key = credentialIdentity(server);
        Long generation = credentialGenerations.get(key);
        if (generation != null) return generation;
        long created = ++nextCredentialGeneration;
        credentialGenerations.put(key, created);
        return created;
    }

    private static String credentialIdentity(ServerConfig server) {
        if (server == null) return "";
        String id = safe(server.getId());
        if (!id.isEmpty()) return id;
        return normalizeUrl(server.getUrl()) + "\n" + safe(server.getUsername());
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
