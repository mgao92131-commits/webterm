package com.webterm.core.api;

/** Value-only conversion between relay-facing and device-local session ids. */
public final class SessionIds {
    private SessionIds() {}

    /**
     * 返回 Agent Session Manager 使用的设备本地 ID。
     *
     * <p>输入可能来自 Relay 列表（deviceId 前缀）、旧版 relay: 包装或已经是本地 ID。
     * 所有实际访问 Agent 会话的 Screen/HTTP/上传路径必须共用此转换。</p>
     */
    public static String agentLocal(String sessionId, String deviceId) {
        if (sessionId == null) return "";
        String value = sessionId.startsWith("relay:")
            ? sessionId.substring("relay:".length())
            : sessionId;
        String prefix = deviceId == null || deviceId.isEmpty() ? "" : deviceId + ":";
        return !prefix.isEmpty() && value.startsWith(prefix)
            ? value.substring(prefix.length())
            : value;
    }

    public static String local(String sessionId, String deviceId) {
        return agentLocal(sessionId, deviceId);
    }

    public static String canonical(String sessionId, String deviceId) {
        if (sessionId == null) return "";
        if (deviceId == null || deviceId.isEmpty() || sessionId.contains(":")) return sessionId;
        return deviceId + ":" + sessionId;
    }
}
