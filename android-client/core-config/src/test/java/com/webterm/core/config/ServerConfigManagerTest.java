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
	public void loadDropsNonRelayConfigs() {
		ServerConfigStore store = mock(ServerConfigStore.class);
		ServerConfig legacy = new ServerConfig("srv", "Mac", "http://example.test", "old", "u", "p");
		when(store.loadServers()).thenReturn(Arrays.asList(legacy));
		ServerConfigManager manager = new ServerConfigManager(store);
		manager.load();
		assertEquals(0, manager.servers().size());
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
