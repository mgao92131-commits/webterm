package com.webterm.mobile;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import android.util.Log;

import org.webrtc.DataChannel;

final class WebRtcDataChannelTransport implements MuxTransport {
    private static final String TAG = "WebRtcMuxTransport";

    private final DataChannel dataChannel;
    private Listener listener;
    private DataChannel.Observer observer;

    WebRtcDataChannelTransport(DataChannel dataChannel) {
        this.dataChannel = dataChannel;
    }

    @Override
    public void start(Listener listener) {
        Log.i(TAG, "p2p mux transport start state=" + dataChannel.state());
        this.listener = listener;
        if (observer != null) {
            dataChannel.unregisterObserver();
        }
        observer = new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(long previousAmount) {
            }

            @Override
            public void onStateChange() {
                DataChannel.State state = dataChannel.state();
                if (state == DataChannel.State.OPEN) {
                    listener.onOpen();
                } else if (state == DataChannel.State.CLOSED || state == DataChannel.State.CLOSING) {
                    listener.onClosed("datachannel " + state.name().toLowerCase(java.util.Locale.US));
                }
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                byte[] data = new byte[buffer.data.remaining()];
                buffer.data.get(data);
                if (buffer.binary) {
                    Log.i(TAG, "p2p mux binary in bytes=" + data.length);
                    listener.onBinary(data);
                } else {
                    Log.i(TAG, "p2p mux text in bytes=" + data.length);
                    listener.onText(new String(data, StandardCharsets.UTF_8));
                }
            }
        };
        dataChannel.registerObserver(observer);
        if (dataChannel.state() == DataChannel.State.OPEN) {
            listener.onOpen();
        }
    }

    @Override
    public void close() {
        if (observer != null) {
            dataChannel.unregisterObserver();
            observer = null;
        }
        listener = null;
        dataChannel.close();
    }

    @Override
    public boolean isConnected() {
        return dataChannel.state() == DataChannel.State.OPEN;
    }

    @Override
    public boolean sendText(String text) {
        if (!isConnected()) return false;
        ByteBuffer data = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
        Log.i(TAG, "p2p mux text out bytes=" + data.remaining());
        return dataChannel.send(new DataChannel.Buffer(data, false));
    }

    @Override
    public boolean sendBinary(byte[] data) {
        if (!isConnected()) return false;
        Log.i(TAG, "p2p mux binary out bytes=" + data.length);
        return dataChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(data), true));
    }
}
