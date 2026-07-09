package com.webterm.transport.websocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.webterm.transport.api.MuxTransport;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class WebSocketMuxTransportTest {

    private OkHttpClient http;
    private OkHttpClient.Builder builder;
    private WebSocket socket1;
    private WebSocket socket2;

    @Before
    public void setUp() {
        http = mock(OkHttpClient.class);
        builder = mock(OkHttpClient.Builder.class);
        when(http.newBuilder()).thenReturn(builder);
        when(builder.pingInterval(anyLong(), any(TimeUnit.class))).thenReturn(builder);
        when(builder.build()).thenReturn(http);
        socket1 = mock(WebSocket.class);
        socket2 = mock(WebSocket.class);
        when(http.newWebSocket(any(Request.class), any(WebSocketListener.class))).thenReturn(socket1, socket2);
    }

    @Test
    public void startClosesStaleSocketAndIsolatesListeners() {
        WebSocketMuxTransport transport = new WebSocketMuxTransport(http, "ws://example.com/ws", "", "proto");

        RecordingListener listener1 = new RecordingListener();
        transport.start(listener1);

        ArgumentCaptor<WebSocketListener> captor1 = ArgumentCaptor.forClass(WebSocketListener.class);
        verify(http).newWebSocket(any(Request.class), captor1.capture());
        WebSocketListener wsListener1 = captor1.getValue();

        wsListener1.onOpen(socket1, mock(Response.class));
        assertTrue(listener1.opened);

        RecordingListener listener2 = new RecordingListener();
        transport.start(listener2);
        verify(socket1).close(1001, "stale socket cleanup");

        ArgumentCaptor<WebSocketListener> captor2 = ArgumentCaptor.forClass(WebSocketListener.class);
        verify(http, times(2)).newWebSocket(any(Request.class), captor2.capture());
        WebSocketListener wsListener2 = captor2.getAllValues().get(1);

        wsListener1.onMessage(socket1, "old");
        assertTrue(listener1.messages.isEmpty());
        assertTrue(listener2.messages.isEmpty());

        wsListener2.onOpen(socket2, mock(Response.class));
        assertTrue(listener2.opened);
        wsListener2.onMessage(socket2, "new");
        assertEquals("new", listener2.messages.get(0));
    }

    @Test
    public void closeInvalidatesCurrentListener() {
        WebSocketMuxTransport transport = new WebSocketMuxTransport(http, "ws://example.com/ws", "", "proto");

        RecordingListener listener = new RecordingListener();
        transport.start(listener);

        ArgumentCaptor<WebSocketListener> captor = ArgumentCaptor.forClass(WebSocketListener.class);
        verify(http).newWebSocket(any(Request.class), captor.capture());
        WebSocketListener wsListener = captor.getValue();

        transport.close();
        verify(socket1).close(1000, "closing mux");

        wsListener.onOpen(socket1, mock(Response.class));
        assertFalse(listener.opened);
    }

    @Test
    public void closeCallbackForwardsWebSocketCode() {
        WebSocketMuxTransport transport = new WebSocketMuxTransport(http, "ws://example.com/ws", "", "proto");

        RecordingListener listener = new RecordingListener();
        transport.start(listener);

        ArgumentCaptor<WebSocketListener> captor = ArgumentCaptor.forClass(WebSocketListener.class);
        verify(http).newWebSocket(any(Request.class), captor.capture());
        WebSocketListener wsListener = captor.getValue();

        wsListener.onClosed(socket1, 1001, "backpressure");
        assertEquals(1001, listener.closeCode);
        assertEquals("backpressure", listener.closeReason);
    }

    private static final class RecordingListener implements MuxTransport.Listener {
        final List<String> messages = new ArrayList<>();
        final List<byte[]> binaries = new ArrayList<>();
        boolean opened;
        int closeCode = -1;
        String closeReason;

        @Override public void onOpen() { opened = true; }
        @Override public void onText(String text) { messages.add(text); }
        @Override public void onBinary(byte[] data) { binaries.add(data); }
        @Override public void onClosed(int code, String reason) { closeCode = code; closeReason = reason; }
        @Override public void onError(String message) {}
    }
}
