package com.webterm.core.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Handler;

import com.webterm.core.contract.diagnostics.DiagnosticLevel;
import com.webterm.core.contract.diagnostics.DiagnosticSink;
import com.webterm.core.contract.diagnostics.Diagnostics;
import com.webterm.transport.api.MuxTransport;
import com.webterm.transport.api.TransportFactory;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 物理连接恢复诊断不变量：主动关闭不启 recovery；EOF/超时启 recovery；
 * 多次重连共享 recoveryId；恢复成功清除；物理断线事件名为 transport_disconnected。
 */
public class DeviceConnectionRecoveryDiagnosticsTest {

    private RecordingSink sink;

    @Before
    public void installSink() {
        sink = new RecordingSink();
        Diagnostics.install(sink);
    }

    @After
    public void resetSink() {
        Diagnostics.install(null);
    }

    @Test
    public void activeCloseDoesNotStartRecovery() {
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection connection = new DeviceConnection(
                synchronousHandler(), "http://example.com", "secret-cookie", "device-raw-1",
                new FakeTransportFactory(transport));
        SimpleListener listener = new SimpleListener();
        String channelId = connection.openScreenChannel("s1", listener);
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));

        assertFalse(connection.hasActiveRecovery());
        connection.closeChannelAndReleaseIfIdle(
                channelId, ConnectionCloseReason.RUNTIME_CLOSED, () -> {});

        assertFalse(connection.hasActiveRecovery());
        assertFalse(sink.hasEvent("device_connection", "recovery_started"));
        assertTrue(sink.hasEvent("device_connection", "transport_close_requested"));
    }

    @Test
    public void activeCloseDuringRecoveryClearsRecoveryWithoutChannelFailed() {
        CapturingHandler handler = new CapturingHandler();
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection connection = new DeviceConnection(
                handler.handler, "http://example.com", "secret-cookie", "device-raw-1",
                new FakeTransportFactory(transport));
        String channelId = connection.openScreenChannel("s1", new SimpleListener());
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));

        transport.simulateClose(1001, "going away");
        assertTrue(connection.hasActiveRecovery());
        assertTrue(sink.hasEvent("device_connection", "transport_disconnected"));
        assertFalse(sink.hasEvent("device_connection", "channel_failed"));

        connection.closeChannelAndReleaseIfIdle(
                channelId, ConnectionCloseReason.RUNTIME_CLOSED, () -> {});

        assertFalse(connection.hasActiveRecovery());
        assertFalse(sink.hasEvent("device_connection", "channel_failed"));
    }

    @Test
    public void eofDisconnectStartsRecoveryWithTransportDisconnectedEvent() {
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection connection = new DeviceConnection(
                synchronousHandler(), "http://example.com", "secret-cookie", "device-raw-1",
                new FakeTransportFactory(transport));
        String channelId = connection.openScreenChannel("s1", new SimpleListener());
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));

        transport.simulateClose(1001, "eof");

        assertTrue(connection.hasActiveRecovery());
        assertNotNull(connection.recoveryIdForDiagnostics());
        assertTrue(sink.hasEvent("device_connection", "recovery_started"));
        assertTrue(sink.hasEvent("device_connection", "transport_disconnected"));
        assertFalse(sink.hasEvent("device_connection", "channel_failed"));
        assertFalse(sink.eventFieldsContainRaw("device-raw-1"));
        assertFalse(sink.eventFieldsContainRaw("secret-cookie"));
    }

    @Test
    public void connectTimeoutStartsRecovery() {
        CapturingHandler handler = new CapturingHandler();
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection connection = new DeviceConnection(
                handler.handler, "http://example.com", "", "device-timeout",
                new FakeTransportFactory(transport));
        connection.openScreenChannel("s1", new SimpleListener());
        assertEquals(1, transport.startCount);
        assertFalse(connection.hasActiveRecovery());

        handler.runDelayed(); // physical connect timeout

        assertTrue(connection.hasActiveRecovery());
        assertTrue(sink.hasEvent("device_connection", "transport_connect_failed"));
        assertTrue(sink.hasEvent("device_connection", "recovery_started"));
        assertEquals("CONNECTING", sink.firstField("device_connection", "transport_connect_failed", "stateBefore"));
        assertEquals("CONNECT", sink.firstField("device_connection", "transport_connect_failed", "failureStage"));
        assertTrue(sink.firstField("device_connection", "transport_connect_failed", "connectDurationMs") instanceof Number);
    }

    @Test
    public void connectFailureBeforeOpenEmitsTransportConnectFailed() {
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection connection = new DeviceConnection(
                synchronousHandler(), "http://example.com", "secret-cookie", "device-raw-1",
                new FakeTransportFactory(transport));
        connection.openScreenChannel("s1", new SimpleListener());
        assertEquals(1, transport.startCount);

        transport.simulateFailure(0, "dns failed");

        assertTrue(connection.hasActiveRecovery());
        assertTrue(sink.hasEvent("device_connection", "transport_connect_failed"));
        assertFalse(sink.hasEvent("device_connection", "transport_disconnected"));
        assertEquals("CONNECTING", sink.firstField("device_connection", "transport_connect_failed", "stateBefore"));
        assertEquals("WEBSOCKET", sink.firstField("device_connection", "transport_connect_failed", "failureStage"));
    }

    @Test
    public void multipleReconnectsShareOneRecoveryIdUntilRecovered() {
        CapturingHandler handler = new CapturingHandler();
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection connection = new DeviceConnection(
                handler.handler, "http://example.com", "", "device-recover",
                new FakeTransportFactory(transport));
        String channelId = connection.openScreenChannel("s1", new SimpleListener());
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));

        transport.simulateClose(1001, "going away");
        assertTrue(connection.hasActiveRecovery());
        String recoveryId = connection.recoveryIdForDiagnostics();
        assertNotNull(recoveryId);

        handler.runDelayed(); // scheduled reconnect → connectPhysical
        assertEquals(recoveryId, connection.recoveryIdForDiagnostics());
        assertTrue(connection.hasActiveRecovery());

        transport.simulateFailure(0, "still down");
        assertEquals("failed reconnect must keep the same recovery transaction",
                recoveryId, connection.recoveryIdForDiagnostics());
        assertTrue(connection.hasActiveRecovery());

        handler.runDelayed(); // second reconnect attempt
        assertEquals(recoveryId, connection.recoveryIdForDiagnostics());

        transport.simulateOpen();
        assertFalse(connection.hasActiveRecovery());
        assertTrue(sink.hasEvent("device_connection", "transport_recovered"));
    }

    @Test
    public void cookieUpdateDuringEofRecoveryKeepsOneRecoveryTransaction() {
        CapturingHandler handler = new CapturingHandler();
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection connection = new DeviceConnection(
                handler.handler, "http://example.com", "cookie-a", "device-cookie-race",
                new FakeTransportFactory(transport));
        String channelId = connection.openScreenChannel("s1", new SimpleListener());
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));

        transport.simulateClose(1001, "EOF");
        String recoveryId = connection.recoveryIdForDiagnostics();
        assertNotNull(recoveryId);

        connection.updateCookie("cookie-b");

        assertEquals("cookie rotation must join the active EOF recovery",
                recoveryId, connection.recoveryIdForDiagnostics());
        assertEquals("cookie rotation must not bypass EOF reconnect backoff",
                1, transport.startCount);
        connection.updateCookie("cookie-b");
        assertEquals("same cookie must not start another replacement", 1, transport.startCount);

        handler.runDelayed();
        assertEquals("scheduled EOF recovery starts one replacement", 2, transport.startCount);
        transport.simulateOpen();
        assertFalse(connection.hasActiveRecovery());
    }

    @Test
    public void successfulReconnectClearsRecoveryViaTransportRecovered() {
        CapturingHandler handler = new CapturingHandler();
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection connection = new DeviceConnection(
                handler.handler, "http://example.com", "", "device-ok",
                new FakeTransportFactory(transport));
        String channelId = connection.openScreenChannel("s1", new SimpleListener());
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));

        transport.simulateClose(1001, "going away");
        assertTrue(connection.hasActiveRecovery());

        handler.runDelayed();
        transport.simulateOpen();

        assertFalse(connection.hasActiveRecovery());
        assertEquals(1, sink.countEvents("device_connection", "transport_recovered"));
        assertTrue(sink.hasEvent("device_connection", "transport_disconnected"));
        assertFalse(sink.hasEvent("device_connection", "channel_failed"));
    }

    @Test
    public void recoveryClearFailureSetsPendingAndRetriesOnNextControlSend() throws Exception {
        CapturingHandler handler = new CapturingHandler();
        FakeMuxTransport transport = new FakeMuxTransport();
        transport.rejectRecoveryClear = true;
        DeviceConnection connection = new DeviceConnection(
                handler.handler, "http://example.com", "", "device-clear-pending",
                new FakeTransportFactory(transport));
        String channelId = connection.openScreenChannel("s1", new SimpleListener());
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));

        transport.simulateClose(1001, "going away");
        assertTrue(connection.hasActiveRecovery());

        handler.runDelayed();
        transport.simulateOpen();

        assertFalse(connection.hasActiveRecovery());
        assertTrue(connection.diagnosticsSnapshot().agentRecoveryContextClearPending);
        assertTrue(sink.hasEvent("device_connection", "diagnostics_recovery_context_clear_failed"));

        transport.rejectRecoveryClear = false;
        org.json.JSONObject ping = new org.json.JSONObject();
        ping.put("type", "client.ping");
        assertTrue(connection.sendControl(ping));

        assertFalse(connection.diagnosticsSnapshot().agentRecoveryContextClearPending);
        boolean sawClear = false;
        for (String text : transport.sentTexts) {
            if (text.contains("\"recovery_hash\":\"\"")) {
                sawClear = true;
                break;
            }
        }
        assertTrue("expected a successful recovery_hash clear after pending retry", sawClear);
    }

    @Test
    public void connectedElapsedMsNowGrowsWhileIdleConnected() throws Exception {
        FakeMuxTransport transport = new FakeMuxTransport();
        DeviceConnection connection = new DeviceConnection(
                synchronousHandler(), "http://example.com", "", "device-uptime",
                new FakeTransportFactory(transport));
        String channelId = connection.openScreenChannel("s1", new SimpleListener());
        transport.simulateOpen();
        transport.simulateText(wsConnected(channelId));

        DeviceConnection.DiagnosticsSnapshot snap = connection.diagnosticsSnapshot();
        assertTrue(snap.connectedAtNanos > 0L);
        long first = snap.connectedElapsedMsNow();
        Thread.sleep(30L);
        long second = snap.connectedElapsedMsNow();
        assertTrue("uptime must grow for idle connected snapshot, first=" + first + " second=" + second,
                second >= first);
        assertTrue(second >= 20L);
    }

    private static Handler synchronousHandler() {
        Handler handler = mock(Handler.class);
        when(handler.post(any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return true;
        });
        when(handler.postDelayed(any(Runnable.class), anyLong())).thenReturn(true);
        return handler;
    }

    private static String wsConnected(String channelId) {
        return "{\"type\":\"ws-connected\",\"tunnelConnectionId\":\"" + channelId + "\"}";
    }

    private static final class CapturingHandler {
        final Handler handler = mock(Handler.class);
        Runnable delayed;

        CapturingHandler() {
            when(handler.post(any(Runnable.class))).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(0).run();
                return true;
            });
            when(handler.postDelayed(any(Runnable.class), anyLong())).thenAnswer(invocation -> {
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

    private static final class SimpleListener implements DeviceConnection.ChannelListener {
        final AtomicBoolean connected = new AtomicBoolean();
        final AtomicReference<ChannelFailure> failure = new AtomicReference<>();

        @Override public void onConnected(String channelId) { connected.set(true); }
        @Override public void onData(String channelId, byte[] payload, boolean binary) {}
        @Override public void onFailure(String channelId, ChannelFailure f) { failure.set(f); }
    }

    private static final class FakeTransportFactory implements TransportFactory {
        private final FakeMuxTransport transport;

        FakeTransportFactory(FakeMuxTransport transport) {
            this.transport = transport;
        }

        @Override public MuxTransport create(String url, String cookie, String protocol) {
            return transport;
        }
    }

    private static final class FakeMuxTransport implements MuxTransport {
        private Listener listener;
        private final List<String> sentTexts = new ArrayList<>();
        private boolean open;
        private int closeCount;
        private int startCount;
        /** 拒绝 recovery_hash="" 的 diagnostics.connection，用于 pending 重试测试。 */
        boolean rejectRecoveryClear;

        @Override public void start(Listener listener) {
            this.listener = listener;
            startCount++;
        }

        void simulateOpen() {
            open = true;
            if (listener != null) listener.onOpen();
        }

        void simulateClose(int code, String reason) {
            open = false;
            if (listener != null) listener.onClosed(code, reason);
        }

        void simulateText(String text) {
            if (listener != null) listener.onText(text);
        }

        void simulateFailure(int code, String reason) {
            open = false;
            if (listener != null) listener.onError(code, reason);
        }

        @Override public void close() { closeCount++; }
        @Override public boolean isConnected() { return open; }

        @Override public boolean sendText(String text) {
            if (rejectRecoveryClear && text != null
                    && text.contains("\"type\":\"diagnostics.connection\"")
                    && text.contains("\"recovery_hash\":\"\"")) {
                return false;
            }
            sentTexts.add(text);
            return true;
        }

        @Override public boolean sendBinary(byte[] data) { return true; }
    }

    private static final class RecordingSink implements DiagnosticSink {
        private final List<Recorded> events = new CopyOnWriteArrayList<>();

        @Override
        public void record(DiagnosticLevel level, String area, String event, Map<String, ?> fields) {
            events.add(new Recorded(area, event, fields));
        }

        boolean hasEvent(String area, String event) {
            for (Recorded recorded : events) {
                if (area.equals(recorded.area) && event.equals(recorded.event)) return true;
            }
            return false;
        }

        Object firstField(String area, String event, String key) {
            for (Recorded recorded : events) {
                if (area.equals(recorded.area) && event.equals(recorded.event)) {
                    return recorded.fields.get(key);
                }
            }
            return null;
        }

        int countEvents(String area, String event) {
            int count = 0;
            for (Recorded recorded : events) {
                if (area.equals(recorded.area) && event.equals(recorded.event)) count++;
            }
            return count;
        }

        boolean eventFieldsContainRaw(String raw) {
            for (Recorded recorded : events) {
                for (Object value : recorded.fields.values()) {
                    if (String.valueOf(value).contains(raw)) return true;
                }
            }
            return false;
        }
    }

    private static final class Recorded {
        final String area;
        final String event;
        final Map<String, ?> fields;

        Recorded(String area, String event, Map<String, ?> fields) {
            this.area = area;
            this.event = event;
            this.fields = fields != null ? fields : Map.of();
        }
    }
}
