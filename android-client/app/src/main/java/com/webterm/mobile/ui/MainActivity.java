package com.webterm.mobile.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
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
import com.webterm.mobile.data.api.WebTermApi;
import com.webterm.mobile.data.api.WebTermUrls;
import com.webterm.mobile.data.cache.TerminalCacheCoordinator;
import com.webterm.mobile.data.config.ServerConfig;
import com.webterm.mobile.data.config.ServerConfigManager;
import com.webterm.mobile.data.config.ServerConfigStore;
import com.webterm.mobile.domain.command.SessionCommandController;
import com.webterm.mobile.domain.relay.RelayCoordinator;
import com.webterm.mobile.domain.server.HomeServerCoordinator;
import com.webterm.mobile.domain.session.RelayMuxSessionManager;
import com.webterm.mobile.domain.session.RelayMuxSessionRegistry;
import com.webterm.mobile.domain.terminal.TerminalClipboardController;
import com.webterm.mobile.domain.terminal.TerminalConnection;
import com.webterm.mobile.domain.terminal.TerminalLifecycleController;
import com.webterm.mobile.domain.terminal.TerminalRuntimeState;
import com.webterm.mobile.domain.terminal.TerminalTitleSynchronizer;
import com.webterm.mobile.recovery.NetworkRecoveryController;
import com.webterm.mobile.transport.P2PConnectionManager;
import com.webterm.mobile.ui.common.DesignTokens;
import com.webterm.mobile.ui.common.PageTransitionAnimator;
import com.webterm.mobile.ui.common.UIUtils;
import com.webterm.mobile.ui.dialog.ServerConfigDialogHelper;
import com.webterm.mobile.ui.dialog.SettingsDialogHelper;
import com.webterm.mobile.ui.home.HomeScreenBuilder;
import com.webterm.mobile.ui.home.SessionRecyclerAdapter;
import com.webterm.mobile.ui.home.SessionRowActions;
import com.webterm.mobile.ui.home.StatusIndicatorView;
import com.webterm.mobile.ui.relay.RelayDevicesScreenBuilder;
import com.webterm.mobile.ui.relay.RelayLoginScreenBuilder;
import com.webterm.mobile.ui.terminal.TerminalConnectionStatusView;
import com.webterm.mobile.ui.terminal.TerminalWindowInsetsController;
import com.webterm.mobile.ui.terminal.WebTermTerminalSessionClient;
import com.webterm.mobile.ui.terminal.WebTermTerminalViewClient;

import androidx.activity.ComponentActivity;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import dagger.hilt.android.AndroidEntryPoint;
import okhttp3.OkHttpClient;

import javax.inject.Inject;

@AndroidEntryPoint

public final class MainActivity extends ComponentActivity implements SessionRowActions, TerminalConnection.Listener, WebTermTerminalViewClient.Host, WebTermTerminalSessionClient.Host, ServerConfigDialogHelper.Host, SettingsDialogHelper.Host, RelayCoordinator.Host, TerminalLifecycleController.Host, NetworkRecoveryController.Host {

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
    private RelayCoordinator mRelayCoordinator;
    private NetworkRecoveryController mNetworkRecoveryController;
    private TextView mHomeSubtitle;
    private final Runnable mBackgroundServerDetach = () -> {
        if (!mInForeground && mSelectedServer != null && mHomeCoordinator != null) {
            mHomeCoordinator.detach();
        }
    };

    private enum ScreenMode {
        DEVICES,
        DEVICE_SESSIONS,
        TERMINAL,
        RELAY_LOGIN,
        RELAY_DEVICES
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setBackgroundColor(DesignTokens.BG_PRIMARY);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        mNetworkRecoveryController = new NetworkRecoveryController(this, mainHandler, this);
        mRelayCoordinator = new RelayCoordinator(http, mainHandler, api, this);
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
        showSessionHome(PageTransitionAnimator.Transition.NONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mInForeground = true;
        cancelBackgroundServerDetach();
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
                mRelayCoordinator.start();
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
            mRelayCoordinator.stop();
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
        mRelayCoordinator.stop();
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
        if (mTerminalLifecycle.hasSession()) {
            if (mSelectedServer != null) {
                showDeviceSessions(mSelectedServer, PageTransitionAnimator.Transition.BACK);
            } else {
                showSessionHome(PageTransitionAnimator.Transition.BACK);
            }
            return;
        }
        if (mScreenMode == ScreenMode.DEVICE_SESSIONS) {
            showSessionHome(PageTransitionAnimator.Transition.BACK);
            return;
        }
        if (mScreenMode == ScreenMode.RELAY_LOGIN || mScreenMode == ScreenMode.RELAY_DEVICES) {
            showSessionHome(PageTransitionAnimator.Transition.BACK);
            return;
        }
        super.onBackPressed();
    }

