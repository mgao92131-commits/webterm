package com.webterm.mobile.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.webterm.core.api.DeviceConnectionKeys;
import com.webterm.core.config.ServerConfig;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class NotificationTerminalResolverTest {

    private static ServerConfig directServer(String url) {
        return new ServerConfig("id-" + url, "PC", url, "cookie", "user", "pw",
            false, false, "", true);
    }

    private static ServerConfig relayMaster(String url) {
        return new ServerConfig("relay_master", "中转服务", url, "cookie", "user", "pw",
            true, false, "", true);
    }

    private static ServerConfig relayDevice(String relayUrl, String deviceId) {
        return new ServerConfig("relay_dev_" + deviceId, "设备", relayUrl, "cookie", "user", "",
            false, true, deviceId, true);
    }

    @Test
    public void resolvesDirectServerFromSavedConfigs() {
        ServerConfig direct = directServer("http://pc.local:18080");
        List<ServerConfig> saved = Arrays.asList(direct);

        NotificationTerminalResolver.Result result = NotificationTerminalResolver.resolve(
            DeviceConnectionKeys.direct("http://pc.local:18080/"), saved, Collections.emptyList(), false);

        assertEquals(NotificationTerminalResolver.ResolveStatus.RESOLVED, result.status);
        assertSame(direct, result.server);
    }

    @Test
    public void skipsRelayMasterWhenMatchingSavedConfigs() {
        // Relay Master 的 deviceId 为空，若不被跳过会被 "direct" 规则误命中。
        ServerConfig master = relayMaster("https://relay.example");
        List<ServerConfig> saved = Arrays.asList(master);

        NotificationTerminalResolver.Result result = NotificationTerminalResolver.resolve(
            DeviceConnectionKeys.direct("https://relay.example"), saved, Collections.emptyList(), false);

        assertEquals(NotificationTerminalResolver.ResolveStatus.WAITING_FOR_RELAY_DEVICES, result.status);
        assertNull(result.server);
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
    public void waitsWhenRelayMasterMatchesButDeviceListEmpty() {
        ServerConfig master = relayMaster("https://relay.example/");

        NotificationTerminalResolver.Result result = NotificationTerminalResolver.resolve(
            DeviceConnectionKeys.relay("https://relay.example", "dev-1"),
            Arrays.asList(master), Collections.emptyList(), false);

        assertEquals(NotificationTerminalResolver.ResolveStatus.WAITING_FOR_RELAY_DEVICES, result.status);
        assertNull(result.server);
    }

    @Test
    public void notFoundWhenNothingMatches() {
        ServerConfig direct = directServer("http://pc.local:18080");

        NotificationTerminalResolver.Result result = NotificationTerminalResolver.resolve(
            DeviceConnectionKeys.relay("https://unknown.example", "dev-9"),
            Arrays.asList(direct), Collections.emptyList(), false);

        assertEquals(NotificationTerminalResolver.ResolveStatus.NOT_FOUND, result.status);
        assertNull(result.server);
    }

    @Test
    public void notFoundForEmptyConnectionKey() {
        NotificationTerminalResolver.Result result = NotificationTerminalResolver.resolve(
            "", Arrays.asList(directServer("http://pc.local")), null, false);

        assertEquals(NotificationTerminalResolver.ResolveStatus.NOT_FOUND, result.status);
    }

    @Test
    public void notFoundWhenRelayListLoadedButTargetIsOffline() {
        ServerConfig master = relayMaster("https://relay.example");

        NotificationTerminalResolver.Result result = NotificationTerminalResolver.resolve(
            DeviceConnectionKeys.relay("https://relay.example", "offline-device"),
            Arrays.asList(master), Collections.emptyList(), true);

        assertEquals(NotificationTerminalResolver.ResolveStatus.NOT_FOUND, result.status);
        assertNull(result.server);
    }
}
