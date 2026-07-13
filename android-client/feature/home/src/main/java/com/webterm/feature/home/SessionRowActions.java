package com.webterm.feature.home;

import com.webterm.core.config.ServerConfig;
public interface SessionRowActions {
    void openSession(ServerConfig server, String sessionId, String termTitle, String createdAt, String instanceId, String cwd);
    void closeSession(ServerConfig server, String sessionId);
}
