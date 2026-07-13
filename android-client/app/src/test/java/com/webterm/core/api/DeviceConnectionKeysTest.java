package com.webterm.core.api;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DeviceConnectionKeysTest {

    @Test
    public void direct_usesLiteralDirectDeviceId() {
        assertEquals("http://example.com\ndirect", DeviceConnectionKeys.direct("http://example.com"));
    }

    @Test
    public void direct_normalizesTrailingSlash() {
        assertEquals("http://example.com\ndirect", DeviceConnectionKeys.direct("http://example.com/"));
        assertEquals("https://example.com\ndirect", DeviceConnectionKeys.direct("https://example.com//"));
    }

    @Test
    public void relay_usesRealDeviceId() {
        assertEquals("https://relay.example\ndev-123",
            DeviceConnectionKeys.relay("https://relay.example", "dev-123"));
    }

    @Test
    public void relay_nullDeviceIdYieldsEmptySegment() {
        assertEquals("https://relay.example\n", DeviceConnectionKeys.relay("https://relay.example", null));
    }

    @Test
    public void forDevice_directEmptyDeviceIdMapsToDirect() {
        assertEquals(DeviceConnectionKeys.direct("http://example.com"),
            DeviceConnectionKeys.forDevice("http://example.com", false, ""));
        assertEquals(DeviceConnectionKeys.direct("http://example.com"),
            DeviceConnectionKeys.forDevice("http://example.com", false, null));
    }

    @Test
    public void forDevice_directNonEmptyDeviceIdKept() {
        assertEquals("http://example.com\ncustom-id",
            DeviceConnectionKeys.forDevice("http://example.com", false, "custom-id"));
    }

    @Test
    public void forDevice_relayDeviceKeepsDeviceId() {
        assertEquals("https://relay.example\ndev-1",
            DeviceConnectionKeys.forDevice("https://relay.example/", true, "dev-1"));
    }
}
