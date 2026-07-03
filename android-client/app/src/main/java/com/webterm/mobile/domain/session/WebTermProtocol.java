package com.webterm.mobile.domain.session;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

import okio.ByteString;

public final class WebTermProtocol {
    public static final byte MSG_INPUT = 0x01;
    public static final byte MSG_OUTPUT = 0x02;
    public static final byte MSG_RESIZE = 0x03;
    public static final byte MSG_HELLO = 0x04;
    public static final byte MSG_INFO = 0x05;
    public static final byte MSG_EXIT = 0x06;
    public static final byte MSG_PING = 0x07;
    public static final byte MSG_PONG = 0x08;
    public static final byte MSG_TITLE = 0x09;
    public static final byte MSG_STATE = 0x0a;

    private WebTermProtocol() {}

    public static ByteString frame(byte type, byte[] payload) {
        byte[] frame = new byte[1 + (payload == null ? 0 : payload.length)];
        frame[0] = type;
        if (payload != null) System.arraycopy(payload, 0, frame, 1, payload.length);
        return ByteString.of(frame);
    }

    public static JSONObject controlPayload(byte[] payload) throws JSONException {
        if (payload == null || payload.length == 0) return new JSONObject();
        String text = new String(payload, StandardCharsets.UTF_8).trim();
        if (text.isEmpty() || "null".equals(text)) return new JSONObject();
        return new JSONObject(text);
    }

    public static long readUint64(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[offset + i] & 0xffL);
        }
        return value;
    }

    public static JSONObject json() {
        return new JSONObject();
    }

    public static JSONObject put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException ignored) {
        }
        return object;
    }

    // ── Tunnel frame（与 go-core protocol/tunnel.go 一致）──
    // [MsgType=0x01 | idLen | id | extraByte | payload]
    static final byte MSG_TYPE_WS_DATA = 0x01;
    static final byte WS_DATA_TEXT = 0x01;
    static final byte WS_DATA_BINARY = 0x02;

    static final class TunnelFrame {
        final String tunnelId;
        final byte extraByte;
        final byte[] payload;

        TunnelFrame(String tunnelId, byte extraByte, byte[] payload) {
            this.tunnelId = tunnelId;
            this.extraByte = extraByte;
            this.payload = payload;
        }
    }

    static byte[] encodeTunnelFrame(String tunnelId, byte[] payload, boolean binary) {
        byte[] idBytes = tunnelId.getBytes(StandardCharsets.UTF_8);
        byte extraByte = binary ? WS_DATA_BINARY : WS_DATA_TEXT;
        byte[] frame = new byte[3 + idBytes.length + (payload == null ? 0 : payload.length)];
        frame[0] = MSG_TYPE_WS_DATA;
        frame[1] = (byte) idBytes.length;
        System.arraycopy(idBytes, 0, frame, 2, idBytes.length);
        frame[2 + idBytes.length] = extraByte;
        if (payload != null) {
            System.arraycopy(payload, 0, frame, 3 + idBytes.length, payload.length);
        }
        return frame;
    }

    static TunnelFrame decodeTunnelFrame(byte[] data) {
        if (data == null || data.length < 3) return null;
        if ((data[0] & 0xff) != MSG_TYPE_WS_DATA) return null;
        int idLen = data[1] & 0xff;
        if (data.length < 2 + idLen + 1) return null;
        String tunnelId = new String(data, 2, idLen, StandardCharsets.UTF_8);
        byte extraByte = data[2 + idLen];
        int payloadStart = 3 + idLen;
        byte[] payload = new byte[data.length - payloadStart];
        System.arraycopy(data, payloadStart, payload, 0, payload.length);
        return new TunnelFrame(tunnelId, extraByte, payload);
    }
}
