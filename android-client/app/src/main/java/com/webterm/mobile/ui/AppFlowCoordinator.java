package com.webterm.mobile.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONObject;

import org.json.JSONObject;

import com.termux.terminal.TerminalSession;
import com.webterm.mobile.CrashReporter;
import com.webterm.mobile.R;
import com.webterm.core.api.WebTermApi;
import com.webterm.core.api.WebTermUrls;
import com.webterm.core.cache.TerminalCacheCoordinator;
import com.webterm.core.config.ServerConfig;
import com.webterm.core.config.ServerConfigManager;
import com.webterm.core.config.ServerConfigStore;
import com.webterm.ui.common.command.SessionCommandController;
import com.webterm.core.relay.RelayService;
import com.webterm.core.api.SessionIds;
import com.webterm.core.session.RelayMuxSessionRegistry;
import com.webterm.feature.terminal.domain.TerminalConnection;
import com.webterm.feature.terminal.domain.TerminalRuntime;
import com.webterm.feature.terminal.domain.TerminalRuntimeRegistry;
import com.webterm.mobile.recovery.NetworkRecoveryController;
import com.webterm.transport.webrtc.P2PConnectionManager;
import com.webterm.ui.common.PageTransitionAnimator;
import com.webterm.ui.common.UIUtils;
import com.webterm.mobile.download.FileDownloadHelper;
import com.webterm.mobile.ui.dialog.ServerConfigDialogHelper;
import com.webterm.mobile.ui.dialog.SettingsDialogHelper;
import com.webterm.feature.home.DeviceSessionsFragment;
import com.webterm.feature.home.HomeFragment;
import com.webterm.feature.relay.RelayDevicesScreenBuilder;
import com.webterm.feature.relay.RelayLoginScreenBuilder;
import com.webterm.feature.relay.RelayUiState;
import com.webterm.feature.terminal.TerminalFragment;
import com.webterm.feature.terminal.TerminalViewModel;
import com.webterm.terminal.ui.TerminalWindowInsetsController;

import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;

import okhttp3.OkHttpClient;

import javax.inject.Inject;

import dagger.hilt.android.scopes.ActivityScoped;

