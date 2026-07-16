package com.webterm.core.api;

/**
 * Stable identity helpers shared by cache, UI and session modules.
 *
 * <p>This is deliberately a value-only type: cache and UI code must not
 * depend on the mux/session implementation just to identify a terminal.</p>
 */
public final class SessionIdentity {
    private SessionIdentity() {}

    public static String cacheKey(String baseUrl, String sessionId, String instanceId, String createdAt) {
        String identity = value(sessionId, instanceId, createdAt);
        if (identity.isEmpty()) return "";
        return WebTermUrls.normalizeBaseUrl(baseUrl) + "#" + identity;
    }

    public static String value(String sessionId, String instanceId, String createdAt) {
        String normalizedInstanceId = normalizePart(instanceId);
        if (!normalizedInstanceId.isEmpty()) return "instance:" + normalizedInstanceId;
        String normalizedCreatedAt = normalizePart(createdAt);
        if (!normalizedCreatedAt.isEmpty()) {
            return "created:" + String.valueOf(sessionId == null ? "" : sessionId) + "@" + normalizedCreatedAt;
        }
        return "";
    }

    public static String normalizePart(String value) {
        return String.valueOf(value == null ? "" : value).trim();
    }
}
