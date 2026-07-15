package com.webterm.feature.terminal.upload;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UploadConnectionKeysTest {
    @Test
    public void relayDeviceUsesRelayDeviceId() {
        assertEquals("https://relay.example.com\ndev-abc",
            UploadConnectionKeys.connectionKey("https://relay.example.com/", "dev-abc"));
    }

    @Test
    public void missingDeviceIdDoesNotInventDirectIdentity() {
        assertEquals("https://relay.example.com\n",
            UploadConnectionKeys.connectionKey("https://relay.example.com", null));
    }
}
