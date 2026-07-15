package com.webterm.mobile.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONObject;

import com.webterm.mobile.CrashReporter;
import com.webterm.mobile.R;
import com.webterm.core.api.WebTermApi;
import com.webterm.core.api.AuthSessionCoordinator;
import com.webterm.core.api.WebTermUrls;
import com.webterm.core.api.DeviceConnectionKeys;
import com.webterm.core.cache.TerminalCacheCoordinator;
import com.webterm.core.config.ServerConfig;
import com.webterm.core.config.ServerConfigManager;
import com.webterm.core.config.ServerConfigStore;
import com.webterm.ui.common.command.SessionCommandController;
import com.webterm.core.relay.RelayService;
import com.webterm.core.api.SessionIds;
import com.webterm.core.session.RelayMuxSessionRegistry;
import com.webterm.core.notifications.TerminalFocusStore;
import com.webterm.feature.terminal.domain.RemoteTerminalIntegration;
import com.webterm.mobile.recovery.NetworkRecoveryController;
import com.webterm.mobile.device.NotificationTerminalResolver;
import com.webterm.ui.common.PageTransitionAnimator;
import com.webterm.ui.common.UIUtils;
import com.webterm.mobile.download.FileDownloadHelper;
import com.webterm.mobile.ui.dialog.ServerConfigDialogHelper;
import com.webterm.mobile.ui.dialog.SettingsDialogHelper;
import com.webterm.feature.home.DeviceSessionsFragment;
import com.webterm.feature.home.HomeFragment;
import com.webterm.feature.home.repository.SessionRepository;
import com.webterm.feature.relay.RelayDevicesScreenBuilder;
import com.webterm.feature.relay.RelayLoginScreenBuilder;
import com.webterm.feature.relay.RelayUiState;
import com.webterm.feature.terminal.TerminalFragment;
import com.webterm.feature.terminal.TerminalViewModel;
import com.webterm.ui.common.WindowInsetsController;

import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;

import okhttp3.OkHttpClient;

import javax.inject.Inject;

import dagger.hilt.android.scopes.ActivityScoped;

