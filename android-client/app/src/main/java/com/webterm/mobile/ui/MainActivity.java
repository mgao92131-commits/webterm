package com.webterm.mobile.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.webterm.mobile.R;
import com.webterm.mobile.device.AndroidNotificationRenderer;
import com.webterm.mobile.device.WebTermDeviceService;
import com.webterm.core.config.ServerConfig;
import com.webterm.ui.common.DesignTokens;
import com.webterm.feature.home.HomeHost;
import com.webterm.feature.home.SessionRowActions;
import com.webterm.core.relay.RelayService;
import com.webterm.ui.common.StatusIndicatorView;
import com.webterm.feature.relay.RelayHost;
import com.webterm.feature.relay.RelayUiState;
import com.webterm.feature.terminal.TerminalFragment;
import com.webterm.feature.terminal.TerminalHost;
import com.webterm.feature.terminal.TerminalViewModel;

import androidx.fragment.app.FragmentActivity;
import androidx.annotation.Nullable;

import dagger.hilt.android.AndroidEntryPoint;

import javax.inject.Inject;

@AndroidEntryPoint
public final class MainActivity extends FragmentActivity implements HomeHost, TerminalHost, RelayHost, SessionRowActions, NotificationOpenHost {

    private static final int REQUEST_POST_NOTIFICATIONS = 1001;

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
        requestNotificationPermissionIfNeeded();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_POST_NOTIFICATIONS) return;
        if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) return;
        WebTermDeviceService.start(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNotificationIntent(intent);
    }

    private void handleNotificationIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(AndroidNotificationRenderer.EXTRA_OPEN_TERMINAL, false)) return;
        coordinator.requestOpenTerminalFromNotification(
            intent.getStringExtra(AndroidNotificationRenderer.EXTRA_CONNECTION_KEY),
            intent.getStringExtra(AndroidNotificationRenderer.EXTRA_SESSION_ID));
    }

    @Override
    public void clearNotificationOpenRequest(String connectionKey, String sessionId) {
        Intent intent = getIntent();
        if (intent == null || !intent.getBooleanExtra(AndroidNotificationRenderer.EXTRA_OPEN_TERMINAL, false)) return;
        if (!android.text.TextUtils.equals(connectionKey, intent.getStringExtra(AndroidNotificationRenderer.EXTRA_CONNECTION_KEY))) return;
        if (!android.text.TextUtils.equals(sessionId, intent.getStringExtra(AndroidNotificationRenderer.EXTRA_SESSION_ID))) return;
        intent.removeExtra(AndroidNotificationRenderer.EXTRA_OPEN_TERMINAL);
        intent.removeExtra(AndroidNotificationRenderer.EXTRA_CONNECTION_KEY);
        intent.removeExtra(AndroidNotificationRenderer.EXTRA_SESSION_ID);
    }

    @Override protected void onResume() {
        super.onResume();
        coordinator.onResume();
        WebTermDeviceService.markActive();
    }
    @Override protected void onPause() { coordinator.onPause(); super.onPause(); }
    @Override protected void onDestroy() { coordinator.onDestroy(); super.onDestroy(); }
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            coordinator.onMemoryPressure();
        }
    }

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

	@Override public void showSettingsDialog() { coordinator.showSettingsDialog(); }
    @Override public void showTerminal(ServerConfig server, String sessionId, String termTitle, String createdAt, String instanceId, String cwd) { coordinator.showTerminal(this, server, sessionId, termTitle, createdAt, instanceId, cwd); }
    @Override public void navigateToDeviceSessions(ServerConfig server) { coordinator.navigateToDeviceSessions(server); }
    @Override public void navigateHome() { coordinator.navigateToHome(); }
    @Override public void createSession(ServerConfig server) { coordinator.createSession(server); }
    @Override public void closeSession(ServerConfig server, String sessionId, Runnable onClosed) { coordinator.closeSession(server, sessionId, onClosed); }
    @Override public void saveServers() { coordinator.saveServers(); }
    @Override public void removeCachedTerminal(String baseUrl, String sessionId) { coordinator.removeCachedTerminal(baseUrl, sessionId); }
    @Override public void onSessionCwdChanged(ServerConfig server, String sessionId, String cwd) { coordinator.onSessionCwdChanged(server, sessionId, cwd); }
    @Override public void removeMissingCachedSessionsForServer(ServerConfig server, java.util.Set<String> liveSessionIdentities) { coordinator.removeMissingCachedSessionsForServer(server, liveSessionIdentities); }
    @Override public void navigateToRelay() { coordinator.navigateToRelay(); }

    @Override
    public RelayStatusBinding bindRelayStatus(RelayService service, TextView subtitle,
                                               StatusIndicatorView status) {
        RelayUiState state = new RelayUiState(service, null);
        state.attachSubtitle(subtitle);
        state.attachStatusDot(status);
        return state::destroy;
    }
    @Override public void shareCrashLog() { coordinator.shareLatestCrashLog(this); }

    // ── TerminalHost ─────────────────────────────────────────────

    @Override public void startRemoteTerminalInFragment(TerminalViewModel.TerminalSessionArgs args, TerminalFragment fragment) { coordinator.startRemoteTerminalInFragment(this, args, fragment); }
    @Override public void detachTerminalFragment(TerminalFragment fragment) { coordinator.detachTerminalFragment(fragment); }
    @Override public com.webterm.core.fileupload.FileUploadController uploadController() { return com.webterm.mobile.device.WebTermDeviceService.uploadController(); }

    // ── RelayHost ─────────────────────────────────────────────────

    @Override public View buildRelayView(RelayUiState relayUiState) { return coordinator.buildRelayView(this); }

    // ── SessionRowActions ────────────────────────────────────────

    @Override public void openSession(ServerConfig server, String sessionId, String termTitle, String createdAt, String instanceId, String cwd) { coordinator.openSession(this, server, sessionId, termTitle, createdAt, instanceId, cwd); }
    @Override public void closeSession(ServerConfig server, String sessionId) { coordinator.closeSession(server, sessionId); }
}
