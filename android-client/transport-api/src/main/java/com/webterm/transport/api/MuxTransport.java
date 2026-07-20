package com.webterm.transport.api;

import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * 设置外部累计器。Transport 重建后只要继续使用同一累计器，
     * 统计就不会因重连而丢失。不调用或传入 null 时 Transport 自行累计。
     */
    default void setTrafficAccumulator(TrafficAccumulator accumulator) {}

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

    /**
     * 线程安全的 WebSocket 收发累计器。
     * 可以由调用方持有并在多次 Transport 重建之间复用，避免重连丢失统计。
     */
    final class TrafficAccumulator {
        private final AtomicLong rxFrames = new AtomicLong();
        private final AtomicLong rxBytes = new AtomicLong();
        private final AtomicLong txFrames = new AtomicLong();
        private final AtomicLong txBytes = new AtomicLong();

        public void recordRx(long bytes) {
            if (bytes <= 0) return;
            rxFrames.incrementAndGet();
            rxBytes.addAndGet(bytes);
        }

        public void recordTx(long bytes) {
            if (bytes <= 0) return;
            txFrames.incrementAndGet();
            txBytes.addAndGet(bytes);
        }

        public TrafficSnapshot snapshot() {
            return new TrafficSnapshot(rxFrames.get(), rxBytes.get(), txFrames.get(), txBytes.get());
        }
    }
}
