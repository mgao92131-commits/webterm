package com.webterm.core.session;

import android.os.Handler;

import com.webterm.core.api.WebTermUrls;
import com.webterm.transport.api.TransportFactory;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import okhttp3.OkHttpClient;

@Singleton
public final class RelayMuxSessionRegistry {
    private final OkHttpClient http;
    private final Handler mainHandler;
    private final TransportFactory transportFactory;
    private final Map<String, RelayMuxSessionManager> managers = new LinkedHashMap<>();

    @Inject
    public RelayMuxSessionRegistry(OkHttpClient http, Handler mainHandler, TransportFactory transportFactory) {
        this.http = http;
        this.mainHandler = mainHandler;
        this.transportFactory = transportFactory;
    }

    public synchronized RelayMuxSessionManager forDevice(String baseUrl, String cookie, String deviceId) {
        String key = key(baseUrl, cookie, deviceId);
        RelayMuxSessionManager manager = managers.get(key);
        if (manager == null) {
            manager = new RelayMuxSessionManager(http, mainHandler, baseUrl, cookie, deviceId, transportFactory);
            managers.put(key, manager);
        }
        return manager;
    }

    public synchronized void reconnectDevice(String deviceId, String reason) {
        for (RelayMuxSessionManager manager : managers.values().toArray(new RelayMuxSessionManager[0])) {
            if (RelayMuxSessionManager.safeEquals(manager.deviceId(), deviceId)) {
                manager.reconnectTransport(reason);
            }
        }
    }

    public synchronized void releaseIfIdle(RelayMuxSessionManager manager) {
        if (manager == null) return;
        manager.stopIfIdle();
        if (!manager.isIdle()) return;
        managers.values().removeIf(value -> value == manager);
    }

    public synchronized void shutdown() {
        for (RelayMuxSessionManager manager : managers.values().toArray(new RelayMuxSessionManager[0])) {
            manager.stop();
        }
        managers.clear();
    }

    private static String key(String baseUrl, String cookie, String deviceId) {
        return WebTermUrls.normalizeBaseUrl(baseUrl) + "\n" + (cookie == null ? "" : cookie) + "\n" + (deviceId == null ? "" : deviceId);
    }
}
