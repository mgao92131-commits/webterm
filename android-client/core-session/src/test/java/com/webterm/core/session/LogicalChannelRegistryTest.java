package com.webterm.core.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LogicalChannelRegistryTest {
    private static final DeviceConnection.ChannelListener LISTENER =
        new DeviceConnection.ChannelListener() {
            @Override public void onConnected(String channelId) {}
            @Override public void onData(String channelId, byte[] payload, boolean binary) {}
            @Override public void onFailure(String channelId, ChannelFailure failure) {}
        };

    @Test
    public void screenOwnerReplacementIsAtomicAndOldRemovalCannotClearNewOwner() {
        LogicalChannelRegistry registry = new LogicalChannelRegistry();
        LogicalChannelRegistry.Channel oldChannel = channel("old", "route");
        LogicalChannelRegistry.Channel newChannel = channel("new", "route");
        registry.put(oldChannel);
        assertNull(registry.claimScreenOwner("route", "old"));

        registry.put(newChannel);
        assertEquals("old", registry.claimScreenOwner("route", "new"));
        assertTrue(registry.removeIfCurrent(oldChannel));
        registry.clearScreenOwnerIfCurrent(oldChannel);

        assertTrue(registry.removeIfCurrent(newChannel));
    }

    @Test
    public void staleChannelObjectCannotRemoveReplacementWithSameId() {
        LogicalChannelRegistry registry = new LogicalChannelRegistry();
        LogicalChannelRegistry.Channel oldChannel = channel("same", null);
        LogicalChannelRegistry.Channel replacement = channel("same", null);
        registry.put(oldChannel);
        registry.put(replacement);

        assertFalse(registry.removeIfCurrent(oldChannel));
        assertEquals(replacement, registry.get("same"));
    }

    private static LogicalChannelRegistry.Channel channel(String id, String route) {
        return new LogicalChannelRegistry.Channel(id, "/ws", null, route, LISTENER);
    }
}
