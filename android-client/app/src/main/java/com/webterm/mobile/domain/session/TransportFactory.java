package com.webterm.mobile.domain.session;

public interface TransportFactory {
    /** Create WebSocket transport, never null */
    MuxTransport createWebSocket(String baseUrl, String cookie, String sessionId);

    /** Create WebRTC DataChannel transport, returns null if P2P unavailable */
    MuxTransport createDataChannel(String deviceId);
}
