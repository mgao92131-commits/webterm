package com.webterm.feature.home;

import com.webterm.core.config.ServerConfig;
import com.webterm.core.relay.RelayService;
import com.webterm.ui.common.StatusIndicatorView;

import android.widget.TextView;

import java.util.Set;

/**
 * Simplified host interface for HomeFragment to communicate with its Activity
 * for operations that require Activity context (dialogs, terminal navigation).
 */
public interface HomeHost extends RelayNavigator {
    interface RelayStatusBinding { void close(); }

	RelayStatusBinding bindRelayStatus(RelayService service, TextView subtitle,
	                                  StatusIndicatorView status);
	void showSettingsDialog();

    /** Open the “添加直连设备” dialog. */
    void showAddDirectDeviceDialog();

    /** Edit a persisted Direct device (re-validates credentials before saving). */
    void editDirectDevice(ServerConfig server);

    /** Force-refresh the cookie and rebuild the connection for a Direct device. */
    void reconnectDirectDevice(ServerConfig server);

    /** Delete a persisted Direct device (Android-side only; does not touch Agent sessions). */
    void deleteDirectDevice(ServerConfig server);
    void showTerminal(ServerConfig server, String sessionId, String termTitle,
                      String createdAt, String instanceId, String cwd);

    /** Navigate to the device sessions destination. */
    void navigateToDeviceSessions(ServerConfig server);

    /** Navigate back to the top-level home destination. */
    void navigateHome();

    /** Create a new terminal session on a server. */
    void createSession(ServerConfig server);

    /** Close a terminal session and run a UI callback after the server confirms it. */
    void closeSession(ServerConfig server, String sessionId, Runnable onClosed);


    /** Persist server configuration changes such as refreshed cookies. */
    void saveServers();

    /** Remove cached terminal state for a closed session. */
    void removeCachedTerminal(String baseUrl, String sessionId);

    /** Sync the currently opened terminal cwd when a manager update reports it. */
    void onSessionCwdChanged(ServerConfig server, String sessionId, String cwd);

    /** Prune stale cached sessions after a live session refresh. */
    void removeMissingCachedSessionsForServer(ServerConfig server, Set<String> liveSessionIdentities);

    /** Export/share the latest crash log. */
    void shareCrashLog();

    /** Whether this build exposes the local diagnostic-log export action. */
    boolean canShareDiagnosticLogs();

    /** Export/share the bounded local diagnostic logs. */
    void shareDiagnosticLogs();
}
