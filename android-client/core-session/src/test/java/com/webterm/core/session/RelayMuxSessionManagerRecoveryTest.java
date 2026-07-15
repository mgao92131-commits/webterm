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

    /** 只关心连接事件的 listener；失败回调记录最近一次 ChannelFailure。 */
    private static final class SimpleListener implements RelayMuxSessionManager.ChannelListener {
        final AtomicBoolean connected = new AtomicBoolean();
        final AtomicReference<ChannelFailure> failure = new AtomicReference<>();

        @Override public void onConnected(String channelId) { connected.set(true); }
        @Override public void onData(String channelId, byte[] payload, boolean binary) {}
        @Override public void onFailure(String channelId, ChannelFailure f) { failure.set(f); }
    }

    @Test
    public void reopenChannelReusesExisting() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        SimpleListener listener1 = new SimpleListener();
        SimpleListener listener2 = new SimpleListener();

        String channelId1 = manager.openScreenChannel("s1", listener1);
        assertFalse("manager should report an open channel", manager.isIdle());

        manager.start();
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId1));
        assertTrue("original listener should be notified when mux connects", listener1.connected.get());

        String channelId2 = manager.openScreenChannel("s1", listener2);
        assertEquals("reattach should reuse the same channel id", channelId1, channelId2);
        assertTrue("reattached listener should be notified immediately when mux already connected", listener2.connected.get());
        assertEquals("reattach should not send another ws-connect", 1, transport.wsConnectCount(channelId1));
    }

    @Test
    public void reattachWhileConnectingWaitsForWsConnected() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        SimpleListener listener1 = new SimpleListener();
        SimpleListener listener2 = new SimpleListener();

        String channelId1 = manager.openScreenChannel("s1", listener1);
        manager.start();
        transport.simulateOpen();
        // ws-connected has NOT been received yet; channel is still CONNECTING.

        String channelId2 = manager.openScreenChannel("s1", listener2);
        assertEquals("reattach should reuse the same channel id", channelId1, channelId2);
        assertFalse("reattached listener should NOT be notified before ws-connected", listener2.connected.get());

        transport.simulateText(wsConnected(channelId1));
        assertTrue("reattached listener should be notified after ws-connected", listener2.connected.get());
    }

    @Test
    public void reattachAfterDetachRehandshakesToReplaceStaleServerClient() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        SimpleListener listener1 = new SimpleListener();
        SimpleListener listener2 = new SimpleListener();

        String channelId1 = manager.openScreenChannel("s1", listener1);
        manager.start();
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId1));
        assertTrue("original listener should be notified", listener1.connected.get());
        assertEquals("one ws-connect after mux open", 1, transport.wsConnectCount(channelId1));

        // Simulate background detach: listener replaced with no-op, but channel stays alive.
        manager.detachChannelListener(channelId1);

        String channelId2 = manager.openScreenChannel("s1", listener2);
        assertEquals("reattach should reuse the same channel id", channelId1, channelId2);
        assertFalse("reattached listener should NOT be notified before re-handshake", listener2.connected.get());
        assertEquals("reattach after detach must send a new ws-connect",
            2, transport.wsConnectCount(channelId1));

        transport.simulateText(wsConnected(channelId1));
        assertTrue("reattached listener should be notified after server ack", listener2.connected.get());
    }

    @Test
    public void reattachWithoutDetachWhileChannelOpeningReusesPendingHandshake() {
        // Regression test for the yellow-indicator stuck scenario. A physical mux
        // reconnect can put a channel into OPENING before the old Fragment has
        // detached. Reattaching replaces the listener but must keep the single
        // in-flight ws-connect; sending another request creates two valid ACKs.
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        SimpleListener listener1 = new SimpleListener();
        SimpleListener listener2 = new SimpleListener();

        String channelId = manager.openScreenChannel("s1", listener1);
        manager.start();
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));
        assertTrue("original listener should be connected", listener1.connected.get());
        assertEquals("one ws-connect after mux open", 1, transport.wsConnectCount(channelId));

        // Physical mux reconnects (e.g. cookie refresh) before UI detaches.
        transport.simulateClose(1001, "going away");
        assertTrue("original listener should get mux disconnected failure", listener1.failure.get() != null);
        assertEquals(ChannelFailure.Kind.MUX_TEMPORARY, listener1.failure.get().kind);

        transport.simulateOpen();
        assertEquals("reopen after reconnect should send ws-connect",
            2, transport.wsConnectCount(channelId));
        // Do NOT ack ws-connected yet: the channel is still CONNECTING.

        // UI returns without ever calling detachChannelListener().
        manager.openScreenChannel("s1", listener2);
        assertFalse("new listener should NOT be connected while channel is CONNECTING",
            listener2.connected.get());
        assertEquals("reattach must reuse the pending handshake",
            2, transport.wsConnectCount(channelId));

        transport.simulateText(wsConnected(channelId));
        assertTrue("new listener should connect after the replacement handshake", listener2.connected.get());
    }

    @Test
    public void supersededWsConnectedAckCannotNotifyCurrentListenerTwice() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));
        SimpleListener first = new SimpleListener();
        AtomicInteger currentConnectedCount = new AtomicInteger();
        RelayMuxSessionManager.ChannelListener current = new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String channelId) { currentConnectedCount.incrementAndGet(); }
            @Override public void onData(String channelId, byte[] payload, boolean binary) {}
            @Override public void onFailure(String channelId, ChannelFailure failure) {}
        };

        String channelId = manager.openScreenChannel("s1", first);
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));

        // 物理 mux 恢复会发 ws-connect #2；页面同时重挂到仍为 OPENING 的
        // channel 时只能替换 listener，不能再发 #3。
        transport.simulateClose(1001, "going away");
        transport.simulateOpen();
        manager.openScreenChannel("s1", current);
        assertEquals(2, transport.wsConnectCount(channelId));

        transport.simulateText(wsConnected(channelId)); // 当前 #2 ACK
        transport.simulateText(wsConnected(channelId)); // 迟到/重复 ACK

        // 每次 onConnected 都会让 TerminalSessionRuntime.beginSync() 发 Hello。
        // 若通知两次，Go 会记录 duplicate screen hello 并关闭当前 screen client。
        assertEquals("superseded ACK must not trigger a second screen Hello",
            1, currentConnectedCount.get());
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

        SimpleListener listener1 = new SimpleListener();
        SimpleListener listener2 = new SimpleListener();

        String channelId = manager.openScreenChannel("s1", listener1);
        manager.start();
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));
        assertTrue(listener1.connected.get());
        assertEquals("one ws-connect after mux open", 1, transport.wsConnectCount(channelId));

        // UI properly detaches before leaving.
        manager.detachChannelListener(channelId);

        // Physical mux drops while the UI is away.
        transport.simulateClose(1001, "going away");

        // UI returns before the mux has reconnected: wasDetached is true, so the
        // listener is rebound but no ws-connect is sent yet.
        manager.openScreenChannel("s1", listener2);
        assertFalse("new listener should wait for re-handshake ack",
            listener2.connected.get());
        assertEquals("no extra ws-connect while mux is still down",
            1, transport.wsConnectCount(channelId));

        // Mux reconnects and reopens the channel.
        transport.simulateOpen();
        assertEquals("reopen after reconnect should send ws-connect",
            2, transport.wsConnectCount(channelId));

        transport.simulateText(wsConnected(channelId));
        assertTrue("new listener should be connected after server ack", listener2.connected.get());
    }

    @Test
    public void wsClose1001KeepsChannelAndReopens() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        SimpleListener listener = new SimpleListener();

        String channelId = manager.openScreenChannel("s1", listener);

        manager.start();
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));
        assertTrue("channel should be connected", listener.connected.get());
        assertEquals("one ws-connect after mux open", 1, transport.wsConnectCount(channelId));

        transport.simulateText(wsClose(channelId, 1001, "going away"));

        ChannelFailure failure = listener.failure.get();
        assertTrue("onFailure should fire for recoverable close", failure != null);
        assertEquals(ChannelFailure.Kind.MUX_TEMPORARY, failure.kind);
        assertEquals(1001, failure.code);
        assertEquals("going away", failure.message);
        assertEquals("ws-connect should be resent after recoverable close", 2, transport.wsConnectCount(channelId));
    }

    @Test
    public void wsClose1000RemovesChannel() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        SimpleListener listener = new SimpleListener();

        String channelId = manager.openScreenChannel("s1", listener);

        manager.start();
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));
        assertTrue(listener.connected.get());

        transport.simulateText(wsClose(channelId, 1000, "normal closure"));

        ChannelFailure failure = listener.failure.get();
        assertTrue("onFailure should fire for permanent close", failure != null);
        assertEquals(ChannelFailure.Kind.REMOTE_CLOSED, failure.kind);
        assertEquals(1000, failure.code);
        assertEquals("normal closure", failure.message);
        assertTrue("channel should be removed after permanent close", manager.isIdle());
        assertEquals("ws-connect should not be resent after permanent close", 1, transport.wsConnectCount(channelId));
    }

    @Test
    public void wsError404RemovesChannel() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        SimpleListener listener = new SimpleListener();

        String channelId = manager.openScreenChannel("s1", listener);

        manager.start();
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));
        assertTrue(listener.connected.get());

        transport.simulateText(wsError(channelId, 404, "session not found"));

        ChannelFailure failure = listener.failure.get();
        assertTrue("onFailure should fire for permanent error", failure != null);
        assertEquals(ChannelFailure.Kind.CHANNEL_NOT_FOUND, failure.kind);
        assertEquals(404, failure.code);
        assertEquals("session not found", failure.message);
        assertTrue("channel should be removed after 404", manager.isIdle());
        assertEquals("ws-connect should not be resent after permanent error", 1, transport.wsConnectCount(channelId));
    }

    @Test
    public void wsError401RemovesChannel() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        SimpleListener listener = new SimpleListener();

        String channelId = manager.openScreenChannel("s1", listener);

        manager.start();
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));
        assertTrue(listener.connected.get());

        transport.simulateText(wsError(channelId, 401, "unauthorized"));

        ChannelFailure failure = listener.failure.get();
        assertTrue("onFailure should fire for auth error", failure != null);
        assertEquals(ChannelFailure.Kind.AUTH_REQUIRED, failure.kind);
        assertEquals(401, failure.code);
        assertEquals("unauthorized", failure.message);
        assertTrue("channel should be removed after auth error", manager.isIdle());
        assertEquals("ws-connect should not be resent after auth error", 1, transport.wsConnectCount(channelId));
    }

    @Test
    public void recoverableWsCloseWhileMuxDisconnectedBuffersReconnect() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        SimpleListener listener = new SimpleListener();

        String channelId = manager.openScreenChannel("s1", listener);

        manager.start();
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));
        assertTrue(listener.connected.get());
        assertEquals("one ws-connect after mux open", 1, transport.wsConnectCount(channelId));

        transport.simulateClose(1001, "going away");
        assertTrue("mux should report disconnected", listener.failure.get() != null);
        assertEquals(ChannelFailure.Kind.MUX_TEMPORARY, listener.failure.get().kind);

        transport.simulateText(wsClose(channelId, 1001, "going away"));

        assertEquals("no ws-connect while mux disconnected", 1, transport.wsConnectCount(channelId));
        assertFalse("channel should remain in channels", manager.isIdle());

        transport.simulateOpen();
        assertEquals("ws-connect resent via reopenChannels after mux reconnect", 2, transport.wsConnectCount(channelId));
    }

    @Test
    public void muxHandshake401IsReportedAsAuthenticationRequired() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "old", "device1",
                new FakeTransportFactory(transport));
        SimpleListener listener = new SimpleListener();

        manager.openScreenChannel("s1", listener);
        transport.simulateFailure(401, "Unauthorized");

        assertEquals(ChannelFailure.Kind.AUTH_REQUIRED, listener.failure.get().kind);
        assertEquals(401, listener.failure.get().code);
        assertEquals("stale authenticated transport must stop retrying", 1, transport.closeCount);
    }

    @Test
    public void wsError500ReopensChannel() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        SimpleListener listener = new SimpleListener();

        String channelId = manager.openScreenChannel("s1", listener);

        manager.start();
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));
        assertTrue(listener.connected.get());

        transport.simulateText(wsError(channelId, 500, "server error"));

        ChannelFailure failure = listener.failure.get();
        assertTrue("onFailure should fire for 5xx error", failure != null);
        assertEquals(ChannelFailure.Kind.SERVER_TEMPORARY, failure.kind);
        assertEquals(500, failure.code);
        assertFalse("channel should remain in channels", manager.isIdle());
        assertEquals("ws-connect should be resent after 5xx error", 2, transport.wsConnectCount(channelId));
    }

    @Test
    public void wsError400ReportsMuxTemporaryFailure() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        SimpleListener listener = new SimpleListener();

        String channelId = manager.openScreenChannel("s1", listener);

        manager.start();
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));
        assertTrue(listener.connected.get());

        transport.simulateText(wsError(channelId, 400, "bad request"));

        ChannelFailure failure = listener.failure.get();
        assertTrue("onFailure should fire for 4xx non-404 error", failure != null);
        assertEquals("unclassified error maps to MUX_TEMPORARY", ChannelFailure.Kind.MUX_TEMPORARY, failure.kind);
        assertEquals(400, failure.code);
        assertEquals("bad request", failure.message);
        assertFalse("channel should remain in channels", manager.isIdle());
        assertEquals("ws-connect should not be resent after 4xx error", 1, transport.wsConnectCount(channelId));
    }

    @Test
    public void recoverableWsCloseWithSynchronousCloseDoesNotReopen() {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        AtomicBoolean failed = new AtomicBoolean();
        AtomicReference<String> channelIdRef = new AtomicReference<>();

        String channelId = manager.openScreenChannel("s1", new RelayMuxSessionManager.ChannelListener() {
            @Override public void onConnected(String id) { channelIdRef.set(id); }
            @Override public void onData(String id, byte[] payload, boolean binary) {}
            @Override public void onFailure(String id, ChannelFailure failure) {
                failed.set(true);
                manager.closeChannel(id);
            }
        });

        manager.start();
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));
        assertTrue(channelIdRef.get() != null);

        transport.simulateText(wsClose(channelId, 1001, "going away"));

        assertTrue("onFailure should fire for recoverable close", failed.get());
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

    @Test
    public void muxOpenRegistersAndroidBeforeOpeningTerminalChannels() throws Exception {
        FakeMuxTransport transport = new FakeMuxTransport();
        RelayMuxSessionManager manager = new RelayMuxSessionManager(
                null, synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));
        manager.setClientRegistration("android_1234", "Pixel 9");
        manager.openScreenChannel("s1", new SimpleListener());
        transport.simulateOpen();

        assertTrue("registration and terminal open should both be sent", transport.sentTexts.size() >= 2);
        JSONObject registration = new JSONObject(transport.sentTexts.get(0));
        assertEquals("client.register", registration.getString("type"));
        assertEquals("android_1234", registration.getString("client_id"));
        assertEquals("Pixel 9", registration.getString("client_name"));
        assertEquals("file_receive", registration.getJSONArray("capabilities").getString(0));
        JSONObject channelOpen = new JSONObject(transport.sentTexts.get(1));
        assertEquals("ws-connect", channelOpen.getString("type"));
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

        public void simulateFailure(int code, String reason) {
            open = false;
            if (listener != null) listener.onError(code, reason);
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
