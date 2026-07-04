package com.webterm.core.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ServerConfigManagerTest {

    private ServerConfigStore store;
    private ServerConfigManager manager;

    @Before
    public void setUp() {
        store = mock(ServerConfigStore.class);
        manager = new ServerConfigManager(store);
    }

    @Test
    public void load_clearsAndLoadsFromStore() {
        ServerConfig server = new ServerConfig("srv1", "Mac", "http://mac.test", "", "", "");
        when(store.loadServers()).thenReturn(Arrays.asList(server));

        manager.load();

        assertEquals(1, manager.servers().size());
        assertSame(server, manager.servers().get(0));
    }

    @Test
    public void save_delegatesToStore() {
        manager.load();
        manager.save();
        verify(store).saveServers(anyList());
    }

    @Test
    public void save_persistsCurrentServers() {
        ServerConfig server = new ServerConfig("srv1", "Mac", "http://mac.test", "", "", "");
        when(store.loadServers()).thenReturn(new ArrayList<ServerConfig>());
        manager.load();
        manager.addOrUpdate(null, "Linux", "http://linux.test", "cookie", "u", "p");

        manager.save();

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(store).saveServers(captor.capture());
        List<ServerConfig> saved = captor.getValue();
        assertEquals(1, saved.size());
        assertEquals("Linux", saved.get(0).getName());
    }

    @Test
    public void addOrUpdate_createsNewServerWhenExistingIsNull() {
        when(store.loadServers()).thenReturn(new ArrayList<ServerConfig>());
        manager.load();

        manager.addOrUpdate(null, "Linux", "http://linux.test", "c", "u", "p");

        assertEquals(1, manager.servers().size());
        ServerConfig created = manager.servers().get(0);
        assertEquals("Linux", created.getName());
        assertEquals("http://linux.test", created.getUrl());
        assertEquals("c", created.getCookie());
    }

    @Test
    public void addOrUpdate_updatesExistingServer() {
        ServerConfig existing = new ServerConfig("srv1", "Mac", "http://mac.test", "", "", "");
        when(store.loadServers()).thenReturn(Arrays.asList(existing));
        manager.load();

        manager.addOrUpdate(existing, "Renamed", "http://new.test", "c", "u", "p");

        assertEquals("Renamed", existing.getName());
        assertEquals("http://new.test", existing.getUrl());
        assertEquals("c", existing.getCookie());
    }

    @Test
    public void remove_removesServer() {
        ServerConfig server = new ServerConfig("srv1", "Mac", "http://mac.test", "", "", "");
        when(store.loadServers()).thenReturn(new ArrayList<ServerConfig>(Arrays.asList(server)));
        manager.load();

        manager.remove(server);

        assertTrue(manager.servers().isEmpty());
    }
}
