package com.webterm.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import okhttp3.OkHttpClient;

final class AppContainer {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient();
    private final WebTermApi api = new WebTermApi(http);
    private final RelayMuxSessionRegistry relayMuxRegistry = new RelayMuxSessionRegistry(http, mainHandler);
    private final ServerConfigStore configStore;
    private final ServerConfigManager serverConfigs;
    private final TerminalCacheCoordinator terminalCache;

    AppContainer(Context context) {
        configStore = new ServerConfigStore(context);
        serverConfigs = new ServerConfigManager(configStore);
        terminalCache = new TerminalCacheCoordinator(context.getFilesDir());
    }

    Handler mainHandler() {
        return mainHandler;
    }

    OkHttpClient http() {
        return http;
    }

    WebTermApi api() {
        return api;
    }

    RelayMuxSessionRegistry relayMuxRegistry() {
        return relayMuxRegistry;
    }

    ServerConfigStore configStore() {
        return configStore;
    }

    ServerConfigManager serverConfigs() {
        return serverConfigs;
    }

    TerminalCacheCoordinator terminalCache() {
        return terminalCache;
    }

    void shutdown() {
        relayMuxRegistry.shutdown();
        http.dispatcher().cancelAll();
    }
}
