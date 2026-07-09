package com.webterm.transport.webrtc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.webterm.transport.api.MuxTransport;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

public class WebRtcDataChannelTransportTest {

    @Test
    public void start_withNewListener_isolatesOldListener() {
        P2PDataChannelEndpoint endpoint = mock(P2PDataChannelEndpoint.class);
        when(endpoint.isOpen()).thenReturn(false);

        WebRtcDataChannelTransport transport = new WebRtcDataChannelTransport(endpoint);

        RecordingListener listener1 = new RecordingListener();
        transport.start(listener1);

        ArgumentCaptor<MuxTransport.Listener> captor = ArgumentCaptor.forClass(MuxTransport.Listener.class);
        verify(endpoint).setMuxListener(captor.capture());
        MuxTransport.Listener wrapper1 = captor.getValue();

        // Replace with a second listener
        RecordingListener listener2 = new RecordingListener();
        transport.start(listener2);

        ArgumentCaptor<MuxTransport.Listener> captor2 = ArgumentCaptor.forClass(MuxTransport.Listener.class);
        verify(endpoint, times(2)).setMuxListener(captor2.capture());

        // Old wrapper events must not reach either listener
        wrapper1.onOpen();
        assertFalse(listener1.opened);
        assertFalse(listener2.opened);

        // New wrapper events reach listener2 (getValue returns the last captured value)
        MuxTransport.Listener wrapper2 = captor2.getValue();
        wrapper2.onOpen();
        assertTrue(listener2.opened);
    }

    @Test
    public void close_invalidatesCurrentListener() {
        P2PDataChannelEndpoint endpoint = mock(P2PDataChannelEndpoint.class);
        when(endpoint.isOpen()).thenReturn(false);

        WebRtcDataChannelTransport transport = new WebRtcDataChannelTransport(endpoint);

        RecordingListener listener = new RecordingListener();
        transport.start(listener);

        ArgumentCaptor<MuxTransport.Listener> captor = ArgumentCaptor.forClass(MuxTransport.Listener.class);
        verify(endpoint).setMuxListener(captor.capture());
        MuxTransport.Listener wrapper = captor.getValue();

        transport.close();
        verify(endpoint).clearMuxListener(wrapper);

        wrapper.onOpen();
        assertFalse(listener.opened);
    }

    private static final class RecordingListener implements MuxTransport.Listener {
        final List<String> messages = new ArrayList<>();
        final List<byte[]> binaries = new ArrayList<>();
        boolean opened;

        @Override public void onOpen() { opened = true; }
        @Override public void onText(String text) { messages.add(text); }
        @Override public void onBinary(byte[] data) { binaries.add(data); }
        @Override public void onClosed(int code, String reason) {}
        @Override public void onError(String message) {}
    }
}
