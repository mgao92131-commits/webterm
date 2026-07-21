package com.webterm.core.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;

import java.util.Arrays;

public class ServerConfigManagerTest {
    private static ServerConfig direct(String id, String url, String user) {
        return new ServerConfig(id, "Direct", url, "cookie", user, "pw", false, false, "");
    }

    private static ServerConfig relayMaster(String id, String url) {
        return new ServerConfig(id, "Relay", url, "cookie", "u", "p", true, false, "");
    }

    private static ServerConfig relayDevice(String id, String url, String deviceId) {
        return new ServerConfig(id, "Device", url, "cookie", "u", "", false, true, deviceId);
    }

    // load 保留 Direct 设备与 Relay Master，只丢弃运行时的 Relay Device。
    @Test
    public void loadKeepsDirectAndRelayMasterButDropsRelayDevice() {
        ServerConfigStore store = mock(ServerConfigStore.class);
        when(store.loadServers()).thenReturn(Arrays.asList(
            direct("direct_1", "http://192.168.1.20:8080", "admin"),
            relayMaster("relay", "http://relay.test"),
            relayDevice("relay_dev_d1", "http://relay.test", "d1")));
        ServerConfigManager manager = new ServerConfigManager(store);
        manager.load();
        assertEquals(2, manager.servers().size());
        assertEquals(1, manager.directDevices().size());
    }

    @Test
    public void directDeviceIsDetected() {
        assertTrue(direct("d", "http://x", "u").isDirectDevice());
        assertFalse(relayMaster("m", "http://x").isDirectDevice());
        assertFalse(relayDevice("dev", "http://x", "d1").isDirectDevice());
    }

    @Test
    public void directDeviceSerializes() throws Exception {
        ServerConfig config = direct("direct_1", "http://192.168.1.20:8080", "admin");
        ServerConfig restored = ServerConfig.fromJSON(config.toJSON());
        assertTrue(restored.isDirectDevice());
        assertEquals("direct_1", restored.getId());
        assertEquals("http://192.168.1.20:8080", restored.getUrl());
        assertEquals("admin", restored.getUsername());
    }

    @Test
    public void addDirectDevicePersists() {
        ServerConfigStore store = mock(ServerConfigStore.class);
        when(store.loadServers()).thenReturn(Arrays.<ServerConfig>asList());
        ServerConfigManager manager = new ServerConfigManager(store);
        manager.load();

        manager.addDirectDevice(direct("direct_1", "http://192.168.1.20:8080", "admin"));

        assertEquals(1, manager.directDevices().size());
        verify(store).saveServers(anyList());
    }

    @Test
    public void duplicateDirectDeviceRejected() {
        ServerConfigStore store = mock(ServerConfigStore.class);
        when(store.loadServers()).thenReturn(Arrays.<ServerConfig>asList());
        ServerConfigManager manager = new ServerConfigManager(store);
        manager.load();
        manager.addDirectDevice(direct("direct_1", "http://192.168.1.20:8080", "admin"));

        // URL + 账户相同视为重复（含结尾斜杠归一化）。
        assertTrue(manager.containsDirectDevice("http://192.168.1.20:8080/", "admin"));
        // 不同账户不算重复。
        assertFalse(manager.containsDirectDevice("http://192.168.1.20:8080", "other"));
    }

    @Test
    public void removeDirectDeviceOnlyRemovesDirect() {
        ServerConfigStore store = mock(ServerConfigStore.class);
        ServerConfig master = relayMaster("relay", "http://relay.test");
        ServerConfig directConfig = direct("direct_1", "http://192.168.1.20:8080", "admin");
        when(store.loadServers()).thenReturn(Arrays.asList(master, directConfig));
        ServerConfigManager manager = new ServerConfigManager(store);
        manager.load();

        assertTrue(manager.removeDirectDevice("direct_1"));
        assertEquals(0, manager.directDevices().size());
        // Relay Master 不受影响。
        assertEquals(1, manager.servers().size());
        assertSame(master, manager.servers().get(0));

        // 不能用 removeDirectDevice 删除 Relay Master。
        assertFalse(manager.removeDirectDevice("relay"));
        assertEquals(1, manager.servers().size());
    }

    @Test
    public void directCredentialOwnerIsSelf() {
        ServerConfigStore store = mock(ServerConfigStore.class);
        ServerConfig directConfig = direct("direct_1", "http://192.168.1.20:8080", "admin");
        when(store.loadServers()).thenReturn(Arrays.asList(directConfig));
        ServerConfigManager manager = new ServerConfigManager(store);
        manager.load();

        assertSame(directConfig, manager.credentialOwner(directConfig));
    }

