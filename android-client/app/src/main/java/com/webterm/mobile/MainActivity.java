package com.webterm.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;

public final class MainActivity extends Activity implements SessionRowActions, TerminalConnection.Listener, WebTermTerminalViewClient.Host, WebTermTerminalSessionClient.Host, ServerConfigDialogHelper.Host, SettingsDialogHelper.Host, RelayCoordinator.Host, TerminalLifecycleController.Host {

    private final OkHttpClient mHttp = new OkHttpClient();
    private final WebTermApi mApi = new WebTermApi(mHttp);
    private final AtomicBoolean mClosed = new AtomicBoolean(false);
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final TerminalRuntimeState mTerminalState = new TerminalRuntimeState();
    private final TerminalConnectionStatusView mConnectionStatus = new TerminalConnectionStatusView();

    private boolean mInForeground = true;
    private android.net.ConnectivityManager.NetworkCallback mNetworkCallback;
    private TerminalConnection mTerminalConnection;
    private TerminalCacheCoordinator mTerminalCache;
    private ServerConfigStore mConfigStore;
    private SessionCommandController mSessionCommands;
    private TerminalTitleSynchronizer mTitleSynchronizer;
    private HomeServerCoordinator mHomeCoordinator;
    private TerminalClipboardController mClipboardController;
    private WebTermTerminalSessionClient mTerminalSessionClient;
    private ServerConfigManager mServerConfigs;
    private TerminalLifecycleController mTerminalLifecycle;

    private LinearLayout mSessionList;
    private SessionRecyclerAdapter mSessionAdapter;
    private ScreenMode mScreenMode = ScreenMode.DEVICES;
    private ServerConfig mSelectedServer;
    private StatusIndicatorView mSelectedServerStatus;

    private int mImeOverlap;
    private RelayCoordinator mRelayCoordinator;
    private TextView mHomeSubtitle;
    private long lastNetworkAvailableTime = 0;

