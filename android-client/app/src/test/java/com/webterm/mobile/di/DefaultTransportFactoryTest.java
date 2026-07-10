package com.webterm.mobile.di;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.webterm.core.config.ServerConfigStore;
import com.webterm.transport.webrtc.P2PConnectionManager;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

public class DefaultTransportFactoryTest {

    private final OkHttpClient http = mock(OkHttpClient.class);
    private final P2PConnectionManager p2p = mock(P2PConnectionManager.class);
    private final ServerConfigStore configStore = mock(ServerConfigStore.class);

    @Test
    public void prepareDataChannelSkippedWhenP2PDisabled() throws InterruptedException {
        when(configStore.isP2PEnabled()).thenReturn(false);
        DefaultTransportFactory factory = new DefaultTransportFactory(http, p2p, configStore);

        CountDownLatch latch = new CountDownLatch(1);
        factory.prepareDataChannel("http://example.com", "cookie", "device1");
        // Give the single-threaded executor a chance to run if it were invoked.
        latch.await(100, TimeUnit.MILLISECONDS);

        verify(p2p, never()).connectToDevice(any(), any(), any());
    }

    @Test
    public void prepareDataChannelStartsP2PWhenEnabled() throws InterruptedException {
        when(configStore.isP2PEnabled()).thenReturn(true);
        DefaultTransportFactory factory = new DefaultTransportFactory(http, p2p, configStore);

        factory.prepareDataChannel("http://example.com", "cookie", "device1");
        // The executor runs asynchronously; wait briefly for it to start.
        Thread.sleep(50);

        verify(p2p).connectToDevice("http://example.com", "cookie", "device1");
    }

    @Test
    public void prepareDataChannelSkippedWhenDeviceIdEmpty() throws InterruptedException {
        when(configStore.isP2PEnabled()).thenReturn(true);
        DefaultTransportFactory factory = new DefaultTransportFactory(http, p2p, configStore);

        CountDownLatch latch = new CountDownLatch(1);
        factory.prepareDataChannel("http://example.com", "cookie", "");
        latch.await(100, TimeUnit.MILLISECONDS);

        verify(p2p, never()).connectToDevice(any(), any(), any());
    }
}
