package com.webterm.core.session;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Handler;

import com.webterm.transport.api.MuxTransport;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class MuxSessionControlTest {

    private static Handler synchronousHandler() {
        Handler handler = mock(Handler.class);
        when(handler.post(any(Runnable.class))).thenAnswer(invocation -> {
            Runnable r = invocation.getArgument(0);
            r.run();
            return true;
        });
        return handler;
    }

    @Test
    public void wsErrorCarriesCodeAndMessage() {
        FakeMuxTransport transport = new FakeMuxTransport();
        AtomicInteger codeRef = new AtomicInteger();
        AtomicReference<String> msgRef = new AtomicReference<>();
        MuxSession session = new MuxSession(transport, synchronousHandler(), new MuxSession.Listener() {
            @Override public void onMuxConnected() {}
            @Override public void onMuxDisconnected(String reason) {}
            @Override public void onTunnelConnected(String tunnelId) {}
            @Override public void onTunnelError(String tunnelId, int code, String message) {
                codeRef.set(code);
                msgRef.set(message);
            }
            @Override public void onTunnelData(String tunnelId, byte[] payload, boolean binary) {}
            @Override public void onTunnelClosed(String tunnelId, int code, String reason) {}
        });
        session.start();
        transport.simulateText("{\"type\":\"ws-error\",\"tunnelConnectionId\":\"term:s1\",\"code\":404,\"message\":\"not found\"}");
        assertEquals(404, codeRef.get());
        assertEquals("not found", msgRef.get());
    }

    @Test
    public void wsCloseCarriesCodeAndReason() {
        FakeMuxTransport transport = new FakeMuxTransport();
        AtomicInteger codeRef = new AtomicInteger();
        AtomicReference<String> reasonRef = new AtomicReference<>();
        MuxSession session = new MuxSession(transport, synchronousHandler(), new MuxSession.Listener() {
            @Override public void onMuxConnected() {}
            @Override public void onMuxDisconnected(String reason) {}
            @Override public void onTunnelConnected(String tunnelId) {}
            @Override public void onTunnelError(String tunnelId, int code, String message) {}
            @Override public void onTunnelData(String tunnelId, byte[] payload, boolean binary) {}
            @Override public void onTunnelClosed(String tunnelId, int code, String reason) {
                codeRef.set(code);
                reasonRef.set(reason);
            }
        });
        session.start();
        transport.simulateText("{\"type\":\"ws-close\",\"tunnelConnectionId\":\"term:s1\",\"code\":1001,\"reason\":\"going away\"}");
        assertEquals(1001, codeRef.get());
        assertEquals("going away", reasonRef.get());
    }

    @Test
    public void wsCloseDefaultsToNormalClosure() {
        FakeMuxTransport transport = new FakeMuxTransport();
        AtomicInteger codeRef = new AtomicInteger();
        AtomicReference<String> reasonRef = new AtomicReference<>();
        MuxSession session = new MuxSession(transport, synchronousHandler(), new MuxSession.Listener() {
            @Override public void onMuxConnected() {}
            @Override public void onMuxDisconnected(String reason) {}
            @Override public void onTunnelConnected(String tunnelId) {}
            @Override public void onTunnelError(String tunnelId, int code, String message) {}
            @Override public void onTunnelData(String tunnelId, byte[] payload, boolean binary) {}
            @Override public void onTunnelClosed(String tunnelId, int code, String reason) {
                codeRef.set(code);
                reasonRef.set(reason);
            }
        });
        session.start();
        transport.simulateText("{\"type\":\"ws-close\",\"tunnelConnectionId\":\"term:s1\"}");
        assertEquals(1000, codeRef.get());
        assertEquals("", reasonRef.get());
    }

    @Test
    public void wsErrorDefaultsToZeroAndEmptyMessage() {
        FakeMuxTransport transport = new FakeMuxTransport();
        AtomicInteger codeRef = new AtomicInteger();
        AtomicReference<String> msgRef = new AtomicReference<>();
        MuxSession session = new MuxSession(transport, synchronousHandler(), new MuxSession.Listener() {
            @Override public void onMuxConnected() {}
            @Override public void onMuxDisconnected(String reason) {}
            @Override public void onTunnelConnected(String tunnelId) {}
            @Override public void onTunnelError(String tunnelId, int code, String message) {
                codeRef.set(code);
                msgRef.set(message);
            }
            @Override public void onTunnelData(String tunnelId, byte[] payload, boolean binary) {}
            @Override public void onTunnelClosed(String tunnelId, int code, String reason) {}
        });
        session.start();
        transport.simulateText("{\"type\":\"ws-error\",\"tunnelConnectionId\":\"term:s1\"}");
        assertEquals(0, codeRef.get());
        assertEquals("", msgRef.get());
    }

    static class FakeMuxTransport implements MuxTransport {
        private Listener listener;

        @Override public void start(Listener listener) {
            this.listener = listener;
        }

        public void simulateText(String text) {
            if (listener != null) listener.onText(text);
        }

        @Override public void close() {}
        @Override public boolean isConnected() { return true; }
        @Override public boolean isP2P() { return false; }
        @Override public boolean sendText(String text) { return true; }
        @Override public boolean sendBinary(byte[] data) { return true; }
    }
}
