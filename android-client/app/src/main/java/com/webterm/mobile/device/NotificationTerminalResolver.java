package com.webterm.mobile.device;

import com.webterm.core.api.DeviceConnectionKeys;
import com.webterm.core.api.WebTermUrls;
import com.webterm.core.config.ServerConfig;

import java.util.List;

/** 通知点击跳转的目标解析器（纯逻辑，无 Android 依赖，可单测）。
 * 输入 connectionKey + 持久化配置 + Relay 在线设备列表，按固定顺序查找目标设备。
 * Relay 设备配置只存在于 RelayService 内存中，因此匹配集合必须同时覆盖两处来源。 */
public final class NotificationTerminalResolver {

    public enum ResolveStatus {
        /** 命中目标设备。 */
        RESOLVED,
        /** connectionKey 属于某个 Relay Master 下的设备，但在线设备列表尚未拉到。 */
        WAITING_FOR_RELAY_DEVICES,
        /** 所有来源均无匹配。 */
        NOT_FOUND
    }

    public static final class Result {
        public final ResolveStatus status;
        /** RESOLVED 时非空。 */
        public final ServerConfig server;

        private Result(ResolveStatus status, ServerConfig server) {
            this.status = status;
            this.server = server;
        }
    }

    private NotificationTerminalResolver() {}

    public static Result resolve(String connectionKey, List<ServerConfig> savedServers,
                                 List<ServerConfig> relayDevices, boolean relayDevicesLoaded) {
        if (connectionKey == null || connectionKey.isEmpty()) {
            return new Result(ResolveStatus.NOT_FOUND, null);
        }
        // 1. 持久化配置中的设备；2. 跳过 Relay Master（它只是中转入口，不是具体设备）。
        if (savedServers != null) {
            for (ServerConfig server : savedServers) {
                if (server == null || server.isRelayMaster()) continue;
                String key = DeviceConnectionKeys.forDevice(
                    server.getUrl(), server.isRelayDevice(), server.getDeviceId());
                if (connectionKey.equals(key)) {
                    return new Result(ResolveStatus.RESOLVED, server);
                }
            }
        }
        // 3. RelayService 内存中的在线 Relay 设备。
        if (relayDevices != null) {
            for (ServerConfig device : relayDevices) {
                if (device == null) continue;
                String key = DeviceConnectionKeys.relay(device.getUrl(), device.getDeviceId());
                if (connectionKey.equals(key)) {
                    return new Result(ResolveStatus.RESOLVED, device);
                }
            }
        }
        // 4. baseUrl 与某个 Relay Master 一致：仅在设备列表尚未完成首次加载时等待。
        // 已成功加载但未命中，说明目标当前离线或已删除，应明确 NOT_FOUND，避免刷新循环。
        String baseUrl = baseUrlPart(connectionKey);
        if (!baseUrl.isEmpty() && savedServers != null) {
            for (ServerConfig server : savedServers) {
                if (server == null || !server.isRelayMaster()) continue;
                if (baseUrl.equals(WebTermUrls.normalizeBaseUrl(server.getUrl()))) {
                    return new Result(relayDevicesLoaded
                        ? ResolveStatus.NOT_FOUND
                        : ResolveStatus.WAITING_FOR_RELAY_DEVICES, null);
                }
            }
        }
        return new Result(ResolveStatus.NOT_FOUND, null);
    }

    private static String baseUrlPart(String connectionKey) {
        int idx = connectionKey.indexOf('\n');
        return idx >= 0 ? connectionKey.substring(0, idx) : connectionKey;
    }
}
