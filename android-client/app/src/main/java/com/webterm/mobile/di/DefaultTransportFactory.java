package com.webterm.mobile.di;

import com.webterm.mobile.domain.session.MuxTransport;
import com.webterm.mobile.domain.session.TransportFactory;
import com.webterm.mobile.transport.P2PConnectionManager;
import com.webterm.mobile.transport.WebSocketMuxTransport;

import javax.inject.Inject;
import javax.inject.Singleton;

import okhttp3.OkHttpClient;

@Singleton
public final class DefaultTransportFactory implements TransportFactory {
    private final OkHttpClient http;
    private final P2PConnectionManager p2p;

    @Inject
    public DefaultTransportFactory(OkHttpClient http, P2PConnectionManager p2p) {
        this.http = http;
        this.p2p = p2p;
    }

    @Override
    public MuxTransport createWebSocket(String baseUrl, String cookie, String sessionId) {
        return new WebSocketMuxTransport(http, baseUrl, cookie, sessionId);
    }

    @Override
    public MuxTransport createDataChannel(String deviceId) {
        return p2p.getDataChannelTransport(deviceId);
    }
}
