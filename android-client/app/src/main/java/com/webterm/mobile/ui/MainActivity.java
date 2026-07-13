package com.webterm.mobile.ui;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import com.webterm.mobile.R;
import com.webterm.mobile.device.AndroidNotificationRenderer;
import com.webterm.core.config.ServerConfig;
import com.webterm.ui.common.DesignTokens;
import com.webterm.feature.home.HomeHost;
import com.webterm.feature.home.SessionRowActions;
import com.webterm.feature.relay.RelayHost;
import com.webterm.feature.relay.RelayUiState;
import com.webterm.feature.settings.SettingsHost;
import com.webterm.feature.terminal.TerminalFragment;
import com.webterm.feature.terminal.TerminalHost;
import com.webterm.feature.terminal.TerminalViewModel;

import androidx.fragment.app.FragmentActivity;
import androidx.annotation.Nullable;

import dagger.hilt.android.AndroidEntryPoint;

import javax.inject.Inject;

@AndroidEntryPoint
public final class MainActivity extends FragmentActivity implements HomeHost, TerminalHost, RelayHost, SettingsHost, SessionRowActions, NotificationOpenHost {

    @Inject AppFlowCoordinator coordinator;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().getDecorView().setBackgroundColor(DesignTokens.BG_PRIMARY);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) getWindow().setDecorFitsSystemWindows(false);
        coordinator.attachActivity(this);
        coordinator.onCreate();
        handleNotificationIntent(getIntent());
        checkAndRequestNotificationPermission();
    }

    private void checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    102
                );
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNotificationIntent(intent);
    }

    private void handleNotificationIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(AndroidNotificationRenderer.EXTRA_OPEN_TERMINAL, false)) return;
        String connectionKey = intent.getStringExtra(AndroidNotificationRenderer.EXTRA_CONNECTION_KEY);
        String sessionId = intent.getStringExtra(AndroidNotificationRenderer.EXTRA_SESSION_ID);
        // 只提交请求，不在此 removeExtra：App 在后台时无法立即导航，
        // extra 需保留到 coordinator 到达终态后回调 clearNotificationOpenRequest 清理。
        coordinator.requestOpenTerminalFromNotification(connectionKey, sessionId);
    }

    // ── NotificationOpenHost ──────────────────────────────────────

    @Override
    public void clearNotificationOpenRequest(String connectionKey, String sessionId) {
        Intent intent = getIntent();
        if (intent == null || !intent.getBooleanExtra(AndroidNotificationRenderer.EXTRA_OPEN_TERMINAL, false)) return;
        // 仅当当前 Intent 仍是同一条通知时才清理，避免旧任务清掉新通知的 extra。
        if (!android.text.TextUtils.equals(connectionKey, intent.getStringExtra(AndroidNotificationRenderer.EXTRA_CONNECTION_KEY))) return;
        if (!android.text.TextUtils.equals(sessionId, intent.getStringExtra(AndroidNotificationRenderer.EXTRA_SESSION_ID))) return;
        intent.removeExtra(AndroidNotificationRenderer.EXTRA_OPEN_TERMINAL);
        intent.removeExtra(AndroidNotificationRenderer.EXTRA_CONNECTION_KEY);
        intent.removeExtra(AndroidNotificationRenderer.EXTRA_SESSION_ID);
    }

    @Override protected void onResume() { super.onResume(); coordinator.onResume(); }
    @Override protected void onPause() { coordinator.onPause(); super.onPause(); }
    @Override protected void onDestroy() { coordinator.onDestroy(); super.onDestroy(); }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == AppFlowCoordinator.REQUEST_CODE_DOWNLOAD_DIR) {
            coordinator.onDownloadDirPickerResult(resultCode, data);
        }
    }

    @Override
    public void onBackPressed() {
        if (coordinator.onBackPressed()) return;
        super.onBackPressed();
    }

    // ── HomeHost ─────────────────────────────────────────────────

    @Override public void showAddServerDialog(ServerConfig existingServer) { coordinator.showAddServerDialog(this, existingServer); }
    @Override public void showSettingsDialog() { coordinator.showSettingsDialog(); }
    @Override public void showTerminal(ServerConfig server, String sessionId, String termTitle, String sessionName, String createdAt, String instanceId, String cwd) { coordinator.showTerminal(this, server, sessionId, termTitle, sessionName, createdAt, instanceId, cwd); }
    @Override public void onServerAuthenticated(ServerConfig existingServer, String name, String url, String cookie, String username, String password) { coordinator.onServerAuthenticated(existingServer, name, url, cookie, username, password); }
    @Override public void navigateToDeviceSessions(ServerConfig server) { coordinator.navigateToDeviceSessions(server); }
    @Override public void navigateHome() { coordinator.navigateToHome(); }
    @Override public void createSession(ServerConfig server) { coordinator.createSession(server); }
    @Override public void closeSession(ServerConfig server, String sessionId, Runnable onClosed) { coordinator.closeSession(server, sessionId, onClosed); }
    @Override public void removeServer(ServerConfig server) { coordinator.removeServer(server); }
    @Override public void saveServers() { coordinator.saveServers(); }
    @Override public void removeCachedTerminal(String baseUrl, String sessionId) { coordinator.removeCachedTerminal(baseUrl, sessionId); }
    @Override public void onSessionCwdChanged(ServerConfig server, String sessionId, String cwd) { coordinator.onSessionCwdChanged(server, sessionId, cwd); }
    @Override public void removeMissingCachedSessionsForServer(ServerConfig server, java.util.Set<String> liveSessionIdentities) { coordinator.removeMissingCachedSessionsForServer(server, liveSessionIdentities); }
    @Override public void navigateToRelay() { coordinator.navigateToRelay(); }
    @Override public void shareCrashLog() { coordinator.shareLatestCrashLog(this); }

    // ── TerminalHost ─────────────────────────────────────────────

    @Override public void startTerminalInFragment(TerminalViewModel.TerminalSessionArgs args, TerminalFragment fragment) { coordinator.startTerminalInFragment(this, args, fragment); }
    @Override public void detachTerminalFragment(TerminalFragment fragment) { coordinator.detachTerminalFragment(fragment); }
    @Override public com.webterm.core.fileupload.FileUploadController uploadController() { return com.webterm.mobile.device.WebTermDeviceService.uploadController(); }

    // ── RelayHost ─────────────────────────────────────────────────

    @Override public View buildRelayView(RelayUiState relayUiState) { return coordinator.buildRelayView(this); }
    @Override public void navigateRelayToHome() { coordinator.navigateRelayToHome(); }

    // ── SettingsHost ─────────────────────────────────────────────

    // showSettingsDialog() already covered by HomeHost

    // ── SessionRowActions ────────────────────────────────────────

    @Override public void openSession(ServerConfig server, String sessionId, String termTitle, String sessionName, String createdAt, String instanceId, String cwd) { coordinator.openSession(this, server, sessionId, termTitle, sessionName, createdAt, instanceId, cwd); }
    @Override public void renameSession(ServerConfig server, String sessionId, String oldName) { coordinator.renameSession(server, sessionId, oldName); }
    @Override public void closeSession(ServerConfig server, String sessionId) { coordinator.closeSession(server, sessionId); }
}
