package com.webterm.core.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Handler;

import com.webterm.transport.api.MuxTransport;
import com.webterm.transport.api.TransportFactory;

import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** P1-2：DeviceConnection recentClosed 淘汰后 lifetime 累计不倒退。 */
public final class DeviceConnectionDiagnosticsRegistryLifetimeTest {

    @Before
    public void setUp() {
        DeviceConnectionDiagnosticsRegistry.clearForTest();
    }

    @After
    public void tearDown() {
        DeviceConnectionDiagnosticsRegistry.clearForTest();
    }

    @Test
    public void closingTwentyConnectionsKeepsLifetimeTotalsMonotonic() {
        Handler handler = synchronousHandler();
        MuxTransport transport = mock(MuxTransport.class);
        TransportFactory factory = (url, cookie, protocol) -> transport;

        long prevAccepted = -1L;
        for (int i = 0; i < 20; i++) {
            DeviceConnection connection = new DeviceConnection(
                handler, "https://relay-life-" + i + ".example/", "cookie", "device-" + i, factory);
            // 本地 outbound 接受一帧，留下可归档的累计计数。
            connection.tryEnqueueTunnelFrame(
                "ch-" + i, new byte[] {1, 2, 3}, true, MuxOutboundQueue.FrameKind.OTHER, null);
            connection.stop();

            Map<String, Object> outbound =
                DeviceConnectionDiagnosticsRegistry.aggregateOutboundQueue();
            long accepted = ((Number) outbound.get("acceptedCount")).longValue();
            assertTrue("acceptedCount must not decrease", accepted >= prevAccepted);
            prevAccepted = accepted;
        }

        Map<String, Long> counts = DeviceConnectionDiagnosticsRegistry.lifetimeConnectionCounts();
        assertEquals(0L, (long) counts.get("activeConnectionCount"));
        assertEquals(16L, (long) counts.get("recentClosedConnectionCount"));
        assertEquals(4L, (long) counts.get("archivedConnectionCount"));
        assertEquals(20L, (long) counts.get("lifetimeConnectionCount"));
        assertEquals(16, DeviceConnectionDiagnosticsRegistry.snapshotRecentClosed().size());

        Map<String, Object> finalOutbound =
            DeviceConnectionDiagnosticsRegistry.aggregateOutboundQueue();
        assertEquals(20L, ((Number) finalOutbound.get("acceptedCount")).longValue());
        // 当前深度类字段不应因 archived 虚增。
        assertEquals(0L, ((Number) finalOutbound.get("currentFrames")).longValue());
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
}