    // ── Navigation ────────────────────────────────────────────────

    private void loadServersFromPrefs() {
        serverConfigs.load();
        mRelayCoordinator.loadMasterFromServers(serverConfigs.servers());
    }

    public void saveServers() {
        serverConfigs.save();
    }

    void showSessionHome() { showSessionHome(PageTransitionAnimator.Transition.BACK); }

    private void showSessionHome(PageTransitionAnimator.Transition transition) {
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

        HomeScreenBuilder.HomeResult home = HomeScreenBuilder.buildHome(
            this,
            () -> showAddServerDialog(null),
            this::showSettingsDialog,
            () -> { loadMultiSessions(); mRelayCoordinator.start(); },
            () -> {
                if (mRelayCoordinator.hasMaster() && mRelayCoordinator.masterConfig().getCookie() != null
                    && !mRelayCoordinator.masterConfig().getCookie().isEmpty()) {
                    showRelayDevicesPage();
                } else {
                    showRelayLoginPage();
                }
            },
            this::shareLatestCrashLog
        );
        mHomeSubtitle = home.subtitle;
        mRelayCoordinator.attachSubtitle(home.subtitle);
        mRelayCoordinator.attachStatusDot(home.homeStatus);
        installRootInsets(home.root, 0, 0, 0, dp(16), true, true);
        mSessionList = home.sessionList;
        loadMultiSessions();
        setContentViewAnimated(home.root, transition);
        mRelayCoordinator.start();
    }

