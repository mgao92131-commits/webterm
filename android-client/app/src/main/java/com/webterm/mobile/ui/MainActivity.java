package com.webterm.mobile.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.webterm.mobile.CrashReporter;
import com.webterm.mobile.R;
import com.webterm.core.api.WebTermApi;
import com.webterm.core.api.WebTermUrls;
import com.webterm.core.cache.TerminalCacheCoordinator;
import com.webterm.core.config.ServerConfig;
import com.webterm.core.config.ServerConfigManager;
import com.webterm.core.config.ServerConfigStore;
import com.webterm.mobile.domain.command.SessionCommandController;
import com.webterm.core.relay.RelayService;
import com.webterm.mobile.domain.server.HomeServerCoordinator;
import com.webterm.core.session.RelayMuxSessionManager;
import com.webterm.core.session.RelayMuxSessionRegistry;
import com.webterm.mobile.domain.terminal.TerminalClipboardController;
import com.webterm.mobile.domain.terminal.TerminalConnection;
import com.webterm.mobile.domain.terminal.TerminalLifecycleController;
import com.webterm.mobile.domain.terminal.TerminalRuntimeState;
import com.webterm.mobile.domain.terminal.TerminalTitleSynchronizer;
import com.webterm.mobile.recovery.NetworkRecoveryController;
import com.webterm.transport.webrtc.P2PConnectionManager;
import com.webterm.mobile.ui.common.DesignTokens;
import com.webterm.mobile.ui.common.PageTransitionAnimator;
import com.webterm.mobile.ui.common.UIUtils;
import com.webterm.mobile.ui.dialog.ServerConfigDialogHelper;
import com.webterm.mobile.ui.dialog.SettingsDialogHelper;
import com.webterm.mobile.ui.home.HomeFragment;
import com.webterm.mobile.ui.home.HomeScreenBuilder;
import com.webterm.mobile.ui.home.SessionRecyclerAdapter;
import com.webterm.mobile.ui.home.SessionRowActions;
import com.webterm.mobile.ui.home.StatusIndicatorView;
import com.webterm.mobile.ui.relay.RelayDevicesScreenBuilder;
import com.webterm.mobile.ui.relay.RelayFragment;
import com.webterm.mobile.ui.relay.RelayLoginScreenBuilder;
import com.webterm.mobile.ui.relay.RelayUiState;
import com.webterm.mobile.ui.terminal.TerminalConnectionStatusView;
import com.webterm.mobile.ui.terminal.TerminalFragment;
import com.webterm.mobile.ui.terminal.TerminalWindowInsetsController;
import com.webterm.mobile.ui.terminal.WebTermTerminalSessionClient;
import com.webterm.mobile.ui.terminal.WebTermTerminalViewClient;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.annotation.Nullable;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import dagger.hilt.android.AndroidEntryPoint;
import okhttp3.OkHttpClient;

import javax.inject.Inject;

@AndroidEntryPoint

public final class MainActivity extends FragmentActivity implements SessionRowActions, TerminalConnection.Listener, WebTermTerminalViewClient.Host, WebTermTerminalSessionClient.Host, ServerConfigDialogHelper.Host, SettingsDialogHelper.Host, RelayService.Host, TerminalLifecycleController.Host, NetworkRecoveryController.Host {

    private static final long BACKGROUND_SERVER_DETACH_MS = 3 * 60 * 1000L;

    private final AtomicBoolean mClosed = new AtomicBoolean(false);
    private final TerminalRuntimeState mTerminalState = new TerminalRuntimeState();
    private final TerminalConnectionStatusView mConnectionStatus = new TerminalConnectionStatusView();

    @Inject WebTermApi api;
    @Inject RelayMuxSessionRegistry relayMuxRegistry;
    @Inject TerminalCacheCoordinator terminalCache;
    @Inject ServerConfigStore configStore;
    @Inject ServerConfigManager serverConfigs;
    @Inject Handler mainHandler;
    @Inject OkHttpClient http;

