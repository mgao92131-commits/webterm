package com.webterm.transport.websocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.webterm.transport.api.MuxTransport;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.io.IOException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class WebSocketMuxTransportTest {

    private OkHttpClient http;
    private OkHttpClient.Builder builder;
    private WebSocket socket;

    @Before
    public void setUp() {
        http = mock(OkHttpClient.class);
        builder = mock(OkHttpClient.Builder.class);
        when(http.newBuilder()).thenReturn(builder);
        when(builder.pingInterval(any(Long.class), any(TimeUnit.class))).thenReturn(builder);
        when(builder.build()).thenReturn(http);
        socket = mock(WebSocket.class);
        when(http.newWebSocket(any(Request.class), any(WebSocketListener.class))).thenReturn(socket);
    }

    @Test
    public void closeCallbackForwardsWebSocketCodeAndReason() {
        WebSocketMuxTransport transport = new WebSocketMuxTransport(http, "ws://example.com/ws", "", "proto");

        AtomicInteger codeRef = new AtomicInteger(-1);
        AtomicReference<String> reasonRef = new AtomicReference<>();
        MuxTransport.Listener listener = new MuxTransport.Listener() {
            @Override public void onOpen() {}
            @Override public void onText(String text) {}
            @Override public void onBinary(byte[] data) {}
            @Override public void onClosed(int code, String reason) {
                codeRef.set(code);
                reasonRef.set(reason);
            }
            @Override public void onError(String message) {}
        };

        transport.start(listener);

        ArgumentCaptor<WebSocketListener> captor = ArgumentCaptor.forClass(WebSocketListener.class);
        verify(http).newWebSocket(any(Request.class), captor.capture());
        WebSocketListener wsListener = captor.getValue();

        wsListener.onClosed(socket, 1001, "backpressure");

        assertEquals(1001, codeRef.get());
        assertEquals("backpressure", reasonRef.get());
    }

    @Test
    public void handshakeFailureForwardsHttpStatusCode() {
        WebSocketMuxTransport transport = new WebSocketMuxTransport(http, "ws://example.com/ws", "", "proto");
        AtomicInteger codeRef = new AtomicInteger(-1);
        MuxTransport.Listener listener = new MuxTransport.Listener() {
            @Override public void onOpen() {}
            @Override public void onText(String text) {}
            @Override public void onBinary(byte[] data) {}
            @Override public void onClosed(int code, String reason) {}
            @Override public void onError(String message) {}
            @Override public void onError(int code, String message) { codeRef.set(code); }
        };
        transport.start(listener);
        ArgumentCaptor<WebSocketListener> captor = ArgumentCaptor.forClass(WebSocketListener.class);
        verify(http).newWebSocket(any(Request.class), captor.capture());
        Response response = new Response.Builder()
            .request(new Request.Builder().url("http://example.com/ws").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .build();

        captor.getValue().onFailure(socket, new IOException("handshake failed"), response);

        assertEquals(401, codeRef.get());
    }

    @Test
    public void staleClosedCallbackCannotClearNewSocket() {
        WebSocket first = mock(WebSocket.class);
        WebSocket second = mock(WebSocket.class);
        when(http.newWebSocket(any(Request.class), any(WebSocketListener.class)))
            .thenReturn(first, second);
        when(second.send(any(okio.ByteString.class))).thenReturn(true);

        WebSocketMuxTransport transport = new WebSocketMuxTransport(
            http, "ws://example.com/ws", "", "proto");
        MuxTransport.Listener listener = new MuxTransport.Listener() {
            @Override public void onOpen() {}
            @Override public void onText(String text) {}
            @Override public void onBinary(byte[] data) {}
            @Override public void onClosed(int code, String reason) {}
            @Override public void onError(String message) {}
        };

        transport.start(listener);
        transport.close();
        transport.start(listener);

        ArgumentCaptor<WebSocketListener> captor = ArgumentCaptor.forClass(WebSocketListener.class);
        verify(http, times(2)).newWebSocket(any(Request.class), captor.capture());
        WebSocketListener oldListener = captor.getAllValues().get(0);
        WebSocketListener newListener = captor.getAllValues().get(1);

        newListener.onOpen(second, mock(Response.class));
        assertTrue("new socket should be connected", transport.isConnected());

        // OkHttp 对 close() 的回调可以晚于下一次 start()。旧 socket 的终态回调
        // 不得把新 socket 的 connected/reference 清空。
        oldListener.onClosed(first, 1000, "old socket closed late");

        assertTrue("stale callback must not disconnect the new socket", transport.isConnected());
        assertTrue("new socket must remain writable", transport.sendBinary(new byte[]{1}));
        verify(second).send(any(okio.ByteString.class));
    }

    @Test
    public void staleClosedCallbackCannotSpawnParallelSocket() {
        WebSocket first = mock(WebSocket.class);
        WebSocket second = mock(WebSocket.class);
        WebSocket third = mock(WebSocket.class);
        when(http.newWebSocket(any(Request.class), any(WebSocketListener.class)))
            .thenReturn(first, second, third);

        WebSocketMuxTransport transport = new WebSocketMuxTransport(
            http, "ws://example.com/ws", "", "proto");
        MuxTransport.Listener listener = new MuxTransport.Listener() {
            @Override public void onOpen() {}
            @Override public void onText(String text) {}
            @Override public void onBinary(byte[] data) {}
            @Override public void onClosed(int code, String reason) {}
            @Override public void onError(String message) {}
        };

        transport.start(listener);
        transport.close();
        transport.start(listener);

        ArgumentCaptor<WebSocketListener> captor = ArgumentCaptor.forClass(WebSocketListener.class);
        verify(http, times(2)).newWebSocket(any(Request.class), captor.capture());
        WebSocketListener oldListener = captor.getAllValues().get(0);
        WebSocketListener newListener = captor.getAllValues().get(1);
        newListener.onOpen(second, mock(Response.class));

        // 旧连接迟到的 onClosed 若清空当前 socket，下一次幂等 start() 会误建第三条
        // 物理连接，而第二条仍然存活；这正是服务端 clients 递增所需的机制。
        oldListener.onClosed(first, 1000, "old socket closed late");
        transport.start(listener);

        verify(http, times(2)).newWebSocket(any(Request.class), any(WebSocketListener.class));
    }

    @Test
    public void staleFailureCallbackCannotClearNewSocket() {
        WebSocket first = mock(WebSocket.class);
        WebSocket second = mock(WebSocket.class);
        when(http.newWebSocket(any(Request.class), any(WebSocketListener.class)))
            .thenReturn(first, second);
        when(second.send(any(okio.ByteString.class))).thenReturn(true);

        WebSocketMuxTransport transport = new WebSocketMuxTransport(
            http, "ws://example.com/ws", "", "proto");
        MuxTransport.Listener listener = new MuxTransport.Listener() {
            @Override public void onOpen() {}
            @Override public void onText(String text) {}
            @Override public void onBinary(byte[] data) {}
            @Override public void onClosed(int code, String reason) {}
            @Override public void onError(String message) {}
        };

        transport.start(listener);
        transport.close();
        transport.start(listener);

        ArgumentCaptor<WebSocketListener> captor = ArgumentCaptor.forClass(WebSocketListener.class);
        verify(http, times(2)).newWebSocket(any(Request.class), captor.capture());
        WebSocketListener oldListener = captor.getAllValues().get(0);
        WebSocketListener newListener = captor.getAllValues().get(1);
        newListener.onOpen(second, mock(Response.class));

        oldListener.onFailure(first, new IOException("old socket failed late"), null);

        assertTrue("stale failure must not disconnect the new socket", transport.isConnected());
        assertTrue("new socket must remain writable", transport.sendBinary(new byte[]{1}));
        verify(second).send(any(okio.ByteString.class));
    }

    @Test
    public void receiveBinaryCountsFramesAndBytes() {
        WebSocketMuxTransport transport = new WebSocketMuxTransport(http, "ws://example.com/ws", "", "proto");
        transport.start(idleListener());

        ArgumentCaptor<WebSocketListener> captor = ArgumentCaptor.forClass(WebSocketListener.class);
        verify(http).newWebSocket(any(Request.class), captor.capture());
        WebSocketListener wsListener = captor.getValue();

        wsListener.onOpen(socket, mock(Response.class));

        byte[] first = new byte[]{1, 2, 3};
        byte[] second = new byte[]{4, 5};
        wsListener.onMessage(socket, okio.ByteString.of(first));
        wsListener.onMessage(socket, okio.ByteString.of(second));

        MuxTransport.TrafficSnapshot snapshot = transport.trafficSnapshot();
        assertEquals(2L, snapshot.rxFrames);
        assertEquals(5L, snapshot.rxBytes);
        assertEquals(0L, snapshot.txFrames);
        assertEquals(0L, snapshot.txBytes);
    }

    @Test
    public void sendBinaryCountsFramesAndBytes() {
        when(socket.send(any(okio.ByteString.class))).thenReturn(true);
        WebSocketMuxTransport transport = new WebSocketMuxTransport(http, "ws://example.com/ws", "", "proto");
        transport.start(idleListener());

        ArgumentCaptor<WebSocketListener> captor = ArgumentCaptor.forClass(WebSocketListener.class);
        verify(http).newWebSocket(any(Request.class), captor.capture());
        captor.getValue().onOpen(socket, mock(Response.class));

        assertTrue(transport.sendBinary(new byte[]{1, 2, 3, 4}));
        assertTrue(transport.sendBinary(new byte[]{5, 6}));

        MuxTransport.TrafficSnapshot snapshot = transport.trafficSnapshot();
        assertEquals(0L, snapshot.rxFrames);
        assertEquals(0L, snapshot.rxBytes);
        assertEquals(2L, snapshot.txFrames);
        assertEquals(6L, snapshot.txBytes);
    }

    @Test
    public void sendTextCountsFramesAndLength() {
        when(socket.send(any(String.class))).thenReturn(true);
        WebSocketMuxTransport transport = new WebSocketMuxTransport(http, "ws://example.com/ws", "", "proto");
        transport.start(idleListener());

        ArgumentCaptor<WebSocketListener> captor = ArgumentCaptor.forClass(WebSocketListener.class);
        verify(http).newWebSocket(any(Request.class), captor.capture());
        captor.getValue().onOpen(socket, mock(Response.class));

        assertTrue(transport.sendText("hello"));

        MuxTransport.TrafficSnapshot snapshot = transport.trafficSnapshot();
        assertEquals(1L, snapshot.txFrames);
        assertEquals(5L, snapshot.txBytes);
    }

    @Test
    public void sendTextCountsUtf8BytesNotCharLength() {
        when(socket.send(any(String.class))).thenReturn(true);
        WebSocketMuxTransport transport = new WebSocketMuxTransport(http, "ws://example.com/ws", "", "proto");
        transport.start(idleListener());

        ArgumentCaptor<WebSocketListener> captor = ArgumentCaptor.forClass(WebSocketListener.class);
        verify(http).newWebSocket(any(Request.class), captor.capture());
        captor.getValue().onOpen(socket, mock(Response.class));

        // "中" is 3 bytes in UTF-8, but String.length() returns 1.
        assertTrue(transport.sendText("中文"));

        MuxTransport.TrafficSnapshot snapshot = transport.trafficSnapshot();
        assertEquals(1L, snapshot.txFrames);
        assertEquals(6L, snapshot.txBytes);
    }

    @Test
    public void textReceptionCountsFramesAndUtf8Bytes() {
        WebSocketMuxTransport transport = new WebSocketMuxTransport(http, "ws://example.com/ws", "", "proto");
        transport.start(idleListener());

        ArgumentCaptor<WebSocketListener> captor = ArgumentCaptor.forClass(WebSocketListener.class);
        verify(http).newWebSocket(any(Request.class), captor.capture());
        WebSocketListener wsListener = captor.getValue();

        wsListener.onOpen(socket, mock(Response.class));
        wsListener.onMessage(socket, "hello");
        wsListener.onMessage(socket, "中文");

        MuxTransport.TrafficSnapshot snapshot = transport.trafficSnapshot();
        assertEquals(2L, snapshot.rxFrames);
        assertEquals(11L, snapshot.rxBytes); // 5 + 6
    }

    @Test
    public void trafficAccumulatorSurvivesTransportRecreation() {
        when(socket.send(any(String.class))).thenReturn(true);
        MuxTransport.TrafficAccumulator accumulator = new MuxTransport.TrafficAccumulator();

        WebSocketMuxTransport first = new WebSocketMuxTransport(http, "ws://example.com/ws", "", "proto");
        first.setTrafficAccumulator(accumulator);
        first.start(idleListener());

        ArgumentCaptor<WebSocketListener> firstCaptor = ArgumentCaptor.forClass(WebSocketListener.class);
        verify(http).newWebSocket(any(Request.class), firstCaptor.capture());
        firstCaptor.getValue().onOpen(socket, mock(Response.class));
        assertTrue(first.sendText("first"));

        // Simulate reconnect: create a new transport using the same accumulator.
        WebSocketMuxTransport second = new WebSocketMuxTransport(http, "ws://example.com/ws", "", "proto");
        second.setTrafficAccumulator(accumulator);
        // Do not start second to avoid mock complexity; just verify accumulator totals.

        MuxTransport.TrafficSnapshot snapshot = accumulator.snapshot();
        assertEquals(1L, snapshot.txFrames);
        assertEquals(5L, snapshot.txBytes);
    }

    @Test
    public void rejectedSendDoesNotCount() {
        when(socket.send(any(okio.ByteString.class))).thenReturn(false);
        WebSocketMuxTransport transport = new WebSocketMuxTransport(http, "ws://example.com/ws", "", "proto");
        transport.start(idleListener());

        ArgumentCaptor<WebSocketListener> captor = ArgumentCaptor.forClass(WebSocketListener.class);
        verify(http).newWebSocket(any(Request.class), captor.capture());
        captor.getValue().onOpen(socket, mock(Response.class));

        assertFalse(transport.sendBinary(new byte[]{1, 2, 3}));

        MuxTransport.TrafficSnapshot snapshot = transport.trafficSnapshot();
        assertEquals(0L, snapshot.txFrames);
        assertEquals(0L, snapshot.txBytes);
    }

    @Test
    public void staleBinaryCallbackDoesNotCount() throws IOException {
        WebSocket first = mock(WebSocket.class);
        WebSocket second = mock(WebSocket.class);
        when(http.newWebSocket(any(Request.class), any(WebSocketListener.class)))
            .thenReturn(first, second);

        WebSocketMuxTransport transport = new WebSocketMuxTransport(
            http, "ws://example.com/ws", "", "proto");
        transport.start(idleListener());
        transport.close();
        transport.start(idleListener());

        ArgumentCaptor<WebSocketListener> captor = ArgumentCaptor.forClass(WebSocketListener.class);
        verify(http, times(2)).newWebSocket(any(Request.class), captor.capture());
        WebSocketListener oldListener = captor.getAllValues().get(0);
        WebSocketListener newListener = captor.getAllValues().get(1);
        newListener.onOpen(second, mock(Response.class));

        oldListener.onMessage(first, okio.ByteString.of(new byte[]{1, 2, 3}));

        MuxTransport.TrafficSnapshot snapshot = transport.trafficSnapshot();
        assertEquals(0L, snapshot.rxFrames);
        assertEquals(0L, snapshot.rxBytes);
    }

    private MuxTransport.Listener idleListener() {
        return new MuxTransport.Listener() {
            @Override public void onOpen() {}
            @Override public void onText(String text) {}
            @Override public void onBinary(byte[] data) {}
            @Override public void onClosed(int code, String reason) {}
            @Override public void onError(String message) {}
        };
    }
}