    private enum ScreenMode {
        DEVICES,
        DEVICE_SESSIONS,
        TERMINAL
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setBackgroundColor(Color.rgb(15, 15, 18));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        mConfigStore = new ServerConfigStore(this);
        mServerConfigs = new ServerConfigManager(mConfigStore);
        mRelayCoordinator = new RelayCoordinator(mHttp, mMainHandler, mApi, this);
        mTerminalCache = new TerminalCacheCoordinator(getFilesDir());
        mSessionCommands = new SessionCommandController(this, mApi, new SessionCommandController.Listener() {
            @Override
            public void onAuthenticated(ServerConfig server) { saveServers(); }
            @Override
            public void onOpenTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String sessionName) {
                showTerminal(baseUrl, cookie, sessionId, termTitle, sessionName);
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
        mTerminalConnection = new TerminalConnection(mHttp, mMainHandler, this);
        mTitleSynchronizer = new TerminalTitleSynchronizer(mMainHandler, () -> mTerminalConnection);
        mClipboardController = new TerminalClipboardController(this, this);
        mTerminalSessionClient = new WebTermTerminalSessionClient(this, this, mClipboardController, mTitleSynchronizer);
        mTerminalLifecycle = new TerminalLifecycleController(
            this, this, mTerminalState, mClosed, mConnectionStatus,
            mTerminalCache, mTerminalConnection, mTitleSynchronizer, mSessionCommands
        );
        mHomeCoordinator = new HomeServerCoordinator(
            this, mHttp, mMainHandler, mApi, mTerminalCache,
            mHttp.dispatcher().executorService(),
            new HomeServerCoordinator.Listener() {
                @Override public boolean isHomeActive() { return MainActivity.this.isHomeActive(); }
                @Override public void onAuthenticated(ServerConfig server) { saveServers(); }
                @Override public void onRemoveCachedTerminal(String baseUrl, String sessionId) {
                    removeCachedTerminal(baseUrl, sessionId);
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
                if (mHomeCoordinator != null) mHomeCoordinator.resume();
            } else {
                mRelayCoordinator.start();
            }
        }
        registerNetworkCallback();
    }

    @Override
    protected void onPause() {
        unregisterNetworkCallback();
        mInForeground = false;
        if (!mTerminalLifecycle.hasSession()) {
            if (mScreenMode == ScreenMode.DEVICE_SESSIONS && mHomeCoordinator != null) mHomeCoordinator.pause();
            mRelayCoordinator.stop();
        } else {
            mTerminalLifecycle.pauseCurrentConnection();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mClosed.set(true);
        mMainHandler.removeCallbacksAndMessages(null);
        mRelayCoordinator.stop();
        if (mHomeCoordinator != null) mHomeCoordinator.destroy();
        if (mTerminalLifecycle.hasSession() && mTerminalLifecycle.hasActiveTerminal()) {
            mTerminalLifecycle.closeTerminal(false);
        }
        if (mTerminalConnection != null) mTerminalConnection.close("activity closed");
        if (mTerminalLifecycle.terminalSession() != null) mTerminalLifecycle.terminalSession().finishIfRunning();
        if (mTerminalCache != null) mTerminalCache.shutdown(mTerminalLifecycle.terminalSession());
        mHttp.dispatcher().cancelAll();
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
        super.onBackPressed();
    }

    // ── Navigation ────────────────────────────────────────────────

    private void loadServersFromPrefs() {
        mServerConfigs.load();
        mRelayCoordinator.loadMasterFromServers(mServerConfigs.servers());
    }

    public void saveServers() {
        mServerConfigs.save();
    }

    void showSessionHome() { showSessionHome(PageTransitionAnimator.Transition.BACK); }

    private void showSessionHome(PageTransitionAnimator.Transition transition) {
        mTerminalLifecycle.closeTerminal(false);
        mClosed.set(false);
        mTerminalState.clearServerSession();
        mScreenMode = ScreenMode.DEVICES;
        mSelectedServer = null;
        mSelectedServerStatus = null;
        mSessionAdapter = null;
        if (mHomeCoordinator != null) {
            mHomeCoordinator.pause();
            mHomeCoordinator.attachSessionAdapter(null);
        }

        HomeScreenBuilder.HomeResult home = HomeScreenBuilder.buildHome(
            this,
            () -> showAddServerDialog(null),
            this::showSettingsDialog,
            () -> { loadMultiSessions(); mRelayCoordinator.start(); },
            () -> RelayConfigDialogHelper.show(mRelayCoordinator, mRelayCoordinator.masterConfig()),
            this::shareLatestCrashLog
        );
        mHomeSubtitle = home.subtitle;
        mRelayCoordinator.attachSubtitle(home.subtitle);
        installRootInsets(home.root, dp(20), dp(8), dp(20), dp(16), true);
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
        for (ServerConfig s : mServerConfigs.servers()) {
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
        installRootInsets(screen.root, dp(20), dp(8), dp(20), dp(16), true);
        mSessionAdapter = new SessionRecyclerAdapter(this, this, this::loadSelectedDeviceSessions);
        screen.sessionList.setAdapter(mSessionAdapter);
        if (mHomeCoordinator != null) {
            mHomeCoordinator.attachSessionAdapter(mSessionAdapter);
            mHomeCoordinator.loadDeviceSessions(server, mSelectedServerStatus);
        }
        setContentViewAnimated(screen.root, transition);
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

    private void showAddServerDialog(final ServerConfig existingServer) {
        ServerConfigDialogHelper.show(this, existingServer);
    }

    private void createSessionOnServer(ServerConfig server) {
        if (mSessionCommands != null) mSessionCommands.createSessionOnServer(server);
    }

    private void confirmRemoveServer(ServerConfig server) {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("确认移除电脑")
            .setMessage("确定要从列表中移除该服务器吗？")
            .setPositiveButton("移除", (dialog, which) -> {
                removeCachedTerminalsForServer(server);
                mServerConfigs.remove(server);
                saveServers();
                if (server == mSelectedServer) {
                    showSessionHome(PageTransitionAnimator.Transition.BACK);
                } else {
                    loadMultiSessions();
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // ── Terminal ───────────────────────────────────────────────────

    void showTerminal(String baseUrl, String cookie, String sessionId) {
        showTerminal(baseUrl, cookie, sessionId, "Terminal", "", "", "");
    }
    void showTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String sessionName) {
        showTerminal(baseUrl, cookie, sessionId, termTitle, sessionName, "", "");
    }
    void showTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String sessionName, String createdAt) {
        showTerminal(baseUrl, cookie, sessionId, termTitle, sessionName, createdAt, "");
    }

    void showTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String sessionName,
                      String createdAt, String instanceId) {
        if (mHomeCoordinator != null) mHomeCoordinator.pause();
        mScreenMode = ScreenMode.TERMINAL;
        mTerminalLifecycle.showTerminal(
            baseUrl, cookie, sessionId, termTitle, sessionName, createdAt, instanceId,
            this, mTerminalSessionClient,
            this::showSessionListOrDeviceHome
        );
    }

    @Override
    public void openSession(ServerConfig server, String sessionId, String termTitle, String sessionName,
                            String createdAt, String instanceId) {
        mSelectedServer = server;
        showTerminal(server.getUrl(), server.getCookie(), sessionId, termTitle, sessionName, createdAt, instanceId);
    }

    // ── TerminalLifecycleController.Host ───────────────────────────

    @Override
    public int getSavedFontSize() { return mConfigStore.getFontSize(); }
    @Override
    public String getSavedFontType() { return mConfigStore.getFontType(); }

    @Override
    public Typeface getTypefaceByName(String type) {
        if ("sans-serif".equals(type)) return Typeface.SANS_SERIF;
        if ("serif".equals(type)) return Typeface.SERIF;
        if ("default".equals(type)) return Typeface.DEFAULT;
        return Typeface.MONOSPACE;
    }

    @Override
    public void installTerminalInsets(View root) {
        TerminalWindowInsetsController.installRootInsets(this, root, 0, 0, 0, 0, false, (imeOverlap) -> {
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
        runOnUiThread(() -> mConnectionStatus.update(state, reconnectAttempts));
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
        mApi.login(baseUrl, "", username, password, new WebTermApi.LoginCallback() {
            @Override
            public void onReady(String readyBaseUrl, String cookie) { callback.onReady(readyBaseUrl, cookie); }
            @Override
            public void onError(String message) { callback.onError(message); }
        });
    }

    @Override
    public void onServerAuthenticated(ServerConfig existingServer, String name, String url, String cookie,
                                      String username, String password) {
        mServerConfigs.addOrUpdate(existingServer, name, url, cookie, username, password);
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
    public void onRelayAuthDone() { showSessionHome(PageTransitionAnimator.Transition.FADE); }
    @Override
    public ServerConfigManager serverConfigs() { return mServerConfigs; }

    // ── SettingsDialogHelper.Host ──────────────────────────────────

    @Override
    public String getFontDisplayName(String fontType) {
        if ("sans-serif".equals(fontType)) return "Sans Serif";
        if ("serif".equals(fontType)) return "Serif";
        if ("default".equals(fontType)) return "Default";
        return "Monospace";
    }
    @Override
    public void saveFontSize(int size) { mConfigStore.saveFontSize(size); }
    @Override
    public void saveFontType(String type) { mConfigStore.saveFontType(type); }
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
                                   boolean avoidImeWithPadding) {
        TerminalWindowInsetsController.installRootInsets(this, root, baseLeft, baseTop, baseRight, baseBottom,
            avoidImeWithPadding, (imeOverlap) -> {
                mImeOverlap = imeOverlap;
                updateKeyboardAvoidance();
            });
    }

    private void removeCachedTerminal(String baseUrl, String sessionId) {
        if (mTerminalCache == null) return;
        if (mTerminalCache.removeTerminal(baseUrl, sessionId, mTerminalState.baseUrl(), mTerminalState.sessionId(),
            mTerminalLifecycle.terminalSession())) {
            mTerminalState.clearPersistence();
        }
        TodoDialogHelper.clearTodo(this, sessionId);
    }

    private void removeMissingCachedSessionsForServer(ServerConfig server, java.util.Set<String> liveSessionIdentities) {
        if (mTerminalCache != null) {
            mTerminalCache.removeMissingForServer(server, liveSessionIdentities, mTerminalLifecycle.terminalSession());
        }
    }

    private void removeCachedTerminalsForServer(ServerConfig server) {
        if (mTerminalCache != null) mTerminalCache.removeServer(server, mTerminalLifecycle.terminalSession());
    }

    // ── Animation ──────────────────────────────────────────────────

    private void setContentViewAnimated(View newRoot, PageTransitionAnimator.Transition transition) {
        PageTransitionAnimator.animate(this, newRoot, transition);
    }

    int dp(int value) {
        return PageTransitionAnimator.dp(this, value);
    }

    private void registerNetworkCallback() {
        if (mNetworkCallback != null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                mNetworkCallback = new android.net.ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(@androidx.annotation.NonNull android.net.Network network) {
                        mMainHandler.post(() -> {
                            long now = System.currentTimeMillis();
                            if (now - lastNetworkAvailableTime > 2000) { // 2秒防抖
                                lastNetworkAvailableTime = now;
                                android.util.Log.i("MainActivity", "Network available: trigger recovery...");

                                // 1. 中转连接重试启动
                                if (mRelayCoordinator != null) {
                                    mRelayCoordinator.resetReconnectAndStart();
                                }

                                // 2. 终端长连接自愈
                                if (mScreenMode == ScreenMode.TERMINAL && mTerminalLifecycle.hasSession() && mTerminalConnection != null) {
                                    TerminalConnection.State s = mTerminalConnection.getState();
                                    if (s == TerminalConnection.State.DISCONNECTED || s == TerminalConnection.State.RECONNECTING) {
                                        mTerminalConnection.reconnectNow();
                                    }
                                }
                            }
                        });
                    }
                };
                cm.registerDefaultNetworkCallback(mNetworkCallback);
            }
        }
    }

    private void unregisterNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && mNetworkCallback != null) {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                cm.unregisterNetworkCallback(mNetworkCallback);
            }
            mNetworkCallback = null;
        }
    }
}
