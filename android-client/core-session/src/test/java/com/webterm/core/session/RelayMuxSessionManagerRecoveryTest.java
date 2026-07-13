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

    private static String wsConnected(String channelId) {
        return "{\"type\":\"ws-connected\",\"tunnelConnectionId\":\"" + channelId + "\"}";
    }

    private static String wsClose(String channelId, int code, String reason) {
        return "{\"type\":\"ws-close\",\"tunnelConnectionId\":\"" + channelId
                + "\",\"code\":" + code + ",\"reason\":\"" + reason + "\"}";
    }

    private static String wsError(String channelId, int code, String message) {
        return "{\"type\":\"ws-error\",\"tunnelConnectionId\":\"" + channelId
                + "\",\"code\":" + code + ",\"message\":\"" + message + "\"}";
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

        String channelId1 = manager.openScreenChannel("s1", listener1);
        assertFalse("manager should report an open channel", manager.isIdle());

        manager.start();
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId1));
        assertTrue("original listener should be notified when mux connects", listener1Connected.get());

        String channelId2 = manager.openScreenChannel("s1", listener2);
        assertEquals("reattach should reuse the same channel id", channelId1, channelId2);
        assertTrue("reattached listener should be notified immediately when mux already connected", listener2Connected.get());
        assertEquals("reattach should not send another ws-connect", 1, transport.wsConnectCount(channelId1));
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

        String channelId1 = manager.openScreenChannel("s1", listener1);
        manager.start();
        transport.simulateOpen();
        // ws-connected has NOT been received yet; channel is still CONNECTING.

        String channelId2 = manager.openScreenChannel("s1", listener2);
        assertEquals("reattach should reuse the same channel id", channelId1, channelId2);
        assertFalse("reattached listener should NOT be notified before ws-connected", listener2Connected.get());

        transport.simulateText(wsConnected(channelId1));
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

        String channelId1 = manager.openScreenChannel("s1", listener1);
        manager.start();
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId1));
        assertTrue("original listener should be notified", listener1Connected.get());
        assertEquals("one ws-connect after mux open", 1, transport.wsConnectCount(channelId1));

        // Simulate background detach: listener replaced with no-op, but channel stays alive.
        manager.detachChannelListener(channelId1);

        String channelId2 = manager.openScreenChannel("s1", listener2);
        assertEquals("reattach should reuse the same channel id", channelId1, channelId2);
        assertFalse("reattached listener should NOT be notified before re-handshake", listener2Connected.get());
        assertEquals("reattach after detach must send a new ws-connect",
            2, transport.wsConnectCount(channelId1));

        transport.simulateText(wsConnected(channelId1));
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

        String channelId = manager.openScreenChannel("s1", listener1);
        manager.start();
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));
        assertTrue("original listener should be connected", listener1Connected.get());
        assertEquals("one ws-connect after mux open", 1, transport.wsConnectCount(channelId));

        // Physical mux reconnects (e.g. cookie refresh) before UI detaches.
        transport.simulateClose(1001, "going away");
        assertTrue("original listener should get mux disconnected", listener1MuxDisconnected.get());

        transport.simulateOpen();
        assertEquals("reopen after reconnect should send ws-connect",
            2, transport.wsConnectCount(channelId));
        // Do NOT ack ws-connected yet: the channel is still CONNECTING.

        // UI returns without ever calling detachChannelListener().
        manager.openScreenChannel("s1", listener2);
        assertFalse("new listener should NOT be connected while channel is CONNECTING",
            listener2Connected.get());
        assertEquals("reattach must replace the stale pending handshake",
            3, transport.wsConnectCount(channelId));

        transport.simulateText(wsConnected(channelId));
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

        String channelId = manager.openScreenChannel("s1", listener1);
        manager.start();
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));
        assertTrue(listener1Connected.get());
        assertEquals("one ws-connect after mux open", 1, transport.wsConnectCount(channelId));

        // UI properly detaches before leaving.
        manager.detachChannelListener(channelId);

        // Physical mux drops while the UI is away.
        transport.simulateClose(1001, "going away");

        // UI returns before the mux has reconnected: wasDetached is true, so the
        // listener is rebound but no ws-connect is sent yet.
        manager.openScreenChannel("s1", listener2);
        assertFalse("new listener should wait for re-handshake ack",
            listener2Connected.get());
        assertEquals("no extra ws-connect while mux is still down",
            1, transport.wsConnectCount(channelId));

        // Mux reconnects and reopens the channel.
        transport.simulateOpen();
        assertEquals("reopen after reconnect should send ws-connect",
            2, transport.wsConnectCount(channelId));

        transport.simulateText(wsConnected(channelId));
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

        String channelId = manager.openScreenChannel("s1", new RelayMuxSessionManager.ChannelListener() {
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
        transport.simulateText(wsConnected(channelId));
        assertTrue("channel should be connected", connected.get());
        assertEquals("one ws-connect after mux open", 1, transport.wsConnectCount(channelId));

        transport.simulateText(wsClose(channelId, 1001, "going away"));

        assertTrue("onClosed should fire for recoverable close", closed.get());
        assertEquals(1001, closedCode.get());
        assertEquals("going away", closedReason.get());
        assertFalse("onChannelGone should not fire for recoverable close", gone.get());
        assertEquals("ws-connect should be resent after recoverable close", 2, transport.wsConnectCount(channelId));
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

        String channelId = manager.openScreenChannel("s1", new RelayMuxSessionManager.ChannelListener() {
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
        transport.simulateText(wsConnected(channelId));
        assertTrue(connected.get());

        transport.simulateText(wsClose(channelId, 1000, "normal closure"));

        assertFalse("onClosed should not fire for permanent close", closed.get());
        assertTrue("onChannelGone should fire for permanent close", gone.get());
        assertEquals(1000, goneCode.get());
        assertEquals("normal closure", goneReason.get());
        assertEquals("ws-connect should not be resent after permanent close", 1, transport.wsConnectCount(channelId));
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

        String channelId = manager.openScreenChannel("s1", new RelayMuxSessionManager.ChannelListener() {
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
        transport.simulateText(wsConnected(channelId));
        assertTrue(connected.get());

        transport.simulateText(wsError(channelId, 404, "session not found"));

        assertFalse("onError should not fire for permanent error", error.get());
        assertTrue("onChannelGone should fire for permanent error", gone.get());
        assertEquals(404, goneCode.get());
        assertEquals("session not found", goneMessage.get());
        assertEquals("ws-connect should not be resent after permanent error", 1, transport.wsConnectCount(channelId));
    }

    @Test
    public void wsError401RemovesChannel() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        AtomicBoolean connected = new AtomicBoolean();
        AtomicBoolean error = new AtomicBoolean();
        AtomicBoolean gone = new AtomicBoolean();
        AtomicInteger goneCode = new AtomicInteger();
        AtomicReference<String> goneMessage = new AtomicReference<>();

        String channelId = manager.openScreenChannel("s1", new RelayMuxSessionManager.ChannelListener() {
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
        transport.simulateText(wsConnected(channelId));
        assertTrue(connected.get());

        transport.simulateText(wsError(channelId, 401, "unauthorized"));

        assertFalse("onError should not fire for auth error", error.get());
        assertTrue("onChannelGone should fire for auth error", gone.get());
        assertEquals(401, goneCode.get());
        assertEquals("unauthorized", goneMessage.get());
        assertTrue("channel should be removed after auth error", manager.isIdle());
        assertEquals("ws-connect should not be resent after auth error", 1, transport.wsConnectCount(channelId));
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

        String channelId = manager.openScreenChannel("s1", new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) { connected.set(true); }
            @Override public void onError(String channelId, int code, String message) {}
            @Override public void onData(String channelId, byte[] payload, boolean binary) {}
            @Override public void onMuxDisconnected(String reason) { muxDisconnected.set(true); }
            @Override public void onClosed(String channelId, int code, String reason) { closed.set(true); }
            @Override public void onChannelGone(String channelId, int code, String reason) {}
        });

        manager.start();
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));
        assertTrue(connected.get());
        assertEquals("one ws-connect after mux open", 1, transport.wsConnectCount(channelId));

        transport.simulateClose(1001, "going away");
        assertTrue("mux should report disconnected", muxDisconnected.get());

        transport.simulateText(wsClose(channelId, 1001, "going away"));

        assertTrue("onClosed should fire for recoverable close", closed.get());
        assertEquals("no ws-connect while mux disconnected", 1, transport.wsConnectCount(channelId));
        assertFalse("channel should remain in channels", manager.isIdle());

        transport.simulateOpen();
        assertEquals("ws-connect resent via reopenChannels after mux reconnect", 2, transport.wsConnectCount(channelId));
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

        String channelId = manager.openScreenChannel("s1", new RelayMuxSessionManager.ChannelListener() {
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
        transport.simulateText(wsConnected(channelId));
        assertTrue(connected.get());

        transport.simulateText(wsError(channelId, 500, "server error"));

        assertTrue("onClosed should fire for 5xx error", closed.get());
        assertEquals(500, closedCode.get());
        assertFalse("onError should not fire for 5xx error", error.get());
        assertFalse("onChannelGone should not fire for 5xx error", gone.get());
        assertFalse("channel should remain in channels", manager.isIdle());
        assertEquals("ws-connect should be resent after 5xx error", 2, transport.wsConnectCount(channelId));
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

        String channelId = manager.openScreenChannel("s1", new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) { connected.set(true); }
            @Override public void onError(String channelId, int code, String message) { error.set(true); }
            @Override public void onData(String channelId, byte[] payload, boolean binary) {}
            @Override public void onMuxDisconnected(String reason) {}
            @Override public void onClosed(String channelId, int code, String reason) { closed.set(true); }
            @Override public void onChannelGone(String channelId, int code, String reason) { gone.set(true); }
        });

        manager.start();
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));
        assertTrue(connected.get());

        transport.simulateText(wsError(channelId, 400, "bad request"));

        assertTrue("onError should fire for 4xx non-404 error", error.get());
        assertFalse("onClosed should not fire for 4xx error", closed.get());
        assertFalse("onChannelGone should not fire for 4xx error", gone.get());
        assertFalse("channel should remain in channels", manager.isIdle());
        assertEquals("ws-connect should not be resent after 4xx error", 1, transport.wsConnectCount(channelId));
    }

    @Test
    public void recoverableWsCloseWithSynchronousCloseDoesNotReopen() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        AtomicBoolean connected = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();

        String channelId = manager.openScreenChannel("s1", new RelayMuxSessionManager.ChannelListener() {
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
        transport.simulateText(wsConnected(channelId));
        assertTrue(connected.get());

        transport.simulateText(wsClose(channelId, 1001, "going away"));

        assertTrue("onClosed should fire for recoverable close", closed.get());
        assertTrue("channel should be removed by synchronous close", manager.isIdle());
        assertEquals("ws-connect should not be resent after listener closed channel", 1, transport.wsConnectCount(channelId));
    }

    @Test
    public void controlFramesForUnknownChannelAreIgnored() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        manager.start();
        transport.simulateOpen();

        String missingChannelId = "term:missing:webterm.screen.v1";
        transport.simulateText(wsConnected(missingChannelId));
        transport.simulateText(wsClose(missingChannelId, 1001, "going away"));
        transport.simulateText(wsError(missingChannelId, 404, "session not found"));

        assertTrue("unknown channel frames must not create or keep channels", manager.isIdle());
        assertEquals("unknown channel frames must not trigger reconnects",
            0, transport.wsConnectCount(missingChannelId));
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
