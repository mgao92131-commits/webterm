package com.webterm.core.session;

import java.util.LinkedHashMap;
import java.util.Map;

/** 设备连接内 logical channel、route owner 与 channel 代际的唯一内存所有者。 */
public final class LogicalChannelRegistry {
    static final class Channel {
        enum State { CLOSED, OPENING, OPEN, RETRY_WAIT }

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

        Channel(String id, String path, String[] protocols, String screenRouteKey,
                DeviceConnection.ChannelListener listener) {
            this.id = id;
            this.path = path;
            this.protocols = protocols;
            this.screenRouteKey = screenRouteKey;
            this.listener = listener;
        }
    }

    private final Map<String, Channel> channels = new LinkedHashMap<>();
    private final Map<String, String> screenOwners = new LinkedHashMap<>();

    Channel get(String channelId) {
        return channels.get(channelId);
    }

    Channel put(Channel channel) {
        return channels.put(channel.id, channel);
    }

    /** 原子声明 screen route owner，返回被替换的旧 channel id。 */
    String claimScreenOwner(String routeKey, String channelId) {
        return screenOwners.put(routeKey, channelId);
    }

    boolean removeIfCurrent(Channel channel) {
        if (channel == null || channels.get(channel.id) != channel) return false;
        channels.remove(channel.id);
        clearScreenOwnerIfCurrent(channel);
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
        channels.clear();
        screenOwners.clear();
    }
}
