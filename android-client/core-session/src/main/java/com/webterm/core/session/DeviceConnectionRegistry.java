package com.webterm.core.session;

import android.os.Handler;
import android.os.HandlerThread;

import com.webterm.core.api.WebTermUrls;
import com.webterm.transport.api.TransportFactory;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class DeviceConnectionRegistry {
    private final Handler mainHandler;
    private final TransportFactory transportFactory;
    private final Map<String, DeviceConnection> managers = new LinkedHashMap<>();

    @Inject
    public DeviceConnectionRegistry(Handler mainHandler, TransportFactory transportFactory) {
        this.mainHandler = mainHandler;
        this.transportFactory = transportFactory;
    }

    public synchronized DeviceConnection forDevice(String baseUrl, String cookie, String deviceId) {
        String key = key(baseUrl, deviceId);
        DeviceConnection manager = managers.get(key);
        if (manager == null) {
            HandlerThread eventThread = new HandlerThread(
                "WebTerm-DeviceConnection-" + Integer.toHexString(key.hashCode()));
            eventThread.start();
            manager = new DeviceConnection(
                new Handler(eventThread.getLooper()),
                mainHandler,
                baseUrl,
                cookie,
                deviceId,
                transportFactory,
                eventThread::quitSafely);
            managers.put(key, manager);
        } else {
            manager.updateCookie(cookie);
        }
        return manager;
    }

    public synchronized void releaseIfIdle(DeviceConnection manager) {
        if (manager == null) return;
        if (!manager.isIdle()) return;
        managers.values().removeIf(value -> value == manager);
        manager.stop();
    }

    public synchronized void shutdown() {
        for (DeviceConnection manager : managers.values().toArray(new DeviceConnection[0])) {
            manager.stop();
        }
        managers.clear();
    }

    private static String key(String baseUrl, String deviceId) {
        // Cookie is a rotating auth token, not part of device identity.
        // A device should share one manager regardless of cookie refreshes.
        return WebTermUrls.normalizeBaseUrl(baseUrl) + "\n" + (deviceId == null ? "" : deviceId);
    }
}
