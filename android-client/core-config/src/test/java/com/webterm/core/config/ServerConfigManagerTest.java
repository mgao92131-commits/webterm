package com.webterm.core.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;

import java.util.Arrays;

public class ServerConfigManagerTest {
    @Test
    public void updateCookieFromNavigationCopyUpdatesCanonicalAndPersists() {
        ServerConfigStore store = mock(ServerConfigStore.class);
        ServerConfig canonical = new ServerConfig("srv", "Mac", "http://example.test", "old", "u", "p");
        when(store.loadServers()).thenReturn(Arrays.asList(canonical));
        ServerConfigManager manager = new ServerConfigManager(store);
        manager.load();
        ServerConfig copy = new ServerConfig("srv", "Mac", "http://example.test", "old", "u", "p");

        ServerConfig owner = manager.updateCookie(copy, "fresh");

        assertSame(canonical, owner);
        assertEquals("fresh", canonical.getCookie());
        assertEquals("fresh", copy.getCookie());
        verify(store).saveServers(manager.servers());
    }

    @Test
    public void relayDeviceUsesRelayMasterCredentials() {
        ServerConfigStore store = mock(ServerConfigStore.class);
        ServerConfig master = new ServerConfig("relay", "Relay", "http://relay.test", "old", "u", "p",
            true, false, "");
        when(store.loadServers()).thenReturn(Arrays.asList(master));
        ServerConfigManager manager = new ServerConfigManager(store);
        manager.load();
        ServerConfig device = new ServerConfig("relay_dev_d1", "Mac", "http://relay.test", "old", "u", "",
            false, true, "d1");

        assertSame(master, manager.credentialOwner(device));
        manager.updateCookie(device, "fresh");

        assertEquals("fresh", master.getCookie());
        assertEquals("fresh", device.getCookie());
    }
}
