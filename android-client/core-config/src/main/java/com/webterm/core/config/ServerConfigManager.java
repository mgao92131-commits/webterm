package com.webterm.core.config;

import java.util.ArrayList;
import java.util.List;

public final class ServerConfigManager {
    private final ServerConfigStore store;
    private final List<ServerConfig> servers = new ArrayList<>();

    public ServerConfigManager(ServerConfigStore store) {
        this.store = store;
    }

    public void load() {
        servers.clear();
        servers.addAll(store.loadServers());
    }

    public void save() {
        store.saveServers(servers);
    }

    public List<ServerConfig> servers() {
        return servers;
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
