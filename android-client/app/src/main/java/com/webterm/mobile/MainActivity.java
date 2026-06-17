package com.webterm.mobile;

import android.app.Activity;
import android.widget.Button;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;

import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;

public final class MainActivity extends Activity implements SessionRowActions, TerminalConnection.Listener, WebTermTerminalViewClient.Host, WebTermTerminalSessionClient.Host, ServerConfigDialogHelper.Host, SettingsDialogHelper.Host, RelayConfigDialogHelper.Host {

    private static final int TRANSCRIPT_ROWS = 10000;
    private static final byte[] CLEAR_SCREEN_BYTES = "\u001b[3J\u001b[2J\u001b[H".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private final OkHttpClient mHttp = new OkHttpClient();
    private final WebTermApi mApi = new WebTermApi(mHttp);
    private final AtomicBoolean mClosed = new AtomicBoolean(false);
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private TerminalView mTerminalView;
    private TerminalSession mTerminalSession;
    private TerminalConnection mTerminalConnection;
    private final TerminalRuntimeState mTerminalState = new TerminalRuntimeState();
    private boolean mCtrlDown;
    private boolean mInForeground = true;
    private TerminalCacheCoordinator mTerminalCache;
    private ServerConfigStore mConfigStore;
    private SessionCommandController mSessionCommands;
    private TerminalTitleSynchronizer mTitleSynchronizer;
    private HomeServerCoordinator mHomeCoordinator;
    private TerminalClipboardController mClipboardController;
    private WebTermTerminalSessionClient mTerminalSessionClient;
    private ServerConfigManager mServerConfigs;
    private LinearLayout mSessionList;
    private final TerminalConnectionStatusView mConnectionStatus = new TerminalConnectionStatusView();
    private TextView mTerminalTitle;
    private TextView mTerminalSubtitle;

    private View mTerminalRoot;
    private View mTerminalViewport;
    private View mQuickBar;
    private Button mCtrlButton;
    private int mImeOverlap;

    // Relay fields
    private ServerConfig mRelayMasterConfig;
    private ServerSessionMonitor mRelayMonitor;
    private final java.util.List<ServerConfig> mRelayDevices = new java.util.ArrayList<>();
    private TextView mHomeSubtitle;
    private RelayState mRelayState = RelayState.NOT_CONFIGURED;

