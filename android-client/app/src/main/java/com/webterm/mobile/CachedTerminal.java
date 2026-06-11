package com.webterm.mobile;

import com.termux.terminal.TerminalSession;

final class CachedTerminal {
    final String baseUrl;
    final String sessionId;
    TerminalSession terminalSession;
    String cookie;
    String instanceId;
    String termTitle;
    String sessionName;
    String createdAt;
    long lastSeq;
    long persistedSeq;
    int columns;
    int rows;
    final java.util.List<TerminalDiskCache.Frame> pendingDiskFrames = new java.util.ArrayList<>();

    CachedTerminal(String baseUrl, String cookie, String sessionId, String instanceId, String termTitle, String sessionName, String createdAt, TerminalSession terminalSession) {
        this.baseUrl = baseUrl;
        this.cookie = cookie;
        this.sessionId = sessionId;
        this.instanceId = instanceId;
        this.termTitle = termTitle;
        this.sessionName = sessionName;
        this.createdAt = createdAt;
        this.terminalSession = terminalSession;
    }
}
