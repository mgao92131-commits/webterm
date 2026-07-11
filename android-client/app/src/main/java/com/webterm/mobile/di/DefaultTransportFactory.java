package com.webterm.mobile.di;

import com.webterm.transport.api.MuxTransport;
import com.webterm.transport.api.TransportFactory;
import com.webterm.transport.websocket.WebSocketMuxTransport;

import javax.inject.Inject;
import javax.inject.Singleton;

import okhttp3.OkHttpClient;

@Singleton
public final class DefaultTransportFactory implements TransportFactory {
    private final OkHttpClient http;

    @Inject
    public DefaultTransportFactory(OkHttpClient http) {
        this.http = http;
    }

    @Override
    public MuxTransport createWebSocket(String baseUrl, String cookie, String sessionId) {
        return new WebSocketMuxTransport(http, baseUrl, cookie, sessionId);
    }
}
