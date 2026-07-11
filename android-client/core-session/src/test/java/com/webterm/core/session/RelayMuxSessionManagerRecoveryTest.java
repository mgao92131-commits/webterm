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
        when(handler.postDelayed(any(Runnable.class), any(long.class))).thenReturn(true);
        return handler;
    }

    @Test
    public void reopenChannelReusesExisting() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        AtomicBoolean listener1Connected = new AtomicBoolean();
        AtomicBoolean listener2Connected = new AtomicBoolean();

        RelayMuxSessionManager.ChannelListener listener1 = new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) { listener1Connected.set(true); }
            @Override public void onError(String channelId, int code, String message) {}
            @Override public void onData(String channelId, byte[] payload, boolean binary) {}
            @Override public void onMuxDisconnected(String reason) {}
        };
        RelayMuxSessionManager.ChannelListener listener2 = new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) { listener2Connected.set(true); }
            @Override public void onError(String channelId, int code, String message) {}
            @Override public void onData(String channelId, byte[] payload, boolean binary) {}
            @Override public void onMuxDisconnected(String reason) {}
        };

        String channelId1 = manager.openTerminalChannel("s1", listener1);
        assertTrue("manager should report existing terminal channel", manager.hasTerminalChannel("s1"));

        manager.start();
        transport.simulateOpen();
        transport.simulateText("{\"type\":\"ws-connected\",\"tunnelConnectionId\":\"term:s1\"}");
        assertTrue("original listener should be notified when mux connects", listener1Connected.get());

        String channelId2 = manager.openTerminalChannel("s1", listener2);
        assertEquals("reattach should reuse the same channel id", channelId1, channelId2);
        assertTrue("reattached listener should be notified immediately when mux already connected", listener2Connected.get());
        assertEquals("reattach should not send another ws-connect", 1, transport.wsConnectCount("term:s1"));
    }

    @Test
    public void reattachWhileConnectingWaitsForWsConnected() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        AtomicBoolean listener1Connected = new AtomicBoolean();
        AtomicBoolean listener2Connected = new AtomicBoolean();

        RelayMuxSessionManager.ChannelListener listener1 = new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) { listener1Connected.set(true); }
            @Override public void onError(String channelId, int code, String message) {}
            @Override public void onData(String channelId, byte[] payload, boolean binary) {}
            @Override public void onMuxDisconnected(String reason) {}
        };
        RelayMuxSessionManager.ChannelListener listener2 = new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) { listener2Connected.set(true); }
            @Override public void onError(String channelId, int code, String message) {}
            @Override public void onData(String channelId, byte[] payload, boolean binary) {}
            @Override public void onMuxDisconnected(String reason) {}
        };

        String channelId1 = manager.openTerminalChannel("s1", listener1);
        manager.start();
        transport.simulateOpen();
        // ws-connected has NOT been received yet; channel is still CONNECTING.

        String channelId2 = manager.openTerminalChannel("s1", listener2);
        assertEquals("reattach should reuse the same channel id", channelId1, channelId2);
        assertFalse("reattached listener should NOT be notified before ws-connected", listener2Connected.get());

        transport.simulateText("{\"type\":\"ws-connected\",\"tunnelConnectionId\":\"term:s1\"}");
        assertTrue("reattached listener should be notified after ws-connected", listener2Connected.get());
    }

    @Test
    public void reattachAfterDetachRehandshakesToReplaceStaleServerClient() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        AtomicBoolean listener1Connected = new AtomicBoolean();
        AtomicBoolean listener2Connected = new AtomicBoolean();

        RelayMuxSessionManager.ChannelListener listener1 = new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) { listener1Connected.set(true); }
            @Override public void onError(String channelId, int code, String message) {}
            @Override public void onData(String channelId, byte[] payload, boolean binary) {}
            @Override public void onMuxDisconnected(String reason) {}
        };
        RelayMuxSessionManager.ChannelListener listener2 = new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) { listener2Connected.set(true); }
            @Override public void onError(String channelId, int code, String message) {}
            @Override public void onData(String channelId, byte[] payload, boolean binary) {}
            @Override public void onMuxDisconnected(String reason) {}
        };

        String channelId1 = manager.openTerminalChannel("s1", listener1);
        manager.start();
        transport.simulateOpen();
        transport.simulateText("{\"type\":\"ws-connected\",\"tunnelConnectionId\":\"term:s1\"}");
        assertTrue("original listener should be notified", listener1Connected.get());
        assertEquals("one ws-connect after mux open", 1, transport.wsConnectCount("term:s1"));

        // Simulate background detach: listener replaced with no-op, but channel stays alive.
        manager.detachChannelListener(channelId1);

        String channelId2 = manager.openTerminalChannel("s1", listener2);
        assertEquals("reattach should reuse the same channel id", channelId1, channelId2);
        assertFalse("reattached listener should NOT be notified before re-handshake", listener2Connected.get());
        assertEquals("reattach after detach must send a new ws-connect",
            2, transport.wsConnectCount("term:s1"));

        transport.simulateText("{\"type\":\"ws-connected\",\"tunnelConnectionId\":\"term:s1\"}");
        assertTrue("reattached listener should be notified after server ack", listener2Connected.get());
    }

    @Test
    public void reattachWithoutDetachWhileChannelConnectingRehandshakes() {
        // Regression test for the yellow-indicator stuck scenario. A physical mux
        // reconnect can put a channel into CONNECTING before the old Fragment has
        // detached. Reattaching must explicitly re-handshake instead of waiting
        // forever for a stale ws-connected acknowledgement.
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        AtomicBoolean listener1Connected = new AtomicBoolean();
        AtomicBoolean listener1MuxDisconnected = new AtomicBoolean();
        AtomicBoolean listener2Connected = new AtomicBoolean();

        RelayMuxSessionManager.ChannelListener listener1 = new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) { listener1Connected.set(true); }
            @Override public void onError(String channelId, int code, String message) {}
            @Override public void onData(String channelId, byte[] payload, boolean binary) {}
            @Override public void onMuxDisconnected(String reason) { listener1MuxDisconnected.set(true); }
        };
        RelayMuxSessionManager.ChannelListener listener2 = new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) { listener2Connected.set(true); }
            @Override public void onError(String channelId, int code, String message) {}
            @Override public void onData(String channelId, byte[] payload, boolean binary) {}
            @Override public void onMuxDisconnected(String reason) {}
        };

        manager.openTerminalChannel("s1", listener1);
        manager.start();
        transport.simulateOpen();
        transport.simulateText("{\"type\":\"ws-connected\",\"tunnelConnectionId\":\"term:s1\"}");
        assertTrue("original listener should be connected", listener1Connected.get());
        assertEquals("one ws-connect after mux open", 1, transport.wsConnectCount("term:s1"));

        // Physical mux reconnects (e.g. cookie refresh) before UI detaches.
        transport.simulateClose(1001, "going away");
        assertTrue("original listener should get mux disconnected", listener1MuxDisconnected.get());

        transport.simulateOpen();
        assertEquals("reopen after reconnect should send ws-connect",
            2, transport.wsConnectCount("term:s1"));
        // Do NOT ack ws-connected yet: the channel is still CONNECTING.

        // UI returns without ever calling detachChannelListener().
        manager.openTerminalChannel("s1", listener2);
        assertFalse("new listener should NOT be connected while channel is CONNECTING",
            listener2Connected.get());
        assertEquals("reattach must replace the stale pending handshake",
            3, transport.wsConnectCount("term:s1"));

        transport.simulateText("{\"type\":\"ws-connected\",\"tunnelConnectionId\":\"term:s1\"}");
        assertTrue("new listener should connect after the replacement handshake", listener2Connected.get());
    }

    @Test
    public void detachBeforeMuxReconnectAllowsCleanReattach() {
        // Counter-test: if the UI properly detaches before the mux reconnect,
        // reattach is recognized as wasDetached=true. The re-handshake ws-connect
        // is sent once the mux is back up, and the new listener connects after ack.
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        AtomicBoolean listener1Connected = new AtomicBoolean();
        AtomicBoolean listener2Connected = new AtomicBoolean();

        RelayMuxSessionManager.ChannelListener listener1 = new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) { listener1Connected.set(true); }
            @Override public void onError(String channelId, int code, String message) {}
            @Override public void onData(String channelId, byte[] payload, boolean binary) {}
            @Override public void onMuxDisconnected(String reason) {}
        };
        RelayMuxSessionManager.ChannelListener listener2 = new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) { listener2Connected.set(true); }
            @Override public void onError(String channelId, int code, String message) {}
            @Override public void onData(String channelId, byte[] payload, boolean binary) {}
            @Override public void onMuxDisconnected(String reason) {}
        };

        manager.openTerminalChannel("s1", listener1);
        manager.start();
        transport.simulateOpen();
        transport.simulateText("{\"type\":\"ws-connected\",\"tunnelConnectionId\":\"term:s1\"}");
        assertTrue(listener1Connected.get());
        assertEquals("one ws-connect after mux open", 1, transport.wsConnectCount("term:s1"));

        // UI properly detaches before leaving.
        manager.detachChannelListener("term:s1");

        // Physical mux drops while the UI is away.
        transport.simulateClose(1001, "going away");

        // UI returns before the mux has reconnected: wasDetached is true, so the
        // listener is rebound but no ws-connect is sent yet.
        manager.openTerminalChannel("s1", listener2);
        assertFalse("new listener should wait for re-handshake ack",
            listener2Connected.get());
        assertEquals("no extra ws-connect while mux is still down",
            1, transport.wsConnectCount("term:s1"));

        // Mux reconnects and reopens the channel.
        transport.simulateOpen();
        assertEquals("reopen after reconnect should send ws-connect",
            2, transport.wsConnectCount("term:s1"));

        transport.simulateText("{\"type\":\"ws-connected\",\"tunnelConnectionId\":\"term:s1\"}");
        assertTrue("new listener should be connected after server ack", listener2Connected.get());
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

    @Test
    public void recoverableWsCloseWhileMuxDisconnectedBuffersReconnect() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        AtomicBoolean connected = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean muxDisconnected = new AtomicBoolean();

        manager.openTerminalChannel("s1", new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) { connected.set(true); }
            @Override public void onError(String channelId, int code, String message) {}
            @Override public void onData(String channelId, byte[] payload, boolean binary) {}
            @Override public void onMuxDisconnected(String reason) { muxDisconnected.set(true); }
            @Override public void onClosed(String channelId, int code, String reason) { closed.set(true); }
            @Override public void onChannelGone(String channelId, int code, String reason) {}
        });

        manager.start();
        transport.simulateOpen();
        transport.simulateText("{\"type\":\"ws-connected\",\"tunnelConnectionId\":\"term:s1\"}");
        assertTrue(connected.get());
        assertEquals("one ws-connect after mux open", 1, transport.wsConnectCount("term:s1"));

        transport.simulateClose(1001, "going away");
        assertTrue("mux should report disconnected", muxDisconnected.get());

        transport.simulateText("{\"type\":\"ws-close\",\"tunnelConnectionId\":\"term:s1\",\"code\":1001,\"reason\":\"going away\"}");

        assertTrue("onClosed should fire for recoverable close", closed.get());
        assertEquals("no ws-connect while mux disconnected", 1, transport.wsConnectCount("term:s1"));
        assertFalse("channel should remain in channels", manager.isIdle());

        transport.simulateOpen();
        assertEquals("ws-connect resent via reopenChannels after mux reconnect", 2, transport.wsConnectCount("term:s1"));
    }

    @Test
    public void wsError500ReopensChannel() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        AtomicBoolean connected = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean error = new AtomicBoolean();
        AtomicBoolean gone = new AtomicBoolean();
        AtomicInteger closedCode = new AtomicInteger();

        manager.openTerminalChannel("s1", new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) { connected.set(true); }
            @Override public void onError(String channelId, int code, String message) { error.set(true); }
            @Override public void onData(String channelId, byte[] payload, boolean binary) {}
            @Override public void onMuxDisconnected(String reason) {}
            @Override public void onClosed(String channelId, int code, String reason) {
                closed.set(true);
                closedCode.set(code);
            }
            @Override public void onChannelGone(String channelId, int code, String reason) { gone.set(true); }
        });

        manager.start();
        transport.simulateOpen();
        transport.simulateText("{\"type\":\"ws-connected\",\"tunnelConnectionId\":\"term:s1\"}");
        assertTrue(connected.get());

        transport.simulateText("{\"type\":\"ws-error\",\"tunnelConnectionId\":\"term:s1\",\"code\":500,\"message\":\"server error\"}");

        assertTrue("onClosed should fire for 5xx error", closed.get());
        assertEquals(500, closedCode.get());
        assertFalse("onError should not fire for 5xx error", error.get());
        assertFalse("onChannelGone should not fire for 5xx error", gone.get());
        assertFalse("channel should remain in channels", manager.isIdle());
        assertEquals("ws-connect should be resent after 5xx error", 2, transport.wsConnectCount("term:s1"));
    }

    @Test
    public void wsError400ReportsError() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        AtomicBoolean connected = new AtomicBoolean();
        AtomicBoolean error = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean gone = new AtomicBoolean();

        manager.openTerminalChannel("s1", new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) { connected.set(true); }
            @Override public void onError(String channelId, int code, String message) { error.set(true); }
            @Override public void onData(String channelId, byte[] payload, boolean binary) {}
            @Override public void onMuxDisconnected(String reason) {}
            @Override public void onClosed(String channelId, int code, String reason) { closed.set(true); }
            @Override public void onChannelGone(String channelId, int code, String reason) { gone.set(true); }
        });

        manager.start();
        transport.simulateOpen();
        transport.simulateText("{\"type\":\"ws-connected\",\"tunnelConnectionId\":\"term:s1\"}");
        assertTrue(connected.get());

        transport.simulateText("{\"type\":\"ws-error\",\"tunnelConnectionId\":\"term:s1\",\"code\":400,\"message\":\"bad request\"}");

        assertTrue("onError should fire for 4xx non-404 error", error.get());
        assertFalse("onClosed should not fire for 4xx error", closed.get());
        assertFalse("onChannelGone should not fire for 4xx error", gone.get());
        assertFalse("channel should remain in channels", manager.isIdle());
        assertEquals("ws-connect should not be resent after 4xx error", 1, transport.wsConnectCount("term:s1"));
    }

    @Test
    public void recoverableWsCloseWithSynchronousCloseDoesNotReopen() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        AtomicBoolean connected = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();

        manager.openTerminalChannel("s1", new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) { connected.set(true); }
            @Override public void onError(String channelId, int code, String message) {}
            @Override public void onData(String channelId, byte[] payload, boolean binary) {}
            @Override public void onMuxDisconnected(String reason) {}
            @Override public void onClosed(String channelId, int code, String reason) {
                closed.set(true);
                manager.closeChannel(channelId);
            }
            @Override public void onChannelGone(String channelId, int code, String reason) {}
        });

        manager.start();
        transport.simulateOpen();
        transport.simulateText("{\"type\":\"ws-connected\",\"tunnelConnectionId\":\"term:s1\"}");
        assertTrue(connected.get());

        transport.simulateText("{\"type\":\"ws-close\",\"tunnelConnectionId\":\"term:s1\",\"code\":1001,\"reason\":\"going away\"}");

        assertTrue("onClosed should fire for recoverable close", closed.get());
        assertTrue("channel should be removed by synchronous close", manager.isIdle());
        assertEquals("ws-connect should not be resent after listener closed channel", 1, transport.wsConnectCount("term:s1"));
    }

    @Test
    public void channelLastSeqUpdatedAndRetainedOnReattach() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        AtomicBoolean connected = new AtomicBoolean();

        RelayMuxSessionManager.ChannelListener listener1 = new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) { connected.set(true); }
            @Override public void onError(String channelId, int code, String message) {}
            @Override public void onData(String channelId, byte[] payload, boolean binary) {}
            @Override public void onMuxDisconnected(String reason) {}
        };

        String channelId1 = manager.openTerminalChannel("s1", listener1);
        manager.start();
        transport.simulateOpen();
        transport.simulateText("{\"type\":\"ws-connected\",\"tunnelConnectionId\":\"term:s1\"}");
        assertTrue(connected.get());

        manager.updateChannelLastSeq(channelId1, 55L);
        assertEquals(55L, manager.getChannelLastSeq(channelId1));

        AtomicBoolean connected2 = new AtomicBoolean();
        manager.openTerminalChannel("s1", new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) { connected2.set(true); }
            @Override public void onError(String channelId, int code, String message) {}
            @Override public void onData(String channelId, byte[] payload, boolean binary) {}
            @Override public void onMuxDisconnected(String reason) {}
        });

        assertTrue(connected2.get());
        assertEquals(55L, manager.getChannelLastSeq(channelId1));
    }

    @Test
    public void channelLastSeqOnlyIncreases() {
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(new FakeMuxTransport()));

        manager.openTerminalChannel("s1", new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) {}
            @Override public void onError(String channelId, int code, String message) {}
            @Override public void onData(String channelId, byte[] payload, boolean binary) {}
            @Override public void onMuxDisconnected(String reason) {}
        });

        String channelId = RelayMuxSessionManager.terminalChannelId("s1");
        manager.updateChannelLastSeq(channelId, 100L);
        manager.updateChannelLastSeq(channelId, 50L);
        assertEquals(100L, manager.getChannelLastSeq(channelId));
        manager.updateChannelLastSeq(channelId, 150L);
        assertEquals(150L, manager.getChannelLastSeq(channelId));
    }

    @Test
    public void getChannelLastSeqReturnsZeroForMissingChannel() {
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(new FakeMuxTransport()));

        assertEquals(0L, manager.getChannelLastSeq("term:missing"));
    }

    private static class FakeTransportFactory implements TransportFactory {
        private final FakeMuxTransport transport;

        FakeTransportFactory(FakeMuxTransport transport) {
            this.transport = transport;
        }

        @Override public MuxTransport create(String url, String cookie, String protocol) {
            return transport;
        }
    }

    private static class FakeMuxTransport implements MuxTransport {
        private Listener listener;
        private final List<String> sentTexts = new ArrayList<>();
        private boolean open = false;
        private int closeCount;

        @Override public void start(Listener listener) {
            this.listener = listener;
        }

        public void simulateOpen() {
            open = true;
            if (listener != null) listener.onOpen();
        }

        public void simulateClose(int code, String reason) {
            open = false;
            if (listener != null) listener.onClosed(code, reason);
        }

        public void simulateText(String text) {
            if (listener != null) listener.onText(text);
        }

        @Override public void close() { closeCount++; }
        @Override public boolean isConnected() { return open; }

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

        int wsCloseCount(String tunnelId) {
            int count = 0;
            for (String text : sentTexts) {
                try {
                    JSONObject msg = new JSONObject(text);
                    if ("ws-close".equals(msg.optString("type"))
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
