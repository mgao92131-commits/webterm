package com.webterm.transport.api;

public interface TransportFactory {
    /** Create WebSocket transport, never null */
    MuxTransport createWebSocket(String baseUrl, String cookie, String sessionId);

    /** Create WebRTC DataChannel transport, returns null if P2P unavailable */
    MuxTransport createDataChannel(String deviceId);

    /**
     * Optionally initiate P2P establishment before {@link #createDataChannel} is called.
     * Implementations may start an asynchronous P2P handshake here; the caller will
     * fall back to WebSocket immediately if no DataChannel is available yet.
     */
    default void prepareDataChannel(String baseUrl, String cookie, String deviceId) {
    }
}