    private enum RelayState {
        NOT_CONFIGURED,
        CONNECTING,
        AUTH_FAILED,
        CONNECT_FAILED,
        CONNECTED_NO_DEVICES,
        CONNECTED_WITH_DEVICES
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        mConfigStore = new ServerConfigStore(this);
        mServerConfigs = new ServerConfigManager(mConfigStore);
        mTerminalCache = new TerminalCacheCoordinator(getFilesDir());
        mSessionCommands = new SessionCommandController(this, mApi, new SessionCommandController.Listener() {
            @Override
            public void onAuthenticated(ServerConfig server) {
                saveServersToPrefs();
            }

            @Override
            public void onOpenTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String sessionName) {
                showTerminal(baseUrl, cookie, sessionId, termTitle, sessionName);
            }

            @Override
            public void onRemoveCachedTerminal(String baseUrl, String sessionId) {
                removeCachedTerminal(baseUrl, sessionId);
            }

            @Override
            public void onShowHome() {
                showSessionHome();
            }
        });
        mTerminalConnection = new TerminalConnection(mHttp, mMainHandler, this);
        mTitleSynchronizer = new TerminalTitleSynchronizer(mMainHandler, () -> mTerminalConnection);
        mClipboardController = new TerminalClipboardController(this);
        mTerminalSessionClient = new WebTermTerminalSessionClient(this, this, mClipboardController, mTitleSynchronizer);
        mHomeCoordinator = new HomeServerCoordinator(
            this,
            mHttp,
            mMainHandler,
            mApi,
            mTerminalCache,
            mHttp.dispatcher().executorService(),
            this,
            new HomeServerCoordinator.Listener() {
                @Override
                public boolean isHomeActive() {
                    return MainActivity.this.isHomeActive();
                }

                @Override
                public void onAuthenticated(ServerConfig server) {
                    saveServersToPrefs();
                }

                @Override
                public void onCreateSession(ServerConfig server) {
                    createSessionOnServer(server);
                }

                @Override
                public void onEditServer(ServerConfig server) {
                    showAddServerDialog(server);
                }

                @Override
                public void onRemoveServer(ServerConfig server) {
                    confirmRemoveServer(server);
                }

                @Override
                public void onRemoveCachedTerminal(String baseUrl, String sessionId) {
                    removeCachedTerminal(baseUrl, sessionId);
                }

                @Override
                public void onRemoveMissingCachedSessionsForServer(String baseUrl, java.util.Set<String> liveSessionIdentities) {
                    removeMissingCachedSessionsForServer(baseUrl, liveSessionIdentities);
                }
            });
        loadServersFromPrefs();
        showSessionHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mInForeground = true;
        if (mTerminalState.hasSession() && mTerminalSession != null && (mTerminalConnection == null || !mTerminalConnection.hasSocket())) {
            mClosed.set(false);
            connectTerminal();
        } else if (!mTerminalState.hasSession() && mSessionList != null) {
            if (mHomeCoordinator != null) mHomeCoordinator.resume();
            startRelayMonitor();
        }
    }

    @Override
    protected void onPause() {
        mInForeground = false;
        if (!mTerminalState.hasSession()) {
            if (mHomeCoordinator != null) mHomeCoordinator.pause();
            stopRelayMonitor();
        } else {
            pauseCurrentTerminalConnection();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mClosed.set(true);
        mMainHandler.removeCallbacksAndMessages(null);
        stopRelayMonitor();
        if (mHomeCoordinator != null) mHomeCoordinator.destroy();
        if (mTerminalState.hasSession() && mTerminalSession != null) {
            cacheCurrentTerminal();
        }
        if (mTerminalConnection != null) mTerminalConnection.close("activity closed");
        if (mTerminalSession != null) mTerminalSession.finishIfRunning();
        if (mTerminalCache != null) mTerminalCache.shutdown(mTerminalSession);
        mHttp.dispatcher().cancelAll();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mTerminalState.hasSession()) {
            showSessionHome();
            return;
        }
        super.onBackPressed();
    }

    private void loadServersFromPrefs() {
        mServerConfigs.load();
        mRelayMasterConfig = null;
        for (ServerConfig s : mServerConfigs.servers()) {
            if (s.isRelayMaster) {
                mRelayMasterConfig = s;
                break;
            }
        }
    }

    void saveServersToPrefs() {
        mServerConfigs.save();
    }

    void showSessionHome() {
        closeCurrentTerminal(false);
        mClosed.set(false);
        mTerminalState.clearServerSession();

        HomeScreenBuilder.HomeResult home = HomeScreenBuilder.buildHome(
            this,
            () -> showAddServerDialog(null),
            this::showSettingsDialog,
            this::loadMultiSessions,
            this::showRelayDialog
        );
        mHomeSubtitle = home.subtitle;
        updateSubtitleState(mRelayState);
        installRootInsets(home.root, dp(20), dp(24), dp(20), dp(16), true);
        mSessionList = home.sessionList;
        if (mHomeCoordinator != null) mHomeCoordinator.attachSessionList(mSessionList);
        setContentView(home.root);
        loadMultiSessions();
        startRelayMonitor();
    }

    private void loadMultiSessions() {
        if (mHomeCoordinator != null) {
            java.util.List<ServerConfig> allServers = new java.util.ArrayList<>();
            for (ServerConfig s : mServerConfigs.servers()) {
                if (!s.isRelayMaster) {
                    allServers.add(s);
                }
            }
            if (mRelayDevices != null && !mRelayDevices.isEmpty()) {
                allServers.addAll(mRelayDevices);
            }
            mHomeCoordinator.load(allServers);
        }
    }

    // RelayConfigDialogHelper.Host Implementation
    private void showRelayDialog() {
        RelayConfigDialogHelper.show(this, mRelayMasterConfig);
    }

    @Override
    public void loginRelay(String baseUrl, String username, String password, RelayConfigDialogHelper.LoginCallback callback) {
        mApi.login(baseUrl, username, password, new WebTermApi.LoginCallback() {
            @Override
            public void onReady(String url, String cookie) {
                callback.onReady(url, cookie);
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    @Override
    public void onRelayAuthenticated(String url, String cookie, String username, String password) {
        ServerConfig existingMaster = null;
        for (ServerConfig s : mServerConfigs.servers()) {
            if (s.isRelayMaster) {
                existingMaster = s;
                break;
            }
        }
        if (existingMaster == null) {
            existingMaster = new ServerConfig(
                "relay_mst_" + System.currentTimeMillis(),
                "中转服务器",
                url,
                cookie,
                username,
                password,
                true,  // isRelayMaster
                false, // isRelayDevice
                ""
            );
            mServerConfigs.servers().add(existingMaster);
        } else {
            existingMaster.url = url;
            existingMaster.cookie = cookie;
            existingMaster.username = username;
            existingMaster.password = password;
        }
        mRelayMasterConfig = existingMaster;
        saveServersToPrefs();
        startRelayMonitor();
        showSessionHome();
    }

    @Override
    public void onDisconnectRelay() {
        ServerConfig existingMaster = null;
        for (ServerConfig s : mServerConfigs.servers()) {
            if (s.isRelayMaster) {
                existingMaster = s;
                break;
            }
        }
        if (existingMaster != null) {
            mServerConfigs.servers().remove(existingMaster);
        }
        mRelayMasterConfig = null;
        saveServersToPrefs();
        stopRelayMonitor();
        mRelayDevices.clear();
        updateSubtitleState(RelayState.NOT_CONFIGURED);
        showSessionHome();
    }

    private void updateSubtitleState(RelayState state) {
        mRelayState = state;
        if (mHomeSubtitle == null) return;
        mMainHandler.post(() -> {
            switch (state) {
                case NOT_CONFIGURED:
                    mHomeSubtitle.setText("⚠️ 中转服务未连接");
                    mHomeSubtitle.setTextColor(Color.rgb(245, 158, 11));
                    break;
                case CONNECTING:
                    mHomeSubtitle.setText("⏳ 正在连接中转服务...");
                    mHomeSubtitle.setTextColor(Color.rgb(245, 158, 11));
                    break;
                case AUTH_FAILED:
                    mHomeSubtitle.setText("🚨 中转服务登录失败");
                    mHomeSubtitle.setTextColor(Color.rgb(239, 68, 68));
                    break;
                case CONNECT_FAILED:
                    mHomeSubtitle.setText("🚨 无法连接中转服务，正在重连...");
                    mHomeSubtitle.setTextColor(Color.rgb(239, 68, 68));
                    break;
                case CONNECTED_NO_DEVICES:
                    mHomeSubtitle.setText("🟢 中转服务已连接 (无在线电脑)");
                    mHomeSubtitle.setTextColor(Color.rgb(16, 185, 129));
                    break;
                case CONNECTED_WITH_DEVICES:
                    mHomeSubtitle.setText("🟢 已连接中转服务");
                    mHomeSubtitle.setTextColor(Color.rgb(16, 185, 129));
                    break;
            }
        });
    }

    private void startRelayMonitor() {
        stopRelayMonitor();
        if (mRelayMasterConfig == null || mRelayMasterConfig.url.isEmpty()) {
            updateSubtitleState(RelayState.NOT_CONFIGURED);
            return;
        }
        updateSubtitleState(RelayState.CONNECTING);
        mRelayMonitor = new ServerSessionMonitor(mHttp, mMainHandler, mRelayMasterConfig, new ServerSessionMonitor.Listener() {
            @Override
            public void onMonitorConnected() {
                activity().runOnUiThread(() -> {
                    if (mRelayDevices.isEmpty()) {
                        updateSubtitleState(RelayState.CONNECTED_NO_DEVICES);
                    } else {
                        updateSubtitleState(RelayState.CONNECTED_WITH_DEVICES);
                    }
                });
            }

            @Override
            public void onMonitorPollingFallback() {
                activity().runOnUiThread(() -> updateSubtitleState(RelayState.CONNECTING));
            }

            @Override
            public void onMonitorSessions(JSONArray sessions) {
                // 主监控长连接只获取设备列表，不获取具体某设备的会话列表
            }

            @Override
            public void onMonitorSession(JSONObject session) {
            }

            @Override
            public void onMonitorSessionClosed(String sessionId) {
            }

            @Override
            public void onMonitorDevices(JSONArray devices) {
                mMainHandler.post(() -> {
                    mRelayDevices.clear();
                    for (int i = 0; i < devices.length(); i++) {
                        JSONObject deviceObj = devices.optJSONObject(i);
                        if (deviceObj == null) continue;
                        String deviceId = deviceObj.optString("deviceId");
                        String deviceName = deviceObj.optString("deviceName");
                        if (deviceId.isEmpty()) continue;

                        mRelayDevices.add(new ServerConfig(
                            "relay_dev_" + deviceId,
                            deviceName,
                            mRelayMasterConfig.url,
                            mRelayMasterConfig.cookie,
                            mRelayMasterConfig.username,
                            mRelayMasterConfig.password,
                            false, // isRelayMaster
                            true,  // isRelayDevice
                            deviceId
                        ));
                    }
                    if (mRelayDevices.isEmpty()) {
                        updateSubtitleState(RelayState.CONNECTED_NO_DEVICES);
                    } else {
                        updateSubtitleState(RelayState.CONNECTED_WITH_DEVICES);
                    }
                    loadMultiSessions();
                });
            }

            @Override
            public void onMonitorError(String errorMsg) {
                activity().runOnUiThread(() -> {
                    if (errorMsg != null && errorMsg.contains("401")) {
                        if (mRelayMasterConfig != null && mRelayMasterConfig.username != null && !mRelayMasterConfig.username.isEmpty() && mRelayMasterConfig.password != null && !mRelayMasterConfig.password.isEmpty()) {
                            updateSubtitleState(RelayState.CONNECTING);
                            mApi.login(mRelayMasterConfig.url, mRelayMasterConfig.username, mRelayMasterConfig.password, new WebTermApi.LoginCallback() {
                                @Override
                                public void onReady(String url, String cookie) {
                                    mRelayMasterConfig.cookie = cookie;
                                    saveServersToPrefs();
                                    startRelayMonitor();
                                }

                                @Override
                                public void onError(String message) {
                                    updateSubtitleState(RelayState.AUTH_FAILED);
                                }
                            });
                        } else {
                            updateSubtitleState(RelayState.AUTH_FAILED);
                        }
                    } else {
                        updateSubtitleState(RelayState.CONNECT_FAILED);
                    }
                });
            }
        });
        mRelayMonitor.start();
    }

    private void stopRelayMonitor() {
        if (mRelayMonitor != null) {
            mRelayMonitor.stop();
            mRelayMonitor = null;
        }
    }

    private boolean isHomeActive() {
        return mInForeground && !mClosed.get() && !mTerminalState.hasSession() && mSessionList != null;
    }

    private void confirmRemoveServer(ServerConfig server) {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("确认移除电脑")
            .setMessage("确定要从列表中移除该服务器吗？")
            .setPositiveButton("移除", (dialog, which) -> {
                removeCachedTerminalsForServer(server.url);
                mServerConfigs.remove(server);
                saveServersToPrefs();
                loadMultiSessions();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void createSessionOnServer(ServerConfig server) {
        if (mSessionCommands != null) mSessionCommands.createSessionOnServer(server);
    }

    private void showAddServerDialog(final ServerConfig existingServer) {
        ServerConfigDialogHelper.show(this, existingServer);
    }

    @Override
    public Activity activity() {
        return this;
    }

    @Override
    public void onServerAuthenticated(ServerConfig existingServer, String name, String url, String cookie, String username, String password) {
        mServerConfigs.addOrUpdate(existingServer, name, url, cookie, username, password);
        saveServersToPrefs();
        showSessionHome();
    }

    void showRenameDialog(ServerConfig server, String sessionId, String oldName) {
        if (mSessionCommands != null) mSessionCommands.showRenameDialog(server, sessionId, oldName);
    }

    void showCloseConfirmDialog(ServerConfig server, String sessionId) {
        if (mSessionCommands != null) mSessionCommands.showCloseConfirmDialog(server, sessionId);
    }

    private void deleteSession(String sessionId) {
        if (mSessionCommands != null) mSessionCommands.deleteCurrentSession(mTerminalState.baseUrl(), mTerminalState.cookie(), sessionId);
    }

    void showTerminal(String baseUrl, String cookie, String sessionId) {
        showTerminal(baseUrl, cookie, sessionId, "Terminal", "", "", "");
    }

    void showTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String sessionName) {
        showTerminal(baseUrl, cookie, sessionId, termTitle, sessionName, "", "");
    }

    void showTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String sessionName, String createdAt) {
        showTerminal(baseUrl, cookie, sessionId, termTitle, sessionName, createdAt, "");
    }

    void showTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String sessionName, String createdAt, String instanceId) {
        if (mHomeCoordinator != null) mHomeCoordinator.pause();
        String normalizedInstanceId = SessionIdentity.normalizePart(instanceId);
        String normalizedCreatedAt = SessionIdentity.normalizePart(createdAt);
        CachedTerminal cached = mTerminalCache == null ? null : mTerminalCache.getMemory(baseUrl, sessionId, normalizedInstanceId, normalizedCreatedAt);
        final TerminalDiskCache.RestoreResult[] diskRestore = new TerminalDiskCache.RestoreResult[1];
        if (cached == null && mTerminalCache != null && !SessionIdentity.cacheKey(baseUrl, sessionId, normalizedInstanceId, normalizedCreatedAt).isEmpty()) {
            diskRestore[0] = mTerminalCache.restore(baseUrl, sessionId, normalizedInstanceId, normalizedCreatedAt);
        }
        mTerminalState.setServerSession(baseUrl, cookie, sessionId);
        TerminalLaunchState launchState = TerminalLaunchState.resolve(
            sessionId,
            termTitle,
            sessionName,
            normalizedCreatedAt,
            normalizedInstanceId,
            cached,
            diskRestore[0]
        );
        mClosed.set(false);
        mTerminalState.applyLaunchState(launchState);

        TerminalScreenBuilder.Result terminalScreen = TerminalScreenBuilder.build(
            this,
            launchState.headerTitle,
            launchState.headerSubtitle,
            getSavedFontSize(),
            getTypefaceByName(getSavedFontType()),
            new WebTermTerminalViewClient(this),
            this::showSessionHome,
            () -> {
                if (mTerminalConnection != null) mTerminalConnection.reconnectNow();
            },
            () -> TodoDialogHelper.show(this, sessionId),
            () -> {
                mCtrlDown = !mCtrlDown;
                TerminalScreenBuilder.updateCtrlButtonState(MainActivity.this, mCtrlButton, mCtrlDown);
            },
            this::writeTerminal
        );
        mTerminalRoot = terminalScreen.root;
        mTerminalView = terminalScreen.terminalView;
        mTerminalViewport = terminalScreen.terminalViewport;
        mQuickBar = terminalScreen.quickBar;
        mCtrlButton = terminalScreen.ctrlButton;
        mTerminalTitle = terminalScreen.title;
        mTerminalSubtitle = terminalScreen.subtitle;
        mConnectionStatus.bind(terminalScreen.statusIndicator, terminalScreen.retryButton);
        installRootInsets(mTerminalRoot, 0, 0, 0, 0, false);
        setContentView(mTerminalRoot);
        mTerminalRoot.post(this::updateKeyboardAvoidance);

        mTerminalSession = cached != null && cached.terminalSession != null
            ? cached.terminalSession
            : TerminalSession.createExternalSession(TRANSCRIPT_ROWS, mTerminalSessionClient, mTerminalSessionClient);
        mTerminalView.attachSession(mTerminalSession);
        if (cached == null && diskRestore[0] != null && diskRestore[0].snapshotBytes != null && diskRestore[0].snapshotBytes.length > 0) {
            try {
                mTerminalSession.getEmulator().getScreen().deserialize(diskRestore[0].snapshotBytes);
            } catch (Throwable t) {
                android.util.Log.e("MainActivity", "Failed to deserialize snapshot", t);
            }
        }
        mTerminalView.requestFocus();
        mTerminalRoot.post(() -> {
            if (mTerminalView != null) mTerminalView.updateSize();
            connectTerminal();
        });
    }

    @Override
    public void openSession(ServerConfig server, String sessionId, String termTitle, String sessionName, String createdAt, String instanceId) {
        showTerminal(server.url, server.cookie, sessionId, termTitle, sessionName, createdAt, instanceId);
    }

    @Override
    public void renameSession(ServerConfig server, String sessionId, String oldName) {
        showRenameDialog(server, sessionId, oldName);
    }

    @Override
    public void closeSession(ServerConfig server, String sessionId) {
        showCloseConfirmDialog(server, sessionId);
    }

    private void installRootInsets(View root, int baseLeft, int baseTop, int baseRight, int baseBottom, boolean avoidImeWithPadding) {
        TerminalWindowInsetsController.installRootInsets(this, root, baseLeft, baseTop, baseRight, baseBottom, avoidImeWithPadding, (imeOverlap) -> {
            mImeOverlap = imeOverlap;
            updateKeyboardAvoidance();
        });
    }

    private void updateKeyboardAvoidance() {
        TerminalWindowInsetsController.updateKeyboardAvoidance(this, mTerminalRoot, mTerminalViewport, mQuickBar, mTerminalView, mImeOverlap);
    }

    private void closeCurrentTerminal(boolean closeRemote) {
        String closingBaseUrl = mTerminalState.baseUrl();
        String closingSessionId = mTerminalState.sessionId();
        if (!closeRemote) {
            cacheCurrentTerminal();
        }
        mClosed.set(true);
        closeTerminalConnection("leaving terminal");
        if (mTerminalSession != null && closeRemote) {
            mTerminalSession.finishIfRunning();
        }
        mTerminalSession = null;
        mTerminalView = null;
        mConnectionStatus.clear();
        mTerminalTitle = null;
        mTerminalSubtitle = null;
        mTerminalRoot = null;
        mTerminalViewport = null;
        mQuickBar = null;
        mCtrlButton = null;
        mCtrlDown = false;
        mImeOverlap = 0;
        mTerminalState.clearTerminalDetails();
        if (mTitleSynchronizer != null) mTitleSynchronizer.reset();
        if (closeRemote && closingSessionId != null) {
            removeCachedTerminal(closingBaseUrl, closingSessionId);
            deleteSession(closingSessionId);
        }
        mTerminalState.clearServerSession();
    }

    private void pauseCurrentTerminalConnection() {
        cacheCurrentTerminal();
        closeTerminalConnection("activity paused");
    }

    private void closeTerminalConnection(String reason) {
        if (mTitleSynchronizer != null) mTitleSynchronizer.cancel();
        if (mTerminalConnection != null) mTerminalConnection.close(reason);
    }

    private void cacheCurrentTerminal() {
        if (mTerminalCache == null) return;
        mTerminalCache.saveCurrent(mTerminalState.snapshot(mTerminalTitle, mTerminalSubtitle, mTerminalSession));
    }

    private void removeCachedTerminal(String baseUrl, String sessionId) {
        if (mTerminalCache == null) return;
        if (mTerminalCache.removeTerminal(baseUrl, sessionId, mTerminalState.baseUrl(), mTerminalState.sessionId(), mTerminalSession)) {
            mTerminalState.clearPersistence();
        }
        TodoDialogHelper.clearTodo(this, sessionId);
    }

    private void removeMissingCachedSessionsForServer(String baseUrl, java.util.Set<String> liveSessionIdentities) {
        if (mTerminalCache != null) mTerminalCache.removeMissingForServer(baseUrl, liveSessionIdentities, mTerminalSession);
    }

    private void removeCachedTerminalsForServer(String baseUrl) {
        if (mTerminalCache != null) mTerminalCache.removeServer(baseUrl, mTerminalSession);
    }

    private void handleKey(int keyCode) {
        if (mTerminalView != null) mTerminalView.handleKeyCode(keyCode, 0);
    }

    private void writeTerminal(String data) {
        if (mTerminalSession != null) mTerminalSession.write(data);
    }

    private void showKeyboard() {
        if (mTerminalView == null) return;
        mTerminalView.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(mTerminalView, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    @Override
    public void onTerminalViewTapped() {
        showKeyboard();
    }

    @Override
    public boolean readTerminalControlKey() {
        return mCtrlDown;
    }

    @Override
    public void clearTerminalControlKey() {
        if (mCtrlDown) {
            mCtrlDown = false;
            runOnUiThread(() -> {
                if (mCtrlButton != null) {
                    TerminalScreenBuilder.updateCtrlButtonState(MainActivity.this, mCtrlButton, false);
                }
            });
        }
    }

    @Override
    public void login(String baseUrl, String username, String password, ServerConfigDialogHelper.LoginCallback callback) {
        mApi.login(baseUrl, username, password, new WebTermApi.LoginCallback() {
            @Override
            public void onReady(String readyBaseUrl, String cookie) {
                callback.onReady(readyBaseUrl, cookie);
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    private void connectTerminal() {
        if (mTerminalConnection == null || !mTerminalState.hasSession() || mTerminalState.baseUrl() == null || mTerminalState.cookie() == null) return;
        mTerminalConnection.updateSize(mTerminalState.columns(), mTerminalState.rows());
        mTerminalConnection.connect(mTerminalState.baseUrl(), mTerminalState.cookie(), mTerminalState.sessionId(), mTerminalState.lastSeq());
    }

    private void appendOutput(String data) {
        if (mTerminalSession != null) mTerminalSession.appendOutput(data);
    }

    private void appendOutput(byte[] data) {
        if (mTerminalSession != null) mTerminalSession.appendOutput(data);
    }

    private void appendStatus(String line) {
        appendOutput("\r\n[" + line + "]\r\n");
    }

    @Override
    public void onConnectionStatus(String text, boolean connected) {
        runOnUiThread(() -> mConnectionStatus.update(text, connected));
    }

    @Override
    public void onOutput(long seq, byte[] data) {
        if (seq > 0) {
            mTerminalState.onOutput(seq, data);
            if (mTerminalConnection != null) mTerminalConnection.updateLastSeq(seq);
        }
        appendOutput(data);
    }

    @Override
    public void onInfo(JSONObject info) {
        String termTitle = info.optString("termTitle", "").trim();
        String name = info.optString("name", "").trim();
        String instanceId = info.optString("instanceId", "").trim();
        String createdAt = info.optString("createdAt", "").trim();
        mTerminalState.updateIdentity(instanceId, createdAt);
        runOnUiThread(() -> {
            if (mTerminalTitle != null && !termTitle.isEmpty()) mTerminalTitle.setText(termTitle);
            if (mTerminalSubtitle != null && !name.isEmpty()) mTerminalSubtitle.setText(name);
        });
    }

    @Override
    public void onExit(int code) {
        appendStatus("Remote session exited");
        removeCachedTerminal(mTerminalState.baseUrl(), mTerminalState.sessionId());
        if (mTerminalSession != null) mTerminalSession.notifyExternalSessionFinished(code);
    }

    @Override
    public void onProtocolError(String message) {
        appendStatus(message);
    }

    @Override
    public void onTerminalInput(String data) {
        if (mTerminalConnection != null) mTerminalConnection.sendInput(data);
    }

    @Override
    public void onTerminalResize(int columns, int rows) {
        mTerminalState.updateSize(columns, rows);
        if (mTerminalConnection != null) mTerminalConnection.updateSize(columns, rows);
    }

    @Override
    public void onTerminalTextChanged() {
        if (mTerminalView != null) mTerminalView.onScreenUpdated();
        updateKeyboardAvoidance();
    }

    @Override
    public TextView terminalTitleView() {
        return mTerminalTitle;
    }

    int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public int getSavedFontSize() {
        return mConfigStore.getFontSize();
    }

    @Override
    public String getSavedFontType() {
        return mConfigStore.getFontType();
    }

    @Override
    public Typeface getTypefaceByName(String type) {
        if ("sans-serif".equals(type)) return Typeface.SANS_SERIF;
        if ("serif".equals(type)) return Typeface.SERIF;
        if ("default".equals(type)) return Typeface.DEFAULT;
        return Typeface.MONOSPACE;
    }

    private void showSettingsDialog() {
        SettingsDialogHelper.show(this);
    }

    @Override
    public String getFontDisplayName(String fontType) {
        if ("sans-serif".equals(fontType)) return "Sans Serif";
        if ("serif".equals(fontType)) return "Serif";
        if ("default".equals(fontType)) return "Default";
        return "Monospace";
    }

    @Override
    public void saveFontSize(int size) {
        mConfigStore.saveFontSize(size);
    }

    @Override
    public void saveFontType(String type) {
        mConfigStore.saveFontType(type);
    }

    @Override
    public void applyTerminalFontSize(int size) {
        if (mTerminalView != null) mTerminalView.setTextSize(size);
    }

    @Override
    public void applyTerminalTypeface(Typeface typeface) {
        if (mTerminalView != null) mTerminalView.setTypeface(typeface);
    }
}
