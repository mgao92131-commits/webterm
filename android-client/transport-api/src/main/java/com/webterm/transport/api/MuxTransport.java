package com.webterm.transport.api;

public interface MuxTransport {
    interface Listener {
        void onOpen();
        void onText(String text);
        void onBinary(byte[] data);
        void onClosed(int code, String reason);
        void onError(String message);
    }

    void start(Listener listener);

    void close();

    boolean isConnected();

    boolean sendText(String text);

    boolean sendBinary(byte[] data);

    /** Returns true if this transport is a P2P (WebRTC) connection. Default false. */
    default boolean isP2P() { return false; }
}
