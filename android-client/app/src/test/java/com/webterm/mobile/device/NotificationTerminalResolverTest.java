package com.webterm.mobile.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.webterm.core.api.DeviceConnectionKeys;
import com.webterm.core.config.ServerConfig;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class NotificationTerminalResolverTest {
    private static ServerConfig relayMaster(String url) {
        return new ServerConfig("relay_master", "中转服务", url, "cookie", "user", "pw",
            true, false, "");
    }

    private static ServerConfig relayDevice(String relayUrl, String deviceId) {
        return new ServerConfig("relay_dev_" + deviceId, "设备", relayUrl, "cookie", "user", "",
            false, true, deviceId);
    }

    @Test
    public void resolvesOnlineRelayDevice() {
        ServerConfig master = relayMaster("https://relay.example");
        ServerConfig device = relayDevice("https://relay.example", "dev-1");
        NotificationTerminalResolver.Result result = NotificationTerminalResolver.resolve(
            DeviceConnectionKeys.relay("https://relay.example", "dev-1"),
            Arrays.asList(master), Arrays.asList(device), true);
        assertEquals(NotificationTerminalResolver.ResolveStatus.RESOLVED, result.status);
        assertSame(device, result.server);
    }

    @Test
    public void waitsUntilRelayDeviceListLoads() {
        ServerConfig master = relayMaster("https://relay.example/");
        NotificationTerminalResolver.Result result = NotificationTerminalResolver.resolve(
            DeviceConnectionKeys.relay("https://relay.example", "dev-1"),
            Arrays.asList(master), Collections.emptyList(), false);
        assertEquals(NotificationTerminalResolver.ResolveStatus.WAITING_FOR_RELAY_DEVICES, result.status);
        assertNull(result.server);
    }

    @Test
    public void loadedRelayListDoesNotResolveOfflineDevice() {
        ServerConfig master = relayMaster("https://relay.example");
        NotificationTerminalResolver.Result result = NotificationTerminalResolver.resolve(
            DeviceConnectionKeys.relay("https://relay.example", "offline"),
            Arrays.asList(master), Collections.emptyList(), true);
        assertEquals(NotificationTerminalResolver.ResolveStatus.NOT_FOUND, result.status);
        assertNull(result.server);
    }
}
