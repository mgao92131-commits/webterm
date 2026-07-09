package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Handler;

import com.webterm.core.session.RelayMuxSessionManager;
import com.webterm.core.session.RelayMuxSessionRegistry;
import com.webterm.core.session.WebTermProtocol;
import com.webterm.transport.api.MuxTransport;
import com.webterm.transport.api.TransportFactory;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class TerminalConnectionIntegrationTest {

    private Handler testHandler;
    private FakeMuxTransport transport;
    private RelayMuxSessionRegistry registry;

    @Before
    public void setUp() {
        testHandler = mock(Handler.class);
        when(testHandler.post(any(Runnable.class))).thenAnswer(invocation -> {
            Runnable r = invocation.getArgument(0);
            r.run();
            return true;
        });
        when(testHandler.postDelayed(any(Runnable.class), anyLong())).thenAnswer(invocation -> {
            Runnable r = invocation.getArgument(0);
            r.run();
            return true;
        });

        transport = new FakeMuxTransport();
        registry = new RelayMuxSessionRegistry(null, testHandler, new FakeTransportFactory(transport));
    }

    @Test
    public void detachReattachPreservesLastSeq() throws JSONException {
        TerminalConnection connection1 = createConnection();
        connection1.connect("http://example.com", "cookie", "s1", 0, "device1");

        transport.simulateOpen();
        assertEquals("mux open should send ws-connect",
            1, transport.wsConnectCount("term:s1"));

        transport.simulateText("{\"type\":\"ws-connected\",\"tunnelConnectionId\":\"term:s1\"}");

        HelloFrame hello1 = transport.lastHelloFrame("term:s1");
        assertTrue("first HELLO should be sent", hello1 != null);
        assertEquals("first HELLO should start with lastSeq=0", 0L, hello1.lastSeq);

        byte[] outputFrame = outputFrame(10L, new byte[0]);
        transport.simulateBinary(encodeTunnelFrame("term:s1", outputFrame, true));
        assertEquals("lastSeq should advance to 10 after OUTPUT", 10L, connection1.getLastSeq());

        connection1.detach();
        assertEquals("detach should leave channel lastSeq in manager", 10L,
            registry.forDevice("http://example.com", "cookie", "device1")
                .getChannelLastSeq("term:s1"));

        TerminalConnection connection2 = createConnection();
        connection2.connect("http://example.com", "cookie", "s1", 0, "device1");

        assertEquals("reattach should reuse existing channel (no extra ws-connect)",
            1, transport.wsConnectCount("term:s1"));
        assertEquals("reattached connection should be connected",
            TerminalConnection.State.CONNECTED, connection2.getState());

        HelloFrame hello2 = transport.lastHelloFrame("term:s1");
        assertTrue("second HELLO should be sent", hello2 != null);
        assertEquals("second HELLO should carry lastSeq=10", 10L, hello2.lastSeq);
    }

    private TerminalConnection createConnection() {
        return new TerminalConnection(testHandler, registry, new TerminalConnection.Listener() {
            @Override public void onConnectionStatus(TerminalConnection.State state, int reconnectAttempts) {}
            @Override public void onOutput(long seq, byte[] data) {}
            @Override public void onState(long seq, byte[] data) {}
            @Override public void onInfo(org.json.JSONObject info) {}
            @Override public void onHook(org.json.JSONObject ev) {}
            @Override public void onDownloadHook(String downloadId, String fileName, long fileSize, String sessionId) {}
            @Override public void onExit(int code) {}
            @Override public void onProtocolError(String message) {}
        });
    }

    private static byte[] outputFrame(long seq, byte[] data) {
        byte[] payload = new byte[8 + data.length];
        writeUint64(payload, 0, seq);
        System.arraycopy(data, 0, payload, 8, data.length);
        return WebTermProtocol.frame(WebTermProtocol.MSG_OUTPUT, payload).toByteArray();
    }

    private static void writeUint64(byte[] buffer, int offset, long value) {
        for (int i = 7; i >= 0; i--) {
            buffer[offset + i] = (byte) (value & 0xffL);
            value >>= 8;
        }
    }

    private static byte[] encodeTunnelFrame(String tunnelId, byte[] payload, boolean binary) {
        byte[] idBytes = tunnelId.getBytes(StandardCharsets.UTF_8);
        byte extraByte = binary ? (byte) 0x02 : (byte) 0x01;
        byte[] frame = new byte[3 + idBytes.length + (payload == null ? 0 : payload.length)];
        frame[0] = 0x01; // MSG_TYPE_WS_DATA
        frame[1] = (byte) idBytes.length;
        System.arraycopy(idBytes, 0, frame, 2, idBytes.length);
        frame[2 + idBytes.length] = extraByte;
        if (payload != null) {
            System.arraycopy(payload, 0, frame, 3 + idBytes.length, payload.length);
        }
        return frame;
    }

    private static class HelloFrame {
        final long lastSeq;

        HelloFrame(long lastSeq) {
            this.lastSeq = lastSeq;
        }
    }

    private static class FakeMuxTransport implements MuxTransport {
        private Listener listener;
        private boolean open;
        private final List<String> sentTexts = new ArrayList<>();
        private final List<TunnelBinary> sentBinaries = new ArrayList<>();

        @Override public void start(Listener listener) {
            this.listener = listener;
        }

        void simulateOpen() {
            open = true;
            if (listener != null) listener.onOpen();
        }

        void simulateText(String text) {
            if (listener != null) listener.onText(text);
        }

        void simulateBinary(byte[] data) {
            if (listener != null) listener.onBinary(data);
        }

        @Override public void close() {
            open = false;
        }

        @Override public boolean isConnected() {
            return open;
        }

        @Override public boolean sendText(String text) {
            sentTexts.add(text);
            return true;
        }

        @Override public boolean sendBinary(byte[] data) {
            sentBinaries.add(new TunnelBinary(data));
            return true;
        }

        int wsConnectCount(String tunnelId) {
            int count = 0;
            for (String text : sentTexts) {
                try {
                    JSONObject msg = new JSONObject(text);
                    if ("ws-connect".equals(msg.optString("type"))
                            && tunnelId.equals(msg.optString("tunnelConnectionId"))) {
                        count++;
                    }
                } catch (JSONException ignored) {
                }
            }
            return count;
        }

        HelloFrame lastHelloFrame(String tunnelId) {
            HelloFrame result = null;
            for (TunnelBinary binary : sentBinaries) {
                byte[] data = binary.data;
                if (data == null || data.length < 3) continue;
                if ((data[0] & 0xff) != 0x01) continue; // MSG_TYPE_WS_DATA
                int idLen = data[1] & 0xff;
                if (data.length < 2 + idLen + 1) continue;
                String id = new String(data, 2, idLen, StandardCharsets.UTF_8);
                if (!tunnelId.equals(id)) continue;
                int payloadStart = 3 + idLen;
                byte[] payload = new byte[data.length - payloadStart];
                System.arraycopy(data, payloadStart, payload, 0, payload.length);
                if (payload.length == 0) continue;
                if (payload[0] != WebTermProtocol.MSG_HELLO) continue;
                String json = new String(payload, 1, payload.length - 1, StandardCharsets.UTF_8);
                try {
                    JSONObject hello = new JSONObject(json);
                    result = new HelloFrame(hello.optLong("lastSeq", -1));
                } catch (JSONException ignored) {
                }
            }
            return result;
        }
    }

    private static class TunnelBinary {
        final byte[] data;

        TunnelBinary(byte[] data) {
            this.data = data;
        }
    }

    private static class FakeTransportFactory implements TransportFactory {
        private final FakeMuxTransport transport;

        FakeTransportFactory(FakeMuxTransport transport) {
            this.transport = transport;
        }

        @Override public MuxTransport createWebSocket(String url, String cookie, String protocol) {
            return transport;
        }

        @Override public MuxTransport createDataChannel(String deviceId) {
            return null;
        }

        @Override public void prepareDataChannel(String baseUrl, String cookie, String deviceId) {}
    }
}
