package com.webterm.core.api;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class WebTermUrls {
    private WebTermUrls() {}

    public static String normalizeBaseUrl(String raw) {
        String value = String.valueOf(raw == null ? "" : raw).trim();
        while (value.endsWith("/") && !value.endsWith("://")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty()) return "";
        if (!hasHttpScheme(value)) value = "http://" + value;
        return value;
    }

    /** 用户输入的中转服务器地址校验结果。 */
    public static final class BaseUrlCheck {
        public final boolean valid;
        /** 校验通过时返回规范化后的地址（去空格、去尾部斜杠、scheme 小写）；否则为空串。 */
        public final String normalized;
        /** 校验失败时的中文提示；校验通过时为空串。 */
        public final String error;

        private BaseUrlCheck(boolean valid, String normalized, String error) {
            this.valid = valid;
            this.normalized = normalized;
            this.error = error;
        }
    }

    /**
     * 规范化并校验用户输入的中转服务器地址。
     *
     * <p>规则：去首尾空格、去尾部斜杠；缺省补 http；仅允许 http/https；
     * 必须包含有效主机；不允许 query、fragment 与账号信息。
     */
    public static BaseUrlCheck validateBaseUrl(String raw) {
        String value = String.valueOf(raw == null ? "" : raw).trim();
        if (value.isEmpty()) {
            return new BaseUrlCheck(false, "", "请输入中转服务器地址");
        }
        while (value.endsWith("/") && !value.endsWith("://")) {
            value = value.substring(0, value.length() - 1);
        }
        if (!hasHttpScheme(value)) {
            if (hasOtherScheme(value)) {
                return new BaseUrlCheck(false, "", "仅支持 http 或 https 地址");
            }
            value = "http://" + value;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return new BaseUrlCheck(false, "", "服务器地址格式不正确");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return new BaseUrlCheck(false, "", "服务器地址缺少有效主机");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            return new BaseUrlCheck(false, "", "服务器地址不能包含查询参数或片段");
        }
        if (uri.getUserInfo() != null) {
            return new BaseUrlCheck(false, "", "服务器地址不能包含账号信息");
        }
        StringBuilder sb = new StringBuilder(uri.getScheme().toLowerCase(Locale.ROOT));
        sb.append("://").append(host.toLowerCase(Locale.ROOT));
        if (uri.getPort() != -1) {
            sb.append(':').append(uri.getPort());
        }
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if (!path.isEmpty()) {
                sb.append(path);
            }
        }
        return new BaseUrlCheck(true, sb.toString(), "");
    }

    /** 判断两个地址规范化后是否指向同一服务器。 */
    public static boolean sameBaseUrl(String a, String b) {
        BaseUrlCheck checkA = validateBaseUrl(a);
        BaseUrlCheck checkB = validateBaseUrl(b);
        return checkA.valid && checkB.valid && checkA.normalized.equals(checkB.normalized);
    }

    private static boolean hasHttpScheme(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** 形如 "ftp://host"、"ws://host" 的显式非 http(s) scheme（必须带 "://"，避免把 "host:9001" 的端口误判为 scheme）。 */
    private static boolean hasOtherScheme(String value) {
        return value.indexOf("://") > 0 && !hasHttpScheme(value);
    }

    public static String toWebSocketUrl(String baseUrl) {
        if (baseUrl.startsWith("https://")) return "wss://" + baseUrl.substring("https://".length());
        if (baseUrl.startsWith("http://")) return "ws://" + baseUrl.substring("http://".length());
        return "ws://" + baseUrl;
    }

    public static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