@ActivityScoped
public final class AppFlowCoordinator implements
    ServerConfigDialogHelper.Host, SettingsDialogHelper.Host, RelayService.Host {

    private static final String TAG = "AppFlowCoordinator";
    public static final int REQUEST_CODE_DOWNLOAD_DIR = 0x1001;

    private final WebTermApi api;
    private final AuthSessionCoordinator authCoordinator;
    private final RelayMuxSessionRegistry relayMuxRegistry;
    private final TerminalCacheCoordinator terminalCache;
    private final ServerConfigStore configStore;
    private final ServerConfigManager serverConfigs;
    private final Handler mainHandler;
    private final OkHttpClient http;
    private final TerminalFocusStore terminalFocus;
    private final SessionRepository sessionRepository;

    private final RemoteTerminalIntegration remoteTerminalIntegration;
    private final RelayService mRelayService;
    private FileDownloadHelper downloadHelper;

    private boolean mInForeground = true;
    private boolean activityResumed;
    private boolean terminalAuthRecoveryInFlight;
    private int terminalAuthRecoveryGeneration;
    private SessionCommandController mSessionCommands;

    private ScreenMode mScreenMode = ScreenMode.DEVICES;
    private ServerConfig mSelectedServer;

    private int mImeOverlap;
    private RelayUiState mRelayUiState;
    private NetworkRecoveryController mNetworkRecoveryController;
    private TextView mHomeSubtitle;
    private NavController mNavController;
    private HomeFragment mHomeFragment;
    private MainActivity mActivity;
    private PendingTerminalOpen pendingTerminalOpen;
    private String currentTerminalConnectionKey = "";
    private String currentTerminalSessionId = "";

    public enum ScreenMode {
        DEVICES,
        DEVICE_SESSIONS,
        TERMINAL,
        RELAY_LOGIN,
        RELAY_DEVICES
    }

    @Inject
    public AppFlowCoordinator(
        WebTermApi api,
        AuthSessionCoordinator authCoordinator,
        RelayMuxSessionRegistry relayMuxRegistry,
        TerminalCacheCoordinator terminalCache,
        ServerConfigStore configStore,
        ServerConfigManager serverConfigs,
        Handler mainHandler,
        OkHttpClient http,
        TerminalFocusStore terminalFocus,
        SessionRepository sessionRepository,
        RemoteTerminalIntegration remoteTerminalIntegration,
        RelayService relayService
    ) {
        this.api = api;
        this.authCoordinator = authCoordinator;
        this.relayMuxRegistry = relayMuxRegistry;
        this.terminalCache = terminalCache;
        this.configStore = configStore;
        this.serverConfigs = serverConfigs;
        this.mainHandler = mainHandler;
        this.http = http;
        this.terminalFocus = terminalFocus;
        this.sessionRepository = sessionRepository;
        this.remoteTerminalIntegration = remoteTerminalIntegration;
        this.mRelayService = relayService;
    }

    public void attachActivity(MainActivity activity) {
        this.mActivity = activity;
        if (this.downloadHelper == null) {
            this.downloadHelper = new FileDownloadHelper(activity, api, configStore);
        }
        androidx.navigation.fragment.NavHostFragment navHost = (androidx.navigation.fragment.NavHostFragment) activity.getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
        this.mNavController = navHost != null ? navHost.getNavController() : null;
    }

    public void onCreate() {
        mNetworkRecoveryController = new NetworkRecoveryController(mActivity, mainHandler);
        mNetworkRecoveryController.getOnNetworkAvailable().observe(mActivity, v -> {
            if (mRelayService != null) {
                mRelayService.resetAndRefresh();
            }
            if (mScreenMode == ScreenMode.TERMINAL && remoteTerminalIntegration.needsReconnect()) {
                requestTerminalFreshReconnect();
            }
        });
        mRelayService.setHost(this);
        mRelayUiState = new RelayUiState(mRelayService, this);
        mSessionCommands = new SessionCommandController(mActivity, api, authCoordinator, new SessionCommandController.Listener() {
            @Override
            public void onAuthenticated(ServerConfig server) { saveServers(); }
            @Override
            public void onOpenTerminal(String baseUrl, String cookie, String sessionId, String termTitle, boolean isRelayDevice, String relayDeviceId) {
                showTerminal(baseUrl, cookie, sessionId, termTitle, "", "", isRelayDevice, relayDeviceId, "");
            }
            @Override
            public void onRemoveCachedTerminal(String baseUrl, String sessionId) {
                removeCachedTerminal(baseUrl, sessionId);
            }
            @Override public void onSessionClosed(ServerConfig server, String sessionId) { }
            @Override
            public void onShowHome() { showSessionListOrDeviceHome(); }
        });
        loadServersFromPrefs();
    }

    public void onResume() {
        mInForeground = true;
        activityResumed = true;
        remoteTerminalIntegration.setAppVisible(true);
        if (!currentTerminalConnectionKey.isEmpty() && !currentTerminalSessionId.isEmpty()) {
            terminalFocus.setVisible(currentTerminalConnectionKey, currentTerminalSessionId);
        }
        mainHandler.post(this::drainPendingTerminalOpen);

        mHomeFragment = findHomeFragment();

        // 回到前台时，如果在主页列表，自动重新开启/拉取中转设备
        if (!hasTerminalSession() && mScreenMode == ScreenMode.DEVICES) {
            mRelayService.start();
        }

        // 主动同步一次最新的中转设备数据到列表上
        if (mHomeFragment != null) {
            mHomeFragment.refreshDevices();
        }

        if (remoteTerminalIntegration.needsReconnect()) {
            requestTerminalFreshReconnect();
        }
        mNetworkRecoveryController.register();
    }

    public void onPause() {
        if (mNetworkRecoveryController != null) mNetworkRecoveryController.unregister();
        mInForeground = false;
        activityResumed = false;
        remoteTerminalIntegration.setAppVisible(false);
        terminalFocus.clear();
        if (!hasTerminalSession()) {
            mRelayService.stop();
        }
    }

    public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        mRelayService.stop();
        remoteTerminalIntegration.stop();
        terminalCache.shutdown();
        // Relay mux 与 terminal runtime 是进程级所有权。Activity 重建不能关闭它们；
        // 进程退出时由系统统一回收。
    }

    public void onMemoryPressure() {
        remoteTerminalIntegration.onMemoryPressure();
    }

    public boolean onBackPressed() {
        int destinationId = currentDestinationId();
        if (destinationId == R.id.terminalFragment) {
            detachCurrentTerminalView();
            if (mNavController != null) mNavController.popBackStack();
            mScreenMode = mSelectedServer != null ? ScreenMode.DEVICE_SESSIONS : ScreenMode.DEVICES;
            return true;
        }
        if (destinationId == R.id.deviceSessionsFragment) {
            if (mNavController != null) mNavController.popBackStack(R.id.homeFragment, false);
            mScreenMode = ScreenMode.DEVICES;
            mSelectedServer = null;
            return true;
        }
        if (mScreenMode == ScreenMode.RELAY_LOGIN || mScreenMode == ScreenMode.RELAY_DEVICES) {
            showSessionHome();
            return true;
        }
        return false;
    }

    // ── Public accessors ─────────────────────────────────────────────

    public RelayService getRelayService() { return mRelayService; }
    public RelayUiState getRelayUiState() { return mRelayUiState; }
    public ServerConfigManager getServerConfigs() { return serverConfigs; }
    public ServerConfigStore getConfigStore() { return configStore; }
    public WebTermApi getApi() { return api; }
    public Handler getMainHandler() { return mainHandler; }
    public SessionCommandController getSessionCommands() { return mSessionCommands; }
    public RelayMuxSessionRegistry getRelayMuxRegistry() { return relayMuxRegistry; }
    public TerminalCacheCoordinator getTerminalCache() { return terminalCache; }
    public OkHttpClient getHttpClient() { return http; }
    public void setHomeSubtitle(TextView subtitle) { mHomeSubtitle = subtitle; }
    public ServerConfig getSelectedServer() { return mSelectedServer; }
    public void setSelectedServer(ServerConfig server) { mSelectedServer = server; }
    public ScreenMode getScreenMode() { return mScreenMode; }
    public void setScreenMode(ScreenMode mode) { mScreenMode = mode; }
    public boolean isInForeground() { return mInForeground; }
    public NetworkRecoveryController getNetworkRecoveryController() { return mNetworkRecoveryController; }

    // ── Navigation methods ─────────────────────────────────────────

    public void navigateToTerminal(String baseUrl, String cookie, String sessionId,
                                   String termTitle,
                                   String createdAt, String instanceId,
                                   boolean relayDevice, String relayDeviceId, String cwd) {
        Bundle args = new Bundle();
        args.putString("baseUrl", baseUrl);
        args.putString("cookie", cookie);
        args.putString("sessionId", sessionId);
        args.putString("termTitle", termTitle);
        args.putString("createdAt", createdAt != null ? createdAt : "");
        args.putString("instanceId", instanceId != null ? instanceId : "");
        args.putBoolean("relayDevice", relayDevice);
        args.putString("relayDeviceId", relayDeviceId != null ? relayDeviceId : "");
        args.putString("cwd", cwd != null ? cwd : "");
        ServerConfig identityServer = mSelectedServer;
        if (identityServer != null
            && WebTermUrls.normalizeBaseUrl(identityServer.getUrl()).equals(
                WebTermUrls.normalizeBaseUrl(baseUrl))) {
            args.putString("serverConfigId", identityServer.getId());
            args.putString("authIdentity", identityServer.getUsername());
        } else {
            args.putString("serverConfigId", WebTermUrls.normalizeBaseUrl(baseUrl));
            args.putString("authIdentity", "default");
        }
        if (mNavController != null) {
            mNavController.navigate(R.id.terminalFragment, args);
        }
    }

    public void navigateToDeviceSessions(ServerConfig server) {
        if (server == null) { navigateToHome(); return; }
        detachCurrentTerminalView();
        mScreenMode = ScreenMode.DEVICE_SESSIONS;
        mSelectedServer = server;
        if (mNavController != null) {
            mNavController.navigate(R.id.deviceSessionsFragment, DeviceSessionsFragment.args(server));
        }
    }

    public void navigateToRelay() {
        if (mNavController != null) {
            mNavController.navigate(R.id.relayFragment);
        }
    }

    public void navigateToHome() {
        if (mNavController != null) {
            mNavController.popBackStack(R.id.homeFragment, false);
        }
    }

    private int currentDestinationId() {
        if (mNavController == null || mNavController.getCurrentDestination() == null) return 0;
        return mNavController.getCurrentDestination().getId();
    }

    private boolean hasTerminalSession() {
        return remoteTerminalIntegration.hasSession();
    }

    private boolean hasActiveTerminal() {
        return remoteTerminalIntegration.hasSession();
    }

    private boolean isRemoteTerminalActive() {
        return remoteTerminalIntegration.hasSession();
    }

    private void detachCurrentTerminalView() {
        // webterm.screen.v1 owns a separate controller/connection lifecycle.
        // Do not leave its old View listener and mux channel alive after a
        // terminal page is popped; reopening must start from a fresh snapshot.
        terminalAuthRecoveryGeneration++;
        terminalAuthRecoveryInFlight = false;
        remoteTerminalIntegration.stop();
        terminalFocus.clear();
        currentTerminalConnectionKey = "";
        currentTerminalSessionId = "";
    }

    // ── UI helpers ───────────────────────────────────────────────────

    public void installRootInsets(Activity activity, View root, int baseLeft, int baseTop, int baseRight,
                                  int baseBottom, boolean avoidImeWithPadding,
                                  boolean includeStatusBar) {
        WindowInsetsController.installRootInsets(activity, root, baseLeft, baseTop,
            baseRight, baseBottom, avoidImeWithPadding, includeStatusBar, (imeOverlap) -> {
                mImeOverlap = imeOverlap;
                updateKeyboardAvoidance();
            });
    }

    public View buildRelayView(Activity activity) {
        if (mRelayService.hasMaster() && mRelayService.masterConfig().getCookie() != null
            && !mRelayService.masterConfig().getCookie().isEmpty()) {
            return buildRelayDevicesView(activity);
        } else {
            return buildRelayLoginView(activity);
        }
    }

    public void navigateRelayToHome() {
        if (mNavController != null) {
            mNavController.popBackStack(R.id.homeFragment, false);
        }
    }

    private View buildRelayLoginView(Activity activity) {
        String savedEmail = mRelayService.masterConfig() != null
            ? mRelayService.masterConfig().getUsername() : "";
        RelayLoginScreenBuilder.RelayLoginScreen screen =
            RelayLoginScreenBuilder.buildLogin(mRelayUiState, savedEmail);
        mScreenMode = ScreenMode.RELAY_LOGIN;
        installRootInsets(activity, screen.root, 0, 0, 0, dp(activity, 16), true, true);
        return screen.root;
    }

    private View buildRelayDevicesView(Activity activity) {
        RelayDevicesScreenBuilder.RelayDevicesScreen screen =
            RelayDevicesScreenBuilder.build(mRelayUiState);
        mScreenMode = ScreenMode.RELAY_DEVICES;
        installRootInsets(activity, screen.root, 0, 0, 0, dp(activity, 16), true, true);
        screen.refresh.run();
        return screen.root;
    }

    // ── Navigation ───────────────────────────────────────────────────

    private void loadServersFromPrefs() {
        serverConfigs.load();
        mRelayService.loadMasterFromServers(serverConfigs.servers());
    }

    public void saveServers() {
        serverConfigs.save();
    }

    public void showSessionHome() {
        detachCurrentTerminalView();
        mScreenMode = ScreenMode.DEVICES;
        mSelectedServer = null;

        if (mNavController != null) {
            mNavController.popBackStack(R.id.homeFragment, false);
        }
        mainHandler.post(() -> {
            if (mHomeFragment == null) mHomeFragment = findHomeFragment();
            if (mHomeFragment != null) {
                mHomeFragment.showHomeScreen();
            }
        });
    }

    public void loadMultiSessions() {
        // This is called from HomeFragment's loadMultiSessions
    }

    public void showDeviceSessions(Activity activity, ServerConfig server) {
        navigateToDeviceSessions(server);
    }

    private boolean isSelectedServer(ServerConfig server) {
        if (server == null || mSelectedServer == null) return false;
        if (server == mSelectedServer) return true;
        if (server.getId() != null && !server.getId().isEmpty() && server.getId().equals(mSelectedServer.getId())) return true;
        String serverUrl = WebTermUrls.normalizeBaseUrl(server.getUrl());
        String selectedUrl = WebTermUrls.normalizeBaseUrl(mSelectedServer.getUrl());
        String serverDeviceId = server.getDeviceId() == null ? "" : server.getDeviceId();
        String selectedDeviceId = mSelectedServer.getDeviceId() == null ? "" : mSelectedServer.getDeviceId();
        return serverUrl.equals(selectedUrl) && serverDeviceId.equals(selectedDeviceId);
    }

    private void showSessionListOrDeviceHome() {
        if (currentDestinationId() == R.id.terminalFragment) {
            detachCurrentTerminalView();
            if (mNavController != null) mNavController.popBackStack();
            mScreenMode = mSelectedServer != null ? ScreenMode.DEVICE_SESSIONS : ScreenMode.DEVICES;
            return;
        }
        if (currentDestinationId() == R.id.deviceSessionsFragment) {
            mScreenMode = ScreenMode.DEVICE_SESSIONS;
            return;
        }
        if (mSelectedServer != null) {
            navigateToDeviceSessions(mSelectedServer);
        } else {
            showSessionHome();
        }
    }

    public void showAddServerDialog(Activity activity, final ServerConfig existingServer) {
        ServerConfigDialogHelper.show(this, existingServer);
    }

    public void confirmRemoveServer(Activity activity, ServerConfig server) {
        AlertDialog dialog = new AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("确认移除电脑")
            .setMessage("确定要从列表中移除该服务器吗？")
            .setPositiveButton("移除", (d, which) -> {
                removeCachedTerminalsForServer(server);
                serverConfigs.remove(server);
                saveServers();
                if (server == mSelectedServer) {
                    showSessionHome();
                } else {
                    if (mHomeFragment != null) {
                        mHomeFragment.showHomeScreen();
                    }
                }
            })
            .setNegativeButton("取消", null)
            .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    // ── Terminal ─────────────────────────────────────────────────────

    void showTerminal(String baseUrl, String cookie, String sessionId) {
        showTerminal(baseUrl, cookie, sessionId, "Terminal", "", "", false, "", "");
    }
    void showTerminal(String baseUrl, String cookie, String sessionId, String termTitle) {
        showTerminal(baseUrl, cookie, sessionId, termTitle, "", "", false, "", "");
    }
    void showTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String createdAt) {
        showTerminal(baseUrl, cookie, sessionId, termTitle, createdAt, "", false, "", "");
    }
    void showTerminal(String baseUrl, String cookie, String sessionId, String termTitle,
                      String createdAt, String instanceId, boolean relayDevice) {
        showTerminal(baseUrl, cookie, sessionId, termTitle, createdAt, instanceId, relayDevice, "", "");
    }
    void showTerminal(String baseUrl, String cookie, String sessionId, String termTitle,
                      String createdAt, String instanceId, boolean relayDevice, String relayDeviceId, String cwd) {
        currentTerminalConnectionKey = DeviceConnectionKeys.forDevice(baseUrl, relayDevice, relayDeviceId);
        currentTerminalSessionId = sessionId == null ? "" : sessionId;
        if (activityResumed) terminalFocus.setVisible(currentTerminalConnectionKey, currentTerminalSessionId);
        navigateToTerminal(baseUrl, cookie, sessionId, termTitle, createdAt, instanceId,
            relayDevice, relayDeviceId, cwd);
    }

    // ── TerminalHost ─────────────────────────────────────────────────

    public void startRemoteTerminalInFragment(Activity activity, TerminalViewModel.TerminalSessionArgs args,
                                                TerminalFragment fragment) {
        mScreenMode = ScreenMode.TERMINAL;
        // Fragment/终端切换会让此前尚未返回的认证刷新回调失效。
        terminalAuthRecoveryGeneration++;
        terminalAuthRecoveryInFlight = false;
        remoteTerminalIntegration.setAuthenticationListener(this::recoverTerminalAuthentication);
        remoteTerminalIntegration.setTitleListener(new RemoteTerminalIntegration.TitleListener() {
            @Override public void onTitleChanged(String title) {
                // 顶栏由 RemoteTerminalIntegration 直接更新；这里保留应用层通知入口。
            }
            @Override public void onWorkingDirectoryChanged(String cwd) {
                ServerConfig server = findServerForRemoteTerminal();
                if (server != null && remoteTerminalIntegration.sessionId() != null) {
                    updateCurrentSessionCwd(server, remoteTerminalIntegration.sessionId(), cwd);
                }
            }
        });
        remoteTerminalIntegration.start(activity, fragment, args,
            configStore.getFontSize(), getTypefaceByName(configStore.getFontType()));
    }

    public void detachTerminalFragment(TerminalFragment fragment) {
        remoteTerminalIntegration.detach(fragment);
    }

    // ── HomeHost ─────────────────────────────────────────────────────

    public void showTerminal(Activity activity, ServerConfig server, String sessionId, String termTitle,
                             String createdAt, String instanceId, String cwd) {
        openSession(activity, server, sessionId, termTitle, createdAt, instanceId, cwd);
    }

    public void openSession(Activity activity, ServerConfig server, String sessionId, String termTitle,
                            String createdAt, String instanceId, String cwd) {
        mSelectedServer = server;
        showTerminal(server.getUrl(), server.getCookie(), sessionId, termTitle, createdAt, instanceId, server.isRelayDevice(), server.getDeviceId(), cwd);
    }

    public void requestOpenTerminalFromNotification(String connectionKey, String sessionId) {
        if (connectionKey == null || connectionKey.isEmpty() || sessionId == null || sessionId.isEmpty()) {
            clearNotificationOpenRequest(new PendingTerminalOpen(connectionKey, sessionId));
            return;
        }
        pendingTerminalOpen = new PendingTerminalOpen(connectionKey, sessionId);
        if (activityResumed) mainHandler.post(this::drainPendingTerminalOpen);
    }

    private void drainPendingTerminalOpen() {
        PendingTerminalOpen pending = pendingTerminalOpen;
        if (pending == null || !activityResumed || mActivity == null
            || mActivity.getSupportFragmentManager().isStateSaved() || mNavController == null) return;
        NotificationTerminalResolver.Result result = NotificationTerminalResolver.resolve(
            pending.connectionKey, serverConfigs.servers(), mRelayService.devices(),
            mRelayService.areDevicesLoaded());
        if (result.status == NotificationTerminalResolver.ResolveStatus.RESOLVED) {
            pendingTerminalOpen = null;
            if (!pending.connectionKey.equals(currentTerminalConnectionKey)
                || !pending.sessionId.equals(currentTerminalSessionId)) {
                ServerConfig server = result.server;
                mSelectedServer = server;
                showTerminal(server.getUrl(), server.getCookie(), pending.sessionId, "Terminal",
                    "", "", server.isRelayDevice(), server.getDeviceId(), "");
            }
            clearNotificationOpenRequest(pending);
        } else if (result.status == NotificationTerminalResolver.ResolveStatus.WAITING_FOR_RELAY_DEVICES) {
            if (!pending.relayRefreshRequested) {
                pending.relayRefreshRequested = true;
                mRelayService.refresh();
            }
        } else {
            Log.w(TAG, "通知跳转找不到目标设备: " + pending.connectionKey);
            pendingTerminalOpen = null;
            Toast.makeText(mActivity, "无法找到通知对应的设备", Toast.LENGTH_SHORT).show();
            clearNotificationOpenRequest(pending);
        }
    }

    private void clearNotificationOpenRequest(PendingTerminalOpen pending) {
        if (mActivity instanceof NotificationOpenHost) {
            ((NotificationOpenHost) mActivity).clearNotificationOpenRequest(
                pending.connectionKey, pending.sessionId);
        }
    }

    private static final class PendingTerminalOpen {
        final String connectionKey;
        final String sessionId;
        boolean relayRefreshRequested;

        PendingTerminalOpen(String connectionKey, String sessionId) {
            this.connectionKey = connectionKey;
            this.sessionId = sessionId;
        }
    }

    private void startFileDownload(String downloadId, String fileName, long fileSize, String sessionId) {
        if (mSelectedServer == null) {
            Toast.makeText(mActivity, "下载失败：未选择服务器", Toast.LENGTH_SHORT).show();
            return;
        }
        downloadHelper.startDownload(mSelectedServer, downloadId, fileName, fileSize, sessionId);
    }

    private void updateCurrentSessionCwd(ServerConfig server, String sessionId, String cwd) {
        if (server == null || sessionId == null || sessionId.isEmpty()) return;
        if (!isRemoteTerminalActive()) return;
        if (!WebTermUrls.normalizeBaseUrl(server.getUrl()).equals(WebTermUrls.normalizeBaseUrl(remoteTerminalIntegration.baseUrl()))) return;
        if (!sameTerminalSessionId(sessionId, remoteTerminalIntegration.sessionId(), server.getDeviceId())) return;
        sessionRepository.updateSessionCwd(server, sessionId, cwd);
    }

    private void requestTerminalFreshReconnect() {
        if (isRemoteTerminalActive()) {
            ServerConfig server = findServerForRemoteTerminal();
            if (server != null) {
                authCoordinator.recover(server, new AuthSessionCoordinator.Callback() {
                    @Override public void onAuthenticated(ServerConfig canonical, String cookie) {
                        mActivity.runOnUiThread(() -> remoteTerminalIntegration.reconnectFresh(cookie));
                    }

                    @Override public void onFailure(AuthSessionCoordinator.Failure failure) {
                        mActivity.runOnUiThread(() -> Toast.makeText(mActivity,
                            "连接恢复失败，请稍后重试", Toast.LENGTH_SHORT).show());
                    }
                });
            } else {
                remoteTerminalIntegration.reconnectFresh(remoteTerminalIntegration.cookie());
            }
            return;
        }

    }

    /**
     * 活动终端收到 401 时加入进程级单飞认证恢复。401 不代表 PTY 已结束；
     * refresh/login 成功并持久化凭据后重建 screen channel，失败则停留在可重试状态。
     */
    private void recoverTerminalAuthentication(String reason) {
        if (terminalAuthRecoveryInFlight || !isRemoteTerminalActive()) return;
        ServerConfig server = findServerForRemoteTerminal();
        if (server == null) {
            Toast.makeText(mActivity, "终端认证已失效，请返回设备列表重新登录", Toast.LENGTH_SHORT).show();
            return;
        }
        terminalAuthRecoveryInFlight = true;
        int recoveryGeneration = terminalAuthRecoveryGeneration;
        authCoordinator.recover(server, new AuthSessionCoordinator.Callback() {
            @Override public void onAuthenticated(ServerConfig canonical, String refreshedCookie) {
                mActivity.runOnUiThread(() -> {
                    if (recoveryGeneration != terminalAuthRecoveryGeneration) return;
                    terminalAuthRecoveryInFlight = false;
                    if (!isRemoteTerminalActive()) return;
                    remoteTerminalIntegration.reconnectFresh(refreshedCookie);
                });
            }

            @Override public void onFailure(AuthSessionCoordinator.Failure failure) {
                mActivity.runOnUiThread(() -> {
                    if (recoveryGeneration != terminalAuthRecoveryGeneration) return;
                    terminalAuthRecoveryInFlight = false;
                    if (!isRemoteTerminalActive()) return;
                    Toast.makeText(mActivity, "终端认证恢复失败，请点击重试", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private ServerConfig findServerForRemoteTerminal() {
        if (!isRemoteTerminalActive()) return null;
        String baseUrl = WebTermUrls.normalizeBaseUrl(remoteTerminalIntegration.baseUrl());
        String terminalDeviceId = remoteTerminalIntegration.relayDeviceId() == null
            ? "" : remoteTerminalIntegration.relayDeviceId();
        if (mSelectedServer != null) {
            String selectedDeviceId = mSelectedServer.getDeviceId() == null
                ? "" : mSelectedServer.getDeviceId();
            if (WebTermUrls.normalizeBaseUrl(mSelectedServer.getUrl()).equals(baseUrl)
                && selectedDeviceId.equals(terminalDeviceId)) {
                return mSelectedServer;
            }
        }
        // 同一 relay URL 下可能配置多个设备。优先匹配 URL + deviceId，避免
        // 401 恢复时刷新并保存到同 URL 的另一个设备配置。
        for (ServerConfig server : serverConfigs.servers()) {
            String serverDeviceId = server.getDeviceId() == null ? "" : server.getDeviceId();
            if (WebTermUrls.normalizeBaseUrl(server.getUrl()).equals(baseUrl)
                && serverDeviceId.equals(terminalDeviceId)) {
                return server;
            }
        }
        // 非 relay/旧配置可能没有稳定 deviceId；仅在没有歧义时按 URL 回退。
        ServerConfig onlyUrlMatch = null;
        for (ServerConfig server : serverConfigs.servers()) {
            if (!WebTermUrls.normalizeBaseUrl(server.getUrl()).equals(baseUrl)) continue;
            if (onlyUrlMatch != null) return null;
            onlyUrlMatch = server;
        }
        return onlyUrlMatch;
    }

    public void onSessionCwdChanged(ServerConfig server, String sessionId, String cwd) {
        updateCurrentSessionCwd(server, sessionId, cwd);
    }

    private static boolean sameTerminalSessionId(String a, String b, String deviceId) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        return SessionIds.local(a, deviceId)
            .equals(SessionIds.local(b, deviceId));
    }

    // ── Terminal preferences ───────────────────────────────────────

    public int getSavedFontSize() { return configStore.getFontSize(); }
    public String getSavedFontType() { return configStore.getFontType(); }
    public Typeface getTypefaceByName(String type) {
        if ("sans-serif".equals(type)) return Typeface.SANS_SERIF;
        if ("serif".equals(type)) return Typeface.SERIF;
        if ("default".equals(type)) return Typeface.DEFAULT;
        return Typeface.MONOSPACE;
    }

    public void updateKeyboardAvoidance() {
        if (isRemoteTerminalActive()) {
            remoteTerminalIntegration.updateKeyboardAvoidance();
        }
    }

    // ── SessionRowActions (not implemented by AppFlowCoordinator directly; handled via MainActivity) ──────────────────────────────────────────

    public void closeSession(ServerConfig server, String sessionId) {
        closeSession(server, sessionId, null);
    }

    public void closeSession(ServerConfig server, String sessionId, Runnable onClosed) {
        if (mSessionCommands != null) {
            mSessionCommands.showCloseConfirmDialog(server, sessionId, () -> {
                disposeRuntimeForSession(server, sessionId);
                if (onClosed != null) onClosed.run();
            });
        }
    }

    public void createSession(ServerConfig server) {
        if (mSessionCommands != null && server != null) mSessionCommands.createSessionOnServer(server);
    }

    public void removeServer(ServerConfig server) {
        if (server == null) return;
        if (isRemoteTerminalActive()
            && WebTermUrls.normalizeBaseUrl(server.getUrl()).equals(WebTermUrls.normalizeBaseUrl(remoteTerminalIntegration.baseUrl()))) {
            remoteTerminalIntegration.closeSession();
        }
        remoteTerminalIntegration.closeServer(server.getId());
        removeCachedTerminalsForServer(server);
        serverConfigs.remove(server);
        saveServers();
        if (server == mSelectedServer) {
            mSelectedServer = null;
            mScreenMode = ScreenMode.DEVICES;
        }
        mHomeFragment = findHomeFragment();
        if (mHomeFragment != null) mHomeFragment.refreshDevices();
    }

    // ── ServerConfigDialogHelper.Host ──────────────────────────────

    @Override
    public Activity activity() { return mActivity; }

    @Override
    public void login(String baseUrl, String username, String password, ServerConfigDialogHelper.LoginCallback callback) {
        api.login(baseUrl, "", username, password, new WebTermApi.LoginCallback() {
            @Override
            public void onReady(String readyBaseUrl, String cookie) { callback.onReady(readyBaseUrl, cookie); }
            @Override
            public void onError(String message) { callback.onError(message); }
        });
    }

    @Override
    public void onServerAuthenticated(ServerConfig existingServer, String name, String url, String cookie,
                                      String username, String password) {
        serverConfigs.addOrUpdate(existingServer, name, url, cookie, username, password);
        saveServers();
        if (existingServer != null && existingServer == mSelectedServer) {
            navigateToDeviceSessions(existingServer);
        } else {
            showSessionHome();
        }
    }

    // ── RelayService.Host ──────────────────────────────────────

    @Override
    public void onRelayDevicesChanged() {
        mHomeFragment = findHomeFragment();
        if (mHomeFragment != null) {
            mHomeFragment.refreshDevices();
        }
        mainHandler.post(this::drainPendingTerminalOpen);
    }
    @Override
    public void onRelayDevicesLoadFailed(String message) {
        PendingTerminalOpen pending = pendingTerminalOpen;
        if (pending == null || !pending.relayRefreshRequested || !activityResumed) return;
        pendingTerminalOpen = null;
        Toast.makeText(mActivity, message == null || message.isEmpty()
            ? "无法获取中转设备列表" : message, Toast.LENGTH_SHORT).show();
        clearNotificationOpenRequest(pending);
    }
    @Override
    public void onRelayAuthDone() {
        if (mScreenMode == ScreenMode.RELAY_LOGIN
            && mRelayService.hasMaster()
            && mRelayService.masterConfig().getCookie() != null
            && !mRelayService.masterConfig().getCookie().isEmpty()) {
            if (mNavController != null) {
                mNavController.popBackStack(R.id.homeFragment, false);
                mainHandler.postDelayed(() -> {
                    if (mNavController != null) {
                        mNavController.navigate(R.id.relayFragment);
                    }
                }, 100);
            }
        } else {
            showSessionHome();
        }
    }
    @Override
    public ServerConfigManager serverConfigs() { return serverConfigs; }

    // ── SettingsDialogHelper.Host ──────────────────────────────────

    @Override
    public String getFontDisplayName(String fontType) {
        if ("sans-serif".equals(fontType)) return "Sans Serif";
        if ("serif".equals(fontType)) return "Serif";
        if ("default".equals(fontType)) return "Default";
        return "Monospace";
    }
    @Override
    public void saveFontSize(int size) { configStore.saveFontSize(size); }
    @Override
    public void saveFontType(String type) { configStore.saveFontType(type); }
    @Override
    public void applyTerminalFontSize(int size) {
        if (isRemoteTerminalActive()) {
            remoteTerminalIntegration.updateFontSize(size);
        }
    }
    @Override
    public void applyTerminalTypeface(Typeface typeface) {
        if (isRemoteTerminalActive()) {
            remoteTerminalIntegration.updateTypeface(typeface);
        }
    }
    @Override
    public String getDownloadDirDisplayName() {
        String uriStr = configStore.getDownloadDirUri();
        if (uriStr == null || uriStr.isEmpty()) {
            return "未设置";
        }
        try {
            DocumentFile doc = DocumentFile.fromTreeUri(mActivity, Uri.parse(uriStr));
            String name = doc != null ? doc.getName() : null;
            return name != null && !name.isEmpty() ? name : "已设置";
        } catch (Exception e) {
            return "已设置";
        }
    }
    @Override
    public void openDownloadDirPicker() {
        if (mActivity == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Uri initialUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
        }
        mActivity.startActivityForResult(intent, REQUEST_CODE_DOWNLOAD_DIR);
    }

    @Override
    public boolean isBatteryOptimizationIgnored() {
        if (mActivity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager pm = (PowerManager) mActivity.getSystemService(android.content.Context.POWER_SERVICE);
        return pm == null || pm.isIgnoringBatteryOptimizations(mActivity.getPackageName());
    }

    @Override
    public void requestIgnoreBatteryOptimization() {
        if (mActivity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + mActivity.getPackageName()));
            mActivity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(mActivity, "打开设置失败，请手动在系统设置中允许后台运行", Toast.LENGTH_LONG).show();
        }
    }

    public void onDownloadDirPickerResult(int resultCode, Intent data) {
        if (mActivity == null || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri treeUri = data.getData();
        try {
            mActivity.getContentResolver().takePersistableUriPermission(treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            configStore.setDownloadDirUri(treeUri.toString());
            Toast.makeText(mActivity, "下载目录已保存", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(mActivity, "无法保存下载目录：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────

    public void showSettingsDialog() { SettingsDialogHelper.show(this); }

    public void shareLatestCrashLog(Activity activity) {
        String crashLog = CrashReporter.readLatestCrash(activity);
        if (crashLog == null || crashLog.trim().isEmpty()) {
            Toast.makeText(activity, "暂无崩溃日志", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "WebTerm 崩溃日志");
        send.putExtra(Intent.EXTRA_TEXT, crashLog);
        activity.startActivity(Intent.createChooser(send, "导出崩溃日志"));
    }

    public void shareCrashLog() {
        shareLatestCrashLog(mActivity);
    }

    public void removeCachedTerminal(String baseUrl, String sessionId) {
        terminalCache.removeTerminal(baseUrl, sessionId);
    }

    public void removeMissingCachedSessionsForServer(ServerConfig server, java.util.Set<String> liveSessionIdentities) {
        terminalCache.removeMissingForServer(server, liveSessionIdentities);
    }

    private void removeCachedTerminalsForServer(ServerConfig server) {
        terminalCache.removeServer(server);
    }

    private void disposeRuntimeForSession(ServerConfig server, String sessionId) {
        remoteTerminalIntegration.closeStoredSession(server.getId(), sessionId);
        if (isRemoteTerminalActive()
            && sessionId.equals(remoteTerminalIntegration.sessionId())
            && WebTermUrls.normalizeBaseUrl(server.getUrl()).equals(WebTermUrls.normalizeBaseUrl(remoteTerminalIntegration.baseUrl()))) {
            remoteTerminalIntegration.closeSession();
            return;
        }
    }

    // ── Fragment helpers ───────────────────────────────────────────

    private HomeFragment findHomeFragment() {
        if (mActivity == null) return null;
        Fragment f = mActivity.getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
        if (f instanceof androidx.navigation.fragment.NavHostFragment) {
            for (Fragment fragment : f.getChildFragmentManager().getFragments()) {
                if (fragment instanceof HomeFragment) {
                    return (HomeFragment) fragment;
                }
            }
        }
        return null;
    }

    // ── Animation ──────────────────────────────────────────────────

    private int dp(Activity activity, int value) {
        return PageTransitionAnimator.dp(activity, value);
    }
}
