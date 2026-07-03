package com.webterm.mobile.domain.session;

import com.webterm.mobile.data.api.WebTermUrls;
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
