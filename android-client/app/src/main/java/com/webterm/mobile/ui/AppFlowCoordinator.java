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
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONObject;

import com.webterm.mobile.CrashReporter;
import com.webterm.mobile.R;
import com.webterm.mobile.diagnostics.DiagnosticLogExporter;
import com.webterm.data.http.WebTermApi;
import com.webterm.core.api.AuthSessionCoordinator;
import com.webterm.core.api.WebTermUrls;
import com.webterm.core.api.DeviceConnectionKeys;
import com.webterm.core.cache.TerminalCacheCoordinator;
import com.webterm.core.config.ServerConfig;
import com.webterm.core.config.ServerConfigManager;
import com.webterm.core.config.ServerConfigStore;
import com.webterm.core.relay.RelayService;
import com.webterm.core.api.SessionIds;
import com.webterm.core.session.DeviceConnectionRegistry;
import com.webterm.core.notifications.TerminalFocusStore;
import com.webterm.feature.terminal.domain.RemoteTerminalIntegration;
import com.webterm.mobile.recovery.NetworkRecoveryController;
import com.webterm.mobile.device.NotificationTerminalResolver;
import com.webterm.mobile.device.WebTermDeviceService;
import com.webterm.ui.common.PageTransitionAnimator;
import com.webterm.ui.common.UIUtils;
import com.webterm.mobile.ui.dialog.DirectDeviceDialog;
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


import javax.inject.Inject;

import dagger.hilt.android.scopes.ActivityScoped;