    @Inject TerminalLifecycleController.Factory terminalLifecycleFactory;
    @Inject HomeServerCoordinator.Factory homeServerFactory;
    @Inject TerminalConnection.Factory terminalConnectionFactory;
    @Inject TerminalClipboardController.Factory terminalClipboardFactory;
    @Inject TerminalTitleSynchronizer.Factory terminalTitleFactory;

    private boolean mInForeground = true;
    private TerminalConnection mTerminalConnection;
    private SessionCommandController mSessionCommands;
    private TerminalTitleSynchronizer mTitleSynchronizer;
    private HomeServerCoordinator mHomeCoordinator;
    private TerminalClipboardController mClipboardController;
    private WebTermTerminalSessionClient mTerminalSessionClient;
    private TerminalLifecycleController mTerminalLifecycle;
    @Inject P2PConnectionManager p2pManager;

    private LinearLayout mSessionList;
    private SessionRecyclerAdapter mSessionAdapter;
    private ScreenMode mScreenMode = ScreenMode.DEVICES;
    private ServerConfig mSelectedServer;
    private StatusIndicatorView mSelectedServerStatus;

    private int mImeOverlap;
    @Inject RelayService mRelayService;
    private RelayUiState mRelayUiState;
    private NetworkRecoveryController mNetworkRecoveryController;
    private TextView mHomeSubtitle;
    private NavController mNavController;
    private HomeFragment mHomeFragment;
    private final Runnable mBackgroundServerDetach = () -> {
        if (!mInForeground && mSelectedServer != null && mHomeCoordinator != null) {
            mHomeCoordinator.detach();
        }
    };

    public enum ScreenMode {
        DEVICES,
        DEVICE_SESSIONS,
        TERMINAL,
        RELAY_LOGIN,
        RELAY_DEVICES
    }

    // ── Public accessors for Fragments ─────────────────────────────

    public RelayService getRelayService() { return mRelayService; }
    public RelayUiState getRelayUiState() { return mRelayUiState; }
    public ServerConfigManager getServerConfigs() { return serverConfigs; }
    public ServerConfigStore getConfigStore() { return configStore; }
    public WebTermApi getApi() { return api; }
    public Handler getMainHandler() { return mainHandler; }
    public P2PConnectionManager getP2PManager() { return p2pManager; }
    public TerminalLifecycleController getTerminalLifecycle() { return mTerminalLifecycle; }
    public HomeServerCoordinator getHomeCoordinator() { return mHomeCoordinator; }
    public SessionCommandController getSessionCommands() { return mSessionCommands; }
    public RelayMuxSessionRegistry getRelayMuxRegistry() { return relayMuxRegistry; }
    public TerminalCacheCoordinator getTerminalCache() { return terminalCache; }
    public TerminalConnection getTerminalConnection() { return mTerminalConnection; }
    public OkHttpClient getHttpClient() { return http; }
    public LinearLayout getSessionList() { return mSessionList; }
    public void setSessionList(LinearLayout list) { mSessionList = list; }
    public void setHomeSubtitle(TextView subtitle) { mHomeSubtitle = subtitle; }
    public ServerConfig getSelectedServer() { return mSelectedServer; }
    public void setSelectedServer(ServerConfig server) { mSelectedServer = server; }
    public StatusIndicatorView getSelectedServerStatus() { return mSelectedServerStatus; }
    public void setSelectedServerStatus(StatusIndicatorView status) { mSelectedServerStatus = status; }
    public SessionRecyclerAdapter getSessionAdapter() { return mSessionAdapter; }
    public void setSessionAdapter(SessionRecyclerAdapter adapter) { mSessionAdapter = adapter; }
    public ScreenMode getScreenMode() { return mScreenMode; }
    public void setScreenMode(ScreenMode mode) { mScreenMode = mode; }
    public boolean isInForeground() { return mInForeground; }
    public AtomicBoolean getClosed() { return mClosed; }
    public TerminalRuntimeState getTerminalState() { return mTerminalState; }
    public NetworkRecoveryController getNetworkRecoveryController() { return mNetworkRecoveryController; }

