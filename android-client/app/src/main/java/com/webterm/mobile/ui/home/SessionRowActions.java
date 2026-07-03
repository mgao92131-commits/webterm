package com.webterm.mobile.ui.home;

import com.webterm.mobile.data.config.ServerConfig;
public interface SessionRowActions {
    void openSession(ServerConfig server, String sessionId, String termTitle, String sessionName, String createdAt, String instanceId, String cwd);
    void renameSession(ServerConfig server, String sessionId, String oldName);
    void closeSession(ServerConfig server, String sessionId);
}
