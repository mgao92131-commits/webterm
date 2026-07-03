package com.webterm.mobile.data.config;

import java.util.ArrayList;
import java.util.List;

public final class ServerConfigManager {
    private final ServerConfigStore store;
    private final List<ServerConfig> servers = new ArrayList<>();

    public ServerConfigManager(ServerConfigStore store) {
        this.store = store;
    }

    void load() {
        servers.clear();
        servers.addAll(store.loadServers());
    }

    void save() {
        store.saveServers(servers);
    }

    List<ServerConfig> servers() {
        return servers;
    }

    void addOrUpdate(ServerConfig existingServer, String name, String url, String cookie, String username, String password) {
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

    void remove(ServerConfig server) {
        servers.remove(server);
    }
}
