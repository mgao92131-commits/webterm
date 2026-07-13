package com.webterm.feature.home.repository;

import com.webterm.core.config.ServerConfig;
import com.webterm.core.session.ChannelFailure;
import com.webterm.core.session.RelayMuxSessionManager;
import com.webterm.core.session.RelayMuxSessionRegistry;
import com.webterm.feature.home.domain.ServerSessionMonitor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * WebSocket data source for a single server's session list.
 * Opens a /ws/sessions manager channel and forwards events to a listener.
 * Does not own connection lifecycle beyond open/close channel.
 */
@Singleton
public final class ServerSessionDataSource {

    private final RelayMuxSessionRegistry relayMuxRegistry;

    @Inject
    public ServerSessionDataSource(RelayMuxSessionRegistry relayMuxRegistry) {
        this.relayMuxRegistry = relayMuxRegistry;
    }

    public interface Listener {
        void onConnected();
        void onConnecting();
        void onDisconnected(ChannelFailure failure);
        void onSessions(JSONArray sessions);
        void onSession(JSONObject session);
        void onSessionClosed(String sessionId);
    }

    public void start(ServerConfig server, Listener listener) {
        RelayMuxSessionManager mux = muxFor(server);
        String channelId = managerChannelId(server);
        mux.openChannel(channelId, "/ws/sessions", null, new RelayMuxSessionManager.ChannelListener() {
            @Override
            public void onConnected(String id) {
                if (!channelId.equals(id)) return;
                listener.onConnected();
            }

            @Override
            public void onData(String id, byte[] payload, boolean binary) {
                if (!channelId.equals(id) || binary) return;
                dispatch(new String(payload, StandardCharsets.UTF_8), listener, server.getDeviceId());
            }

            @Override
            public void onFailure(String id, ChannelFailure failure) {
                if (!channelId.equals(id)) return;
                listener.onDisconnected(failure);
            }

            @Override
            public void onReconnectAttempt(int attempt) {
                listener.onConnecting();
            }
        });
        mux.start();
    }

    public void stop(ServerConfig server) {
        RelayMuxSessionManager mux = muxFor(server);
        mux.closeChannel(managerChannelId(server));
        relayMuxRegistry.releaseIfIdle(mux);
    }

    private RelayMuxSessionManager muxFor(ServerConfig server) {
        return relayMuxRegistry.forDevice(
            server.getUrl(),
            server.getCookie() != null ? server.getCookie() : "",
            server.getDeviceId()
        );
    }

    private void dispatch(String text, Listener listener, String relayDeviceId) {
        ServerSessionMonitor.dispatchMessage(text, new ServerSessionMonitor.Listener() {
            @Override
            public void onMonitorConnected() {
                // Channel-level connected is already reported via ChannelListener.onConnected.
            }

            @Override
            public void onMonitorPollingFallback() {
                // Repository handles fallback polling; data source only reports WS state.
            }

            @Override
            public void onMonitorSessions(JSONArray sessions) {
                listener.onSessions(sessions);
            }

            @Override
            public void onMonitorSession(JSONObject session) {
                listener.onSession(session);
            }

            @Override
            public void onMonitorSessionClosed(String sessionId) {
                listener.onSessionClosed(sessionId);
            }

            @Override
            public void onMonitorDevices(JSONArray devices) {
                // Device list updates are handled elsewhere.
            }

            @Override
            public void onMonitorError(String errorMsg) {
                listener.onDisconnected(ChannelFailure.muxTemporary(0, errorMsg));
            }
        }, relayDeviceId);
    }

    private static String managerChannelId(ServerConfig server) {
        String deviceId = server.getDeviceId() == null ? "" : server.getDeviceId();
        return "manager:" + deviceId;
    }
}
