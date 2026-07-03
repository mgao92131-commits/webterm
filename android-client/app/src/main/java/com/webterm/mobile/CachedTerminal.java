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
    String cwd;
    String createdAt;
    long lastSeq;
    int columns;
    int rows;

    CachedTerminal(String baseUrl, String cookie, String sessionId, String instanceId, String termTitle, String sessionName, String cwd, String createdAt, TerminalSession terminalSession) {
        this.baseUrl = baseUrl;
        this.cookie = cookie;
        this.sessionId = sessionId;
        this.instanceId = instanceId;
        this.termTitle = termTitle;
        this.sessionName = sessionName;
        this.cwd = cwd;
        this.createdAt = createdAt;
        this.terminalSession = terminalSession;
    }
}
