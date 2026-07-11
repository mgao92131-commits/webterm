package com.webterm.transport.api;

public interface TransportFactory {
    /** Create WebSocket transport, never null */
    MuxTransport createWebSocket(String baseUrl, String cookie, String sessionId);
}
