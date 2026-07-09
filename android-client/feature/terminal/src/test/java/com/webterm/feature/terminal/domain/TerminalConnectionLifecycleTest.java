package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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
    public void detachReplacesChannelListener() {
        connection.connect("http://example.com", "cookie", "s1", 0, "device1");

        connection.detach();

        verify(manager).detachChannelListener("term:s1");
        verify(manager, never()).closeChannel(anyString());
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

    @Test
    public void onDataUpdatesChannelLastSeq() {
        when(manager.isConnected()).thenReturn(true);
        connection.connect("http://example.com", "cookie", "s1", 0, "device1");

        ArgumentCaptor<RelayMuxSessionManager.ChannelListener> listenerCaptor =
            ArgumentCaptor.forClass(RelayMuxSessionManager.ChannelListener.class);
        verify(manager).openTerminalChannel(anyString(), listenerCaptor.capture());

        RelayMuxSessionManager.ChannelListener channelListener = listenerCaptor.getValue();
        channelListener.onConnected("term:s1");

        byte[] payload = new byte[8 + 3];
        writeUint64(payload, 0, 42L);
        byte[] frame = WebTermProtocol.frame(WebTermProtocol.MSG_OUTPUT, payload).toByteArray();
        channelListener.onData("term:s1", frame, true);

        assertEquals(42L, connection.getLastSeq());
        verify(manager).updateChannelLastSeq("term:s1", 42L);
    }

    @Test
    public void reattachSeedsLastSeqFromChannel() {
        when(manager.getChannelLastSeq("term:s1")).thenReturn(123L);
        when(manager.isConnected()).thenReturn(true);

        connection.connect("http://example.com", "cookie", "s1", 0, "device1");

        ArgumentCaptor<RelayMuxSessionManager.ChannelListener> listenerCaptor =
            ArgumentCaptor.forClass(RelayMuxSessionManager.ChannelListener.class);
        verify(manager).openTerminalChannel(anyString(), listenerCaptor.capture());

        listenerCaptor.getValue().onConnected("term:s1");

        ArgumentCaptor<byte[]> frameCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(manager).sendTunnelFrame(eq("term:s1"), frameCaptor.capture(), eq(true));
        byte[] helloFrame = frameCaptor.getValue();
        byte[] helloPayload = new byte[helloFrame.length - 1];
        System.arraycopy(helloFrame, 1, helloPayload, 0, helloPayload.length);
        String helloJson = new String(helloPayload, StandardCharsets.UTF_8);
        org.junit.Assert.assertTrue("hello should contain channel seq: " + helloJson,
            helloJson.contains("\"lastSeq\":123"));
    }

    private static void writeUint64(byte[] data, int offset, long value) {
        for (int i = 7; i >= 0; i--) {
            data[offset + i] = (byte) (value & 0xffL);
            value >>= 8;
        }
    }
}