@ActivityScoped
public final class AppFlowCoordinator implements
	SettingsDialogHelper.Host, RelayService.Host, DirectDeviceDialog.Host {

    private static final String TAG = "AppFlowCoordinator";
    public static final int REQUEST_CODE_DOWNLOAD_DIR = 0x1001;

    private final WebTermApi api;
    private final AuthSessionCoordinator authCoordinator;
    private final DeviceConnectionRegistry deviceConnectionRegistry;
    private final TerminalCacheCoordinator terminalCache;
    private final ServerConfigStore configStore;
    private final ServerConfigManager serverConfigs;
    private final Handler mainHandler;
    private final TerminalFocusStore terminalFocus;
    private final SessionRepository sessionRepository;

    private final RemoteTerminalIntegration remoteTerminalIntegration;
    private final RelayService mRelayService;

    private boolean activityResumed;
    private boolean terminalAuthRecoveryInFlight;
    private int terminalAuthRecoveryGeneration;
    private int directRequestGeneration;
    private SessionCommandController mSessionCommands;

    private ScreenMode mScreenMode = ScreenMode.DEVICES;
    private ServerConfig mSelectedServer;

    private RelayUiState mRelayUiState;
    private NetworkRecoveryController mNetworkRecoveryController;
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
        DeviceConnectionRegistry deviceConnectionRegistry,
        TerminalCacheCoordinator terminalCache,
        ServerConfigStore configStore,
        ServerConfigManager serverConfigs,
        Handler mainHandler,
        TerminalFocusStore terminalFocus,
        SessionRepository sessionRepository,
        RemoteTerminalIntegration remoteTerminalIntegration,
        RelayService relayService
    ) {
        this.api = api;
        this.authCoordinator = authCoordinator;
        this.deviceConnectionRegistry = deviceConnectionRegistry;
        this.terminalCache = terminalCache;
        this.configStore = configStore;
        this.serverConfigs = serverConfigs;
        this.mainHandler = mainHandler;
        this.terminalFocus = terminalFocus;
        this.sessionRepository = sessionRepository;
        this.remoteTerminalIntegration = remoteTerminalIntegration;
        this.mRelayService = relayService;
    }

    public void attachActivity(MainActivity activity) {
        this.mActivity = activity;
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
            public void onOpenTerminal(ServerConfig server, String sessionId, String termTitle) {
                mSelectedServer = server;
                showTerminal(server, server.getUrl(), server.getCookie(), sessionId, termTitle,
                    "", "", server.isRelayDevice(), server.getDeviceId(), "");
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
        activityResumed = true;
        remoteTerminalIntegration.setAppVisible(true);
        if (!currentTerminalConnectionKey.isEmpty() && !currentTerminalSessionId.isEmpty()) {
            terminalFocus.setVisible(currentTerminalConnectionKey, currentTerminalSessionId);
        }
        mainHandler.post(this::drainPendingTerminalOpen);

        mHomeFragment = findHomeFragment();

        // 回到前台时，如果在主页列表，自动重新开启/拉取中转设备
        if (!isRemoteTerminalActive() && mScreenMode == ScreenMode.DEVICES) {
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
        activityResumed = false;
        remoteTerminalIntegration.setAppVisible(false);
        terminalFocus.clear();
        if (!isRemoteTerminalActive()) {
            mRelayService.stop();
        }
    }

    public void onDestroy() {
        directRequestGeneration++;
        mainHandler.removeCallbacksAndMessages(null);
        mRelayService.stop();
        remoteTerminalIntegration.stop();
        terminalCache.shutdown();
        // Device connection 与 terminal runtime 是进程级所有权。Activity 重建不能关闭它们；
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

    // ── Navigation methods ─────────────────────────────────────────

    public void navigateToTerminal(ServerConfig identity, String baseUrl, String cookie, String sessionId,
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
        ServerConfig identityServer = identity;
        boolean directDevice = identityServer != null && identityServer.isDirectDevice()
            && WebTermUrls.normalizeBaseUrl(identityServer.getUrl()).equals(
                WebTermUrls.normalizeBaseUrl(baseUrl));
        if (identityServer != null
            && WebTermUrls.normalizeBaseUrl(identityServer.getUrl()).equals(
                WebTermUrls.normalizeBaseUrl(baseUrl))) {
            args.putString("serverConfigId", identityServer.getId());
            args.putString("authIdentity", identityServer.getUsername());
        } else {
            args.putString("serverConfigId", WebTermUrls.normalizeBaseUrl(baseUrl));
            args.putString("authIdentity", "default");
        }
        // 统一身份：connectionKey 由 resolve() 一次性计算，后续模块只消费、不再推导。
        args.putBoolean("directDevice", directDevice);
        args.putString("connectionKey", DeviceConnectionKeys.resolve(
            directDevice,
            identityServer != null ? identityServer.getId() : "",
            baseUrl,
            relayDeviceId));
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
            baseRight, baseBottom, avoidImeWithPadding, includeStatusBar,
            (imeOverlap) -> updateKeyboardAvoidance());
    }

    public View buildRelayView(Activity activity) {
        if (mRelayService.hasMaster() && mRelayService.masterConfig().getCookie() != null
            && !mRelayService.masterConfig().getCookie().isEmpty()) {
            return buildRelayDevicesView(activity);
        } else {
            return buildRelayLoginView(activity);
        }
    }

    private View buildRelayLoginView(Activity activity) {
        ServerConfig master = mRelayService.masterConfig();
        String savedUrl = master != null && master.getUrl() != null && !master.getUrl().isEmpty()
            ? master.getUrl() : ServerConfigStore.DEFAULT_URL;
        String savedEmail = master != null ? master.getUsername() : "";
        RelayLoginScreenBuilder.RelayLoginScreen screen =
            RelayLoginScreenBuilder.buildLogin(mRelayUiState, savedUrl, savedEmail);
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

	// ── Terminal ─────────────────────────────────────────────────────

	void showTerminal(ServerConfig identity, String baseUrl, String cookie, String sessionId, String termTitle,
                      String createdAt, String instanceId, boolean relayDevice, String relayDeviceId, String cwd) {
		boolean directDevice = identity != null && identity.isDirectDevice()
            && WebTermUrls.normalizeBaseUrl(identity.getUrl()).equals(WebTermUrls.normalizeBaseUrl(baseUrl));
		currentTerminalConnectionKey = DeviceConnectionKeys.resolve(
            directDevice, identity != null ? identity.getId() : "", baseUrl, relayDeviceId);
        currentTerminalSessionId = sessionId == null ? "" : sessionId;
        if (activityResumed) terminalFocus.setVisible(currentTerminalConnectionKey, currentTerminalSessionId);
        navigateToTerminal(identity, baseUrl, cookie, sessionId, termTitle, createdAt, instanceId,
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
        // 现场捕获菜单入口：debug/diag 返回 4 项（按记录状态动态显示），release 返回空列表。
        remoteTerminalIntegration.setDebugMenuItems(
            com.webterm.mobile.diagnostics.TerminalCaptureMenu.items(activity));
        remoteTerminalIntegration.start(activity, fragment, args,
            configStore.getFontSize(), getTypefaceByName(configStore.getFontType()));
    }

    public void detachTerminalFragment(TerminalFragment fragment) {
        remoteTerminalIntegration.detach(fragment);
    }

    // ── HomeHost ─────────────────────────────────────────────────────

    public void openSession(ServerConfig server, String sessionId, String termTitle,
                            String createdAt, String instanceId, String cwd) {
        mSelectedServer = server;
        showTerminal(server, server.getUrl(), server.getCookie(), sessionId, termTitle, createdAt, instanceId, server.isRelayDevice(), server.getDeviceId(), cwd);
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
                showTerminal(server, server.getUrl(), server.getCookie(), pending.sessionId, "Terminal",
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
	public Activity activity() { return mActivity; }

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

    // ── 添加直连设备 ─────────────────────────────────────────────

    public void showAddDirectDeviceDialog() {
        DirectDeviceDialog.show(this);
    }

    @Override
    public DirectDeviceDialog.RequestHandle submitDirectDevice(String normalizedUrl, String username, String password,
                                   DirectDeviceDialog.Callback callback) {
        // 去重：相同 URL + 账户视为同一 Direct 设备。
        if (serverConfigs.containsDirectDevice(normalizedUrl, username)) {
            callback.onError("该地址和账户已经添加");
            return () -> {};
        }
        final int requestGeneration = ++directRequestGeneration;
        WebTermApi.RequestHandle call = api.login(normalizedUrl, "", username, password, new WebTermApi.LoginCallback() {
            @Override
            public void onReady(String baseUrl, String cookie) {
                mainHandler.post(() -> {
                    if (directRequestGeneration != requestGeneration) return;
                    String id = "direct_" + java.util.UUID.randomUUID();
                    String name = directDeviceDisplayName(normalizedUrl);
                    ServerConfig config = new ServerConfig(
                        id, name, normalizedUrl, cookie, username, password,
                        false, false, "");
                    serverConfigs.addDirectDevice(config);
                    if (mHomeFragment == null) mHomeFragment = findHomeFragment();
                    if (mHomeFragment != null) mHomeFragment.refreshDevices();
                    // 触发后台服务重新加载设备并建立 Direct 连接，无需重启 App。
                    WebTermDeviceService.refresh(mActivity);
                    callback.onSuccess(name);
                });
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> {
                    if (directRequestGeneration != requestGeneration) return;
                    callback.onError(message == null || message.isEmpty() ? "连接失败" : message);
                });
            }

            @Override
            public void onError(int code, String message) {
                mainHandler.post(() -> {
                    if (directRequestGeneration != requestGeneration) return;
                    callback.onError(mapDirectLoginError(code));
                });
            }
        });
        return () -> {
            if (directRequestGeneration == requestGeneration) directRequestGeneration++;
            if (call != null) call.cancel();
        };
    }

    /** 设备名优先取主机名/IP（登录响应暂未返回设备名）。 */
    private static String directDeviceDisplayName(String normalizedUrl) {
        try {
            String host = android.net.Uri.parse(normalizedUrl).getHost();
            if (host != null && !host.isEmpty()) return host;
        } catch (Exception ignored) {
        }
        return normalizedUrl;
    }

    /** 将登录 HTTP 状态码映射为面向用户的错误文案（计划 §21.1）。 */
    private static String mapDirectLoginError(int code) {
        switch (code) {
            case 401:
            case 403:
                return "账户或密码错误";
            case 404:
                return "该地址不是 WebTerm Direct Agent";
            case 0:
                return "无法连接到该地址，请确认 Agent 已开启 Direct 模式";
            default:
                return "连接失败（HTTP " + code + "）";
        }
    }

    public void editDirectDevice(ServerConfig server) {
        if (server == null) return;
        DirectDeviceDialog.showForEdit(this, server);
    }

    @Override
    public DirectDeviceDialog.RequestHandle updateDirectDevice(String oldConfigId, String normalizedUrl, String username,
                                   String password, DirectDeviceDialog.Callback callback) {
        // 编辑去重需排除自身，否则“只改密码”会被误判为重复。
        if (serverConfigs.containsDirectDevice(normalizedUrl, username, oldConfigId)) {
            callback.onError("该地址和账户已经添加");
            return () -> {};
        }
        final int requestGeneration = ++directRequestGeneration;
        WebTermApi.RequestHandle call = api.login(normalizedUrl, "", username, password, new WebTermApi.LoginCallback() {
            @Override
            public void onReady(String baseUrl, String cookie) {
                mainHandler.post(() -> {
                    if (directRequestGeneration != requestGeneration) return;
                    String name = directDeviceDisplayName(normalizedUrl);
                    // 先按旧地址清理本地 Runtime 与缓存（此时配置尚未被原位改写）。
                    ServerConfig target = findDirectServerById(oldConfigId);
                    remoteTerminalIntegration.closeServer(oldConfigId);
                    if (target != null) removeCachedTerminalsForServer(target);
                    // 原位更新，保持 configId（从而 connectionKey）不变；地址变化由
                    // DeviceConnectionRegistry 在下次 forDirectDevice 时重建连接。
                    boolean updated = serverConfigs.updateDirectDevice(
                        oldConfigId, normalizedUrl, cookie, username, password, name);
                    if (!updated) {
                        callback.onError("设备不存在，请重新添加");
                        return;
                    }
                    refreshHomeDevices();
                    WebTermDeviceService.refresh(mActivity);
                    callback.onSuccess(name);
                });
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> {
                    if (directRequestGeneration != requestGeneration) return;
                    callback.onError(message == null || message.isEmpty() ? "连接失败" : message);
                });
            }

            @Override
            public void onError(int code, String message) {
                mainHandler.post(() -> {
                    if (directRequestGeneration != requestGeneration) return;
                    callback.onError(mapDirectLoginError(code));
                });
            }
        });
        return () -> {
            if (directRequestGeneration == requestGeneration) directRequestGeneration++;
            if (call != null) call.cancel();
        };
    }

    /** 在持久化配置中按 configId 查找 Direct 设备。 */
    private ServerConfig findDirectServerById(String configId) {
        if (configId == null || configId.isEmpty()) return null;
        for (ServerConfig server : serverConfigs.directDevices()) {
            if (configId.equals(server.getId())) return server;
        }
        return null;
    }

    public void reconnectDirectDevice(ServerConfig server) {
        if (server == null || mActivity == null) return;
        final String username = server.getUsername();
        final String password = server.getPassword();
        api.login(server.getUrl(), "", username, password, new WebTermApi.LoginCallback() {
            @Override
            public void onReady(String baseUrl, String cookie) {
                mainHandler.post(() -> {
                    // 刷新 Cookie 并触发后台服务重建连接。
                    serverConfigs.updateCookie(server, cookie);
                    WebTermDeviceService.refresh(mActivity);
                    android.widget.Toast.makeText(mActivity, "已重新连接", android.widget.Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> android.widget.Toast.makeText(mActivity,
                    "重新连接失败：" + (message == null ? "" : message),
                    android.widget.Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onError(int code, String message) {
                mainHandler.post(() -> android.widget.Toast.makeText(mActivity,
                    mapDirectLoginError(code), android.widget.Toast.LENGTH_SHORT).show());
            }
        });
    }

    public void deleteDirectDevice(ServerConfig server) {
        if (server == null || mActivity == null) return;
        new android.app.AlertDialog.Builder(mActivity)
            .setTitle("删除直连设备")
            .setMessage("确定删除该直连设备吗？\n这不会关闭电脑上的终端会话，但会关闭手机上的当前连接和缓存页面。")
            .setPositiveButton("删除", (dialog, which) -> {
                // 仅清理 Android 本地 Runtime / 缓存 / 配置与连接，不调用远程 Session DELETE，
                // 因此 PC 上已打开的 PTY 会话不受影响。
                remoteTerminalIntegration.closeServer(server.getId());
                removeCachedTerminalsForServer(server);
                serverConfigs.removeDirectDevice(server.getId());
                WebTermDeviceService.refresh(mActivity);
                refreshHomeDevices();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 刷新主页设备列表。 */
    private void refreshHomeDevices() {
        if (mHomeFragment == null) mHomeFragment = findHomeFragment();
        if (mHomeFragment != null) mHomeFragment.refreshDevices();
    }


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

    public boolean canShareDiagnosticLogs() {
        return DiagnosticLogExporter.isAvailable();
    }

    public void shareDiagnosticLogs() {
        if (mActivity != null) {
            DiagnosticLogExporter.share(mActivity);
        }
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
