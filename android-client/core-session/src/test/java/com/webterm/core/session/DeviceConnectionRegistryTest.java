package com.webterm.core.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Handler;

import com.webterm.transport.api.MuxTransport;
import com.webterm.transport.api.TransportFactory;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class DeviceConnectionRegistryTest {
    private static Handler synchronousHandler() {
        Handler handler = mock(Handler.class);
        when(handler.post(any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return true;
        });
        when(handler.postDelayed(any(Runnable.class), any(long.class))).thenReturn(true);
        return handler;
    }

    private static DeviceConnectionRegistry.StateHandlerFactory synchronousStateFactory() {
        return key -> new DeviceConnectionRegistry.StateHandler(synchronousHandler(), () -> {});
    }

    @Test
    public void forceReleaseRemovesManagerWithActiveChannelAndStopsTransport() throws Exception {
        RecordingFactory factory = new RecordingFactory();
        DeviceConnectionRegistry registry = new DeviceConnectionRegistry(
            synchronousHandler(), factory, synchronousStateFactory());
        DeviceConnection manager = registry.forDirectDevice("direct_1", "http://old", "");
        manager.openChannel("channel", "/ws/sessions", null, new NoOpChannelListener());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (manager.isIdle() && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertFalse("test manager should have an active logical channel", manager.isIdle());

        registry.forceRelease(manager);
        assertTrue("force release should close the physical transport",
            factory.transports.get(0).closed.await(2, TimeUnit.SECONDS));

        DeviceConnection replacement = registry.forDirectDevice("direct_1", "http://new", "");
        assertNotSame("released manager must not be returned again", manager, replacement);
        registry.forceRelease(replacement);
    }

    @Test
    public void forceReleaseIsIdempotent() throws Exception {
        RecordingFactory factory = new RecordingFactory();
        DeviceConnectionRegistry registry = new DeviceConnectionRegistry(
            synchronousHandler(), factory, synchronousStateFactory());
        DeviceConnection manager = registry.forDirectDevice("direct_2", "http://old", "");

        registry.forceRelease(manager);
        registry.forceRelease(manager);

        assertTrue(factory.transports.get(0).closed.await(2, TimeUnit.SECONDS));
        assertEquals(1, factory.transports.get(0).closeCount.get());
    }

    @Test
    public void editingDirectAddressStopsOldManagerBeforeCreatingNewOne() throws Exception {
        RecordingFactory factory = new RecordingFactory();
        DeviceConnectionRegistry registry = new DeviceConnectionRegistry(
            synchronousHandler(), factory, synchronousStateFactory());
        DeviceConnection oldManager = registry.forDirectDevice("direct_3", "http://old", "");

        DeviceConnection newManager = registry.forDirectDevice("direct_3", "http://new", "");

        assertNotSame(oldManager, newManager);
        assertTrue(factory.transports.get(0).closed.await(2, TimeUnit.SECONDS));
        registry.forceRelease(newManager);
    }

    @Test
    public void closeChannelAndReleaseIfIdleStopsTransportWhenLastChannelCloses() throws Exception {
        RecordingFactory factory = new RecordingFactory();
        DeviceConnectionRegistry registry = new DeviceConnectionRegistry(
            synchronousHandler(), factory, synchronousStateFactory());
        DeviceConnection manager = registry.forDirectDevice("direct_atomic", "http://host", "");
        manager.openChannel("ch1", "/ws/sessions", null, new NoOpChannelListener());
        assertFalse(manager.isIdle());

        manager.closeChannelAndReleaseIfIdle("ch1", ConnectionCloseReason.RUNTIME_CLOSED,
            () -> registry.removeIfSame(manager));

        assertTrue(manager.isStopped());
        assertTrue(manager.isIdle());
        assertTrue(factory.transports.get(0).closed.await(2, TimeUnit.SECONDS));

        DeviceConnection replacement = registry.forDirectDevice("direct_atomic", "http://host", "");
        assertNotSame(manager, replacement);
        assertFalse(replacement.isStopped());
        registry.forceRelease(replacement);
    }

    @Test
    public void closeChannelAndReleaseIfIdleKeepsManagerWhenControlListenerPresent() throws Exception {
        RecordingFactory factory = new RecordingFactory();
        DeviceConnectionRegistry registry = new DeviceConnectionRegistry(
            synchronousHandler(), factory, synchronousStateFactory());
        DeviceConnection manager = registry.forDirectDevice("direct_control", "http://host", "");
        manager.setControlListener(msg -> {});
        manager.openChannel("ch1", "/ws/sessions", null, new NoOpChannelListener());

        manager.closeChannelAndReleaseIfIdle("ch1", ConnectionCloseReason.CHANNELS_IDLE,
            () -> registry.removeIfSame(manager));

        assertFalse("control listener must keep the shared connection", manager.isStopped());
        assertFalse(manager.isIdle());
        assertSame(manager, registry.forDirectDevice("direct_control", "http://host", ""));
        registry.forceRelease(manager);
    }

    @Test
    public void getOrCreateDoesNotReturnStoppedManager() throws Exception {
        RecordingFactory factory = new RecordingFactory();
        DeviceConnectionRegistry registry = new DeviceConnectionRegistry(
            synchronousHandler(), factory, synchronousStateFactory());
        DeviceConnection oldManager = registry.forDirectDevice("direct_stopped", "http://host", "");
        oldManager.openChannel("ch1", "/ws/sessions", null, new NoOpChannelListener());
        oldManager.closeChannelAndReleaseIfIdle("ch1", ConnectionCloseReason.RUNTIME_CLOSED,
            () -> registry.removeIfSame(oldManager));
        assertTrue(oldManager.isStopped());

        DeviceConnection next = registry.forDirectDevice("direct_stopped", "http://host", "");
        assertNotSame(oldManager, next);
        assertFalse(next.isStopped());
        registry.forceRelease(next);
    }

    @Test
    public void removeIfSameDoesNotDeleteReplacementManager() throws Exception {
        RecordingFactory factory = new RecordingFactory();
        DeviceConnectionRegistry registry = new DeviceConnectionRegistry(
            synchronousHandler(), factory, synchronousStateFactory());
        DeviceConnection oldManager = registry.forDirectDevice("direct_replace", "http://host", "");
        registry.forceRelease(oldManager);
        DeviceConnection newManager = registry.forDirectDevice("direct_replace", "http://host", "");

        registry.removeIfSame(oldManager);

        assertSame("async remove of old manager must not drop the replacement",
            newManager, registry.forDirectDevice("direct_replace", "http://host", ""));
        registry.forceRelease(newManager);
    }

    private static final class NoOpChannelListener implements DeviceConnection.ChannelListener {
        @Override public void onConnected(String channelId) {}
        @Override public void onData(String channelId, byte[] payload, boolean binary) {}
        @Override public void onFailure(String channelId, ChannelFailure failure) {}
    }

    private static final class RecordingFactory implements TransportFactory {
        final List<RecordingTransport> transports = new ArrayList<>();

        @Override public synchronized MuxTransport create(String url, String cookie, String protocol) {
            RecordingTransport transport = new RecordingTransport();
            transports.add(transport);
            return transport;
        }
    }

    private static final class RecordingTransport implements MuxTransport {
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicInteger closeCount = new AtomicInteger();

        @Override public void start(Listener listener) {}
        @Override public void close() {
            closeCount.incrementAndGet();
            closed.countDown();
        }
        @Override public boolean isConnected() { return false; }
        @Override public boolean sendText(String text) { return false; }
        @Override public boolean sendBinary(byte[] data) { return false; }
    }
}
