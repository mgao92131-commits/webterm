package com.webterm.transport.websocket;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
}
