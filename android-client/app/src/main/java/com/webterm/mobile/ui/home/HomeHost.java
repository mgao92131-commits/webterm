package com.webterm.mobile.ui.home;

import com.webterm.core.config.ServerConfig;

/**
 * Simplified host interface for HomeFragment to communicate with its Activity
 * for operations that require Activity context (dialogs, terminal navigation).
 */
public interface HomeHost {
    void showAddServerDialog(ServerConfig existingServer);
    void showSettingsDialog();
    void showTerminal(ServerConfig server, String sessionId, String termTitle, String sessionName,
                      String createdAt, String instanceId, String cwd);
    void onServerAuthenticated(ServerConfig existingServer, String name, String url,
                               String cookie, String username, String password);
}
