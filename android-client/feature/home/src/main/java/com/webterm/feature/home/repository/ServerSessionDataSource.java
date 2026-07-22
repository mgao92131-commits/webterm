package com.webterm.feature.home.repository;

import android.os.Handler;
import android.os.Looper;

import com.webterm.core.config.ServerConfig;
import com.webterm.core.session.ChannelFailure;
import com.webterm.core.session.DeviceConnection;
import com.webterm.core.session.DeviceConnectionRegistry;
import com.webterm.feature.home.domain.SessionMessageParser;

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

    private final DeviceConnectionRegistry deviceConnectionRegistry;
    private final Handler mainHandler;

    @Inject
    public ServerSessionDataSource(DeviceConnectionRegistry deviceConnectionRegistry,
                                   Handler mainHandler) {
        this.deviceConnectionRegistry = deviceConnectionRegistry;
        this.mainHandler = mainHandler;
    }

    /** Listener callbacks always run on the Android main thread. */
    public interface Listener {
        void onConnected();
        void onConnecting();
        void onDisconnected(ChannelFailure failure);
        void onSessions(JSONArray sessions);
        void onSession(JSONObject session);
        void onSessionClosed(String sessionId);
    }

    public void start(ServerConfig server, Listener listener) {
        DeviceConnection connection = connectionFor(server);
        String channelId = managerChannelId(server);
        connection.openChannel(channelId, "/ws/sessions", null, new DeviceConnection.ChannelListener() {
            @Override
            public void onConnected(String id) {
                if (!channelId.equals(id)) return;
                dispatchOnMain(listener::onConnected);
            }

            @Override
            public void onData(String id, byte[] payload, boolean binary) {
                if (!channelId.equals(id) || binary) return;
                dispatch(new String(payload, StandardCharsets.UTF_8), listener, server.getDeviceId());
            }

            @Override
            public void onFailure(String id, ChannelFailure failure) {
                if (!channelId.equals(id)) return;
                dispatchOnMain(() -> listener.onDisconnected(failure));
            }

            @Override
            public void onReconnectAttempt(int attempt) {
                dispatchOnMain(listener::onConnecting);
            }
        });
        connection.start();
    }

    public void stop(ServerConfig server) {
        DeviceConnection connection = connectionFor(server);
        connection.closeChannel(managerChannelId(server));
        deviceConnectionRegistry.releaseIfIdle(connection);
    }

    private DeviceConnection connectionFor(ServerConfig server) {
        return deviceConnectionRegistry.forDevice(
            server.getUrl(),
            server.getCookie() != null ? server.getCookie() : "",
            server.getDeviceId()
        );
    }

    void dispatch(String text, Listener listener, String relayDeviceId) {
        SessionMessageParser.dispatchMessage(text, new SessionMessageParser.Listener() {
            @Override
            public void onMonitorSessions(JSONArray sessions) {
                dispatchOnMain(() -> listener.onSessions(sessions));
            }

            @Override
            public void onMonitorSession(JSONObject session) {
                dispatchOnMain(() -> listener.onSession(session));
            }

            @Override
            public void onMonitorSessionClosed(String sessionId) {
                dispatchOnMain(() -> listener.onSessionClosed(sessionId));
            }

            @Override
            public void onMonitorError(String errorMsg) {
                dispatchOnMain(() -> listener.onDisconnected(
                    ChannelFailure.muxTemporary(0, errorMsg)));
            }
        }, relayDeviceId);
    }

    void dispatchOnMain(Runnable callback) {
        if (Looper.myLooper() == mainHandler.getLooper()) {
            callback.run();
        } else {
            mainHandler.post(callback);
        }
    }

    private static String managerChannelId(ServerConfig server) {
        String deviceId = server.getDeviceId() == null ? "" : server.getDeviceId();
        return "manager:" + deviceId;
    }
}
