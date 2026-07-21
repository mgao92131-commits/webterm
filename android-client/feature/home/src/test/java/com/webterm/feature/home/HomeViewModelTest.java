package com.webterm.feature.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.webterm.core.config.ServerConfig;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class HomeViewModelTest {
    private static ServerConfig direct(String id, String name) {
        return new ServerConfig(id, name, "http://192.168.1.20:8080", "c", "u", "p",
            false, false, "");
    }

    private static ServerConfig relay(String id, String name) {
        return new ServerConfig(id, name, "http://relay.test", "c", "u", "",
            false, true, id);
    }

    @Test
    public void directDevicesAppearInHomeList() {
        List<ServerConfig> merged = HomeViewModel.mergeDevices(
            Arrays.asList(direct("d1", "Mac")), Collections.<ServerConfig>emptyList());
        assertEquals(1, merged.size());
        assertTrue(merged.get(0).isDirectDevice());
    }

    @Test
    public void relayDevicesAppearInHomeList() {
        List<ServerConfig> merged = HomeViewModel.mergeDevices(
            Collections.<ServerConfig>emptyList(), Arrays.asList(relay("r1", "PC")));
        assertEquals(1, merged.size());
        assertTrue(merged.get(0).isRelayDevice());
    }

    @Test
    public void directDevicesSortBeforeRelay() {
        List<ServerConfig> merged = HomeViewModel.mergeDevices(
            Arrays.asList(direct("d1", "Mac")),
            Arrays.asList(relay("r1", "PC"), relay("r2", "Server")));
        assertEquals(3, merged.size());
        // 第一个必须是 Direct，其后两个是 Relay。
        assertTrue(merged.get(0).isDirectDevice());
        assertTrue(merged.get(1).isRelayDevice());
        assertTrue(merged.get(2).isRelayDevice());
    }

    @Test
    public void sortsByNameWithinGroups() {
        List<ServerConfig> merged = HomeViewModel.mergeDevices(
            Arrays.asList(direct("d2", "zeta"), direct("d1", "alpha")),
            Arrays.asList(relay("r2", "yankee"), relay("r1", "bravo")));
        assertEquals("alpha", merged.get(0).getName());
        assertEquals("zeta", merged.get(1).getName());
        assertEquals("bravo", merged.get(2).getName());
        assertEquals("yankee", merged.get(3).getName());
    }

    @Test
    public void emptyOnlyWhenBothListsEmpty() {
        assertTrue(HomeViewModel.mergeDevices(
            new ArrayList<ServerConfig>(), new ArrayList<ServerConfig>()).isEmpty());
        assertEquals(1, HomeViewModel.mergeDevices(
            Arrays.asList(direct("d1", "Mac")), new ArrayList<ServerConfig>()).size());
        assertEquals(1, HomeViewModel.mergeDevices(
            new ArrayList<ServerConfig>(), Arrays.asList(relay("r1", "PC"))).size());
    }

    @Test
    public void mergeDoesNotMutateInputs() {
        List<ServerConfig> direct = new ArrayList<>(Arrays.asList(direct("d2", "zeta"), direct("d1", "alpha")));
        HomeViewModel.mergeDevices(direct, Collections.<ServerConfig>emptyList());
        // 输入列表顺序不被排序副作用改变。
        assertEquals("zeta", direct.get(0).getName());
        assertEquals("alpha", direct.get(1).getName());
    }
}
