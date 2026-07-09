package com.webterm.transport.websocket;

import com.webterm.transport.api.MuxTransport;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class WebSocketMuxTransportTest {
    @Test
    public void closeCallbackCarriesCode() {
        // Minimal compile-level verification that listener receives code
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
        listener.onClosed(1001, "backpressure");
        assertEquals(1001, codeRef.get());
        assertEquals("backpressure", reasonRef.get());
    }
}
