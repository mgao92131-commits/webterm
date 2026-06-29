package com.webterm.mobile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class WebTermProtocolTest {

    @Test
    public void encodeTunnelFrameBinaryMatchesGoCoreFormat() {
        byte[] idBytes = "term:s1".getBytes();
        byte[] payload = new byte[]{0x04, 0x01, 0x02, 0x03}; // MsgHello + dummy
        byte[] frame = WebTermProtocol.encodeTunnelFrame("term:s1", payload, true);

        // [0x01 | idLen | id... | extraByte(0x02 binary) | payload...]
        assertEquals(0x01, frame[0] & 0xff);
        assertEquals(idBytes.length, frame[1] & 0xff);
        for (int i = 0; i < idBytes.length; i++) {
            assertEquals(idBytes[i], frame[2 + i]);
        }
        assertEquals(WebTermProtocol.WS_DATA_BINARY, frame[2 + idBytes.length] & 0xff);
        for (int i = 0; i < payload.length; i++) {
            assertEquals(payload[i], frame[3 + idBytes.length + i]);
        }
    }

    @Test
    public void encodeTunnelFrameTextUsesTextExtraByte() {
        byte[] payload = "{\"type\":\"hello\"}".getBytes();
        byte[] frame = WebTermProtocol.encodeTunnelFrame("manager", payload, false);
        int idLen = "manager".length();
        assertEquals(WebTermProtocol.WS_DATA_TEXT, frame[2 + idLen] & 0xff);
    }

    @Test
    public void decodeTunnelFrameRoundTripsBinary() {
        byte[] payload = new byte[]{0x01, 0x02, 0x03};
        byte[] frame = WebTermProtocol.encodeTunnelFrame("term:s1", payload, true);
        WebTermProtocol.TunnelFrame decoded = WebTermProtocol.decodeTunnelFrame(frame);
        assertEquals("term:s1", decoded.tunnelId);
        assertEquals(WebTermProtocol.WS_DATA_BINARY, decoded.extraByte & 0xff);
        assertArrayEquals(payload, decoded.payload);
    }

    @Test
    public void decodeTunnelFrameReturnsNullForTooShort() {
        assertNull(WebTermProtocol.decodeTunnelFrame(new byte[]{0x01, 0x05}));
    }

    @Test
    public void decodeTunnelFrameReturnsNullForWrongMsgType() {
        byte[] payload = new byte[]{0x01};
        byte[] frame = new byte[3 + "x".length() + payload.length];
        frame[0] = 0x02; // wrong msg type
        frame[1] = 1;
        frame[2] = 'x';
        frame[3] = WebTermProtocol.WS_DATA_BINARY;
        frame[4] = 0x01;
        assertNull(WebTermProtocol.decodeTunnelFrame(frame));
    }
}
