package com.webterm.mobile;

interface SessionRowActions {
    void openSession(ServerConfig server, String sessionId, String termTitle, String sessionName, String createdAt, String instanceId);
    void renameSession(ServerConfig server, String sessionId, String oldName);
    void closeSession(ServerConfig server, String sessionId);
}
