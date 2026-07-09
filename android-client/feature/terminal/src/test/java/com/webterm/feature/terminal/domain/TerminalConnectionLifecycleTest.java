package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;

import com.webterm.core.session.RelayMuxSessionManager;
import com.webterm.core.session.RelayMuxSessionRegistry;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class TerminalConnectionLifecycleTest {

    private Handler testHandler;
    private RelayMuxSessionRegistry registry;
    private RelayMuxSessionManager manager;
    private TerminalConnection connection;
    private TerminalConnection.Listener listener;

    @Before
    public void setUp() {
        testHandler = mock(Handler.class);
        when(testHandler.post(any(Runnable.class))).thenAnswer(invocation -> {
            Runnable r = invocation.getArgument(0);
            r.run();
            return true;
        });
        when(testHandler.postDelayed(any(Runnable.class), any(long.class))).thenReturn(true);

        registry = mock(RelayMuxSessionRegistry.class);
        manager = mock(RelayMuxSessionManager.class);
        when(registry.forDevice(anyString(), anyString(), anyString())).thenReturn(manager);
        when(manager.openTerminalChannel(anyString(), any(RelayMuxSessionManager.ChannelListener.class)))
            .thenReturn("term:s1");

        listener = mock(TerminalConnection.Listener.class);
        connection = new TerminalConnection(testHandler, registry, listener);
    }

    @Test
    public void detachDoesNotCloseChannel() {
        connection.connect("http://example.com", "cookie", "s1", 0, "device1");

        connection.detach();

        verify(manager, never()).closeChannel(anyString());
        verify(registry, never()).releaseIfIdle(any());
        assertEquals(TerminalConnection.State.DISCONNECTED, connection.getState());
    }

    @Test
    public void closeSessionClosesChannel() {
        connection.connect("http://example.com", "cookie", "s1", 0, "device1");

        connection.closeSession();

        verify(manager).closeChannel("term:s1");
        verify(registry).releaseIfIdle(manager);
        assertEquals(TerminalConnection.State.DISCONNECTED, connection.getState());
    }

    @Test
    public void closeSessionIsIdempotent() {
        connection.connect("http://example.com", "cookie", "s1", 0, "device1");

        connection.closeSession();
        connection.closeSession();

        verify(manager, times(1)).closeChannel("term:s1");
        verify(registry, times(1)).releaseIfIdle(manager);
    }

    @Test
    public void channelGoneGuardPreventsDuplicateExit() {
        connection.connect("http://example.com", "cookie", "s1", 0, "device1");

        ArgumentCaptor<RelayMuxSessionManager.ChannelListener> listenerCaptor =
            ArgumentCaptor.forClass(RelayMuxSessionManager.ChannelListener.class);
        verify(manager).openTerminalChannel(anyString(), listenerCaptor.capture());

        RelayMuxSessionManager.ChannelListener channelListener = listenerCaptor.getValue();
        channelListener.onChannelGone("term:s1", 404, "not found");

        verify(listener, times(1)).onExit(0);
        assertEquals(TerminalConnection.State.DISCONNECTED, connection.getState());

        channelListener.onChannelGone("term:s1", 404, "not found");

        verify(listener, times(1)).onExit(0);
    }

    @Test
    public void detachIgnoresChannelGone() {
        connection.connect("http://example.com", "cookie", "s1", 0, "device1");

        ArgumentCaptor<RelayMuxSessionManager.ChannelListener> listenerCaptor =
            ArgumentCaptor.forClass(RelayMuxSessionManager.ChannelListener.class);
        verify(manager).openTerminalChannel(anyString(), listenerCaptor.capture());

        connection.detach();

        listenerCaptor.getValue().onChannelGone("term:s1", 0, "gone");

        verify(listener, never()).onExit(any(int.class));
        assertEquals(TerminalConnection.State.DISCONNECTED, connection.getState());
    }
}
