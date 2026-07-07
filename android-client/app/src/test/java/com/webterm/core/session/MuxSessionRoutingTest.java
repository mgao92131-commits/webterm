package com.webterm.core.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class MuxSessionRoutingTest {

    static final class RecordingListener implements MuxSession.Listener {
        final List<String> tunnelData = new ArrayList<>();
        @Override public void onMuxConnected() {}
        @Override public void onMuxDisconnected(String reason) {}
        @Override public void onTunnelConnected(String tunnelId) {}
        @Override public void onTunnelError(String tunnelId, String message) {}
        @Override public void onTunnelData(String tunnelId, byte[] payload, boolean binary) {
            tunnelData.add(tunnelId + ":" + binary + ":" + new String(payload));
        }
        @Override public void onTunnelClosed(String tunnelId) {}
    }

    @Test
    public void dispatchBinaryFrameRoutesByTunnelId() {
        RecordingListener listener = new RecordingListener();
        // term:s1 通道收到一帧 binary payload {MsgOutput=0x02}
        byte[] payload = new byte[]{0x02};
        byte[] frame = WebTermProtocol.encodeTunnelFrame("term:s1", payload, true);

        MuxSession.dispatchBinaryFrame(frame, listener);

        assertEquals(1, listener.tunnelData.size());
        assertEquals("term:s1:true:" + new String(payload), listener.tunnelData.get(0));
    }

    @Test
    public void dispatchBinaryFrameIgnoresInvalidFrame() {
        RecordingListener listener = new RecordingListener();
        MuxSession.dispatchBinaryFrame(new byte[]{0x01, 0x05}, listener); // too short
        assertTrue(listener.tunnelData.isEmpty());
    }
}
