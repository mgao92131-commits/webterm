package com.webterm.transport.webrtc;

import com.webterm.transport.api.MuxTransport;

import android.util.Log;

import org.webrtc.DataChannel;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class P2PDataChannelEndpoint {
    private static final String TAG = "P2PDataChannelEndpoint";

    interface StateListener {
        void onOpen();
        void onClosed(String reason);
    }

    private final DataChannel dataChannel;
    private final StateListener stateListener;
    private MuxTransport.Listener muxListener;

    P2PDataChannelEndpoint(DataChannel dataChannel, StateListener stateListener) {
        this.dataChannel = dataChannel;
        this.stateListener = stateListener;
        this.dataChannel.registerObserver(new DataChannel.Observer() {
            @Override public void onBufferedAmountChange(long previousAmount) {
            }

            @Override public void onStateChange() {
                DataChannel.State state = dataChannel.state();
                if (state == DataChannel.State.OPEN) {
                    stateListener.onOpen();
                    MuxTransport.Listener listener = muxListener;
                    if (listener != null) listener.onOpen();
                } else if (state == DataChannel.State.CLOSED || state == DataChannel.State.CLOSING) {
                    String reason = "datachannel " + state.name().toLowerCase(java.util.Locale.US);
                    stateListener.onClosed(reason);
                    MuxTransport.Listener listener = muxListener;
                    if (listener != null) listener.onClosed(reason);
                }
            }

            @Override public void onMessage(DataChannel.Buffer buffer) {
                byte[] data = new byte[buffer.data.remaining()];
                buffer.data.get(data);
                MuxTransport.Listener listener = muxListener;
                if (listener == null) return;
                if (buffer.binary) {
                    Log.i(TAG, "p2p mux binary in bytes=" + data.length);
                    listener.onBinary(data);
                } else {
                    Log.i(TAG, "p2p mux text in bytes=" + data.length);
                    listener.onText(new String(data, StandardCharsets.UTF_8));
                }
            }
        });
    }

    synchronized void setMuxListener(MuxTransport.Listener listener) {
        muxListener = listener;
        if (listener != null && isOpen()) {
            listener.onOpen();
        }
    }

    synchronized void clearMuxListener(MuxTransport.Listener listener) {
        if (muxListener == listener) {
            muxListener = null;
        }
    }

    boolean isOpen() {
        return dataChannel.state() == DataChannel.State.OPEN;
    }

    boolean sendText(String text) {
        if (!isOpen()) return false;
        ByteBuffer data = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
        Log.i(TAG, "p2p mux text out bytes=" + data.remaining());
        return dataChannel.send(new DataChannel.Buffer(data, false));
    }

    boolean sendBinary(byte[] data) {
        if (!isOpen()) return false;
        Log.i(TAG, "p2p mux binary out bytes=" + data.length);
        return dataChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(data), true));
    }

    void close() {
        muxListener = null;
        dataChannel.unregisterObserver();
        try { dataChannel.close(); } catch (Exception ignored) {}
    }
}
