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

public class DeviceConnectionRecoveryTest {

    private static final class CapturingHandler {
        final Handler handler = mock(Handler.class);
        Runnable delayed;

        CapturingHandler() {
            when(handler.post(any(Runnable.class))).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(0).run();
                return true;
            });
            when(handler.postDelayed(any(Runnable.class), any(long.class))).thenAnswer(invocation -> {
                delayed = invocation.getArgument(0);
                return true;
            });
        }

        void runDelayed() {
            Runnable task = delayed;
            delayed = null;
            if (task != null) task.run();
        }
    }

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
    private static final class SimpleListener implements DeviceConnection.ChannelListener {
        final AtomicBoolean connected = new AtomicBoolean();
        final AtomicReference<ChannelFailure> failure = new AtomicReference<>();

        @Override public void onConnected(String channelId) { connected.set(true); }
        @Override public void onData(String channelId, byte[] payload, boolean binary) {}
        @Override public void onFailure(String channelId, ChannelFailure f) { failure.set(f); }
    }

    @Test
    public void reopenChannelReusesExisting() {
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection manager = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device1",
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
    public void newRuntimeOwnerReplacesOpenScreenChannelBeforeConnecting() {
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection manager = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        SimpleListener oldRuntime = new SimpleListener();
        SimpleListener newRuntime = new SimpleListener();
        String oldChannelId = manager.openScreenChannel("s1", "runtime-a", oldRuntime);
        transport.simulateOpen();
        transport.simulateText(wsConnected(oldChannelId));
        assertTrue(oldRuntime.connected.get());

        String newChannelId = manager.openScreenChannel("s1", "runtime-b", newRuntime);
        assertFalse("different runtime owners require different logical channel ids",
            oldChannelId.equals(newChannelId));
        assertEquals("old screen handler must be closed before replacement", 1,
            transport.wsCloseCount(oldChannelId));
        assertTrue("superseded runtime must be told that its channel is no longer owned",
            oldRuntime.failure.get() != null);
        assertEquals(ChannelFailure.Kind.CLIENT_CLOSED, oldRuntime.failure.get().kind);
        assertEquals("new owner must open a fresh server-side screen handler", 1,
            transport.wsConnectCount(newChannelId));
        assertEquals("both runtime tunnel IDs must share one stable replacement route",
            transport.wsConnectRouteKey(oldChannelId),
            transport.wsConnectRouteKey(newChannelId));
        assertFalse("new runtime cannot send Hello before its own ws-connected ACK",
            newRuntime.connected.get());

        transport.simulateText(wsConnected(newChannelId));
        assertTrue(newRuntime.connected.get());
    }

    @Test
    public void sameRuntimeOwnerWhileOpeningReusesSingleHandshake() {
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection manager = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));
        SimpleListener first = new SimpleListener();
        SimpleListener replacement = new SimpleListener();

        String channelId = manager.openScreenChannel("s1", "runtime-a", first);
        transport.simulateOpen();
        String sameChannelId = manager.openScreenChannel("s1", "runtime-a", replacement);

        assertEquals(channelId, sameChannelId);
        assertEquals("same owner must keep the only in-flight ws-connect", 1,
            transport.wsConnectCount(channelId));
        assertFalse(replacement.connected.get());

        transport.simulateText(wsConnected(channelId));
        assertTrue(replacement.connected.get());
    }

    @Test
    public void staleAckForSupersededOwnerCannotConnectNewRuntime() {
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection manager = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));
        SimpleListener oldRuntime = new SimpleListener();
        SimpleListener newRuntime = new SimpleListener();

        String oldChannelId = manager.openScreenChannel("s1", "runtime-a", oldRuntime);
        transport.simulateOpen();
        String newChannelId = manager.openScreenChannel("s1", "runtime-b", newRuntime);

        transport.simulateText(wsConnected(oldChannelId));
        assertFalse("old owner ACK must not be routed into the replacement runtime",
            newRuntime.connected.get());

        transport.simulateText(wsConnected(newChannelId));
        assertTrue(newRuntime.connected.get());
    }

    @Test
    public void failedSupersededCloseForcesPhysicalMuxReconnect() {
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection manager = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        String oldChannelId = manager.openScreenChannel("s1", "runtime-a", new SimpleListener());
        transport.simulateOpen();
        transport.simulateText(wsConnected(oldChannelId));
        transport.sendTextResult = false;

        manager.openScreenChannel("s1", "runtime-b", new SimpleListener());

        assertEquals("failed close must tear down the physical mux", 1, transport.closeCount);
        assertEquals("failed close must immediately start a fresh physical attempt",
            2, transport.startCount);
    }

    @Test
    public void reattachWhileConnectingWaitsForWsConnected() {
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection manager = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device1",
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
        DeviceConnection manager = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device1",
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
        DeviceConnection manager = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device1",
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
        DeviceConnection manager = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));
        SimpleListener first = new SimpleListener();
        AtomicInteger currentConnectedCount = new AtomicInteger();
        DeviceConnection.ChannelListener current = new DeviceConnection.ChannelListener() {
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
        DeviceConnection manager = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device1",
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
    public void wsClose1001KeepsChannelAndReopensAfterBackoff() {
        CapturingHandler handler = new CapturingHandler();
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection manager = new DeviceConnection(
                handler.handler, "http://example.com", "", "device1",
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
        assertEquals("error callback must not recursively reopen the channel",
            1, transport.wsConnectCount(channelId));

        handler.runDelayed();
        assertEquals("retry timer should reconcile the desired-open channel",
            2, transport.wsConnectCount(channelId));
    }

    @Test
    public void wsClose1000RemovesChannel() {
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection manager = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device1",
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
        DeviceConnection manager = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device1",
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
        DeviceConnection manager = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device1",
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
        CapturingHandler handler = new CapturingHandler();
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection manager = new DeviceConnection(
                handler.handler, "http://example.com", "", "device1",
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
        assertEquals("physical recovery reconciles every desired-open channel once",
            2, transport.wsConnectCount(channelId));
    }

    @Test
    public void muxHandshake401IsReportedAsAuthenticationRequired() {
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection manager = new DeviceConnection(
                synchronousHandler(), "http://example.com", "old", "device1",
                new FakeTransportFactory(transport));
        SimpleListener listener = new SimpleListener();

        manager.openScreenChannel("s1", listener);
        transport.simulateFailure(401, "Unauthorized");

        assertEquals(ChannelFailure.Kind.AUTH_REQUIRED, listener.failure.get().kind);
        assertEquals(401, listener.failure.get().code);
        assertEquals("stale authenticated transport must stop retrying", 1, transport.closeCount);
    }

    @Test
    public void wsError500ReopensChannelAfterBackoff() {
        CapturingHandler handler = new CapturingHandler();
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection manager = new DeviceConnection(
                handler.handler, "http://example.com", "", "device1",
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
        assertEquals("5xx callback must not immediately resend ws-connect",
            1, transport.wsConnectCount(channelId));
        handler.runDelayed();
        assertEquals("5xx channel retries after the backoff expires",
            2, transport.wsConnectCount(channelId));
    }

    @Test
    public void wsError400ReportsMuxTemporaryFailureAndUsesBackoff() {
        CapturingHandler handler = new CapturingHandler();
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection manager = new DeviceConnection(
                handler.handler, "http://example.com", "", "device1",
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
        assertEquals("temporary error callback must not immediately re-open",
            1, transport.wsConnectCount(channelId));
        handler.runDelayed();
        assertEquals("temporary channel error retries after backoff",
            2, transport.wsConnectCount(channelId));
    }

    @Test
    public void forceReconnectMovesLiveChannelsBackToWaitingAndReopensThem() {
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection manager = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));
        String channelId = manager.openScreenChannel("s1", new SimpleListener());
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));
        assertEquals(1, transport.wsConnectCount(channelId));

        manager.forceReconnect("test");
        transport.simulateOpen();

        assertEquals("new physical mux must reopen every retained logical channel",
            2, transport.wsConnectCount(channelId));
    }

    @Test
    public void channelOpenTimeoutRetriesInsteadOfRemainingOpeningForever() {
        CapturingHandler handler = new CapturingHandler();
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection manager = new DeviceConnection(
                handler.handler, "http://example.com", "", "device1",
                new FakeTransportFactory(transport));
        SimpleListener listener = new SimpleListener();
        String channelId = manager.openScreenChannel("s1", listener);
        transport.simulateOpen();
        assertEquals(1, transport.wsConnectCount(channelId));

        handler.runDelayed();

        assertTrue("open timeout must surface a recoverable failure", listener.failure.get() != null);
        assertEquals(ChannelFailure.Kind.MUX_TEMPORARY, listener.failure.get().kind);
        assertEquals("open timeout callback must not immediately retry", 1,
            transport.wsConnectCount(channelId));
        handler.runDelayed();
        assertEquals("timed out channel must retry ws-connect", 2,
            transport.wsConnectCount(channelId));
    }

    @Test
    public void recoverableWsCloseWithSynchronousCloseDoesNotReopen() {
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection manager = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));

        AtomicBoolean failed = new AtomicBoolean();
        AtomicReference<String> channelIdRef = new AtomicReference<>();

        String channelId = manager.openScreenChannel("s1", new DeviceConnection.ChannelListener() {
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
        DeviceConnection manager = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device1",
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
        DeviceConnection manager = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device1",
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
        assertEquals("term:s1:webterm.screen.v1", channelOpen.getString("channelRouteKey"));
        assertTrue(channelOpen.getString("channelOwnerKey")
            .endsWith(":term:s1:webterm.screen.v1"));
    }

    @Test
    public void routesDeviceLevelControlMessageOutsideTerminalChannels() {
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection connection = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));
        AtomicReference<String> type = new AtomicReference<>();
        connection.setControlListener(msg -> type.set(msg.optString("type")));

        connection.start();
        transport.simulateOpen();
        transport.simulateText("{\"type\":\"agent_notification\",\"event_id\":\"e1\"}");

        assertEquals("agent_notification", type.get());
    }

    @Test
    public void controlListenerKeepsConnectionOwnedWithoutLogicalChannels() {
        DeviceConnection connection = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(new FakeMuxTransport()));

        connection.setControlListener(msg -> {});
        assertFalse("control plane owner must keep the device connection alive", connection.isIdle());

        connection.setControlListener(null);
        assertTrue("connection becomes idle after the control owner releases it", connection.isIdle());
    }

    @Test
    public void sendControlUsesPhysicalConnectionTextFrame() throws Exception {
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection connection = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));
        connection.start();
        transport.simulateOpen();

        assertTrue(connection.sendControl(new JSONObject().put("type", "file_send.accepted")));
        JSONObject sent = new JSONObject(transport.sentTexts.get(transport.sentTexts.size() - 1));
        assertEquals("file_send.accepted", sent.getString("type"));
    }

    @Test
    public void tunnelSendSeparatesLocalAcceptanceFromWebSocketRejection() {
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection connection = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));
        String channelId = connection.openScreenChannel("s1", new SimpleListener());
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));
        transport.sendBinaryResult = false;
        AtomicReference<DeviceConnection.TunnelSendResult> result = new AtomicReference<>();

        assertTrue("call only waits for bounded local queue acceptance",
                connection.tryEnqueueTunnelFrame(channelId, new byte[] {1, 2, 3}, true,
                        result::set));
        assertEquals(DeviceConnection.TunnelSendResult.TRANSPORT_REJECTED, result.get());
        assertTrue("transport rejection must trigger recovery", transport.closeCount > 0);
    }

    @Test
    public void consecutiveTransportRejectionsTriggerOnlyOneReconnect() {
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection connection = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));
        String channelId = connection.openScreenChannel("s1", new SimpleListener());
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));
        transport.sendBinaryResult = false;
        AtomicReference<DeviceConnection.TunnelSendResult> first = new AtomicReference<>();
        AtomicReference<DeviceConnection.TunnelSendResult> second = new AtomicReference<>();

        assertTrue(connection.tryEnqueueTunnelFrame(channelId, new byte[] {1}, true, first::set));
        assertTrue(connection.tryEnqueueTunnelFrame(channelId, new byte[] {2}, true, second::set));

        assertEquals(DeviceConnection.TunnelSendResult.TRANSPORT_REJECTED, first.get());
        assertEquals("consecutive rejections must not chain transport rebuilds",
                2, transport.startCount);
        assertEquals("old transport is closed exactly once", 1, transport.closeCount);
    }

    @Test
    public void localQueueFullDoesNotReconnectSharedPhysicalMux() {
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection connection = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));
        String channelA = connection.openScreenChannel("s1", new SimpleListener());
        String channelB = connection.openScreenChannel("s2", new SimpleListener());
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelA));
        transport.simulateText(wsConnected(channelB));
        AtomicReference<DeviceConnection.TunnelSendResult> rejected = new AtomicReference<>();

        // 单帧超过 8 MiB 预算会在本地立即被拒绝；这只是当前输入的背压事实，
        // 不能关闭一设备共享的物理 Mux。
        assertFalse(connection.tryEnqueueTunnelFrame(
                channelA, new byte[8 * 1024 * 1024 + 1], true, rejected::set));
        assertEquals(DeviceConnection.TunnelSendResult.LOCAL_QUEUE_FULL, rejected.get());
        assertEquals("queue pressure on A must not close the shared transport",
                0, transport.closeCount);
        assertEquals("queue pressure must not start a replacement transport",
                1, transport.startCount);
        assertTrue(connection.isConnected());

        AtomicReference<DeviceConnection.TunnelSendResult> channelBResult =
                new AtomicReference<>();
        assertTrue("unrelated channel B remains writable",
                connection.tryEnqueueTunnelFrame(
                        channelB, new byte[] {1, 2, 3}, true, channelBResult::set));
        assertEquals(DeviceConnection.TunnelSendResult.WEBSOCKET_ENQUEUED,
                channelBResult.get());
    }

    @Test
    public void binaryTunnelFrameRoutesDirectlyOnDeviceEventLoop() {
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection connection = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device1",
                new FakeTransportFactory(transport));
        AtomicReference<byte[]> received = new AtomicReference<>();
        String channelId = connection.openScreenChannel("s1", new DeviceConnection.ChannelListener() {
            @Override public void onConnected(String id) {}
            @Override public void onData(String id, byte[] payload, boolean binary) {
                if (binary) received.set(payload);
            }
            @Override public void onFailure(String id, ChannelFailure failure) {}
        });
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));

        byte[] payload = new byte[]{0x02, 0x03};
        transport.simulateBinary(WebTermProtocol.encodeTunnelFrame(channelId, payload, true));

        assertTrue("binary payload must reach the logical channel", received.get() != null);
        assertEquals(2, received.get().length);
    }

    @Test
    public void physicalConnectTimeoutUsesBackoffBeforeRestart() {
        CapturingHandler handler = new CapturingHandler();
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection connection = new DeviceConnection(
                handler.handler, "http://example.com", "", "device1",
                new FakeTransportFactory(transport));
        connection.openScreenChannel("s1", new SimpleListener());
        assertEquals(1, transport.startCount);

        handler.runDelayed();
        assertEquals("timeout closes the stale physical attempt", 1, transport.closeCount);
        assertEquals("retry is delayed instead of recursive", 1, transport.startCount);

        handler.runDelayed();
        assertEquals("backoff expiry starts a fresh physical attempt", 2, transport.startCount);
    }

    private static class FakeMuxTransport implements MuxTransport {
        private Listener listener;
        private final List<String> sentTexts = new ArrayList<>();
        private boolean open = false;
        private boolean sendTextResult = true;
        private boolean sendBinaryResult = true;
        private int closeCount;
        private int startCount;

        @Override public void start(Listener listener) {
            this.listener = listener;
            startCount++;
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

        public void simulateBinary(byte[] data) {
            if (listener != null) listener.onBinary(data);
        }

        public void simulateFailure(int code, String reason) {
            open = false;
            if (listener != null) listener.onError(code, reason);
        }

        @Override public void close() { closeCount++; }
        @Override public boolean isConnected() { return open; }

        @Override public boolean sendText(String text) {
            sentTexts.add(text);
            return sendTextResult;
        }

        @Override public boolean sendBinary(byte[] data) { return sendBinaryResult; }

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

        String wsConnectRouteKey(String tunnelId) {
            for (String text : sentTexts) {
                try {
                    JSONObject msg = new JSONObject(text);
                    if ("ws-connect".equals(msg.optString("type"))
                            && tunnelId.equals(msg.optString("tunnelConnectionId"))) {
                        return msg.optString("channelRouteKey");
                    }
                } catch (JSONException e) {
                    // ignore non-JSON
                }
            }
            return "";
        }
    }
}
