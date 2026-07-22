package com.webterm.core.config;

import java.net.URI;
import java.net.URISyntaxException;

import okhttp3.HttpUrl;

/**
 * 规范化“添加直连设备”弹框中输入的地址。与 Dialog 分离，便于单元测试。
 *
 * <p>支持的输入形式：
 * <ul>
 *   <li>{@code 192.168.1.20}            → {@code http://192.168.1.20:8080}</li>
 *   <li>{@code 192.168.1.20:9000}       → {@code http://192.168.1.20:9000}</li>
 *   <li>{@code http://192.168.1.20:8080}→ 原样（去掉结尾斜杠）</li>
 *   <li>{@code https://pc.example.com}  → {@code https://pc.example.com:443}</li>
 * </ul>
 *
 * <p>规则：无协议补 {@code http://}；http 无端口补 Direct 默认端口 8080，https
 * 无端口补标准 443；移除结尾 {@code /}；拒绝 {@code /api}、{@code /ws/sessions}
 * 等具体路径；拒绝非法端口与空主机名。
 */
public final class DirectDeviceAddressNormalizer {
    /** Direct Agent 的默认监听端口。 */
    public static final int DEFAULT_DIRECT_PORT = 8080;
    private static final int DEFAULT_HTTPS_PORT = 443;

    private DirectDeviceAddressNormalizer() {}

    /** 规范化结果：成功时 {@link #url} 非空，失败时 {@link #error} 非空。 */
    public static final class Result {
        public final boolean ok;
        public final String url;
        public final String host;
        public final int port;
        public final String error;

        private Result(boolean ok, String url, String host, int port, String error) {
            this.ok = ok;
            this.url = url;
            this.host = host;
            this.port = port;
            this.error = error;
        }

        static Result ok(String url, String host, int port) {
            return new Result(true, url, host, port, null);
        }

        static Result error(String message) {
            return new Result(false, null, null, 0, message);
        }
    }

    public static Result normalize(String input) {
        String raw = input == null ? "" : input.trim();
        if (raw.isEmpty()) {
            return Result.error("地址不能为空");
        }

        String scheme = "http";
        String rest = raw;
        boolean explicitScheme = false;
        if (raw.regionMatches(true, 0, "http://", 0, "http://".length())) {
            explicitScheme = true;
            rest = raw.substring("http://".length());
        } else if (raw.regionMatches(true, 0, "https://", 0, "https://".length())) {
            explicitScheme = true;
            scheme = "https";
            rest = raw.substring("https://".length());
        } else if (raw.contains("://")) {
            return Result.error("地址格式错误");
        }
        // 无 // 的 host:port 形式仍然合法；其它类似 scheme:value 的形式拒绝，
        // 避免 ftp:foo、ws:foo 被当作普通主机名。
        int schemeSeparator = raw.indexOf(':');
        if (!explicitScheme && schemeSeparator > 0
            && raw.substring(0, schemeSeparator).matches("[A-Za-z][A-Za-z0-9+.-]*")
            && !raw.substring(schemeSeparator + 1)
                .replaceFirst("/$", "")
                .matches("[0-9]+")) {
            return Result.error("地址格式错误");
        }

        final URI uri;
        try {
            uri = new URI(scheme + "://" + rest);
        } catch (URISyntaxException e) {
            return Result.error("地址格式错误");
        }
        if (!scheme.equalsIgnoreCase(uri.getScheme())) {
            return Result.error("地址格式错误");
        }
        if (uri.getUserInfo() != null) {
            return Result.error("地址不能包含账户信息");
        }
        if (uri.getQuery() != null) {
            return Result.error("地址不能包含查询参数");
        }
        if (uri.getFragment() != null) {
            return Result.error("地址不能包含片段");
        }
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            return Result.error("地址不能包含路径");
        }
        String rawAuthority = uri.getRawAuthority();
        if (rawAuthority == null || rawAuthority.isEmpty()) {
            return Result.error("主机名无效");
        }
        if (rawAuthority.endsWith(":")) {
            return Result.error("端口无效");
        }

        String host = uri.getHost();
        if (host == null || host.trim().isEmpty()) {
            return Result.error("主机名无效");
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }

        int port = uri.getPort();
        if (port == -1) {
            port = scheme.equals("https") ? DEFAULT_HTTPS_PORT : DEFAULT_DIRECT_PORT;
        }
        if (port <= 0 || port > 65535) {
            return Result.error("端口无效");
        }

        try {
            HttpUrl validated = new HttpUrl.Builder()
                .scheme(scheme)
                .host(host)
                .port(port)
                .build();
            // HttpUrl 会隐藏默认端口；URI 输出保留显式端口，同时由标准 URI
            // 构造器负责 IPv6 方括号，不手工拼接 host。
            String normalizedUrl = new URI(
                scheme, null, validated.host(), port, null, null, null).toString();
            return Result.ok(normalizedUrl, host, port);
        } catch (IllegalArgumentException | URISyntaxException e) {
            return Result.error("地址格式错误");
        }
    }
}
