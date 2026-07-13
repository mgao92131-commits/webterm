package com.webterm.core.notifications;

import javax.inject.Inject;
import javax.inject.Singleton;

/** Process-wide record of the terminal currently visible to the user. */
@Singleton
public final class TerminalFocusStore {
    private String connectionKey = "";
    private String sessionId = "";

    @Inject public TerminalFocusStore() {}

    public synchronized void setVisible(String connectionKey, String sessionId) {
        this.connectionKey = connectionKey == null ? "" : connectionKey;
        this.sessionId = sessionId == null ? "" : sessionId;
    }

    public synchronized void clear() { connectionKey = ""; sessionId = ""; }

    public synchronized boolean isVisible(String connectionKey, String sessionId) {
        return !this.connectionKey.isEmpty() && this.connectionKey.equals(connectionKey)
            && this.sessionId.equals(sessionId == null ? "" : sessionId);
    }
}
