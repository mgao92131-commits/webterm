package com.webterm.mobile.data.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class WebTermUrls {
    private WebTermUrls() {}

    static String normalizeBaseUrl(String raw) {
        String value = String.valueOf(raw == null ? "" : raw).trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) return "";
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "http://" + value;
        return value;
    }

    static String toWebSocketUrl(String baseUrl) {
        if (baseUrl.startsWith("https://")) return "wss://" + baseUrl.substring("https://".length());
        if (baseUrl.startsWith("http://")) return "ws://" + baseUrl.substring("http://".length());
        return "ws://" + baseUrl;
    }

    static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
