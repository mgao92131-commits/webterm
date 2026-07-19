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

    /** 返回当前 WebSocket 收发统计快照；默认实现返回全零。 */
    default TrafficSnapshot trafficSnapshot() {
        return TrafficSnapshot.ZERO;
    }

    /** WebSocket 收发统计。 */
    final class TrafficSnapshot {
        public static final TrafficSnapshot ZERO = new TrafficSnapshot(0L, 0L, 0L, 0L);

        public final long rxFrames;
        public final long rxBytes;
        public final long txFrames;
        public final long txBytes;

        public TrafficSnapshot(long rxFrames, long rxBytes, long txFrames, long txBytes) {
            this.rxFrames = Math.max(0L, rxFrames);
            this.rxBytes = Math.max(0L, rxBytes);
            this.txFrames = Math.max(0L, txFrames);
            this.txBytes = Math.max(0L, txBytes);
        }

        @Override
        public String toString() {
            return "TrafficSnapshot{rxFrames=" + rxFrames
                + ", rxBytes=" + rxBytes
                + ", txFrames=" + txFrames
                + ", txBytes=" + txBytes + '}';
        }
    }
}
