package com.webterm.core.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.webterm.core.config.ServerConfig;

import org.json.JSONArray;
import org.junit.Test;

import java.util.List;

public final class RelayServiceDeviceConfigTest {
    @Test
    public void onlineDevicesInheritRelayConnectionCredentials() throws Exception {
        ServerConfig master = new ServerConfig(
            "master", "Relay", "https://relay.example", "sid=abc", "gao", "pw",
            true, false, "", false);
        JSONArray devices = new JSONArray("["
            + "{\"deviceId\":\"online-1\",\"deviceName\":\"PC\",\"online\":true},"
            + "{\"deviceId\":\"offline-1\",\"deviceName\":\"Old PC\",\"online\":false}]");

        List<ServerConfig> result = RelayService.toOnlineDeviceConfigs(master, devices);

        assertEquals(1, result.size());
        ServerConfig device = result.get(0);
        assertEquals("online-1", device.getDeviceId());
        assertEquals("https://relay.example", device.getUrl());
        assertEquals("sid=abc", device.getCookie());
        assertTrue(device.isRelayDevice());
    }
}
