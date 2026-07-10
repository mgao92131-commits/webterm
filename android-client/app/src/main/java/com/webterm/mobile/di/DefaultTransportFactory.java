package com.webterm.mobile.di;

import com.webterm.core.config.ServerConfigStore;
import com.webterm.transport.api.MuxTransport;
import com.webterm.transport.api.TransportFactory;
import com.webterm.transport.webrtc.P2PConnectionManager;
import com.webterm.transport.websocket.WebSocketMuxTransport;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Singleton;

import okhttp3.OkHttpClient;

@Singleton
public final class DefaultTransportFactory implements TransportFactory {
    private final OkHttpClient http;
    private final P2PConnectionManager p2p;
    private final ServerConfigStore configStore;
    private final Executor p2pExecutor = Executors.newSingleThreadExecutor(
        r -> new Thread(r, "p2p-init")
    );

    @Inject
    public DefaultTransportFactory(OkHttpClient http, P2PConnectionManager p2p, ServerConfigStore configStore) {
        this.http = http;
        this.p2p = p2p;
        this.configStore = configStore;
    }

    @Override
    public MuxTransport createWebSocket(String baseUrl, String cookie, String sessionId) {
        return new WebSocketMuxTransport(http, baseUrl, cookie, sessionId);
    }

    @Override
    public MuxTransport createDataChannel(String deviceId) {
        return p2p.getDataChannelTransport(deviceId);
    }

    @Override
    public void prepareDataChannel(String baseUrl, String cookie, String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) return;
        if (configStore != null && !configStore.isP2PEnabled()) return;
        p2pExecutor.execute(() -> p2p.connectToDevice(baseUrl, cookie, deviceId));
    }
}