    @Test
    public void relayDeviceUsesRelayMasterCredentials() {
        ServerConfigStore store = mock(ServerConfigStore.class);
        ServerConfig master = relayMaster("relay", "http://relay.test");
        when(store.loadServers()).thenReturn(Arrays.asList(master));
        ServerConfigManager manager = new ServerConfigManager(store);
        manager.load();
        ServerConfig device = relayDevice("relay_dev_d1", "http://relay.test", "d1");

        assertSame(master, manager.credentialOwner(device));
        manager.updateCookie(device, "fresh");

        assertEquals("fresh", master.getCookie());
        assertEquals("fresh", device.getCookie());
    }

    @Test
    public void directDevicesListOnlyDirect() {
        ServerConfigStore store = mock(ServerConfigStore.class);
        when(store.loadServers()).thenReturn(Arrays.asList(
            direct("d1", "http://a", "u1"),
            relayMaster("relay", "http://relay.test"),
            direct("d2", "http://b", "u2")));
        ServerConfigManager manager = new ServerConfigManager(store);
        manager.load();
        assertEquals(2, manager.directDevices().size());
        for (ServerConfig config : manager.directDevices()) {
            assertNotNull(config);
            assertTrue(config.isDirectDevice());
        }
    }

    // ── C3：编辑保持 configId / 去重排除自身 ──────────────────────

    @Test
    public void updateDirectDevicePreservesConfigId() {
        ServerConfigStore store = mock(ServerConfigStore.class);
        ServerConfig config = direct("direct_1", "http://192.168.1.20:8080", "admin");
        when(store.loadServers()).thenReturn(Arrays.asList(config));
        ServerConfigManager manager = new ServerConfigManager(store);
        manager.load();

        assertTrue(manager.updateDirectDevice("direct_1", "http://10.0.0.5:8080", "new-cookie",
            "admin2", "pw2", "新名字"));

        assertEquals(1, manager.directDevices().size());
        ServerConfig updated = manager.directDevices().get(0);
        // configId 保持不变，connectionKey 因而稳定。
        assertEquals("direct_1", updated.getId());
        assertEquals("http://10.0.0.5:8080", updated.getUrl());
        assertEquals("new-cookie", updated.getCookie());
        assertEquals("admin2", updated.getUsername());
        assertEquals("新名字", updated.getName());
        verify(store).saveServers(anyList());
    }

    @Test
    public void updateDirectDeviceMissingIdFails() {
        ServerConfigStore store = mock(ServerConfigStore.class);
        when(store.loadServers()).thenReturn(Arrays.asList(direct("direct_1", "http://a", "u")));
        ServerConfigManager manager = new ServerConfigManager(store);
        manager.load();
        assertFalse(manager.updateDirectDevice("nope", "http://b", "c", "u", "p", "n"));
    }

    @Test
    public void editingSelfIsNotDuplicate() {
        ServerConfigStore store = mock(ServerConfigStore.class);
        when(store.loadServers()).thenReturn(
            Arrays.asList(direct("direct_1", "http://192.168.1.20:8080", "admin")));
        ServerConfigManager manager = new ServerConfigManager(store);
        manager.load();

        // 不排除自身时，相同 URL+账户算重复。
        assertTrue(manager.containsDirectDevice("http://192.168.1.20:8080", "admin"));
        // 编辑自身（排除自己的 configId）时，只改密码不算重复。
        assertFalse(manager.containsDirectDevice("http://192.168.1.20:8080", "admin", "direct_1"));
    }

    @Test
    public void editingToAnotherDevicesIdentityIsDuplicate() {
        ServerConfigStore store = mock(ServerConfigStore.class);
        when(store.loadServers()).thenReturn(Arrays.asList(
            direct("direct_1", "http://a", "u1"),
            direct("direct_2", "http://b", "u2")));
        ServerConfigManager manager = new ServerConfigManager(store);
        manager.load();

        // direct_2 改成 direct_1 的 URL+账户应被判为重复。
        assertTrue(manager.containsDirectDevice("http://a", "u1", "direct_2"));
        // direct_2 保持自己的身份则不重复。
        assertFalse(manager.containsDirectDevice("http://b", "u2", "direct_2"));
    }
}
