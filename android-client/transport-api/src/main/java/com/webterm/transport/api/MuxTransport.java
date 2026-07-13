package com.webterm.transport.api;

public interface MuxTransport {
    interface Listener {
        void onOpen();
        void onText(String text);
        void onBinary(byte[] data);
        void onClosed(int code, String reason);
        void onError(String message);
        /** HTTP handshake status when available; 0 denotes a transport error. */
        default void onError(int code, String message) {
            onError(message);
        }
    }

    void start(Listener listener);

    void close();

    boolean isConnected();

    boolean sendText(String text);

    boolean sendBinary(byte[] data);
}
