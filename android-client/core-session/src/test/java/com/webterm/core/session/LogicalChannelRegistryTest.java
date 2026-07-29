package com.webterm.core.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

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
        assertEquals(
            LogicalChannelRegistry.MissingClassification.CHANNEL_ID_REUSED,
            registry.classifyMissing("same", 0));
        registry.acknowledgeLifecycle("same", replacement.lifecycleId);
        assertNull(registry.tombstone("same"));
    }

    @Test
    public void closedChannelTailUsesBoundedTombstoneClassification() {
        AtomicLong now = new AtomicLong(100L);
        LogicalChannelRegistry registry =
            new LogicalChannelRegistry(now::get, 2, 10L);
        LogicalChannelRegistry.Channel first = channel("first", null);
        first.openedTransportGeneration = 3;
        registry.put(first);
        assertTrue(registry.removeIfCurrent(first, "CLIENT_CLOSE"));
        assertEquals(
            LogicalChannelRegistry.MissingClassification.NORMAL_CLOSE_TAIL,
            registry.classifyMissing("first", 3));

        LogicalChannelRegistry.Channel second = channel("second", null);
        LogicalChannelRegistry.Channel third = channel("third", null);
        registry.put(second);
        registry.removeIfCurrent(second, "CLIENT_CLOSE");
        registry.put(third);
        registry.removeIfCurrent(third, "CLIENT_CLOSE");
        assertEquals(2, registry.tombstoneCount());
        assertEquals(
            LogicalChannelRegistry.MissingClassification.UNKNOWN_CHANNEL,
            registry.classifyMissing("first", 3));

        now.addAndGet(10L);
        assertEquals(0, registry.tombstoneCount());
    }

    @Test
    public void tombstoneSeparatesOldTransportGeneration() {
        LogicalChannelRegistry registry = new LogicalChannelRegistry();
        LogicalChannelRegistry.Channel channel = channel("old-generation", null);
        channel.openedTransportGeneration = 7;
        registry.put(channel);
        registry.removeIfCurrent(channel, "TRANSPORT_REPLACED");

        assertEquals(
            LogicalChannelRegistry.MissingClassification.STALE_TRANSPORT_GENERATION,
            registry.classifyMissing("old-generation", 8));
    }

    private static LogicalChannelRegistry.Channel channel(String id, String route) {
        return new LogicalChannelRegistry.Channel(id, "/ws", null, route, LISTENER);
    }
}
