package com.webterm.core.config;

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
        if (raw.startsWith("http://")) {
            rest = raw.substring("http://".length());
        } else if (raw.startsWith("https://")) {
            scheme = "https";
            rest = raw.substring("https://".length());
        } else if (raw.contains("://")) {
            return Result.error("地址格式错误");
        }

        // 分离路径：只允许空路径或单个 "/"。
        String authority = rest;
        String path = "";
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            authority = rest.substring(0, slash);
            path = rest.substring(slash);
        }
        if (!path.isEmpty() && !path.equals("/")) {
            return Result.error("地址不能包含路径");
        }
        if (authority.isEmpty()) {
            return Result.error("主机名无效");
        }
        if (authority.contains("@")) {
            return Result.error("地址格式错误");
        }

        // 解析 host 与 port，支持 [ipv6]:port。
        String hostPart;
        String hostForUrl;
        String portStr = null;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                return Result.error("主机名无效");
            }
            hostPart = authority.substring(1, close);
            hostForUrl = "[" + hostPart + "]";
            String after = authority.substring(close + 1);
            if (after.startsWith(":")) {
                portStr = after.substring(1);
            } else if (!after.isEmpty()) {
                return Result.error("地址格式错误");
            }
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon >= 0) {
                hostPart = authority.substring(0, colon);
                portStr = authority.substring(colon + 1);
            } else {
                hostPart = authority;
            }
            hostForUrl = hostPart;
        }
        if (hostPart.isEmpty()) {
            return Result.error("主机名无效");
        }

        int port;
        if (portStr == null) {
            // 未写端口：http 补 Direct 默认端口，https 补标准 443。
            port = scheme.equals("http") ? DEFAULT_DIRECT_PORT : DEFAULT_HTTPS_PORT;
        } else if (portStr.isEmpty()) {
            // 写了冒号但端口为空（如 host:）视为非法。
            return Result.error("端口无效");
        } else {
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                return Result.error("端口无效");
            }
            if (port <= 0 || port > 65535) {
                return Result.error("端口无效");
            }
        }

        return Result.ok(scheme + "://" + hostForUrl + ":" + port, hostPart, port);
    }
}
