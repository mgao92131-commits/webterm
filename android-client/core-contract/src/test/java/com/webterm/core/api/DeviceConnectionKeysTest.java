package com.webterm.core.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DeviceConnectionKeysTest {
    private static final String URL = "http://192.168.1.20:8080";

    @Test
    public void directAndRelayKeysDoNotCollide() {
        String direct = DeviceConnectionKeys.direct("direct_1", URL);
        // Relay 设备即使 deviceId 为空，键也与 Direct 键不同。
        String relayEmptyDevice = DeviceConnectionKeys.relay(URL, "");
        String relayWithDevice = DeviceConnectionKeys.relay(URL, "d1");
        assertNotEquals(direct, relayEmptyDevice);
        assertNotEquals(direct, relayWithDevice);
    }

    @Test
    public void twoDirectConfigsDoNotCollide() {
        // 同一地址、不同账户（不同 configId）必须得到不同的连接键。
        String a = DeviceConnectionKeys.direct("direct_admin", URL);
        String b = DeviceConnectionKeys.direct("direct_guest", URL);
        assertNotEquals(a, b);
    }

    @Test
    public void directKeyIsStableForSameConfig() {
        assertEquals(
            DeviceConnectionKeys.direct("direct_1", URL),
            DeviceConnectionKeys.direct("direct_1", URL));
    }

    @Test
    public void directKeyUsesConfigIdPrefix() {
        assertEquals("direct:direct_1", DeviceConnectionKeys.direct("direct_1", URL));
        assertTrue(DeviceConnectionKeys.direct("direct_1", URL).startsWith("direct:"));
    }

    @Test
    public void directKeyFallsBackToUrlWhenConfigIdMissing() {
        String byUrl = DeviceConnectionKeys.direct("", URL);
        assertEquals("direct:" + URL, byUrl);
        // 不同地址回退后仍不冲突。
        assertNotEquals(byUrl, DeviceConnectionKeys.direct("", "http://10.0.0.5:8080"));
    }

    @Test
    public void relayKeyPreservesLegacyFormat() {
        // 既有 Relay 键格式保持不变，避免破坏已缓存的连接身份。
        assertEquals(
            WebTermUrls.normalizeBaseUrl(URL) + "\n" + "d1",
            DeviceConnectionKeys.relay(URL, "d1"));
        assertEquals(DeviceConnectionKeys.relay(URL, "d1"), DeviceConnectionKeys.forDevice(URL, "d1"));
    }
}