@ActivityScoped
public final class AppFlowCoordinator implements
    ServerConfigDialogHelper.Host, SettingsDialogHelper.Host, RelayService.Host {

    public static final int REQUEST_CODE_DOWNLOAD_DIR = 0x1001;

    private final WebTermApi api;
    private final RelayMuxSessionRegistry relayMuxRegistry;
    private final TerminalCacheCoordinator terminalCache;
    private final ServerConfigStore configStore;
    private final ServerConfigManager serverConfigs;
    private final Handler mainHandler;
    private final OkHttpClient http;

    private final TerminalRuntime.Factory terminalRuntimeFactory;
    private final P2PConnectionManager p2pManager;
    private final RelayService mRelayService;
    private FileDownloadHelper downloadHelper;

    private boolean mInForeground = true;
    private SessionCommandController mSessionCommands;
    private TerminalRuntimeRegistry mTerminalRuntimeRegistry;
    private TerminalRuntime mTerminalRuntime;
    // Auth refresh is asynchronous. Bind callbacks to the terminal that requested
    // them so a response from a terminal the user has already left cannot reconnect
    // the terminal currently on screen with the wrong server cookie.
    private int mTerminalReconnectGeneration;
    private boolean mTerminalReconnectInFlight;
    private TerminalReconnectTarget mTerminalReconnectInFlightTarget;

    private ScreenMode mScreenMode = ScreenMode.DEVICES;
    private ServerConfig mSelectedServer;

    private int mImeOverlap;
    private RelayUiState mRelayUiState;
    private NetworkRecoveryController mNetworkRecoveryController;
    private TextView mHomeSubtitle;
    private NavController mNavController;
    private HomeFragment mHomeFragment;
    private MainActivity mActivity;

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
        RelayMuxSessionRegistry relayMuxRegistry,
        TerminalCacheCoordinator terminalCache,
        ServerConfigStore configStore,
        ServerConfigManager serverConfigs,
        Handler mainHandler,
        OkHttpClient http,
        TerminalRuntime.Factory terminalRuntimeFactory,
        P2PConnectionManager p2pManager,
        RelayService relayService
    ) {
        this.api = api;
        this.relayMuxRegistry = relayMuxRegistry;
        this.terminalCache = terminalCache;
        this.configStore = configStore;
        this.serverConfigs = serverConfigs;
        this.mainHandler = mainHandler;
        this.http = http;
        this.terminalRuntimeFactory = terminalRuntimeFactory;
        this.p2pManager = p2pManager;
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
            TerminalConnection connection = currentConnection();
            if (mScreenMode == ScreenMode.TERMINAL && hasTerminalSession() && connection != null) {
                TerminalConnection.State s = connection.getState();
                if (s == TerminalConnection.State.DISCONNECTED || s == TerminalConnection.State.RECONNECTING) {
                    requestTerminalFreshReconnect();
                }
            }
        });
        mRelayService.setHost(this);
        mRelayUiState = new RelayUiState(mRelayService, this);
        mSessionCommands = new SessionCommandController(mActivity, api, new SessionCommandController.Listener() {
            @Override
            public void onAuthenticated(ServerConfig server) { saveServers(); }
            @Override
            public void onOpenTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String sessionName, boolean isRelayDevice, String relayDeviceId) {
                showTerminal(baseUrl, cookie, sessionId, termTitle, sessionName, "", "", isRelayDevice, relayDeviceId, "");
            }
            @Override
            public void onRemoveCachedTerminal(String baseUrl, String sessionId) {
                removeCachedTerminal(baseUrl, sessionId);
            }
            @Override public void onSessionClosed(ServerConfig server, String sessionId) { }
            @Override
            public void onShowHome() { showSessionListOrDeviceHome(); }
        });
        mTerminalRuntimeRegistry = new TerminalRuntimeRegistry(mActivity, terminalRuntimeFactory, mSessionCommands);
        p2pManager.setListener(new P2PConnectionManager.Listener() {
            @Override public void onConnecting(String deviceId) { }
            @Override public void onConnected(String deviceId) {
                relayMuxRegistry.reconnectDevice(deviceId, "p2p connected");
            }
            @Override public void onDisconnected(String deviceId, String reason) {
                if (isP2pDisabledReason(reason)) {
                    // P2P is administratively disabled on the server. The mux is
                    // already running over relay websocket; do not trigger a reconnect.
                    return;
                }
                relayMuxRegistry.reconnectDevice(deviceId, "p2p disconnected: " + reason);
            }
            @Override public void onError(String deviceId, String message) { }
        });
        loadServersFromPrefs();
    }

    public void onResume() {
        mInForeground = true;

        mHomeFragment = findHomeFragment();

        // 回到前台时，如果在主页列表，自动重新开启/拉取中转设备
        if (!hasTerminalSession() && mScreenMode == ScreenMode.DEVICES) {
            mRelayService.start();
        }

        // 主动同步一次最新的中转设备数据到列表上
        if (mHomeFragment != null) {
            mHomeFragment.refreshDevices();
        }

        TerminalConnection connection = currentConnection();
        if (hasTerminalSession() && hasActiveTerminal() && connection != null) {
            TerminalConnection.State s = connection.getState();
            if (s == TerminalConnection.State.RECONNECTING) {
                requestTerminalFreshReconnect();
            } else if (s == TerminalConnection.State.DISCONNECTED) {
                requestTerminalFreshReconnect();
            }
        }
        mNetworkRecoveryController.register();
    }

    public void onPause() {
        if (mNetworkRecoveryController != null) mNetworkRecoveryController.unregister();
        mInForeground = false;
        if (!hasTerminalSession()) {
            mRelayService.stop();
        } else {
            if (mTerminalRuntimeRegistry != null) mTerminalRuntimeRegistry.pauseAllForBackground();
        }
    }

    public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        mRelayService.stop();
        if (mTerminalRuntimeRegistry != null) mTerminalRuntimeRegistry.shutdown();
        if (p2pManager != null) p2pManager.disconnect();
        terminalCache.shutdown(null);
        relayMuxRegistry.shutdown();
        http.dispatcher().cancelAll();
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
    public P2PConnectionManager getP2PManager() { return p2pManager; }
    public SessionCommandController getSessionCommands() { return mSessionCommands; }
    public RelayMuxSessionRegistry getRelayMuxRegistry() { return relayMuxRegistry; }
    public TerminalCacheCoordinator getTerminalCache() { return terminalCache; }
    public TerminalConnection getTerminalConnection() { return currentConnection(); }
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
                                   String termTitle, String sessionName,
                                   String createdAt, String instanceId,
                                   boolean relayDevice, String relayDeviceId, String cwd) {
        Bundle args = new Bundle();
        args.putString("baseUrl", baseUrl);
        args.putString("cookie", cookie);
        args.putString("sessionId", sessionId);
        args.putString("termTitle", termTitle);
        args.putString("sessionName", sessionName);
        args.putString("createdAt", createdAt != null ? createdAt : "");
        args.putString("instanceId", instanceId != null ? instanceId : "");
        args.putBoolean("relayDevice", relayDevice);
        args.putString("relayDeviceId", relayDeviceId != null ? relayDeviceId : "");
        args.putString("cwd", cwd != null ? cwd : "");
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
        return mTerminalRuntime != null && mTerminalRuntime.hasSession();
    }

    private boolean hasActiveTerminal() {
        return mTerminalRuntime != null && mTerminalRuntime.hasActiveTerminal();
    }

    private TerminalConnection currentConnection() {
        return mTerminalRuntime == null ? null : mTerminalRuntime.connection();
    }

    private TerminalSession currentTerminalSession() {
        return mTerminalRuntime == null ? null : mTerminalRuntime.terminalSession();
    }

    private void detachCurrentTerminalView() {
        if (mTerminalRuntime != null) mTerminalRuntime.detachView();
    }

    // ── UI helpers ───────────────────────────────────────────────────

    public void installRootInsets(Activity activity, View root, int baseLeft, int baseTop, int baseRight,
                                  int baseBottom, boolean avoidImeWithPadding,
                                  boolean includeStatusBar) {
        TerminalWindowInsetsController.installRootInsets(activity, root, baseLeft, baseTop,
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
        if (p2pManager != null) p2pManager.disconnect();
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
        showTerminal(baseUrl, cookie, sessionId, "Terminal", "", "", "", false, "", "");
    }
    void showTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String sessionName) {
        showTerminal(baseUrl, cookie, sessionId, termTitle, sessionName, "", "", false, "", "");
    }
    void showTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String sessionName, String createdAt) {
        showTerminal(baseUrl, cookie, sessionId, termTitle, sessionName, createdAt, "", false, "", "");
    }
    void showTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String sessionName,
                      String createdAt, String instanceId, boolean relayDevice) {
        showTerminal(baseUrl, cookie, sessionId, termTitle, sessionName, createdAt, instanceId, relayDevice, "", "");
    }
    void showTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String sessionName,
                      String createdAt, String instanceId, boolean relayDevice, String relayDeviceId, String cwd) {
        navigateToTerminal(baseUrl, cookie, sessionId, termTitle, sessionName, createdAt, instanceId,
            relayDevice, relayDeviceId, cwd);
    }

    // ── TerminalHost ─────────────────────────────────────────────────

    public void startTerminalInFragment(Activity activity, TerminalViewModel.TerminalSessionArgs args,
                                          TerminalFragment fragment) {
        startTerminalInFragment(activity, args.baseUrl, args.cookie, args.sessionId,
            args.termTitle, args.sessionName, args.createdAt, args.instanceId,
            args.relayDevice, args.relayDeviceId, args.cwd, fragment);
    }

    public void detachTerminalFragment(TerminalFragment fragment) {
        TerminalRuntime runtime = fragment.getRuntime();
        if (runtime != null) runtime.detachView();
    }

    public void startTerminalInFragment(Activity activity, String baseUrl, String cookie, String sessionId,
                                         String termTitle, String sessionName,
                                         String createdAt, String instanceId,
                                         boolean relayDevice, String relayDeviceId, String cwd,
                                         TerminalFragment fragment) {
        mScreenMode = ScreenMode.TERMINAL;
        TerminalViewModel.TerminalSessionArgs args = new TerminalViewModel.TerminalSessionArgs(
            baseUrl, cookie, sessionId, termTitle, sessionName, createdAt, instanceId,
            relayDevice, relayDeviceId, cwd
        );
        if (mTerminalRuntimeRegistry == null) {
            mTerminalRuntimeRegistry = new TerminalRuntimeRegistry(activity, terminalRuntimeFactory, mSessionCommands);
        }
        mTerminalRuntime = mTerminalRuntimeRegistry.getOrCreate(args);
        fragment.setRuntime(mTerminalRuntime);

        TerminalRuntime.ViewHost fragmentHost = new TerminalRuntime.ViewHost() {
            @Override public int getSavedFontSize() { return configStore.getFontSize(); }
            @Override public String getSavedFontType() { return configStore.getFontType(); }
            @Override public Typeface getTypefaceByName(String type) { return AppFlowCoordinator.this.getTypefaceByName(type); }
            @Override public void installTerminalInsets(View root) {
                TerminalWindowInsetsController.installRootInsets(activity, root, 0, 0, 0, 0, false, true, (imeOverlap) -> {
                    mImeOverlap = imeOverlap;
                    AppFlowCoordinator.this.updateKeyboardAvoidance();
                });
            }
            @Override public void setContentRoot(View root) {
                fragment.setTerminalContent(root);
            }
            @Override public void updateKeyboardAvoidance() {
                TerminalWindowInsetsController.updateKeyboardAvoidance(activity,
                    mTerminalRuntime.terminalRoot(), mTerminalRuntime.terminalViewport(),
                    mTerminalRuntime.quickBar(), mTerminalRuntime.terminalView(), mImeOverlap);
            }
            @Override public void requestTerminalReconnect() {
                AppFlowCoordinator.this.requestTerminalFreshReconnect();
            }
        };

        mTerminalRuntime.attach(args, fragmentHost, this::showSessionListOrDeviceHome);
        mTerminalRuntime.setHookListener(new TerminalRuntime.HookListener() {
            @Override public void onHook(JSONObject ev) {
                fragment.showHookNotification(ev);
            }
            @Override public void onDownloadHook(String downloadId, String fileName, long fileSize, String sessionId) {
                startFileDownload(downloadId, fileName, fileSize, sessionId);
            }
        });
    }

    // ── HomeHost ─────────────────────────────────────────────────────

    public void showTerminal(Activity activity, ServerConfig server, String sessionId, String termTitle,
                             String sessionName, String createdAt, String instanceId, String cwd) {
        openSession(activity, server, sessionId, termTitle, sessionName, createdAt, instanceId, cwd);
    }

    public void openSession(Activity activity, ServerConfig server, String sessionId, String termTitle, String sessionName,
                            String createdAt, String instanceId, String cwd) {
        mSelectedServer = server;
        showTerminal(server.getUrl(), server.getCookie(), sessionId, termTitle, sessionName, createdAt, instanceId, server.isRelayDevice(), server.getDeviceId(), cwd);
    }

    private void startFileDownload(String downloadId, String fileName, long fileSize, String sessionId) {
        if (mSelectedServer == null) {
            Toast.makeText(mActivity, "下载失败：未选择服务器", Toast.LENGTH_SHORT).show();
            return;
        }
        TerminalConnection connection = currentConnection();
        downloadHelper.startDownload(mSelectedServer, downloadId, fileName, fileSize, sessionId, connection);
    }

    private void updateCurrentSessionCwd(ServerConfig server, String sessionId, String cwd) {
        if (server == null || sessionId == null || sessionId.isEmpty()) return;
        if (mTerminalRuntime == null) return;
        if (!WebTermUrls.normalizeBaseUrl(server.getUrl()).equals(WebTermUrls.normalizeBaseUrl(mTerminalRuntime.state().baseUrl()))) return;
        if (!sameTerminalSessionId(sessionId, mTerminalRuntime.state().sessionId(), server.getDeviceId())) return;
        mTerminalRuntime.state().setCwd(cwd);
    }

    private void requestTerminalFreshReconnect() {
        if (mTerminalRuntime == null || !mTerminalRuntime.state().hasSession()) return;
        TerminalReconnectTarget target = TerminalReconnectTarget.capture(mTerminalRuntime);
        if (target == null) return;
        if (mTerminalReconnectInFlight && target.equals(mTerminalReconnectInFlightTarget)) return;
        int generation = ++mTerminalReconnectGeneration;
        ServerConfig server = findServerForRuntime();
        if (server == null) {
            mTerminalReconnectInFlight = false;
            mTerminalReconnectInFlightTarget = null;
            if (target.matches(mTerminalRuntime)) {
                mTerminalRuntime.reconnectFresh(mTerminalRuntime.state().cookie());
            }
            return;
        }
        mTerminalReconnectInFlight = true;
        mTerminalReconnectInFlightTarget = target;
        String currentCookie = server.getCookie() != null ? server.getCookie() : mTerminalRuntime.state().cookie();
        if (currentCookie == null || currentCookie.isEmpty()) {
            loginAndReconnectTerminal(server, target, generation);
            return;
        }
        api.refresh(server.getUrl(), currentCookie, new WebTermApi.LoginCallback() {
            @Override public void onReady(String baseUrl, String cookie) {
                mActivity.runOnUiThread(() -> reconnectTerminalWithCookie(server, cookie, target, generation));
            }

            @Override public void onError(String message) {
                mActivity.runOnUiThread(() -> {
                    if (isCurrentTerminalReconnect(target, generation)) {
                        loginAndReconnectTerminal(server, target, generation);
                    }
                });
            }
        });
    }

    private void loginAndReconnectTerminal(ServerConfig server, TerminalReconnectTarget target, int generation) {
        if (!isCurrentTerminalReconnect(target, generation)) return;
        if (server == null || server.getPassword() == null || server.getPassword().isEmpty()) {
            clearTerminalReconnectInFlight(target, generation);
            if (target.matches(mTerminalRuntime)) {
                mTerminalRuntime.reconnectFresh(mTerminalRuntime.state().cookie());
            }
            return;
        }
        api.login(server.getUrl(), server.getCookie(), server.getUsername(), server.getPassword(), new WebTermApi.LoginCallback() {
            @Override public void onReady(String baseUrl, String cookie) {
                mActivity.runOnUiThread(() -> reconnectTerminalWithCookie(server, cookie, target, generation));
            }

            @Override public void onError(String message) {
                mActivity.runOnUiThread(() -> {
                    if (!isCurrentTerminalReconnect(target, generation)) return;
                    clearTerminalReconnectInFlight(target, generation);
                    if (target.matches(mTerminalRuntime)) {
                        mTerminalRuntime.appendOutput("\r\n[重连鉴权失败: " + message + "]\r\n");
                    }
                });
            }
        });
    }

    private void reconnectTerminalWithCookie(ServerConfig server, String cookie, TerminalReconnectTarget target, int generation) {
        if (!isCurrentTerminalReconnect(target, generation)) return;
        clearTerminalReconnectInFlight(target, generation);
        if (server != null && cookie != null && !cookie.isEmpty()) {
            server.setCookie(cookie);
            saveServers();
        }
        if (target.matches(mTerminalRuntime)) {
            mTerminalRuntime.reconnectFresh(cookie);
        }
    }

    private boolean isCurrentTerminalReconnect(TerminalReconnectTarget target, int generation) {
        return generation == mTerminalReconnectGeneration
            && mTerminalReconnectInFlight
            && target != null
            && target.equals(mTerminalReconnectInFlightTarget)
            && target.matches(mTerminalRuntime);
    }

    private void clearTerminalReconnectInFlight(TerminalReconnectTarget target, int generation) {
        if (generation != mTerminalReconnectGeneration || !target.equals(mTerminalReconnectInFlightTarget)) return;
        mTerminalReconnectInFlight = false;
        mTerminalReconnectInFlightTarget = null;
    }

    private static final class TerminalReconnectTarget {
        final String baseUrl;
        final String relayDeviceId;
        final String sessionId;

        private TerminalReconnectTarget(String baseUrl, String relayDeviceId, String sessionId) {
            this.baseUrl = baseUrl;
            this.relayDeviceId = relayDeviceId;
            this.sessionId = sessionId;
        }

        static TerminalReconnectTarget capture(TerminalRuntime runtime) {
            if (runtime == null || !runtime.state().hasSession()) return null;
            String sessionId = runtime.state().sessionId();
            if (sessionId == null || sessionId.isEmpty()) return null;
            return new TerminalReconnectTarget(
                WebTermUrls.normalizeBaseUrl(runtime.state().baseUrl()),
                runtime.state().relayDeviceId() == null ? "" : runtime.state().relayDeviceId(),
                sessionId
            );
        }

        boolean matches(TerminalRuntime runtime) {
            if (runtime == null || !runtime.state().hasSession()) return false;
            return baseUrl.equals(WebTermUrls.normalizeBaseUrl(runtime.state().baseUrl()))
                && relayDeviceId.equals(runtime.state().relayDeviceId() == null ? "" : runtime.state().relayDeviceId())
                && sessionId.equals(runtime.state().sessionId());
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof TerminalReconnectTarget)) return false;
            TerminalReconnectTarget that = (TerminalReconnectTarget) other;
            return baseUrl.equals(that.baseUrl)
                && relayDeviceId.equals(that.relayDeviceId)
                && sessionId.equals(that.sessionId);
        }

        @Override public int hashCode() {
            int result = baseUrl.hashCode();
            result = 31 * result + relayDeviceId.hashCode();
            return 31 * result + sessionId.hashCode();
        }
    }

    private ServerConfig findServerForRuntime() {
        if (mTerminalRuntime == null || !mTerminalRuntime.state().hasSession()) return null;
        String baseUrl = WebTermUrls.normalizeBaseUrl(mTerminalRuntime.state().baseUrl());
        String relayDeviceId = mTerminalRuntime.state().relayDeviceId() == null ? "" : mTerminalRuntime.state().relayDeviceId();
        if (mSelectedServer != null
            && WebTermUrls.normalizeBaseUrl(mSelectedServer.getUrl()).equals(baseUrl)
            && relayDeviceId.equals(mSelectedServer.getDeviceId() == null ? "" : mSelectedServer.getDeviceId())) {
            return mSelectedServer;
        }
        for (ServerConfig server : serverConfigs.servers()) {
            String deviceId = server.getDeviceId() == null ? "" : server.getDeviceId();
            if (WebTermUrls.normalizeBaseUrl(server.getUrl()).equals(baseUrl) && relayDeviceId.equals(deviceId)) {
                return server;
            }
        }
        return null;
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
    public boolean isP2PEnabled() { return configStore.isP2PEnabled(); }
    public void saveP2PEnabled(boolean enabled) { configStore.saveP2PEnabled(enabled); }

    private static boolean isP2pDisabledReason(String reason) {
        if (reason == null) return false;
        String lower = reason.toLowerCase(java.util.Locale.US);
        return lower.contains("p2p disabled") || lower.contains("503");
    }

    public Typeface getTypefaceByName(String type) {
        if ("sans-serif".equals(type)) return Typeface.SANS_SERIF;
        if ("serif".equals(type)) return Typeface.SERIF;
        if ("default".equals(type)) return Typeface.DEFAULT;
        return Typeface.MONOSPACE;
    }

    public void updateKeyboardAvoidance() {
        if (mTerminalRuntime == null) return;
        TerminalWindowInsetsController.updateKeyboardAvoidance(mActivity,
            mTerminalRuntime.terminalRoot(), mTerminalRuntime.terminalViewport(),
            mTerminalRuntime.quickBar(), mTerminalRuntime.terminalView(), mImeOverlap);
    }

    // ── SessionRowActions (not implemented by AppFlowCoordinator directly; handled via MainActivity) ──────────────────────────────────────────

    public void renameSession(ServerConfig server, String sessionId, String oldName) {
        if (mSessionCommands != null) mSessionCommands.showRenameDialog(server, sessionId, oldName);
    }
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
        if (mTerminalRuntimeRegistry != null) {
            mTerminalRuntimeRegistry.disposeServer(server);
            if (!mTerminalRuntimeRegistry.contains(mTerminalRuntime)) {
                mTerminalRuntime = null;
            }
        }
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
        if (mTerminalRuntime != null) mTerminalRuntime.updateFontSize(size);
    }
    @Override
    public void applyTerminalTypeface(Typeface typeface) {
        if (mTerminalRuntime != null) mTerminalRuntime.updateTypeface(typeface);
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
        String currentBaseUrl = mTerminalRuntime == null ? null : mTerminalRuntime.state().baseUrl();
        String currentSessionId = mTerminalRuntime == null ? null : mTerminalRuntime.state().sessionId();
        if (terminalCache.removeTerminal(baseUrl, sessionId, currentBaseUrl, currentSessionId,
            currentTerminalSession())) {
            if (mTerminalRuntime != null) mTerminalRuntime.state().clearPersistence();
        }
    }

    public void removeMissingCachedSessionsForServer(ServerConfig server, java.util.Set<String> liveSessionIdentities) {
        terminalCache.removeMissingForServer(server, liveSessionIdentities, currentTerminalSession());
    }

    private void removeCachedTerminalsForServer(ServerConfig server) {
        terminalCache.removeServer(server, currentTerminalSession());
    }

    private void disposeRuntimeForSession(ServerConfig server, String sessionId) {
        if (mTerminalRuntimeRegistry == null) return;
        TerminalRuntime runtime = mTerminalRuntimeRegistry.find(server, sessionId);
        if (runtime == null) return;
        mTerminalRuntimeRegistry.dispose(runtime);
        if (runtime == mTerminalRuntime) {
            mTerminalRuntime = null;
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
