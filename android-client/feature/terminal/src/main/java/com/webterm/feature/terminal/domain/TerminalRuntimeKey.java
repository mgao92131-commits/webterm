package com.webterm.feature.terminal.domain;

import com.webterm.feature.terminal.TerminalViewModel;

public final class TerminalRuntimeKey {
    private TerminalRuntimeKey() {}

    public static String fromArgs(TerminalViewModel.TerminalSessionArgs args) {
        if (args == null) return "";
        return value(args.baseUrl, args.sessionId, args.instanceId, args.createdAt, args.relayDeviceId);
    }

    public static String value(String baseUrl, String sessionId, String instanceId,
                               String createdAt, String relayDeviceId) {
        return safe(baseUrl) + "\n"
            + safe(sessionId) + "\n"
            + safe(relayDeviceId);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
