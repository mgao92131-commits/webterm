package com.webterm.mobile;

final class TerminalCacheScope {
    private TerminalCacheScope() {}

    static String key(ServerConfig server) {
        if (server == null) return "";
        String base = WebTermUrls.normalizeBaseUrl(server.getUrl());
        if (isRelayDevice(server)) {
            return base + "#" + server.getDeviceId();
        }
        return base;
    }

    static boolean matches(ServerConfig server, String baseUrl, String sessionId) {
        if (server == null) return false;
        if (!WebTermUrls.normalizeBaseUrl(server.getUrl()).equals(WebTermUrls.normalizeBaseUrl(baseUrl))) return false;
        if (!isRelayDevice(server)) return true;
        return String.valueOf(sessionId == null ? "" : sessionId).startsWith(relaySessionPrefix(server));
    }

    static boolean isRelayDevice(ServerConfig server) {
        return server != null
            && server.isRelayDevice()
            && server.getDeviceId() != null
            && !server.getDeviceId().isEmpty();
    }

    static String relaySessionPrefix(ServerConfig server) {
        return isRelayDevice(server) ? server.getDeviceId() + ":" : "";
    }
}
