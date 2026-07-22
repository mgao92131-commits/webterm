package com.webterm.core.session;

import android.os.Handler;
import android.os.HandlerThread;

import com.webterm.core.api.DeviceConnectionKeys;
import com.webterm.core.api.WebTermUrls;
import com.webterm.transport.api.TransportFactory;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class DeviceConnectionRegistry {
    interface StateHandlerFactory {
        StateHandler create(String key);
    }

    static final class StateHandler {
        final Handler handler;
        final Runnable shutdown;

        StateHandler(Handler handler, Runnable shutdown) {
            this.handler = handler;
            this.shutdown = shutdown;
        }
    }

    private final Handler mainHandler;
    private final TransportFactory transportFactory;
    private final StateHandlerFactory stateHandlerFactory;
    private final Map<String, DeviceConnection> managers = new LinkedHashMap<>();

    @Inject
    public DeviceConnectionRegistry(Handler mainHandler, TransportFactory transportFactory) {
        this(mainHandler, transportFactory, DeviceConnectionRegistry::newThreadStateHandler);
    }

    DeviceConnectionRegistry(Handler mainHandler, TransportFactory transportFactory,
                             StateHandlerFactory stateHandlerFactory) {
        this.mainHandler = mainHandler;
        this.transportFactory = transportFactory;
        this.stateHandlerFactory = stateHandlerFactory;
    }

    public synchronized DeviceConnection forDevice(String baseUrl, String cookie, String deviceId) {
        return getOrCreate(key(baseUrl, deviceId), baseUrl, cookie, deviceId);
    }

    /** Relay 设备连接：键空间为 baseUrl + deviceId，连接时携带 x-device-id。 */
    public synchronized DeviceConnection forRelayDevice(String baseUrl, String cookie, String deviceId) {
        return forDevice(baseUrl, cookie, deviceId);
    }

    /**
     * Direct 设备连接：键空间为 {@code direct:{configId}}，deviceId 为空，因此
     * HTTP/WS 请求不携带 x-device-id。同一地址的不同账户使用不同 configId，互不冲突。
     */
    public synchronized DeviceConnection forDirectDevice(String configId, String baseUrl, String cookie) {
        return getOrCreate(DeviceConnectionKeys.direct(configId, baseUrl), baseUrl, cookie, "");
    }

    private DeviceConnection getOrCreate(String key, String baseUrl, String cookie, String deviceId) {
        DeviceConnection manager = managers.get(key);
        // 键不变但地址/设备身份变化（如编辑 Direct 设备改了 IP）：matches() 不含 Cookie，
        // 因此这里只在 baseUrl/deviceId 变化时停掉旧连接并按新地址重建；仅 Cookie 变化走 updateCookie。
        if (manager != null && !manager.matches(baseUrl, cookie, deviceId)) {
            manager.setControlListener(null);
            manager.stop();
            managers.remove(key);
            manager = null;
        }
        if (manager == null) {
            StateHandler state = stateHandlerFactory.create(key);
            manager = new DeviceConnection(
                state.handler,
                mainHandler,
                baseUrl,
                cookie,
                deviceId,
                transportFactory,
                state.shutdown);
            managers.put(key, manager);
        } else {
            manager.updateCookie(cookie);
        }
        return manager;
    }

    public synchronized void releaseIfIdle(DeviceConnection manager) {
        if (manager == null) return;
        if (!manager.isIdle()) return;
        forceReleaseLocked(manager);
    }

    /** 显式删除设备时强制释放连接，不依赖异步 channel 关闭后的再次检查。 */
    public synchronized void forceRelease(DeviceConnection manager) {
        if (manager == null) return;
        forceReleaseLocked(manager);
    }

    /** Direct 设备删除的便捷入口。 */
    public synchronized void forceReleaseDirect(String configId, String baseUrl) {
        String key = DeviceConnectionKeys.direct(configId, baseUrl);
        DeviceConnection manager = managers.remove(key);
        if (manager == null) return;
        manager.setControlListener(null);
        manager.stop();
    }

    private void forceReleaseLocked(DeviceConnection manager) {
        // Collection.removeIf 是 API 24；项目 minSdk 为 23，使用显式迭代保持兼容。
        Iterator<Map.Entry<String, DeviceConnection>> iterator = managers.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() == manager) {
                iterator.remove();
            }
        }
        manager.setControlListener(null);
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

    private static StateHandler newThreadStateHandler(String key) {
        HandlerThread eventThread = new HandlerThread(
            "WebTerm-DeviceConnection-" + Integer.toHexString(key.hashCode()));
        eventThread.start();
        return new StateHandler(new Handler(eventThread.getLooper()), eventThread::quitSafely);
    }
}
