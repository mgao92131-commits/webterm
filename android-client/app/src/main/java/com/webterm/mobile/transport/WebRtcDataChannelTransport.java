package com.webterm.mobile.transport;

import com.webterm.mobile.domain.session.MuxTransport;

import android.util.Log;

public final class WebRtcDataChannelTransport implements MuxTransport {
    private static final String TAG = "WebRtcMuxTransport";

    private final P2PDataChannelEndpoint endpoint;
    private Listener listener;

    WebRtcDataChannelTransport(P2PDataChannelEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    public void start(Listener listener) {
        Log.i(TAG, "p2p mux transport start open=" + endpoint.isOpen());
        this.listener = listener;
        endpoint.setMuxListener(listener);
    }

    @Override
    public void close() {
        endpoint.clearMuxListener(listener);
        listener = null;
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
}
