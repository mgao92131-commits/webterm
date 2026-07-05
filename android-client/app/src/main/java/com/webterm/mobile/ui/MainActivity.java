package com.webterm.mobile.ui;

import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import com.webterm.mobile.R;
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
public final class MainActivity extends FragmentActivity implements HomeHost, TerminalHost, RelayHost, SettingsHost, SessionRowActions {

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
    }

    @Override protected void onResume() { super.onResume(); coordinator.onResume(); }
    @Override protected void onPause() { coordinator.onPause(); super.onPause(); }
    @Override protected void onDestroy() { coordinator.onDestroy(); super.onDestroy(); }

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
    @Override public void navigateToRelay() { coordinator.navigateToRelay(); }
    @Override public void shareCrashLog() { coordinator.shareLatestCrashLog(this); }

    // ── TerminalHost ─────────────────────────────────────────────

    @Override public void startTerminalInFragment(TerminalViewModel.TerminalSessionArgs args, TerminalFragment fragment) { coordinator.startTerminalInFragment(this, args, fragment); }

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