package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
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

    @Test
    public void stateSnapshotResetsLastSeqAndOldOutputIsFiltered() {
        when(manager.isConnected()).thenReturn(true);
        connection.connect("http://example.com", "cookie", "s1", 0, "device1");

        ArgumentCaptor<RelayMuxSessionManager.ChannelListener> listenerCaptor =
            ArgumentCaptor.forClass(RelayMuxSessionManager.ChannelListener.class);
        verify(manager).openTerminalChannel(anyString(), listenerCaptor.capture());

        RelayMuxSessionManager.ChannelListener channelListener = listenerCaptor.getValue();
        channelListener.onConnected("term:s1");

        // Advance client lastSeq to 100 via normal output.
        byte[] outputPayload = new byte[8 + 3];
        writeUint64(outputPayload, 0, 100L);
        byte[] outputFrame = WebTermProtocol.frame(WebTermProtocol.MSG_OUTPUT, outputPayload).toByteArray();
        channelListener.onData("term:s1", outputFrame, true);

        assertEquals(100L, connection.getLastSeq());
        verify(listener).onOutput(eq(100L), any(byte[].class));

        // Server rebuilds the session and sends an authoritative state snapshot with a regressed seq.
        byte[] statePayload = new byte[8 + 3];
        writeUint64(statePayload, 0, 5L);
        byte[] stateFrame = WebTermProtocol.frame(WebTermProtocol.MSG_STATE, statePayload).toByteArray();
        channelListener.onData("term:s1", stateFrame, true);

        // The snapshot must be accepted and lastSeq must reset to the new epoch.
        assertEquals(5L, connection.getLastSeq());
        verify(listener).onState(eq(5L), any(byte[].class));

        // Output from before the snapshot must still be discarded.
        byte[] staleOutputPayload = new byte[8 + 3];
        writeUint64(staleOutputPayload, 0, 3L);
        byte[] staleOutputFrame = WebTermProtocol.frame(WebTermProtocol.MSG_OUTPUT, staleOutputPayload).toByteArray();
        channelListener.onData("term:s1", staleOutputFrame, true);

        verify(listener, never()).onOutput(eq(3L), any(byte[].class));
        assertEquals(5L, connection.getLastSeq());
    }

    @Test
    public void freshReconnectKeepsTheSharedDeviceManagerAndChannels() {
        when(manager.matches("http://example.com", "new-cookie", "device1")).thenReturn(true);
        connection.connect("http://example.com", "cookie", "s1", 0, "device1");

        connection.reconnectFresh("new-cookie");

        verify(registry).forDevice("http://example.com", "cookie", "device1");
        verify(registry).forDevice("http://example.com", "new-cookie", "device1");
        verify(manager).forceReconnect("manual reconnect", true);
    }

    @Test
    public void freshStateResetsBothLocalAndChannelSequence() {
        connection.connect("http://example.com", "cookie", "s1", 0, "device1");

        connection.requestFreshState();

        assertEquals(0L, connection.getLastSeq());
        verify(manager).resetChannelLastSeq("term:s1");
    }

    @Test
    public void freshStateAfterDetachResetsChannelSequence() {
        when(manager.isConnected()).thenReturn(true);
        connection.connect("http://example.com", "cookie", "s1", 0, "device1");

        ArgumentCaptor<RelayMuxSessionManager.ChannelListener> listenerCaptor =
            ArgumentCaptor.forClass(RelayMuxSessionManager.ChannelListener.class);
        verify(manager).openTerminalChannel(anyString(), listenerCaptor.capture());
        RelayMuxSessionManager.ChannelListener channelListener = listenerCaptor.getValue();
        channelListener.onConnected("term:s1");

        // Advance seq to 256.
        byte[] payload = new byte[8];
        writeUint64(payload, 0, 256L);
        byte[] frame = WebTermProtocol.frame(WebTermProtocol.MSG_OUTPUT, payload).toByteArray();
        channelListener.onData("term:s1", frame, true);

        assertEquals(256L, connection.getLastSeq());

        // Detach drops TerminalConnection's references, but the shared manager keeps channel seq.
        connection.detach();

        // Simulate the shared manager still holding the old seq.
        when(manager.getChannelLastSeq("term:s1")).thenReturn(256L);

        connection.requestFreshState();
        assertEquals(0L, connection.getLastSeq());

        // Reconnect: pending fresh state must reset the channel seq so HELLO starts from 0.
        connection.connect("http://example.com", "cookie", "s1", 0, "device1");

        verify(manager).resetChannelLastSeq("term:s1");

        verify(manager, times(2)).openTerminalChannel(anyString(), listenerCaptor.capture());
        RelayMuxSessionManager.ChannelListener newListener = listenerCaptor.getAllValues().get(1);
        newListener.onConnected("term:s1");

        ArgumentCaptor<byte[]> frameCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(manager, times(2)).sendTunnelFrame(eq("term:s1"), frameCaptor.capture(), eq(true));
        byte[] helloFrame = frameCaptor.getAllValues().get(1);
        byte[] helloPayload = new byte[helloFrame.length - 1];
        System.arraycopy(helloFrame, 1, helloPayload, 0, helloPayload.length);
        String helloJson = new String(helloPayload, StandardCharsets.UTF_8);
        assertTrue("hello should reset lastSeq to 0: " + helloJson,
            helloJson.contains("\"lastSeq\":0"));
    }

    private static void writeUint64(byte[] data, int offset, long value) {
        for (int i = 7; i >= 0; i--) {
            data[offset + i] = (byte) (value & 0xffL);
            value >>= 8;
        }
    }
}
