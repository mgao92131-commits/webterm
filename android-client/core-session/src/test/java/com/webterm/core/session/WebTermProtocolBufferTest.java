package com.webterm.core.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.ByteBuffer;
import org.junit.Test;

public final class WebTermProtocolBufferTest {
    @Test
    public void tunnelPayloadIsAReadOnlySliceOfInboundFrame() {
        byte[] encoded = WebTermProtocol.encodeTunnelFrame(
            "screen-1", new byte[] {10, 20, 30}, true);
        ByteBuffer padded = ByteBuffer.allocate(encoded.length + 4);
        padded.position(2);
        padded.put(encoded);
        padded.flip();
        padded.position(2);
        padded.limit(2 + encoded.length);

        WebTermProtocol.TunnelFrame frame =
            WebTermProtocol.decodeTunnelFrame(padded.asReadOnlyBuffer());

        assertNotNull(frame);
        assertEquals("screen-1", frame.tunnelId);
        assertEquals(WebTermProtocol.WS_DATA_BINARY, frame.extraByte);
        assertEquals(3, frame.payload.remaining());
        assertEquals(10, frame.payload.get(0));
        // 修改 backing 只用于证明 decode 没有再分配正文数组。
        padded.put(2 + encoded.length - 1, (byte) 99);
        assertEquals(99, frame.payload.get(2));
    }
}
