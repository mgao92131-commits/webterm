package com.webterm.mobile.data.cache;

import com.termux.terminal.TerminalSession;

public final class CachedTerminal {
    public final String baseUrl;
    public final String sessionId;
    public TerminalSession terminalSession;
    public String cookie;
    public String instanceId;
    public String termTitle;
    public String sessionName;
    public String cwd;
    public String createdAt;
    public long lastSeq;
    public int columns;
    public int rows;

    public CachedTerminal(String baseUrl, String cookie, String sessionId, String instanceId, String termTitle, String sessionName, String cwd, String createdAt, TerminalSession terminalSession) {
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