    // ── Navigation methods for Fragments ───────────────────────────

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

    // ── UI helpers exposed for fragments ───────────────────────────

    public void installRootInsets(View root, int baseLeft, int baseTop, int baseRight,
                                  int baseBottom, boolean avoidImeWithPadding,
                                  boolean includeStatusBar) {
        TerminalWindowInsetsController.installRootInsets(this, root, baseLeft, baseTop,
            baseRight, baseBottom, avoidImeWithPadding, includeStatusBar, (imeOverlap) -> {
                mImeOverlap = imeOverlap;
                updateKeyboardAvoidance();
            });
    }

    public View buildRelayView() {
        if (mRelayService.hasMaster() && mRelayService.masterConfig().getCookie() != null
            && !mRelayService.masterConfig().getCookie().isEmpty()) {
            return buildRelayDevicesView();
        } else {
            return buildRelayLoginView();
        }
    }

    private View buildRelayLoginView() {
        String savedEmail = mRelayService.masterConfig() != null
            ? mRelayService.masterConfig().getUsername() : "";
        RelayLoginScreenBuilder.RelayLoginScreen screen =
            RelayLoginScreenBuilder.buildLogin(mRelayUiState, savedEmail);
        mScreenMode = ScreenMode.RELAY_LOGIN;
        installRootInsets(screen.root, 0, 0, 0, dp(16), true, true);
        return screen.root;
    }

    private View buildRelayDevicesView() {
        RelayDevicesScreenBuilder.RelayDevicesScreen screen =
            RelayDevicesScreenBuilder.build(mRelayUiState);
        mScreenMode = ScreenMode.RELAY_DEVICES;
        installRootInsets(screen.root, 0, 0, 0, dp(16), true, true);
        screen.refresh.run();
        return screen.root;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().getDecorView().setBackgroundColor(DesignTokens.BG_PRIMARY);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }

        // Setup Navigation
        NavHostFragment navHost = (NavHostFragment) getSupportFragmentManager()
            .findFragmentById(R.id.nav_host_fragment);
        if (navHost != null) {
            mNavController = navHost.getNavController();
        }

        mNetworkRecoveryController = new NetworkRecoveryController(this, mainHandler, this);
        mRelayService.setHost(this);
        mRelayUiState = new RelayUiState(mRelayService, this);
        mSessionCommands = new SessionCommandController(this, api, new SessionCommandController.Listener() {
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
            @Override
            public void onSessionClosed(ServerConfig server, String sessionId) {
                handleClosedSessionRow(server, sessionId);
            }
            @Override
            public void onShowHome() { showSessionListOrDeviceHome(); }
        });
        mTerminalConnection = terminalConnectionFactory.create(this);
        p2pManager.setListener(new P2PConnectionManager.Listener() {
            @Override public void onConnecting(String deviceId) {
            }

            @Override public void onConnected(String deviceId) {
                relayMuxRegistry.reconnectDevice(deviceId, "p2p connected");
            }

            @Override public void onDisconnected(String deviceId, String reason) {
                relayMuxRegistry.reconnectDevice(deviceId, "p2p disconnected: " + reason);
            }

            @Override public void onError(String deviceId, String message) {
            }
        });
        mTitleSynchronizer = terminalTitleFactory.create(() -> mTerminalConnection);
        mClipboardController = terminalClipboardFactory.create(this, this);
        mTerminalSessionClient = new WebTermTerminalSessionClient(this, this, mClipboardController, mTitleSynchronizer);
        mTerminalLifecycle = terminalLifecycleFactory.create(
            this, this, mTerminalState, mClosed, mConnectionStatus,
            mTerminalConnection, mTitleSynchronizer, mSessionCommands
        );
        mHomeCoordinator = homeServerFactory.create(this, new HomeServerCoordinator.Listener() {
            @Override public boolean isHomeActive() { return MainActivity.this.isHomeActive(); }
            @Override public boolean isServerContextActive(ServerConfig server) { return MainActivity.this.isServerContextActive(server); }
            @Override public void onAuthenticated(ServerConfig server) { saveServers(); }
            @Override public void onRemoveCachedTerminal(String baseUrl, String sessionId) {
                removeCachedTerminal(baseUrl, sessionId);
            }
            @Override public void onSessionCwdChanged(ServerConfig server, String sessionId, String cwd) {
                updateCurrentSessionCwd(server, sessionId, cwd);
            }
            @Override
            public void onRemoveMissingCachedSessionsForServer(ServerConfig server, java.util.Set<String> liveSessionIdentities) {
                removeMissingCachedSessionsForServer(server, liveSessionIdentities);
            }
        });
        loadServersFromPrefs();

