package com.webterm.core.session;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** 设备连接内 logical channel、route owner 与 channel 代际的唯一内存所有者。 */
public final class LogicalChannelRegistry {
    static final int DEFAULT_MAX_TOMBSTONES = 256;
    static final long DEFAULT_TOMBSTONE_TTL_NANOS = 60_000_000_000L;

    enum MissingClassification {
        NORMAL_CLOSE_TAIL,
        STALE_TRANSPORT_GENERATION,
        STALE_CHANNEL_LIFECYCLE,
        UNKNOWN_CHANNEL,
        CHANNEL_ID_REUSED,
        WRONG_CONNECTION_MAPPING
    }

    static final class Channel {
        enum State { CLOSED, OPENING, OPEN, CLOSING, RETRY_WAIT }

        final String id;
        final String path;
        final String[] protocols;
        final String screenRouteKey;
        DeviceConnection.ChannelListener listener;
        boolean desiredOpen = true;
        State state = State.CLOSED;
        long openGeneration;
        long retryGeneration;
        int retryAttempt;
        long lifecycleId;
        int openedTransportGeneration;
        long lastFrameAtNanos;
        int closeFenceVersion;
        long closeRequestedAtNanos;
        long closeGeneration;
        ConnectionCloseReason localCloseReason;
        Runnable closeCompletion;
        boolean reopenAfterClose;
        DeviceConnection.ChannelListener reopenListener;

        Channel(String id, String path, String[] protocols, String screenRouteKey,
                DeviceConnection.ChannelListener listener) {
            this.id = id;
            this.path = path;
            this.protocols = protocols;
            this.screenRouteKey = screenRouteKey;
            this.listener = listener;
        }
    }

    static final class Tombstone {
        final String channelId;
        final long lifecycleId;
        final int transportGeneration;
        final long closedAtNanos;
        final String closeReason;
        final long lastFrameAtNanos;
        final boolean closeAcknowledged;

        Tombstone(Channel channel, long closedAtNanos, String closeReason) {
            this.channelId = channel.id;
            this.lifecycleId = channel.lifecycleId;
            this.transportGeneration = channel.openedTransportGeneration;
            this.closedAtNanos = closedAtNanos;
            this.closeReason = closeReason == null ? "" : closeReason;
            this.lastFrameAtNanos = channel.lastFrameAtNanos;
            this.closeAcknowledged = "CLOSE_ACK".equals(this.closeReason);
        }
    }

    private final Map<String, Channel> channels = new LinkedHashMap<>();
    private final Map<String, String> screenOwners = new LinkedHashMap<>();
    private final LinkedHashMap<String, Tombstone> tombstones = new LinkedHashMap<>();
    private final LongSupplier nanoTime;
    private final int maxTombstones;
    private final long tombstoneTtlNanos;
    private long nextLifecycleId;

    LogicalChannelRegistry() {
        this(System::nanoTime, DEFAULT_MAX_TOMBSTONES, DEFAULT_TOMBSTONE_TTL_NANOS);
    }

    LogicalChannelRegistry(LongSupplier nanoTime, int maxTombstones, long tombstoneTtlNanos) {
        this.nanoTime = nanoTime == null ? System::nanoTime : nanoTime;
        this.maxTombstones = Math.max(1, maxTombstones);
        this.tombstoneTtlNanos = Math.max(1L, tombstoneTtlNanos);
    }

    Channel get(String channelId) {
        return channels.get(channelId);
    }

    Channel put(Channel channel) {
        pruneTombstones();
        channel.lifecycleId = ++nextLifecycleId;
        Channel previous = channels.put(channel.id, channel);
        if (previous != null) {
            addTombstone(previous, "CHANNEL_ID_REUSED");
        }
        return previous;
    }

    /** 原子声明 screen route owner，返回被替换的旧 channel id。 */
    String claimScreenOwner(String routeKey, String channelId) {
        return screenOwners.put(routeKey, channelId);
    }

    boolean removeIfCurrent(Channel channel) {
        return removeIfCurrent(channel, "REMOVED");
    }

    boolean removeIfCurrent(Channel channel, String closeReason) {
        if (channel == null || channels.get(channel.id) != channel) return false;
        channels.remove(channel.id);
        clearScreenOwnerIfCurrent(channel);
        addTombstone(channel, closeReason);
        return true;
    }

    void clearScreenOwnerIfCurrent(Channel channel) {
        if (channel == null || channel.screenRouteKey == null) return;
        if (channel.id.equals(screenOwners.get(channel.screenRouteKey))) {
            screenOwners.remove(channel.screenRouteKey);
        }
    }

    Channel[] snapshot() {
        return channels.values().toArray(new Channel[0]);
    }

    int size() {
        return channels.size();
    }

    void clear() {
        for (Channel channel : channels.values()) {
            addTombstone(channel, "REGISTRY_CLEARED");
        }
        channels.clear();
        screenOwners.clear();
    }

    MissingClassification classifyMissing(String channelId, int transportGeneration) {
        pruneTombstones();
        Tombstone tombstone = tombstones.get(channelId);
        if (tombstone == null) return MissingClassification.UNKNOWN_CHANNEL;
        if (tombstone.transportGeneration != 0
                && tombstone.transportGeneration != transportGeneration) {
            return MissingClassification.STALE_TRANSPORT_GENERATION;
        }
        if ("CHANNEL_ID_REUSED".equals(tombstone.closeReason)) {
            return MissingClassification.CHANNEL_ID_REUSED;
        }
        return MissingClassification.NORMAL_CLOSE_TAIL;
    }

    Tombstone tombstone(String channelId) {
        pruneTombstones();
        return tombstones.get(channelId);
    }

    void acknowledgeLifecycle(String channelId, long lifecycleId) {
        Tombstone tombstone = tombstones.get(channelId);
        Channel channel = channels.get(channelId);
        if (tombstone != null && channel != null && channel.lifecycleId == lifecycleId) {
            tombstones.remove(channelId);
        }
    }

    int tombstoneCount() {
        pruneTombstones();
        return tombstones.size();
    }

    private void addTombstone(Channel channel, String closeReason) {
        if (channel == null) return;
        long now = nanoTime.getAsLong();
        tombstones.remove(channel.id);
        tombstones.put(channel.id, new Tombstone(channel, now, closeReason));
        pruneTombstonesAt(now);
    }

    private void pruneTombstones() {
        pruneTombstonesAt(nanoTime.getAsLong());
    }

    private void pruneTombstonesAt(long now) {
        java.util.Iterator<Map.Entry<String, Tombstone>> iterator =
            tombstones.entrySet().iterator();
        while (iterator.hasNext()) {
            Tombstone tombstone = iterator.next().getValue();
            if (now - tombstone.closedAtNanos >= tombstoneTtlNanos) {
                iterator.remove();
            }
        }
        while (tombstones.size() > maxTombstones) {
            java.util.Iterator<String> keys = tombstones.keySet().iterator();
            if (!keys.hasNext()) break;
            keys.next();
            keys.remove();
        }
    }
}
