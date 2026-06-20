package com.webterm.mobile;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

import okio.ByteString;

final class WebTermProtocol {
    static final byte MSG_INPUT = 0x01;
    static final byte MSG_OUTPUT = 0x02;
    static final byte MSG_RESIZE = 0x03;
    static final byte MSG_HELLO = 0x04;
    static final byte MSG_INFO = 0x05;
    static final byte MSG_EXIT = 0x06;
    static final byte MSG_PING = 0x07;
    static final byte MSG_PONG = 0x08;
    static final byte MSG_TITLE = 0x09;
    static final byte MSG_STATE = 0x0a;

    private WebTermProtocol() {}

    static ByteString frame(byte type, byte[] payload) {
        byte[] frame = new byte[1 + (payload == null ? 0 : payload.length)];
        frame[0] = type;
        if (payload != null) System.arraycopy(payload, 0, frame, 1, payload.length);
        return ByteString.of(frame);
    }

    static JSONObject controlPayload(byte[] payload) throws JSONException {
        if (payload == null || payload.length == 0) return new JSONObject();
        String text = new String(payload, StandardCharsets.UTF_8).trim();
        if (text.isEmpty() || "null".equals(text)) return new JSONObject();
        return new JSONObject(text);
    }

    static long readUint64(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[offset + i] & 0xffL);
        }
        return value;
    }

    static JSONObject json() {
        return new JSONObject();
    }

    static JSONObject put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException ignored) {
        }
        return object;
    }
}
