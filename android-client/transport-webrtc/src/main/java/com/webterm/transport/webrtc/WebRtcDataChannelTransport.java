package com.webterm.transport.webrtc;

import com.webterm.transport.api.MuxTransport;

import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;

public final class WebRtcDataChannelTransport implements MuxTransport {
    private static final String TAG = "WebRtcMuxTransport";

    private final P2PDataChannelEndpoint endpoint;
    private final AtomicInteger listenerGeneration = new AtomicInteger(0);
    private volatile Listener activeListener;
    private volatile WrapperListener currentWrapper;

    WebRtcDataChannelTransport(P2PDataChannelEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    public void start(Listener listener) {
        Log.i(TAG, "p2p mux transport start open=" + endpoint.isOpen());
        int generation = listenerGeneration.incrementAndGet();
        activeListener = listener;
        WrapperListener wrapper = new WrapperListener(generation);
        currentWrapper = wrapper;
        endpoint.setMuxListener(wrapper);
    }

    @Override
    public void close() {
        listenerGeneration.incrementAndGet();
        activeListener = null;
        WrapperListener wrapper = currentWrapper;
        currentWrapper = null;
        if (wrapper != null) {
            endpoint.clearMuxListener(wrapper);
        }
    }

    @Override
    public boolean isConnected() {
        return endpoint.isOpen();
    }

    @Override
    public boolean sendText(String text) {
        if (!isConnected()) return false;
        Log.i(TAG, "p2p mux text out");
        return endpoint.sendText(text);
    }

    @Override
    public boolean sendBinary(byte[] data) {
        if (!isConnected()) return false;
        return endpoint.sendBinary(data);
    }

    @Override
    public boolean isP2P() { return true; }

    private final class WrapperListener implements MuxTransport.Listener {
        private final int generation;

        WrapperListener(int generation) {
            this.generation = generation;
        }

        private boolean isCurrent() {
            return generation == listenerGeneration.get();
        }

        @Override
        public void onOpen() {
            if (!isCurrent()) return;
            Listener l = activeListener;
            if (l != null) l.onOpen();
        }

        @Override
        public void onText(String text) {
            if (!isCurrent()) return;
            Listener l = activeListener;
            if (l != null) l.onText(text);
        }

        @Override
        public void onBinary(byte[] data) {
            if (!isCurrent()) return;
            Listener l = activeListener;
            if (l != null) l.onBinary(data);
        }

        @Override
        public void onClosed(int code, String reason) {
            if (!isCurrent()) return;
            Listener l = activeListener;
            if (l != null) l.onClosed(1000, reason);
        }

        @Override
        public void onError(String message) {
            if (!isCurrent()) return;
            Listener l = activeListener;
            if (l != null) l.onError(message);
        }
    }
}
