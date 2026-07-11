package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;

import com.webterm.core.session.RelayMuxSessionManager;
import com.webterm.core.session.RelayMuxSessionRegistry;
import com.webterm.core.session.WebTermProtocol;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;

public class TerminalConnectionReconnectTest {

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
    public void reconnectNow_withMatchingSession_forcesReconnect() {
        connection.connect("http://example.com", "cookie", "s1", 0, "device1");

        when(manager.matches("http://example.com", "cookie", "device1")).thenReturn(true);
        when(manager.isConnected()).thenReturn(false);

        connection.reconnectNow();

        verify(manager).forceReconnect("manual reconnect");
    }

    @Test
    public void reconnectNow_withoutMatchingSession_doesNotForceReconnect() {
        connection.connect("http://example.com", "cookie", "s1", 0, "device1");

        when(manager.matches("http://example.com", "cookie", "device1")).thenReturn(false);
        when(manager.isConnected()).thenReturn(false);

        connection.reconnectNow();

        verify(manager, never()).forceReconnect(anyString());
    }

    @Test
    public void reconnectNow_withExistingChannel_startsNewSessionAndSendsHello() {
        when(manager.matches("http://example.com", "cookie", "device1")).thenReturn(true);
        when(manager.isConnected()).thenReturn(true);

        connection.connect("http://example.com", "cookie", "s1", 0, "device1");

        ArgumentCaptor<RelayMuxSessionManager.ChannelListener> listenerCaptor =
            ArgumentCaptor.forClass(RelayMuxSessionManager.ChannelListener.class);
        verify(manager).openTerminalChannel(anyString(), listenerCaptor.capture());
        listenerCaptor.getValue().onConnected("term:s1");

        // Trigger manual reconnect: should force a new physical session.
        connection.reconnectNow();

        verify(manager).forceReconnect("manual reconnect");

        // The second openTerminalChannel call supplies the listener for the new session.
        verify(manager, times(2)).openTerminalChannel(anyString(), listenerCaptor.capture());
        RelayMuxSessionManager.ChannelListener newListener = listenerCaptor.getAllValues().get(1);
        newListener.onConnected("term:s1");

        verify(listener, times(2)).onConnectionStatus(TerminalConnection.State.CONNECTED, 0);

        ArgumentCaptor<byte[]> frameCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(manager, times(2)).sendTunnelFrame(eq("term:s1"), frameCaptor.capture(), eq(true));
        byte[] helloFrame = frameCaptor.getAllValues().get(1);
        assertEquals(WebTermProtocol.MSG_HELLO, helloFrame[0]);
        String helloJson = new String(helloFrame, 1, helloFrame.length - 1, StandardCharsets.UTF_8);
        org.junit.Assert.assertTrue("hello should contain lastSeq: " + helloJson,
            helloJson.contains("\"lastSeq\":0"));
    }

    @Test
    public void reconnectNow_withNonMatchingSession_doesNotLeaveDirtyForceReconnectFlag() {
        when(manager.matches("http://example.com", "cookie", "device1")).thenReturn(true);
        connection.connect("http://example.com", "cookie", "s1", 0, "device1");

        // Simulate the current manager no longer matching (e.g. device identity changed).
        when(manager.matches("http://example.com", "cookie", "device1")).thenReturn(false);
        connection.reconnectNow();

        // No force reconnect should happen while the manager does not match.
        verify(manager, never()).forceReconnect(anyString());

        // A subsequent normal connect with the same matching manager must not be
        // treated as a pending manual reconnect.
        when(manager.matches("http://example.com", "cookie", "device1")).thenReturn(true);
        connection.connect("http://example.com", "cookie", "s1", 0, "device1");

        verify(manager, never()).forceReconnect(anyString());
    }
}
