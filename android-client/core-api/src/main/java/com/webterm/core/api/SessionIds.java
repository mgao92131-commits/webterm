package com.webterm.core.api;

/** Value-only conversion between relay-facing and device-local session ids. */
public final class SessionIds {
    private SessionIds() {}

    public static String local(String sessionId, String deviceId) {
        if (sessionId == null) return "";
        String prefix = deviceId == null || deviceId.isEmpty() ? "" : deviceId + ":";
        return !prefix.isEmpty() && sessionId.startsWith(prefix)
            ? sessionId.substring(prefix.length())
            : sessionId;
    }

    public static String canonical(String sessionId, String deviceId) {
        if (sessionId == null) return "";
        if (deviceId == null || deviceId.isEmpty() || sessionId.contains(":")) return sessionId;
        return deviceId + ":" + sessionId;
    }
}