        // Start relay service; HomeFragment will be found in onResume
        mRelayService.start();
    }

    /**
     * Find the HomeFragment managed by NavHostFragment.
     */
    private HomeFragment findHomeFragment() {
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
        if (f instanceof NavHostFragment) {
            Fragment home = ((NavHostFragment) f).getChildFragmentManager()
                .findFragmentById(R.id.homeFragment);
            if (home instanceof HomeFragment) {
                return (HomeFragment) home;
            }
        }
        return null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        mInForeground = true;
        cancelBackgroundServerDetach();

        // Cache HomeFragment reference (may have been recreated)
        if (mHomeFragment == null) {
            mHomeFragment = findHomeFragment();
        }

        if (mTerminalLifecycle.hasSession() && mTerminalLifecycle.hasActiveTerminal() && mTerminalConnection != null) {
            TerminalConnection.State s = mTerminalConnection.getState();
            if (s == TerminalConnection.State.RECONNECTING) {
                mClosed.set(false);
                mTerminalConnection.reconnectNow();
            } else if (s == TerminalConnection.State.DISCONNECTED) {
                mClosed.set(false);
                mTerminalLifecycle.connectTerminal();
            }
        } else if (!mTerminalLifecycle.hasSession() && hasHomeList()) {
            if (mScreenMode == ScreenMode.DEVICE_SESSIONS) {
                loadSelectedDeviceSessions();
            } else {
                mRelayService.start();
            }
        }
        mNetworkRecoveryController.register();
    }

    @Override
    protected void onPause() {
        if (mNetworkRecoveryController != null) mNetworkRecoveryController.unregister();
        mInForeground = false;
        if (!mTerminalLifecycle.hasSession()) {
            if (mScreenMode == ScreenMode.DEVICE_SESSIONS && mHomeCoordinator != null) mHomeCoordinator.pauseUi();
            mRelayService.stop();
        } else {
            mTerminalLifecycle.pauseCurrentConnection();
        }
        scheduleBackgroundServerDetach();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mClosed.set(true);
        mainHandler.removeCallbacksAndMessages(null);
        mRelayService.stop();
        if (mHomeCoordinator != null) mHomeCoordinator.destroy();
        if (mTerminalLifecycle.hasSession() && mTerminalLifecycle.hasActiveTerminal()) {
            mTerminalLifecycle.closeTerminal(false);
        }
        if (mTerminalConnection != null) mTerminalConnection.close("activity closed");
        if (p2pManager != null) p2pManager.disconnect();
        if (mTerminalLifecycle.terminalSession() != null) mTerminalLifecycle.terminalSession().finishIfRunning();
        terminalCache.shutdown(mTerminalLifecycle.terminalSession());
        relayMuxRegistry.shutdown();
        http.dispatcher().cancelAll();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // If showing terminal, go back to home/device-sessions
        if (mTerminalLifecycle.hasSession()) {
            // Pop back to HomeFragment
            if (mNavController != null) {
                mNavController.popBackStack(R.id.homeFragment, false);
            }
            // After popping back, show device sessions if applicable
            final ServerConfig server = mSelectedServer;
            mainHandler.post(() -> {
                if (mHomeFragment == null) mHomeFragment = findHomeFragment();
                if (mHomeFragment != null && server != null) {
                    mHomeFragment.showDeviceSessions(server);
                } else if (mHomeFragment != null) {
                    mHomeFragment.showHomeScreen();
                }
            });
            return;
        }
        // If HomeFragment is showing device sessions, let it handle back
        if (mHomeFragment != null && mHomeFragment.isShowingDeviceSessions()) {
            mHomeFragment.showHomeScreen();
            mScreenMode = ScreenMode.DEVICES;
            mSelectedServer = null;
            mSelectedServerStatus = null;
            mSessionAdapter = null;
            return;
        }
        // If showing relay, go back to home
        if (mScreenMode == ScreenMode.RELAY_LOGIN || mScreenMode == ScreenMode.RELAY_DEVICES) {
            showSessionHome();
            return;
        }
        super.onBackPressed();
    }

    // ── Navigation ────────────────────────────────────────────────

    private void loadServersFromPrefs() {
        serverConfigs.load();
        mRelayService.loadMasterFromServers(serverConfigs.servers());
    }

    public void saveServers() {
        serverConfigs.save();
    }

    public void showSessionHome() {
        mTerminalLifecycle.closeTerminal(false);
        if (p2pManager != null) p2pManager.disconnect();
        if (mHomeCoordinator != null) {
            mHomeCoordinator.detach();
            mHomeCoordinator.attachSessionAdapter(null);
        }
        mClosed.set(false);
        mTerminalState.clearServerSession();
        mScreenMode = ScreenMode.DEVICES;
        mSelectedServer = null;
        mSelectedServerStatus = null;
        mSessionAdapter = null;
        mSessionList = null;

        // Navigate to HomeFragment
        if (mNavController != null) {
            mNavController.popBackStack(R.id.homeFragment, false);
        }
        // Update HomeFragment to show home screen
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

    public void showDeviceSessions(ServerConfig server) {
        if (server == null) { showSessionHome(); return; }
        mTerminalLifecycle.closeTerminal(false);
        mRelayService.stop();
        mClosed.set(false);
        mTerminalState.clearServerSession();
        mScreenMode = ScreenMode.DEVICE_SESSIONS;
        mSelectedServer = server;
        startP2PIfRelayDevice(server.getUrl(), server.getCookie(), server.isRelayDevice(), server.getDeviceId());
        mHomeSubtitle = null;
        mSessionList = null;

        // Delegate to HomeFragment
        if (mHomeFragment != null) {
            mHomeFragment.showDeviceSessions(server);
        }
    }

    private void loadSelectedDeviceSessions() {
        if (mHomeCoordinator != null && mSelectedServer != null && mSelectedServerStatus != null) {
            mHomeCoordinator.loadDeviceSessions(mSelectedServer, mSelectedServerStatus);
        }
    }

    private void handleClosedSessionRow(ServerConfig server, String sessionId) {
        if (mScreenMode != ScreenMode.DEVICE_SESSIONS || !isSelectedServer(server) || mSessionAdapter == null) return;
        mSessionAdapter.removeSession(sessionId);
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

    private boolean hasHomeList() {
        return mScreenMode == ScreenMode.DEVICE_SESSIONS ? mSessionAdapter != null : mSessionList != null;
    }

    private void showSessionListOrDeviceHome() {
        if (mSelectedServer != null) {
            showDeviceSessions(mSelectedServer);
        } else {
            showSessionHome();
        }
    }

    private boolean isHomeActive() {
        return mInForeground && !mClosed.get() && !mTerminalLifecycle.hasSession()
            && mScreenMode == ScreenMode.DEVICE_SESSIONS && mSessionAdapter != null;
    }

    private boolean isServerContextActive(ServerConfig server) {
        if (mClosed.get() || server == null || mSelectedServer == null) return false;
        if (mScreenMode != ScreenMode.DEVICE_SESSIONS && mScreenMode != ScreenMode.TERMINAL) return false;
        return isSelectedServer(server);
    }

    private void scheduleBackgroundServerDetach() {
        if (mSelectedServer == null) return;
        mainHandler.removeCallbacks(mBackgroundServerDetach);
        mainHandler.postDelayed(mBackgroundServerDetach, BACKGROUND_SERVER_DETACH_MS);
    }

    private void cancelBackgroundServerDetach() {
        mainHandler.removeCallbacks(mBackgroundServerDetach);
    }

    public void showAddServerDialog(final ServerConfig existingServer) {
        ServerConfigDialogHelper.show(this, existingServer);
    }

    public void confirmRemoveServer(ServerConfig server) {
        AlertDialog dialog = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
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

    // ── Terminal ───────────────────────────────────────────────────

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

    /**
     * Called by TerminalFragment when it's ready to start the terminal.
     */
    public void startTerminalInFragment(String baseUrl, String cookie, String sessionId,
                                         String termTitle, String sessionName,
                                         String createdAt, String instanceId,
                                         boolean relayDevice, String relayDeviceId, String cwd,
                                         TerminalFragment fragment) {
        if (mHomeCoordinator != null) mHomeCoordinator.pauseUi();
        mScreenMode = ScreenMode.TERMINAL;

        // Override setContentRoot to update the fragment's view
        TerminalLifecycleController.Host fragmentHost = new TerminalLifecycleController.Host() {
            @Override public int getSavedFontSize() { return configStore.getFontSize(); }
            @Override public String getSavedFontType() { return configStore.getFontType(); }
            @Override public Typeface getTypefaceByName(String type) { return MainActivity.this.getTypefaceByName(type); }
            @Override public void installTerminalInsets(View root) {
                TerminalWindowInsetsController.installRootInsets(MainActivity.this, root, 0, 0, 0, 0, false, true, (imeOverlap) -> {
                    mImeOverlap = imeOverlap;
                    updateKeyboardAvoidance();
                });
            }
            @Override public void setContentRoot(View root) {
                fragment.setTerminalContent(root);
            }
            @Override public void updateKeyboardAvoidance() {
                TerminalWindowInsetsController.updateKeyboardAvoidance(MainActivity.this,
                    mTerminalLifecycle.terminalRoot(), mTerminalLifecycle.terminalViewport(),
                    mTerminalLifecycle.quickBar(), mTerminalLifecycle.terminalView(), mImeOverlap);
            }
        };

        // Create a new TerminalLifecycleController for this fragment context
        TerminalLifecycleController fragController = terminalLifecycleFactory.create(
            this, fragmentHost, mTerminalState, mClosed, mConnectionStatus,
            mTerminalConnection, mTitleSynchronizer, mSessionCommands
        );
        mTerminalLifecycle = fragController;

        fragController.showTerminal(
            baseUrl, cookie, sessionId, termTitle, sessionName, createdAt, instanceId, relayDeviceId, cwd,
            this, mTerminalSessionClient,
            this::showSessionListOrDeviceHome
        );
    }

    @Override
    public void openSession(ServerConfig server, String sessionId, String termTitle, String sessionName,
                            String createdAt, String instanceId, String cwd) {
        mSelectedServer = server;
        showTerminal(server.getUrl(), server.getCookie(), sessionId, termTitle, sessionName, createdAt, instanceId, server.isRelayDevice(), server.getDeviceId(), cwd);
    }

    private void updateCurrentSessionCwd(ServerConfig server, String sessionId, String cwd) {
        if (server == null || sessionId == null || sessionId.isEmpty()) return;
        if (!WebTermUrls.normalizeBaseUrl(server.getUrl()).equals(WebTermUrls.normalizeBaseUrl(mTerminalState.baseUrl()))) return;
        if (!sameTerminalSessionId(sessionId, mTerminalState.sessionId(), server.getDeviceId())) return;
        mTerminalState.setCwd(cwd);
    }

    private static boolean sameTerminalSessionId(String a, String b, String deviceId) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        return RelayMuxSessionManager.localSessionId(a, deviceId)
            .equals(RelayMuxSessionManager.localSessionId(b, deviceId));
    }

    private void startP2PIfRelayDevice(String baseUrl, String cookie, boolean relayDevice, String relayDeviceId) {
        if (!relayDevice || relayDeviceId == null || relayDeviceId.isEmpty()) return;
        if (p2pManager == null) return;
        if (!configStore.isP2PEnabled()) return;
        if (mSelectedServer != null
            && relayDeviceId.equals(mSelectedServer.getDeviceId())
            && !mSelectedServer.isP2PEnabled()) {
            return;
        }
        p2pManager.connectToDevice(baseUrl, cookie, relayDeviceId);
    }

    // ── TerminalLifecycleController.Host ───────────────────────────

    @Override
    public int getSavedFontSize() { return configStore.getFontSize(); }
    @Override
    public String getSavedFontType() { return configStore.getFontType(); }
    @Override
    public boolean isP2PEnabled() { return configStore.isP2PEnabled(); }
    @Override
    public void saveP2PEnabled(boolean enabled) { configStore.saveP2PEnabled(enabled); }

    @Override
    public Typeface getTypefaceByName(String type) {
        if ("sans-serif".equals(type)) return Typeface.SANS_SERIF;
        if ("serif".equals(type)) return Typeface.SERIF;
        if ("default".equals(type)) return Typeface.DEFAULT;
        return Typeface.MONOSPACE;
    }

    @Override
    public void installTerminalInsets(View root) {
        TerminalWindowInsetsController.installRootInsets(this, root, 0, 0, 0, 0, false, true, (imeOverlap) -> {
            mImeOverlap = imeOverlap;
            updateKeyboardAvoidance();
        });
    }

    @Override
    public void setContentRoot(View root) {
        // Default implementation: used when terminal is not in a fragment
        // (should not happen after fragment refactor)
        PageTransitionAnimator.animate(this, root, PageTransitionAnimator.Transition.FORWARD);
    }

    @Override
    public void updateKeyboardAvoidance() {
        TerminalWindowInsetsController.updateKeyboardAvoidance(this,
            mTerminalLifecycle.terminalRoot(), mTerminalLifecycle.terminalViewport(),
            mTerminalLifecycle.quickBar(), mTerminalLifecycle.terminalView(), mImeOverlap);
    }

    // ── WebTermTerminalViewClient.Host ─────────────────────────────

    @Override
    public void onTerminalViewTapped() { mTerminalLifecycle.showKeyboard(); }
    @Override
    public boolean readTerminalControlKey() { return mTerminalLifecycle.readCtrlKey(); }
    @Override
    public void clearTerminalControlKey() { mTerminalLifecycle.setCtrlKey(false); }

    // ── WebTermTerminalSessionClient.Host ──────────────────────────

    @Override
    public void onTerminalInput(String data) {
        if (mTerminalConnection != null) mTerminalConnection.sendInput(data);
    }
    @Override
    public void onTerminalResize(int columns, int rows) {
        mTerminalLifecycle.onTerminalResize(columns, rows);
    }
    @Override
    public void onTerminalTextChanged() { mTerminalLifecycle.onTerminalTextChanged(); }
    @Override
    public TextView terminalTitleView() { return mTerminalLifecycle.terminalTitleView(); }

    // ── TerminalConnection.Listener ─────────────────────────────────

    @Override
    public void onConnectionStatus(TerminalConnection.State state, int reconnectAttempts) {
        boolean isP2P = mTerminalConnection != null && mTerminalConnection.isP2PConnected();
        runOnUiThread(() -> mConnectionStatus.update(state, reconnectAttempts, isP2P));
    }
    @Override
    public void onOutput(long seq, byte[] data) { mTerminalLifecycle.onOutput(seq, data); }
    @Override
    public void onState(long seq, byte[] data) { mTerminalLifecycle.onState(seq, data); }
    @Override
    public void onInfo(JSONObject info) { mTerminalLifecycle.onInfo(info); }
    @Override
    public void onExit(int code) { mTerminalLifecycle.onExit(code); }
    @Override
    public void onProtocolError(String message) {
        mTerminalLifecycle.appendOutput("\r\n[" + message + "]\r\n");
    }

    // ── SessionRowActions ──────────────────────────────────────────

    @Override
    public void renameSession(ServerConfig server, String sessionId, String oldName) {
        if (mSessionCommands != null) mSessionCommands.showRenameDialog(server, sessionId, oldName);
    }
    @Override
    public void closeSession(ServerConfig server, String sessionId) {
        if (mSessionCommands != null) mSessionCommands.showCloseConfirmDialog(server, sessionId);
    }

    // ── ServerConfigDialogHelper.Host ──────────────────────────────

    @Override
    public Activity activity() { return this; }

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
            showDeviceSessions(existingServer);
        } else {
            showSessionHome();
        }
    }

    // ── RelayService.Host ──────────────────────────────────────

    @Override
    public void onRelayDevicesChanged() {
        if (mHomeFragment != null) {
            // Refresh the home screen if it's showing
            mHomeFragment.showHomeScreen();
        }
    }
    @Override
    public void onRelayAuthDone() {
        if (mScreenMode == ScreenMode.RELAY_LOGIN
            && mRelayService.hasMaster()
            && mRelayService.masterConfig().getCookie() != null
            && !mRelayService.masterConfig().getCookie().isEmpty()) {
            // Refresh relay fragment
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
        if (mTerminalLifecycle.terminalView() != null) mTerminalLifecycle.terminalView().setTextSize(size);
    }
    @Override
    public void applyTerminalTypeface(Typeface typeface) {
        if (mTerminalLifecycle.terminalView() != null) mTerminalLifecycle.terminalView().setTypeface(typeface);
    }

    // ── Helpers ────────────────────────────────────────────────────

    public void showSettingsDialog() { SettingsDialogHelper.show(this); }

    public void shareLatestCrashLog() {
        String crashLog = CrashReporter.readLatestCrash(this);
        if (crashLog == null || crashLog.trim().isEmpty()) {
            Toast.makeText(this, "暂无崩溃日志", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "WebTerm 崩溃日志");
        send.putExtra(Intent.EXTRA_TEXT, crashLog);
        startActivity(Intent.createChooser(send, "导出崩溃日志"));
    }

    private void removeCachedTerminal(String baseUrl, String sessionId) {
        if (terminalCache.removeTerminal(baseUrl, sessionId, mTerminalState.baseUrl(), mTerminalState.sessionId(),
            mTerminalLifecycle.terminalSession())) {
            mTerminalState.clearPersistence();
        }
    }

    private void removeMissingCachedSessionsForServer(ServerConfig server, java.util.Set<String> liveSessionIdentities) {
        terminalCache.removeMissingForServer(server, liveSessionIdentities, mTerminalLifecycle.terminalSession());
    }

    private void removeCachedTerminalsForServer(ServerConfig server) {
        terminalCache.removeServer(server, mTerminalLifecycle.terminalSession());
    }

    // ── Animation ──────────────────────────────────────────────────

    int dp(int value) {
        return PageTransitionAnimator.dp(this, value);
    }

    @Override
    public void onNetworkAvailableForRecovery() {
        if (mRelayService != null) {
            mRelayService.resetReconnectAndStart();
        }
        if (mScreenMode == ScreenMode.TERMINAL && mTerminalLifecycle.hasSession() && mTerminalConnection != null) {
            TerminalConnection.State s = mTerminalConnection.getState();
            if (s == TerminalConnection.State.DISCONNECTED || s == TerminalConnection.State.RECONNECTING) {
                mTerminalConnection.reconnectNow();
            }
        }
    }
}