    private void loadMultiSessions() {
        if (mSessionList == null || mScreenMode != ScreenMode.DEVICES) return;
        java.util.List<ServerConfig> allServers = collectVisibleDevices();
        mSessionList.removeAllViews();
        if (allServers.isEmpty()) {
            mSessionList.addView(HomeScreenBuilder.emptyState(this), new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (ServerConfig server : allServers) {
            mSessionList.addView(HomeScreenBuilder.deviceCard(
                this, server,
                (v) -> showDeviceSessions(server, PageTransitionAnimator.Transition.FORWARD),
                () -> showAddServerDialog(server),
                () -> confirmRemoveServer(server)
            ));
        }
    }

    private java.util.List<ServerConfig> collectVisibleDevices() {
        java.util.List<ServerConfig> allServers = new java.util.ArrayList<>();
        for (ServerConfig s : serverConfigs.servers()) {
            if (!s.isRelayMaster()) allServers.add(s);
        }
        List<ServerConfig> relayDevices = mRelayCoordinator.devices();
        if (!relayDevices.isEmpty()) allServers.addAll(relayDevices);
        return allServers;
    }

    private void showDeviceSessions(ServerConfig server) { showDeviceSessions(server, PageTransitionAnimator.Transition.FORWARD); }

    private void showDeviceSessions(ServerConfig server, PageTransitionAnimator.Transition transition) {
        if (server == null) { showSessionHome(PageTransitionAnimator.Transition.BACK); return; }
        mTerminalLifecycle.closeTerminal(false);
        mRelayCoordinator.stop();
        mClosed.set(false);
        mTerminalState.clearServerSession();
        mScreenMode = ScreenMode.DEVICE_SESSIONS;
        mSelectedServer = server;
        startP2PIfRelayDevice(server.getUrl(), server.getCookie(), server.isRelayDevice(), server.getDeviceId());
        mHomeSubtitle = null;
        mSessionList = null;

        HomeScreenBuilder.DeviceSessionsResult screen = HomeScreenBuilder.buildDeviceSessions(
            this, server,
            () -> showSessionHome(PageTransitionAnimator.Transition.BACK),
            () -> createSessionOnServer(server),
            () -> loadSelectedDeviceSessions(),
            () -> showAddServerDialog(server),
            () -> confirmRemoveServer(server)
        );
        mSelectedServerStatus = screen.status;
        installRootInsets(screen.root, 0, 0, 0, dp(16), true, true);
        mSessionAdapter = new SessionRecyclerAdapter(this, this, this::loadSelectedDeviceSessions);
        screen.sessionList.setAdapter(mSessionAdapter);
        setupSwipeToDelete(screen.sessionList, mSessionAdapter);
        if (mHomeCoordinator != null) {
            mHomeCoordinator.attachSessionAdapter(mSessionAdapter);
            mHomeCoordinator.loadDeviceSessions(server, mSelectedServerStatus);
        }
        setContentViewAnimated(screen.root, transition);
    }

    private void showRelayLoginPage() {
        String savedEmail = mRelayCoordinator.masterConfig() != null
            ? mRelayCoordinator.masterConfig().getUsername() : "";
        RelayLoginScreenBuilder.RelayLoginScreen screen =
            RelayLoginScreenBuilder.buildLogin(mRelayCoordinator, savedEmail);

        mScreenMode = ScreenMode.RELAY_LOGIN;
        installRootInsets(screen.root, 0, 0, 0, dp(16), true, true);
        setContentViewAnimated(screen.root, PageTransitionAnimator.Transition.FORWARD);
    }

    private void showRelayDevicesPage() {
        RelayDevicesScreenBuilder.RelayDevicesScreen screen =
            RelayDevicesScreenBuilder.build(mRelayCoordinator);

        mScreenMode = ScreenMode.RELAY_DEVICES;
        installRootInsets(screen.root, 0, 0, 0, dp(16), true, true);
        setContentViewAnimated(screen.root, PageTransitionAnimator.Transition.FORWARD);
        screen.refresh.run();
    }

    private void setupSwipeToDelete(androidx.recyclerview.widget.RecyclerView recyclerView, final SessionRecyclerAdapter adapter) {
        androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback swipeCallback = new androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(0, androidx.recyclerview.widget.ItemTouchHelper.LEFT) {
            @Override
            public boolean onMove(androidx.recyclerview.widget.RecyclerView recyclerView, androidx.recyclerview.widget.RecyclerView.ViewHolder viewHolder, androidx.recyclerview.widget.RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(androidx.recyclerview.widget.RecyclerView.ViewHolder viewHolder, int direction) {
                final int position = viewHolder.getAdapterPosition();
                if (position == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return;

                String sessionId = adapter.getSessionId(position);
                if (sessionId != null) {
                    closeSession(mSelectedServer, sessionId);
                }
                adapter.notifyItemChanged(position);
            }

            @Override
            public int getSwipeDirs(androidx.recyclerview.widget.RecyclerView recyclerView, androidx.recyclerview.widget.RecyclerView.ViewHolder viewHolder) {
                int position = viewHolder.getAdapterPosition();
                if (position != androidx.recyclerview.widget.RecyclerView.NO_POSITION && adapter.isSessionRow(position)) {
                    return androidx.recyclerview.widget.ItemTouchHelper.LEFT;
                }
                return 0;
            }

            @Override
            public void onChildDraw(
                android.graphics.Canvas c,
                androidx.recyclerview.widget.RecyclerView recyclerView,
                androidx.recyclerview.widget.RecyclerView.ViewHolder viewHolder,
                float dX,
                float dY,
                int actionState,
                boolean isCurrentlyActive
            ) {
                if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_SWIPE && dX < 0) {
                    android.view.View itemView = viewHolder.itemView;
                    android.graphics.Paint paint = new android.graphics.Paint();
                    paint.setColor(DesignTokens.DANGER);
                    
                    android.graphics.RectF background = new android.graphics.RectF(
                        itemView.getRight() + dX,
                        itemView.getTop(),
                        itemView.getRight(),
                        itemView.getBottom()
                    );
                    c.drawRect(background, paint);

                    paint.setColor(android.graphics.Color.WHITE);
                    paint.setTextSize(UIUtils.dp(MainActivity.this, 14));
                    paint.setAntiAlias(true);
                    paint.setTypeface(DesignTokens.fontGeistSansSemibold(MainActivity.this));
                    
                    String text = "关闭会话";
                    float textWidth = paint.measureText(text);
                    android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
                    float textHeight = fm.bottom - fm.top;
                    
                    float cardHeight = itemView.getHeight();
                    float x = itemView.getRight() + dX / 2f - textWidth / 2f;
                    float y = itemView.getTop() + cardHeight / 2f - textHeight / 2f - fm.top;
                    
                    if (-dX > textWidth + UIUtils.dp(MainActivity.this, 24)) {
                        c.drawText(text, x, y, paint);
                    }
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
            }
        };
        new androidx.recyclerview.widget.ItemTouchHelper(swipeCallback).attachToRecyclerView(recyclerView);
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
            showDeviceSessions(mSelectedServer, PageTransitionAnimator.Transition.BACK);
        } else {
            showSessionHome(PageTransitionAnimator.Transition.BACK);
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

    private void showAddServerDialog(final ServerConfig existingServer) {
        ServerConfigDialogHelper.show(this, existingServer);
    }

    private void createSessionOnServer(ServerConfig server) {
        if (mSessionCommands != null) mSessionCommands.createSessionOnServer(server);
    }

    private void confirmRemoveServer(ServerConfig server) {
        AlertDialog dialog = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("确认移除电脑")
            .setMessage("确定要从列表中移除该服务器吗？")
            .setPositiveButton("移除", (d, which) -> {
                removeCachedTerminalsForServer(server);
                serverConfigs.remove(server);
                saveServers();
                if (server == mSelectedServer) {
                    showSessionHome(PageTransitionAnimator.Transition.BACK);
                } else {
                    loadMultiSessions();
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
        if (mHomeCoordinator != null) mHomeCoordinator.pauseUi();
        mScreenMode = ScreenMode.TERMINAL;
        mTerminalLifecycle.showTerminal(
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
        setContentViewAnimated(root, PageTransitionAnimator.Transition.FORWARD);
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
            showDeviceSessions(existingServer, PageTransitionAnimator.Transition.FADE);
        } else {
            showSessionHome(PageTransitionAnimator.Transition.FADE);
        }
    }

    // ── RelayCoordinator.Host ──────────────────────────────────────

    @Override
    public void onRelayDevicesChanged() { loadMultiSessions(); }
    @Override
    public void onRelayAuthDone() {
        if (mScreenMode == ScreenMode.RELAY_LOGIN
            && mRelayCoordinator.hasMaster()
            && mRelayCoordinator.masterConfig().getCookie() != null
            && !mRelayCoordinator.masterConfig().getCookie().isEmpty()) {
            showRelayDevicesPage();
        } else {
            showSessionHome(PageTransitionAnimator.Transition.BACK);
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

    private void showSettingsDialog() { SettingsDialogHelper.show(this); }

    private void shareLatestCrashLog() {
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

    private void installRootInsets(View root, int baseLeft, int baseTop, int baseRight, int baseBottom,
                                   boolean avoidImeWithPadding, boolean includeStatusBar) {
        TerminalWindowInsetsController.installRootInsets(this, root, baseLeft, baseTop, baseRight, baseBottom,
            avoidImeWithPadding, includeStatusBar, (imeOverlap) -> {
                mImeOverlap = imeOverlap;
                updateKeyboardAvoidance();
            });
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

    private void setContentViewAnimated(View newRoot, PageTransitionAnimator.Transition transition) {
        PageTransitionAnimator.animate(this, newRoot, transition);
    }

    int dp(int value) {
        return PageTransitionAnimator.dp(this, value);
    }

    @Override
    public void onNetworkAvailableForRecovery() {
        if (mRelayCoordinator != null) {
            mRelayCoordinator.resetReconnectAndStart();
        }
        if (mScreenMode == ScreenMode.TERMINAL && mTerminalLifecycle.hasSession() && mTerminalConnection != null) {
            TerminalConnection.State s = mTerminalConnection.getState();
            if (s == TerminalConnection.State.DISCONNECTED || s == TerminalConnection.State.RECONNECTING) {
                mTerminalConnection.reconnectNow();
            }
        }
    }
}
