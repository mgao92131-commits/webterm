package com.webterm.core.session;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** webterm.mux.v1 JSON control 的集中 typed codec。 */
final class MuxControlCodec {
    static final class Message {
        final String type;
        final String channelId;
        final int code;
        final String message;
        final String reason;
        final JSONObject raw;

        Message(String type, String channelId, int code, String message, String reason, JSONObject raw) {
            this.type = type;
            this.channelId = channelId;
            this.code = code;
            this.message = message;
            this.reason = reason;
            this.raw = raw;
        }
    }

    Message decode(String text) {
        try {
            JSONObject raw = new JSONObject(text);
            return new Message(raw.optString("type"), raw.optString("tunnelConnectionId"),
                raw.optInt("code", 0), raw.optString("message", ""),
                raw.optString("reason", ""), raw);
        } catch (JSONException ignored) {
            return null;
        }
    }

    String connect(String channelId, String path, String routeKey, String ownerKey,
                   String[] protocols) {
        try {
            JSONObject message = base("ws-connect", channelId).put("path", path);
            if (routeKey != null && !routeKey.isEmpty()) {
                message.put("channelRouteKey", routeKey);
                message.put("channelOwnerKey", ownerKey);
            }
            if (protocols != null && protocols.length > 0) {
                JSONArray values = new JSONArray();
                for (String protocol : protocols) values.put(protocol);
                message.put("protocols", values);
            }
            return message.toString();
        } catch (JSONException ignored) {
            return null;
        }
    }

    String close(String channelId) {
        try {
            return base("ws-close", channelId).toString();
        } catch (JSONException ignored) {
            return null;
        }
    }

    private static JSONObject base(String type, String channelId) throws JSONException {
        return new JSONObject().put("type", type).put("tunnelConnectionId", channelId);
    }
}
