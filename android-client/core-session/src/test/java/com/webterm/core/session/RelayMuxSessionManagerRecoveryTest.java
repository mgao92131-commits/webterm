package com.webterm.core.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Handler;

import com.webterm.transport.api.MuxTransport;
import com.webterm.transport.api.TransportFactory;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class RelayMuxSessionManagerRecoveryTest {

    private static Handler synchronousHandler() {
        Handler handler = mock(Handler.class);
        when(handler.post(any(Runnable.class))).thenAnswer(invocation -> {
            Runnable r = invocation.getArgument(0);
            r.run();
            return true;
        });
        when(handler.postDelayed(any(Runnable.class), any(long.class))).thenAnswer(invocation -> {
            Runnable r = invocation.getArgument(0);
            r.run();
            return true;
        });
        return handler;
    }

    @Test
    public void wsClose1001KeepsChannelAndReopens() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        AtomicBoolean connected = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean gone = new AtomicBoolean();
        AtomicInteger closedCode = new AtomicInteger();
        AtomicReference<String> closedReason = new AtomicReference<>();

        manager.openTerminalChannel("s1", new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) { connected.set(true); }
            @Override public void onError(String channelId, int code, String message) {}
            @Override public void onData(String channelId, byte[] payload, boolean binary) {}
            @Override public void onMuxDisconnected(String reason) {}
            @Override public void onClosed(String channelId, int code, String reason) {
                closed.set(true);
                closedCode.set(code);
                closedReason.set(reason);
            }
            @Override public void onChannelGone(String channelId, int code, String reason) {
                gone.set(true);
            }
        });

        manager.start();
        transport.simulateOpen();
        transport.simulateText("{\"type\":\"ws-connected\",\"tunnelConnectionId\":\"term:s1\"}");
        assertTrue("channel should be connected", connected.get());
        assertEquals("one ws-connect after mux open", 1, transport.wsConnectCount("term:s1"));

        transport.simulateText("{\"type\":\"ws-close\",\"tunnelConnectionId\":\"term:s1\",\"code\":1001,\"reason\":\"going away\"}");

        assertTrue("onClosed should fire for recoverable close", closed.get());
        assertEquals(1001, closedCode.get());
        assertEquals("going away", closedReason.get());
        assertFalse("onChannelGone should not fire for recoverable close", gone.get());
        assertEquals("ws-connect should be resent after recoverable close", 2, transport.wsConnectCount("term:s1"));
    }

    @Test
    public void wsClose1000RemovesChannel() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        AtomicBoolean connected = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean gone = new AtomicBoolean();
        AtomicInteger goneCode = new AtomicInteger();
        AtomicReference<String> goneReason = new AtomicReference<>();

        manager.openTerminalChannel("s1", new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) { connected.set(true); }
            @Override public void onError(String channelId, int code, String message) {}
            @Override public void onData(String channelId, byte[] payload, boolean binary) {}
            @Override public void onMuxDisconnected(String reason) {}
            @Override public void onClosed(String channelId, int code, String reason) { closed.set(true); }
            @Override public void onChannelGone(String channelId, int code, String reason) {
                gone.set(true);
                goneCode.set(code);
                goneReason.set(reason);
            }
        });

        manager.start();
        transport.simulateOpen();
        transport.simulateText("{\"type\":\"ws-connected\",\"tunnelConnectionId\":\"term:s1\"}");
        assertTrue(connected.get());

        transport.simulateText("{\"type\":\"ws-close\",\"tunnelConnectionId\":\"term:s1\",\"code\":1000,\"reason\":\"normal closure\"}");

        assertFalse("onClosed should not fire for permanent close", closed.get());
        assertTrue("onChannelGone should fire for permanent close", gone.get());
        assertEquals(1000, goneCode.get());
        assertEquals("normal closure", goneReason.get());
        assertEquals("ws-connect should not be resent after permanent close", 1, transport.wsConnectCount("term:s1"));
    }

    @Test
    public void wsError404RemovesChannel() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        AtomicBoolean connected = new AtomicBoolean();
        AtomicBoolean error = new AtomicBoolean();
        AtomicBoolean gone = new AtomicBoolean();
        AtomicInteger goneCode = new AtomicInteger();
        AtomicReference<String> goneMessage = new AtomicReference<>();

        manager.openTerminalChannel("s1", new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) { connected.set(true); }
            @Override public void onError(String channelId, int code, String message) { error.set(true); }
            @Override public void onData(String channelId, byte[] payload, boolean binary) {}
            @Override public void onMuxDisconnected(String reason) {}
            @Override public void onClosed(String channelId, int code, String reason) {}
            @Override public void onChannelGone(String channelId, int code, String reason) {
                gone.set(true);
                goneCode.set(code);
                goneMessage.set(reason);
            }
        });

        manager.start();
        transport.simulateOpen();
        transport.simulateText("{\"type\":\"ws-connected\",\"tunnelConnectionId\":\"term:s1\"}");
        assertTrue(connected.get());

        transport.simulateText("{\"type\":\"ws-error\",\"tunnelConnectionId\":\"term:s1\",\"code\":404,\"message\":\"session not found\"}");

        assertFalse("onError should not fire for permanent error", error.get());
        assertTrue("onChannelGone should fire for permanent error", gone.get());
        assertEquals(404, goneCode.get());
        assertEquals("session not found", goneMessage.get());
        assertEquals("ws-connect should not be resent after permanent error", 1, transport.wsConnectCount("term:s1"));
    }

    private static class FakeTransportFactory implements TransportFactory {
        private final FakeMuxTransport transport;

        FakeTransportFactory(FakeMuxTransport transport) {
            this.transport = transport;
        }

        @Override public MuxTransport createWebSocket(String url, String cookie, String protocol) {
            return transport;
        }

        @Override public MuxTransport createDataChannel(String deviceId) {
            return transport;
        }

        @Override public void prepareDataChannel(String baseUrl, String cookie, String deviceId) {}
    }

    private static class FakeMuxTransport implements MuxTransport {
        private Listener listener;
        private final List<String> sentTexts = new ArrayList<>();

        @Override public void start(Listener listener) {
            this.listener = listener;
        }

        public void simulateOpen() {
            if (listener != null) listener.onOpen();
        }

        public void simulateText(String text) {
            if (listener != null) listener.onText(text);
        }

        @Override public void close() {}
        @Override public boolean isConnected() { return listener != null; }
        @Override public boolean isP2P() { return false; }

        @Override public boolean sendText(String text) {
            sentTexts.add(text);
            return true;
        }

        @Override public boolean sendBinary(byte[] data) { return true; }

        int wsConnectCount(String tunnelId) {
            int count = 0;
            for (String text : sentTexts) {
                try {
                    JSONObject msg = new JSONObject(text);
                    if ("ws-connect".equals(msg.optString("type"))
                            && tunnelId.equals(msg.optString("tunnelConnectionId"))) {
                        count++;
                    }
                } catch (JSONException e) {
                    // ignore non-JSON
                }
            }
            return count;
        }
    }
}
