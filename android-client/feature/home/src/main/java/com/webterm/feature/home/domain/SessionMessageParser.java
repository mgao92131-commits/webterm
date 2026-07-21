package com.webterm.feature.home.domain;

import android.util.Log;

import com.webterm.core.api.SessionIds;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Parses server session-manager WebSocket messages and dispatches typed events.
 *
 * <p>Connection lifecycle (open/close/reconnect) is owned by the data source; this
 * class is a pure, stateless message parser.
 */
public final class SessionMessageParser {
    private static final String TAG = "SessionMessageParser";

    private SessionMessageParser() {}

    public static void dispatchMessage(@NonNull String text, @NonNull Listener listener) {
        dispatchMessage(text, listener, null);
    }

    public static void dispatchMessage(@NonNull String text, @NonNull Listener listener,
                                       @Nullable String relayDeviceId) {
        try {
            JSONObject msg = new JSONObject(text);
            prefixRelaySessionIds(msg, relayDeviceId);
            String type = msg.optString("type");
            if ("sessions".equals(type)) {
                JSONArray arr = msg.optJSONArray("data");
                listener.onMonitorSessions(arr != null ? arr : new JSONArray());
            } else if ("session".equals(type)) {
                JSONObject sessionData = msg.optJSONObject("data");
                if (sessionData != null) {
                    listener.onMonitorSession(sessionData);
                }
            } else if ("session-closed".equals(type)) {
                String id = msg.optString("id");
                if (!id.isEmpty()) {
                    listener.onMonitorSessionClosed(id);
                }
            } else if ("error".equals(type)) {
                listener.onMonitorError(msg.optString("message"));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse manager WS message", e);
        }
    }

    private static void prefixRelaySessionIds(JSONObject msg, @Nullable String relayDeviceId)
            throws JSONException {
        if (relayDeviceId == null || relayDeviceId.isEmpty()) return;
        String type = msg.optString("type");
        if ("sessions".equals(type)) {
            JSONArray sessions = msg.optJSONArray("data");
            if (sessions == null) return;
            for (int i = 0; i < sessions.length(); i++) {
                prefixRelaySessionId(sessions.optJSONObject(i), relayDeviceId);
            }
        } else if ("session".equals(type)) {
            prefixRelaySessionId(msg.optJSONObject("data"), relayDeviceId);
        } else if ("session-closed".equals(type)) {
            String id = prefixedSessionId(msg.optString("id"), relayDeviceId);
            if (!id.isEmpty()) msg.put("id", id);
        }
    }

    private static void prefixRelaySessionId(@Nullable JSONObject session, String relayDeviceId)
            throws JSONException {
        if (session == null) return;
        String id = prefixedSessionId(session.optString("id"), relayDeviceId);
        if (!id.isEmpty()) session.put("id", id);
    }

    /** Prefixes a device-local id with the relay device id, delegating to {@link SessionIds}. */
    private static String prefixedSessionId(String id, String relayDeviceId) {
        if (id == null || id.isEmpty()) return id == null ? "" : id;
        return SessionIds.canonical(id, relayDeviceId);
    }

    public interface Listener {
        void onMonitorSessions(JSONArray sessions);
        void onMonitorSession(JSONObject session);
        void onMonitorSessionClosed(String sessionId);
        void onMonitorError(String errorMsg);
    }
}
