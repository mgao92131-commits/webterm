package com.webterm.mobile;

import android.os.Handler;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import okhttp3.OkHttpClient;

@Singleton
public final class RelayMuxSessionRegistry {
    private final OkHttpClient http;
    private final Handler mainHandler;
    private final Map<String, RelayMuxSessionManager> managers = new LinkedHashMap<>();
    private RelayMuxSessionManager.TransportProvider transportProvider;

    @Inject
    public RelayMuxSessionRegistry(OkHttpClient http, Handler mainHandler) {
        this.http = http;
        this.mainHandler = mainHandler;
    }

    synchronized void setTransportProvider(RelayMuxSessionManager.TransportProvider provider) {
        transportProvider = provider;
    }

    synchronized RelayMuxSessionManager forDevice(String baseUrl, String cookie, String deviceId) {
        String key = key(baseUrl, cookie, deviceId);
        RelayMuxSessionManager manager = managers.get(key);
        if (manager == null) {
            manager = new RelayMuxSessionManager(http, mainHandler, baseUrl, cookie, deviceId, () -> transportProvider);
            managers.put(key, manager);
        }
        return manager;
    }

    synchronized void reconnectDevice(String deviceId, String reason) {
        for (RelayMuxSessionManager manager : managers.values().toArray(new RelayMuxSessionManager[0])) {
            if (RelayMuxSessionManager.safeEquals(manager.deviceId(), deviceId)) {
                manager.reconnectTransport(reason);
            }
        }
    }

    synchronized void releaseIfIdle(RelayMuxSessionManager manager) {
        if (manager == null) return;
        manager.stopIfIdle();
        if (!manager.isIdle()) return;
        managers.values().removeIf(value -> value == manager);
    }

    synchronized void shutdown() {
        for (RelayMuxSessionManager manager : managers.values().toArray(new RelayMuxSessionManager[0])) {
            manager.stop();
        }
        managers.clear();
        transportProvider = null;
    }

    private static String key(String baseUrl, String cookie, String deviceId) {
        return WebTermUrls.normalizeBaseUrl(baseUrl) + "\n" + (cookie == null ? "" : cookie) + "\n" + (deviceId == null ? "" : deviceId);
    }
}
