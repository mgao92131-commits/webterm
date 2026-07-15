package com.webterm.core.api;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DeviceConnectionKeysTest {
    @Test
    public void relayUsesNormalizedUrlAndDeviceId() {
        assertEquals("https://relay.example\ndev-123",
            DeviceConnectionKeys.relay("https://relay.example/", "dev-123"));
        assertEquals("https://relay.example\ndev-123",
            DeviceConnectionKeys.forDevice("https://relay.example//", "dev-123"));
    }

    @Test
    public void nullDeviceIdYieldsEmptySegment() {
        assertEquals("https://relay.example\n",
            DeviceConnectionKeys.relay("https://relay.example", null));
    }
}
