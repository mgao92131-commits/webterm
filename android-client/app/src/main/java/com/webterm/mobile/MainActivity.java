package com.webterm.mobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalRow;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;

public final class MainActivity extends Activity implements TerminalSessionClient, TerminalSession.ExternalIOClient, SessionRowActions, TerminalConnection.Listener {

    private static final String TAG = "WebTermMobile";
    private static final int TRANSCRIPT_ROWS = 20000;
    private static final int MAX_TERM_TITLE_CHARS = 256;
    private static final long HOME_REFRESH_INITIAL_DELAY_MS = 3000L;
    private static final long HOME_REFRESH_MAX_DELAY_MS = 60000L;
    private final OkHttpClient mHttp = new OkHttpClient();
    private final WebTermApi mApi = new WebTermApi(mHttp);
    private final AtomicBoolean mClosed = new AtomicBoolean(false);
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private String mLastUploadedTitle = "";
    private String mPendingTitle = "";
    private final Runnable mUploadTitleRunnable = this::uploadTerminalTitle;

    TerminalView mTerminalView;
    private TerminalSession mTerminalSession;
    private TerminalConnection mTerminalConnection;
    private String mCookie;
    private String mBaseUrl;
    private String mSessionId;
    private String mSessionInstanceId = "";
    private long mLastSeq;
    private long mPersistedSeq;
    private final java.util.List<TerminalDiskCache.Frame> mPendingDiskFrames = new java.util.ArrayList<>();
    private boolean mCtrlDown;
    private boolean mInForeground = true;
    private long mHomeRefreshDelayMs = HOME_REFRESH_INITIAL_DELAY_MS;
    private TerminalDiskCache mDiskCache;
    private ServerConfigStore mConfigStore;
    private final java.util.Map<String, CachedTerminal> mTerminalCache = new java.util.HashMap<>();
    private LinearLayout mSessionList;
    private TextView mSessionStatus;
    private View mConnectionStatusIndicator;
    private ImageButton mRetryButton;
    private AlphaAnimation mConnectingAnimation;
    private TextView mTerminalTitle;
    private TextView mTerminalSubtitle;

    private static final class CachedTerminal {
        final String baseUrl;
        final String sessionId;
        TerminalSession terminalSession;
        String cookie;
        String instanceId;
        String termTitle;
        String sessionName;
        String createdAt;
        long lastSeq;
        long persistedSeq;
        int columns;
        int rows;
        final java.util.List<TerminalDiskCache.Frame> pendingDiskFrames = new java.util.ArrayList<>();

        CachedTerminal(String baseUrl, String cookie, String sessionId, String instanceId, String termTitle, String sessionName, String createdAt, TerminalSession terminalSession) {
            this.baseUrl = baseUrl;
            this.cookie = cookie;
            this.sessionId = sessionId;
            this.instanceId = instanceId;
            this.termTitle = termTitle;
            this.sessionName = sessionName;
            this.createdAt = createdAt;
            this.terminalSession = terminalSession;
        }
    }

    private static final class ServerGroupHolder {
        final ServerConfig server;
        final LinearLayout subList;
        final TextView status;
        ServerSessionMonitor monitor;
        JSONArray lastSessions = null;
        ServerGroupHolder(ServerConfig server, LinearLayout subList, TextView status) {
            this.server = server;
            this.subList = subList;
            this.status = status;
        }
    }
    private final java.util.List<ServerGroupHolder> mActiveGroups = new java.util.ArrayList<>();
    private final Runnable mHomeRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isHomeRefreshActive()) return;
            boolean needsFallbackRefresh = false;
            for (ServerGroupHolder holder : mActiveGroups) {
                if (holder.monitor == null || !holder.monitor.isConnected()) {
                    needsFallbackRefresh = true;
                    loadSessionsForServer(holder.server, holder.subList, holder.status, null);
                }
            }
            if (needsFallbackRefresh) {
                long nextDelay = nextHomeRefreshDelay();
                scheduleHomeRefresh(nextDelay);
            }
        }
    };

    final java.util.List<ServerConfig> mServers = new java.util.ArrayList<>();
    private final java.util.Map<String, Boolean> mServerCollapsed = new java.util.HashMap<>();
    private View mTerminalRoot;
    private View mTerminalViewport;
    private View mQuickBar;
    private String mSessionCreatedAt = "";
    private int mImeOverlap;
    private int mTerminalColumns;
    private int mTerminalRows;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        mConfigStore = new ServerConfigStore(this);
        mDiskCache = new TerminalDiskCache(getFilesDir());
        mTerminalConnection = new TerminalConnection(mHttp, mMainHandler, this);
        loadServersFromPrefs();
        showSessionHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mInForeground = true;
        if (mSessionId != null && mTerminalSession != null && (mTerminalConnection == null || !mTerminalConnection.hasSocket())) {
            mClosed.set(false);
            connectTerminal();
        } else if (mSessionId == null && mSessionList != null) {
            for (ServerGroupHolder holder : mActiveGroups) {
                connectManagerWS(holder);
            }
            resetHomeRefreshBackoff();
            scheduleHomeRefresh(HOME_REFRESH_INITIAL_DELAY_MS);
        }
    }

    @Override
    protected void onPause() {
        mInForeground = false;
        mMainHandler.removeCallbacks(mHomeRefreshRunnable);
        if (mSessionId == null) {
            for (ServerGroupHolder holder : mActiveGroups) {
                closeManagerWS(holder);
            }
        } else {
            pauseCurrentTerminalConnection();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mClosed.set(true);
        mMainHandler.removeCallbacksAndMessages(null);
        for (ServerGroupHolder holder : mActiveGroups) {
            closeManagerWS(holder);
        }
        if (mSessionId != null && mTerminalSession != null) {
            flushPendingDiskFrames();
            cacheCurrentTerminal();
        }
        if (mTerminalConnection != null) mTerminalConnection.close("activity closed");
        if (mTerminalSession != null) mTerminalSession.finishIfRunning();
        for (CachedTerminal cached : mTerminalCache.values()) {
            if (cached.terminalSession != null && cached.terminalSession != mTerminalSession) cached.terminalSession.finishIfRunning();
        }
        mTerminalCache.clear();
        if (mDiskCache != null) mDiskCache.shutdown();
        mHttp.dispatcher().cancelAll();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mSessionId != null) {
            showSessionHome();
            return;
        }
        super.onBackPressed();
    }

    private void loadServersFromPrefs() {
        mServers.clear();
        mServers.addAll(mConfigStore.loadServers());
    }

    void saveServersToPrefs() {
        mConfigStore.saveServers(mServers);
    }

    void showSessionHome() {
        closeCurrentTerminal(false);
        mClosed.set(false);
        mBaseUrl = null;
        mCookie = null;

        HomeScreenBuilder.HomeResult home = HomeScreenBuilder.buildHome(
            this,
            () -> showAddServerDialog(null),
            this::showSettingsDialog,
            this::loadMultiSessions
        );
        installRootInsets(home.root, dp(20), dp(24), dp(20), dp(16), true);
        mSessionList = home.sessionList;
        setContentView(home.root);
        resetHomeRefreshBackoff();
        loadMultiSessions();
        scheduleHomeRefresh(HOME_REFRESH_INITIAL_DELAY_MS);
    }

    private void loadMultiSessions() {
        resetHomeRefreshBackoff();
        if (mSessionList == null) return;
        java.util.Map<String, JSONArray> tempInMemorySessions = new java.util.HashMap<>();
        for (ServerGroupHolder holder : mActiveGroups) {
            if (holder.lastSessions != null && holder.server != null && holder.server.url != null) {
                tempInMemorySessions.put(normalizeBaseUrl(holder.server.url), holder.lastSessions);
            }
        }
        for (ServerGroupHolder holder : mActiveGroups) {
            closeManagerWS(holder);
        }
        mActiveGroups.clear();
        mSessionList.removeAllViews();

        if (mServers.isEmpty()) {
            mSessionList.addView(HomeScreenBuilder.emptyState(this), new LinearLayout.LayoutParams(-1, -2));
            return;
        }

        for (ServerConfig s : mServers) {
            renderServerGroup(s, tempInMemorySessions);
        }
        scheduleHomeRefresh(HOME_REFRESH_INITIAL_DELAY_MS);
    }

    private boolean isHomeRefreshActive() {
        return mInForeground && !mClosed.get() && mSessionId == null && mSessionList != null;
    }

    private void resetHomeRefreshBackoff() {
        mHomeRefreshDelayMs = HOME_REFRESH_INITIAL_DELAY_MS;
        mMainHandler.removeCallbacks(mHomeRefreshRunnable);
    }

    private void scheduleHomeRefresh(long delayMs) {
        mMainHandler.removeCallbacks(mHomeRefreshRunnable);
        if (!isHomeRefreshActive()) return;
        boolean needsFallbackRefresh = false;
        for (ServerGroupHolder holder : mActiveGroups) {
            if (holder.monitor == null || !holder.monitor.isConnected()) {
                needsFallbackRefresh = true;
                break;
            }
        }
        if (needsFallbackRefresh) {
            mMainHandler.postDelayed(mHomeRefreshRunnable, Math.max(HOME_REFRESH_INITIAL_DELAY_MS, delayMs));
        }
    }

    private long nextHomeRefreshDelay() {
        mHomeRefreshDelayMs = Math.min(HOME_REFRESH_MAX_DELAY_MS, Math.max(HOME_REFRESH_INITIAL_DELAY_MS, mHomeRefreshDelayMs * 2));
        return mHomeRefreshDelayMs;
    }

    private void renderServerGroup(ServerConfig server, java.util.Map<String, JSONArray> tempInMemorySessions) {
        boolean collapsed = mServerCollapsed.containsKey(server.id) && Boolean.TRUE.equals(mServerCollapsed.get(server.id));
        HomeScreenBuilder.ServerGroupResult group = HomeScreenBuilder.buildServerGroup(
            this,
            server,
            collapsed,
            (nextCollapsed) -> mServerCollapsed.put(server.id, nextCollapsed),
            () -> createSessionOnServer(server),
            () -> showAddServerDialog(server),
            () -> confirmRemoveServer(server)
        );
        mSessionList.addView(group.group, new LinearLayout.LayoutParams(-1, -2));

        mActiveGroups.add(new ServerGroupHolder(server, group.subList, group.status));
        ServerGroupHolder holder = mActiveGroups.get(mActiveGroups.size() - 1);
        loadSessionsForServer(server, group.subList, group.status, tempInMemorySessions);
        connectManagerWS(holder);
    }

    private void confirmRemoveServer(ServerConfig server) {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("确认移除电脑")
            .setMessage("确定要从列表中移除该服务器吗？")
            .setPositiveButton("移除", (dialog, which) -> {
                removeCachedTerminalsForServer(server.url);
                mServers.remove(server);
                saveServersToPrefs();
                loadMultiSessions();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void createSessionOnServer(ServerConfig server) {
        createSession(server.url, server.cookie, new SessionCreateCallback() {
            @Override
            public void onReady(String sessionId) {
                runOnUiThread(() -> {
                    showTerminal(server.url, server.cookie, sessionId, "Terminal", "");
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    if (message.contains("401") && server.password != null && !server.password.isEmpty()) {
                        silentLoginAndCreate(server);
                    } else {
                        Toast.makeText(MainActivity.this, "创建失败: " + message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void silentLoginAndCreate(ServerConfig server) {
        login(server.url, server.username, server.password, new LoginCallback() {
            @Override
            public void onReady(String baseUrl, String cookie) {
                server.cookie = cookie;
                saveServersToPrefs();
                createSessionOnServer(server);
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "静默登录失败，无法创建会话: " + message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void loadSessionsForServer(ServerConfig server, LinearLayout subList, TextView status, java.util.Map<String, JSONArray> tempInMemorySessions) {
        if (server.url.isEmpty()) return;
        status.setText("🟡");
        status.setTextColor(Color.rgb(245, 158, 11));

        boolean loadedFromL1 = false;
        if (tempInMemorySessions != null) {
            JSONArray l1Sessions = tempInMemorySessions.get(normalizeBaseUrl(server.url));
            if (l1Sessions != null) {
                ServerGroupHolder holder = findHolderForServer(server);
                if (holder != null) {
                    holder.lastSessions = l1Sessions;
                }
                renderServerSessions(server, l1Sessions, subList);
                subList.setTag("cached_list");
                loadedFromL1 = true;
            }
        }

        // Pre-populate with cached sessions immediately if available, to avoid empty screen during connection
        if (!loadedFromL1 && mDiskCache != null) {
            mHttp.dispatcher().executorService().execute(() -> {
                java.util.List<TerminalDiskCache.Metadata> cached = mDiskCache.getCachedSessionsForServer(server.url);
                if (cached != null && !cached.isEmpty()) {
                    runOnUiThread(() -> {
                        if (subList.getChildCount() == 0) {
                            JSONArray sessions = new JSONArray();
                            for (TerminalDiskCache.Metadata meta : cached) {
                                JSONObject session = new JSONObject();
                                try {
                                    session.put("id", meta.sessionId);
                                    session.put("instanceId", meta.instanceId);
                                    session.put("name", meta.sessionName);
                                    session.put("termTitle", meta.termTitle);
                                    session.put("createdAt", meta.createdAt);
                                    session.put("cols", meta.columns);
                                    session.put("rows", meta.rows);
                                    sessions.put(session);
                                } catch (JSONException ignored) {}
                            }
                            renderServerSessions(server, sessions, subList);
                            subList.setTag("cached_list");
                        }
                    });
                }
            });
        }

        if (server.cookie != null && !server.cookie.isEmpty()) {
            fetchSessionsDirectly(server, subList, status);
        } else if (server.password != null && !server.password.isEmpty()) {
            silentLoginAndFetch(server, subList, status);
        } else {
            status.setText("🔴");
            status.setTextColor(Color.rgb(239, 68, 68));
            renderErrorItem(server, subList, status, "需要登录");
        }
    }

    private boolean shouldKeepExistingList(LinearLayout subList) {
        if (subList == null || subList.getChildCount() == 0) return false;
        View firstChild = subList.getChildAt(0);
        Object tag = firstChild.getTag();
        return tag == null || (!"error_item".equals(tag) && !"empty_item".equals(tag));
    }

    private void fetchSessionsDirectly(ServerConfig server, LinearLayout subList, TextView status) {
        mApi.fetchSessions(server, new WebTermApi.SessionsCallback() {
            @Override
            public void onReady(JSONArray sessions) {
                runOnUiThread(() -> {
                    status.setText("🟢");
                    status.setTextColor(Color.rgb(16, 185, 129));
                    ServerGroupHolder holder = findHolderForServer(server);
                    if (holder != null) {
                        holder.lastSessions = sessions;
                    }
                    renderServerSessions(server, sessions, subList);
                });
            }

            @Override
            public void onError(int code, String message) {
                if (code == 401 && server.password != null && !server.password.isEmpty()) {
                    silentLoginAndFetch(server, subList, status);
                    return;
                }
                showOfflineCachedSessions(server, subList, status, code > 0 ? "HTTP " + code : message);
            }

            @Override
            public void onParseError(String message) {
                runOnUiThread(() -> {
                    status.setText("🔴");
                    status.setTextColor(Color.rgb(239, 68, 68));
                    if (!shouldKeepExistingList(subList)) {
                        renderErrorItem(server, subList, status, "JSON 解析错误");
                    }
                });
            }
        });
    }

    private void silentLoginAndFetch(ServerConfig server, LinearLayout subList, TextView status) {
        login(server.url, server.username, server.password, new LoginCallback() {
            @Override
            public void onReady(String baseUrl, String cookie) {
                server.cookie = cookie;
                saveServersToPrefs();
                runOnUiThread(() -> fetchSessionsDirectly(server, subList, status));
            }

            @Override
            public void onError(String message) {
                showOfflineCachedSessions(server, subList, status, "登录失败: " + message);
            }
        });
    }

    private void showOfflineCachedSessions(ServerConfig server, LinearLayout subList, TextView status, String errorMsg) {
        java.util.List<TerminalDiskCache.Metadata> cachedMetadata = null;
        if (mDiskCache != null) {
            cachedMetadata = mDiskCache.getCachedSessionsForServer(server.url);
        }
        
        final java.util.List<TerminalDiskCache.Metadata> finalCached = cachedMetadata;
        
        runOnUiThread(() -> {
            if (shouldKeepExistingList(subList)) {
                status.setText("🔴");
                status.setTextColor(Color.rgb(239, 68, 68));
                return;
            }
            
            if (finalCached != null && !finalCached.isEmpty()) {
                status.setText("🔴");
                status.setTextColor(Color.rgb(239, 68, 68));
                
                JSONArray sessions = new JSONArray();
                for (TerminalDiskCache.Metadata meta : finalCached) {
                    JSONObject session = new JSONObject();
                    try {
                        session.put("id", meta.sessionId);
                        session.put("instanceId", meta.instanceId);
                        session.put("name", meta.sessionName);
                        session.put("termTitle", meta.termTitle);
                        session.put("createdAt", meta.createdAt);
                        session.put("cols", meta.columns);
                        session.put("rows", meta.rows);
                        sessions.put(session);
                    } catch (JSONException ignored) {}
                }
                
                renderServerSessions(server, sessions, subList);
                subList.setTag("cached_list");
            } else {
                status.setText("🔴");
                status.setTextColor(Color.rgb(239, 68, 68));
                renderErrorItem(server, subList, status, errorMsg);
            }
        });
    }

    private void renderErrorItem(ServerConfig server, LinearLayout subList, TextView status, String error) {
        subList.removeAllViews();
        subList.addView(
            SessionListItemViews.errorItem(this, error, () -> loadSessionsForServer(server, subList, status, null)),
            new LinearLayout.LayoutParams(-1, -2)
        );
    }

    private void renderServerSessions(ServerConfig server, JSONArray sessions, LinearLayout subList) {
        subList.setTag("online_list");
        java.util.Set<String> newIds = new java.util.HashSet<>();
        java.util.Set<String> liveIdentities = new java.util.HashSet<>();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session != null) {
                String id = session.optString("id");
                newIds.add(id);
                String identity = sessionIdentity(id, session.optString("instanceId", ""), session.optString("createdAt", ""));
                if (!identity.isEmpty()) liveIdentities.add(identity);
            }
        }
        removeMissingCachedSessionsForServer(server.url, liveIdentities);

        for (int i = subList.getChildCount() - 1; i >= 0; i--) {
            View child = subList.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof String) {
                String id = (String) tag;
                if (!newIds.contains(id)) {
                    subList.removeViewAt(i);
                }
            } else if ("empty_item".equals(tag) || "error_item".equals(tag)) {
                subList.removeViewAt(i);
            }
        }

        if (sessions.length() == 0) {
            subList.removeAllViews();
            subList.addView(SessionListItemViews.emptyItem(this));
            return;
        }

        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session == null) continue;
            String id = session.optString("id");
            View existingRow = null;
            for (int j = 0; j < subList.getChildCount(); j++) {
                View child = subList.getChildAt(j);
                if (id.equals(child.getTag())) {
                    existingRow = child;
                    break;
                }
            }

            if (existingRow != null) {
                SessionRowHelper.updateSessionRow(this, existingRow, session, server);
            } else {
                addSessionRow(session, server, subList);
            }
        }
    }

    private void addSessionRow(JSONObject session, ServerConfig server, LinearLayout subList) {
        SessionRowHelper.addSessionRow(this, this, session, server, subList);
    }

    private void showAddServerDialog(final ServerConfig existingServer) {
        ServerConfigDialogHelper.show(this, existingServer);
    }

    void showRenameDialog(ServerConfig server, String sessionId, String oldName) {
        RenameSessionDialogHelper.show(() -> this, oldName, (newName, dialog) -> {
            mApi.renameSession(server, sessionId, newName, new WebTermApi.SimpleCallback() {
                @Override
                public void onReady() {
                    runOnUiThread(() -> {
                        showSessionHome();
                        dialog.dismiss();
                    });
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "重命名失败: " + message, Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    });
                }
            });
        });
    }

    void showCloseConfirmDialog(ServerConfig server, String sessionId) {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("❌ 关闭终端会话")
            .setMessage("确定要关闭该终端会话吗？这将会终结其在服务器上的后台进程。")
            .setPositiveButton("关闭", (dialog, which) -> {
                mApi.deleteSession(server.url, server.cookie, sessionId, new WebTermApi.SimpleCallback() {
                    @Override
                    public void onReady() {
                        removeCachedTerminal(server.url, sessionId);
                        runOnUiThread(() -> showSessionHome());
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "关闭失败: " + message, Toast.LENGTH_SHORT).show());
                    }
                });
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void deleteSession(String sessionId) {
        if (mBaseUrl == null || mCookie == null) return;
        mApi.deleteSession(mBaseUrl, mCookie, sessionId, new WebTermApi.SimpleCallback() {
            @Override
            public void onReady() {
                runOnUiThread(MainActivity.this::showSessionHome);
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
            }
        });
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
        mMainHandler.removeCallbacks(mHomeRefreshRunnable);
        for (ServerGroupHolder holder : mActiveGroups) {
            closeManagerWS(holder);
        }
        String normalizedInstanceId = normalizeIdentityPart(instanceId);
        String normalizedCreatedAt = normalizeIdentityPart(createdAt);
        String cacheKey = terminalCacheKey(baseUrl, sessionId, normalizedInstanceId, normalizedCreatedAt);
        CachedTerminal cached = cacheKey.isEmpty() ? null : mTerminalCache.get(cacheKey);
        final TerminalDiskCache.RestoreResult[] diskRestore = new TerminalDiskCache.RestoreResult[1];
        if (cached == null && mDiskCache != null && !cacheKey.isEmpty()) {
            diskRestore[0] = mDiskCache.restore(baseUrl, sessionId, normalizedInstanceId, normalizedCreatedAt, null);
        }
        mBaseUrl = baseUrl;
        mCookie = cookie;
        mSessionId = sessionId;
        TerminalDiskCache.Metadata diskMetadata = diskRestore[0] == null ? null : diskRestore[0].metadata;
        String headerTitle = cached != null && cached.termTitle != null && !cached.termTitle.trim().isEmpty()
            ? cached.termTitle.trim()
            : (diskMetadata != null && diskMetadata.termTitle != null && !diskMetadata.termTitle.trim().isEmpty()
                ? diskMetadata.termTitle.trim()
                : (termTitle == null || termTitle.trim().isEmpty() ? "Terminal" : termTitle.trim()));
        String headerSubtitle = cached != null && cached.sessionName != null && !cached.sessionName.trim().isEmpty()
            ? cached.sessionName.trim()
            : (diskMetadata != null && diskMetadata.sessionName != null && !diskMetadata.sessionName.trim().isEmpty()
                ? diskMetadata.sessionName.trim()
                : (sessionName == null || sessionName.trim().isEmpty() ? sessionId : sessionName.trim()));
        mSessionCreatedAt = cached != null && cached.createdAt != null && !cached.createdAt.trim().isEmpty()
            ? cached.createdAt.trim()
            : (diskMetadata != null && diskMetadata.createdAt != null && !diskMetadata.createdAt.trim().isEmpty()
                ? diskMetadata.createdAt.trim()
                : normalizedCreatedAt);
        mSessionInstanceId = cached != null && cached.instanceId != null && !cached.instanceId.trim().isEmpty()
            ? cached.instanceId.trim()
            : (diskMetadata != null && diskMetadata.instanceId != null && !diskMetadata.instanceId.trim().isEmpty()
                ? diskMetadata.instanceId.trim()
                : normalizedInstanceId);
        mClosed.set(false);
        mLastSeq = cached != null ? cached.lastSeq : (diskRestore[0] != null ? diskRestore[0].lastSeq : 0);
        mPersistedSeq = cached != null ? cached.persistedSeq : (diskRestore[0] != null ? diskRestore[0].lastSeq : 0);
        mPendingDiskFrames.clear();
        if (cached != null) {
            mPendingDiskFrames.addAll(cached.pendingDiskFrames);
        }
        mTerminalColumns = cached != null ? cached.columns : (diskMetadata != null ? diskMetadata.columns : 0);
        mTerminalRows = cached != null ? cached.rows : (diskMetadata != null ? diskMetadata.rows : 0);

        if (mConnectingAnimation == null) {
            mConnectingAnimation = new AlphaAnimation(1.0f, 0.2f);
            mConnectingAnimation.setDuration(600);
            mConnectingAnimation.setRepeatMode(Animation.REVERSE);
            mConnectingAnimation.setRepeatCount(Animation.INFINITE);
        }

        TerminalScreenBuilder.Result terminalScreen = TerminalScreenBuilder.build(
            this,
            headerTitle,
            headerSubtitle,
            getSavedFontSize(),
            getTypefaceByName(getSavedFontType()),
            new NativeTerminalViewClient(),
            this::showSessionHome,
            () -> {
                if (mTerminalConnection != null) mTerminalConnection.reconnectNow();
            },
            () -> mCtrlDown = true,
            this::writeTerminal
        );
        mTerminalRoot = terminalScreen.root;
        mTerminalView = terminalScreen.terminalView;
        mTerminalViewport = terminalScreen.terminalViewport;
        mQuickBar = terminalScreen.quickBar;
        mTerminalTitle = terminalScreen.title;
        mTerminalSubtitle = terminalScreen.subtitle;
        mRetryButton = terminalScreen.retryButton;
        mConnectionStatusIndicator = terminalScreen.statusIndicator;
        installRootInsets(mTerminalRoot, 0, 0, 0, 0, false);
        setContentView(mTerminalRoot);
        mTerminalRoot.post(this::updateKeyboardAvoidance);

        mTerminalSession = cached != null && cached.terminalSession != null
            ? cached.terminalSession
            : TerminalSession.createExternalSession(TRANSCRIPT_ROWS, this, this);
        mTerminalView.attachSession(mTerminalSession);
        if (cached == null && diskRestore[0] != null && mDiskCache != null) {
            mDiskCache.restore(baseUrl, sessionId, mSessionInstanceId, mSessionCreatedAt, (seq, bytes) -> {
                if (mTerminalSession != null) mTerminalSession.appendOutput(bytes);
            });
            cacheCurrentTerminal();
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            root.setPadding(baseLeft, baseTop, baseRight, baseBottom);
            return;
        }
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int statusTop = insets.getInsets(WindowInsets.Type.statusBars()).top;
            int navBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            int imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
            mImeOverlap = Math.max(0, imeBottom - navBottom);
            int bottomInset = avoidImeWithPadding && imeBottom > 0 ? imeBottom : navBottom;
            view.setPadding(baseLeft, baseTop + statusTop, baseRight, baseBottom + bottomInset);
            updateKeyboardAvoidance();
            return insets;
        });
    }

    private void updateKeyboardAvoidance() {
        View root = mTerminalRoot;
        View viewport = mTerminalViewport;
        View quickBar = mQuickBar;
        TerminalView terminal = mTerminalView;
        if (quickBar != null) quickBar.setTranslationY(mImeOverlap > 0 ? -mImeOverlap : 0);
        if (terminal == null) return;
        if (mImeOverlap <= 0 || root == null || viewport == null || terminal.mEmulator == null || terminal.mRenderer == null) {
            terminal.setTranslationY(0);
            return;
        }

        terminal.setTopRow(0);
        terminal.onScreenUpdated(true);

        int[] rootLocation = new int[2];
        int[] viewportLocation = new int[2];
        root.getLocationOnScreen(rootLocation);
        viewport.getLocationOnScreen(viewportLocation);

        int visibleRows = terminal.mEmulator.mRows;
        int lineHeight = terminal.mRenderer.getFontLineSpacing();
        int quickBarHeight = quickBar == null ? dp(92) : quickBar.getHeight();
        int protectedRow = protectedKeyboardRow(terminal);
        int protectedBottom = viewportLocation[1] - rootLocation[1] + (protectedRow + 1) * lineHeight;
        int quickBarTop = root.getHeight() - root.getPaddingBottom() - mImeOverlap - quickBarHeight;
        int neededShift = protectedBottom + dp(12) - quickBarTop;
        int shift = Math.max(0, neededShift);
        terminal.setTranslationY(-shift);
        terminal.invalidate();
    }

    private int protectedKeyboardRow(TerminalView terminal) {
        int visibleRows = terminal.mEmulator.mRows;
        int cursorRow = terminal.mEmulator.getCursorRow();
        int lastContentRow = 0;
        TerminalBuffer screen = terminal.mEmulator.getScreen();
        for (int row = visibleRows - 1; row >= 0; row--) {
            TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            if (!line.isBlank()) {
                lastContentRow = row;
                break;
            }
        }
        return Math.max(0, Math.min(visibleRows - 1, Math.max(cursorRow, lastContentRow)));
    }

    private void closeCurrentTerminal(boolean closeRemote) {
        String closingBaseUrl = mBaseUrl;
        String closingSessionId = mSessionId;
        if (!closeRemote) {
            flushPendingDiskFrames();
            cacheCurrentTerminal();
        }
        mClosed.set(true);
        closeTerminalConnection("leaving terminal");
        if (mTerminalSession != null && closeRemote) {
            mTerminalSession.finishIfRunning();
        }
        mTerminalSession = null;
        mTerminalView = null;
        mConnectionStatusIndicator = null;
        mRetryButton = null;
        mTerminalTitle = null;
        mTerminalSubtitle = null;
        mTerminalRoot = null;
        mTerminalViewport = null;
        mQuickBar = null;
        mSessionInstanceId = "";
        mSessionCreatedAt = "";
        mImeOverlap = 0;
        mTerminalColumns = 0;
        mTerminalRows = 0;
        mMainHandler.removeCallbacks(mUploadTitleRunnable);
        mLastUploadedTitle = "";
        mPendingTitle = "";
        if (closeRemote && closingSessionId != null) {
            removeCachedTerminal(closingBaseUrl, closingSessionId);
            deleteSession(closingSessionId);
        }
        mSessionId = null;
        mLastSeq = 0;
        mPersistedSeq = 0;
        mPendingDiskFrames.clear();
    }

    private void pauseCurrentTerminalConnection() {
        flushPendingDiskFrames();
        cacheCurrentTerminal();
        closeTerminalConnection("activity paused");
    }

    private void closeTerminalConnection(String reason) {
        mMainHandler.removeCallbacks(mUploadTitleRunnable);
        if (mTerminalConnection != null) mTerminalConnection.close(reason);
    }

    private void cacheCurrentTerminal() {
        if (mBaseUrl == null || mSessionId == null || mTerminalSession == null) return;
        String key = terminalCacheKey(mBaseUrl, mSessionId, mSessionInstanceId, mSessionCreatedAt);
        if (key.isEmpty()) return;
        String title = mTerminalTitle == null ? "" : String.valueOf(mTerminalTitle.getText());
        String subtitle = mTerminalSubtitle == null ? "" : String.valueOf(mTerminalSubtitle.getText());
        CachedTerminal cached = mTerminalCache.get(key);
        if (cached == null) {
            cached = new CachedTerminal(mBaseUrl, mCookie, mSessionId, mSessionInstanceId, title, subtitle, mSessionCreatedAt, mTerminalSession);
            mTerminalCache.put(key, cached);
        }
        cached.cookie = mCookie;
        cached.instanceId = mSessionInstanceId;
        cached.termTitle = title;
        cached.sessionName = subtitle;
        cached.createdAt = mSessionCreatedAt;
        cached.terminalSession = mTerminalSession;
        cached.lastSeq = mLastSeq;
        cached.persistedSeq = mPersistedSeq;
        cached.pendingDiskFrames.clear();
        cached.pendingDiskFrames.addAll(mPendingDiskFrames);
        cached.columns = mTerminalColumns;
        cached.rows = mTerminalRows;
    }

    private TerminalDiskCache.Metadata currentDiskMetadata() {
        if (mBaseUrl == null || mSessionId == null) return null;
        TerminalDiskCache.Metadata metadata = new TerminalDiskCache.Metadata();
        metadata.baseUrl = mBaseUrl;
        metadata.sessionId = mSessionId;
        metadata.instanceId = mSessionInstanceId == null ? "" : mSessionInstanceId;
        metadata.createdAt = mSessionCreatedAt == null ? "" : mSessionCreatedAt;
        metadata.termTitle = mTerminalTitle == null ? "" : String.valueOf(mTerminalTitle.getText());
        metadata.sessionName = mTerminalSubtitle == null ? "" : String.valueOf(mTerminalSubtitle.getText());
        metadata.columns = mTerminalColumns;
        metadata.rows = mTerminalRows;
        metadata.lastSeq = mPersistedSeq;
        return metadata;
    }

    private void queuePendingDiskFrame(long seq, byte[] bytes) {
        if (seq <= 0 || bytes == null || bytes.length == 0) return;
        mPendingDiskFrames.add(new TerminalDiskCache.Frame(seq, bytes.clone()));
    }

    private void flushPendingDiskFrames() {
        if (mDiskCache == null || mPendingDiskFrames.isEmpty()) return;
        TerminalDiskCache.Metadata metadata = currentDiskMetadata();
        if (metadata == null) return;
        java.util.List<TerminalDiskCache.Frame> frames = new java.util.ArrayList<>(mPendingDiskFrames);
        long persistedSeq = mDiskCache.appendFramesBlocking(metadata, frames);
        if (persistedSeq <= mPersistedSeq) return;
        mPersistedSeq = persistedSeq;
        for (int i = mPendingDiskFrames.size() - 1; i >= 0; i--) {
            if (mPendingDiskFrames.get(i).seq <= mPersistedSeq) {
                mPendingDiskFrames.remove(i);
            }
        }
    }

    private void removeCachedTerminal(String baseUrl, String sessionId) {
        if (sameServerSession(baseUrl, sessionId, mBaseUrl, mSessionId)) {
            mPendingDiskFrames.clear();
            mPersistedSeq = 0;
        }
        java.util.List<String> keys = new java.util.ArrayList<>();
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        for (java.util.Map.Entry<String, CachedTerminal> entry : mTerminalCache.entrySet()) {
            CachedTerminal cached = entry.getValue();
            if (normalizedBaseUrl.equals(normalizeBaseUrl(cached.baseUrl)) && String.valueOf(sessionId).equals(cached.sessionId)) {
                keys.add(entry.getKey());
            }
        }
        for (String key : keys) {
            CachedTerminal cached = mTerminalCache.remove(key);
            if (cached != null && cached.terminalSession != null && cached.terminalSession != mTerminalSession) {
                cached.terminalSession.finishIfRunning();
            }
        }
        if (mDiskCache != null) mDiskCache.clearAsync(baseUrl, sessionId);
    }

    private void removeMissingCachedSessionsForServer(String baseUrl, java.util.Set<String> liveSessionIdentities) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        java.util.List<String> staleKeys = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, CachedTerminal> entry : mTerminalCache.entrySet()) {
            CachedTerminal cached = entry.getValue();
            if (normalizedBaseUrl.equals(normalizeBaseUrl(cached.baseUrl)) && !liveSessionIdentities.contains(sessionIdentity(cached.sessionId, cached.instanceId, cached.createdAt))) {
                staleKeys.add(entry.getKey());
            }
        }
        for (String key : staleKeys) {
            CachedTerminal cached = mTerminalCache.remove(key);
            if (cached != null && cached.terminalSession != null && cached.terminalSession != mTerminalSession) {
                cached.terminalSession.finishIfRunning();
            }
            if (cached != null && mDiskCache != null) {
                mDiskCache.clearAsync(cached.baseUrl, cached.sessionId);
            }
        }
        if (mDiskCache != null) {
            mDiskCache.clearMissingForServerAsync(baseUrl, liveSessionIdentities);
        }
    }

    private void removeCachedTerminalsForServer(String baseUrl) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, CachedTerminal> entry : mTerminalCache.entrySet()) {
            CachedTerminal cached = entry.getValue();
            if (normalizedBaseUrl.equals(normalizeBaseUrl(cached.baseUrl))) {
                keys.add(entry.getKey());
            }
        }
        for (String key : keys) {
            CachedTerminal cached = mTerminalCache.remove(key);
            if (cached != null && cached.terminalSession != null && cached.terminalSession != mTerminalSession) {
                cached.terminalSession.finishIfRunning();
            }
        }
        if (mDiskCache != null) mDiskCache.clearServerAsync(baseUrl);
    }

    private static String terminalCacheKey(String baseUrl, String sessionId, String instanceId, String createdAt) {
        String identity = sessionIdentity(sessionId, instanceId, createdAt);
        if (identity.isEmpty()) return "";
        return normalizeBaseUrl(baseUrl) + "#" + identity;
    }

    static String sessionIdentity(String sessionId, String instanceId, String createdAt) {
        String normalizedInstanceId = normalizeIdentityPart(instanceId);
        if (!normalizedInstanceId.isEmpty()) return "instance:" + normalizedInstanceId;
        String normalizedCreatedAt = normalizeIdentityPart(createdAt);
        if (!normalizedCreatedAt.isEmpty()) {
            return "created:" + String.valueOf(sessionId == null ? "" : sessionId) + "@" + normalizedCreatedAt;
        }
        return "";
    }

    private static String normalizeIdentityPart(String value) {
        return String.valueOf(value == null ? "" : value).trim();
    }

    private static boolean sameServerSession(String baseUrlA, String sessionIdA, String baseUrlB, String sessionIdB) {
        return normalizeBaseUrl(baseUrlA).equals(normalizeBaseUrl(baseUrlB))
            && String.valueOf(sessionIdA == null ? "" : sessionIdA).equals(String.valueOf(sessionIdB == null ? "" : sessionIdB));
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

    void login(String baseUrl, String username, String password, LoginCallback callback) {
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

    private void createSession(String baseUrl, String cookie, SessionCreateCallback callback) {
        mApi.createSession(baseUrl, cookie, new WebTermApi.SessionCreateCallback() {
            @Override
            public void onReady(String sessionId) {
                callback.onReady(sessionId);
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    private void connectTerminal() {
        if (mTerminalConnection == null || mSessionId == null || mBaseUrl == null || mCookie == null) return;
        mTerminalConnection.updateSize(mTerminalColumns, mTerminalRows);
        mTerminalConnection.connect(mBaseUrl, mCookie, mSessionId, mLastSeq);
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
        runOnUiThread(() -> {
            if (mConnectionStatusIndicator == null) return;
            GradientDrawable bg = (GradientDrawable) mConnectionStatusIndicator.getBackground();
            if (bg != null) {
                if (connected) {
                    bg.setColor(Color.rgb(16, 185, 129)); // 薄荷绿：已连接
                    mConnectionStatusIndicator.clearAnimation();
                    if (mRetryButton != null) mRetryButton.setVisibility(View.GONE);
                } else if (text.contains("Connecting") || text.contains("reconnecting")) {
                    bg.setColor(Color.rgb(245, 158, 11)); // 琥珀金：重连/连接中
                    mConnectionStatusIndicator.clearAnimation();
                    if (mConnectingAnimation != null) {
                        mConnectionStatusIndicator.startAnimation(mConnectingAnimation);
                    }
                    if (mRetryButton != null) mRetryButton.setVisibility(View.GONE);
                } else {
                    bg.setColor(Color.rgb(239, 68, 68)); // 珊瑚红：断开
                    mConnectionStatusIndicator.clearAnimation();
                    if (mRetryButton != null) mRetryButton.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    @Override
    public void onOutput(long seq, byte[] data) {
        if (seq > 0) {
            mLastSeq = seq;
            queuePendingDiskFrame(seq, data);
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
        if (!instanceId.isEmpty()) {
            mSessionInstanceId = instanceId;
        }
        if (!createdAt.isEmpty()) {
            mSessionCreatedAt = createdAt;
        }
        runOnUiThread(() -> {
            if (mTerminalTitle != null && !termTitle.isEmpty()) mTerminalTitle.setText(termTitle);
            if (mTerminalSubtitle != null && !name.isEmpty()) mTerminalSubtitle.setText(name);
        });
    }

    @Override
    public void onExit(int code) {
        appendStatus("Remote session exited");
        removeCachedTerminal(mBaseUrl, mSessionId);
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
    public void onTerminalResize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mTerminalColumns = columns;
        mTerminalRows = rows;
        if (mTerminalConnection != null) mTerminalConnection.updateSize(columns, rows);
    }

    @Override
    public void onTextChanged(@NonNull TerminalSession changedSession) {
        if (mTerminalView != null) mTerminalView.onScreenUpdated();
        updateKeyboardAvoidance();
    }

    @Override
    public void onTitleChanged(@NonNull TerminalSession changedSession) {
        String newTitle = sanitizeTermTitle(changedSession.getTitle());
        if (newTitle.isEmpty()) return;

        final String finalTitle = newTitle;
        runOnUiThread(() -> {
            if (mTerminalTitle != null) {
                mTerminalTitle.setText(finalTitle);
            }
            mPendingTitle = finalTitle;
            mMainHandler.removeCallbacks(mUploadTitleRunnable);
            // 300ms 轻量防抖限制，防止个别程序死循环刷屏导致网络积压
            mMainHandler.postDelayed(mUploadTitleRunnable, 300);
        });
    }

    private void uploadTerminalTitle() {
        final String titleToUpload = sanitizeTermTitle(mPendingTitle);
        if (titleToUpload.isEmpty() || titleToUpload.equals(mLastUploadedTitle)) {
            return;
        }
        if (mTerminalConnection == null || !mTerminalConnection.isConnected()) {
            return;
        }
        mTerminalConnection.sendTitle(titleToUpload);
        mLastUploadedTitle = titleToUpload;
        Log.i(TAG, "Uploaded terminal title via WS: " + titleToUpload);
    }

    private static String sanitizeTermTitle(String title) {
        if (title == null) return "";
        title = title.trim();
        if (title.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (int offset = 0; offset < title.length() && count < MAX_TERM_TITLE_CHARS; ) {
            int codePoint = title.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint)) continue;
            builder.appendCodePoint(codePoint);
            count++;
        }
        return builder.toString().trim();
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip() || session == null) return;
        CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (text != null) session.write(text.toString());
    }

    @Override
    public void onBell(@NonNull TerminalSession session) {
    }

    @Override
    public void onColorsChanged(@NonNull TerminalSession session) {
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession session, int pid) {
    }

    @Override
    public Integer getTerminalCursorStyle() {
        return null;
    }

    @Override public void logError(String tag, String message) { android.util.Log.e(tag, message); }
    @Override public void logWarn(String tag, String message) { android.util.Log.w(tag, message); }
    @Override public void logInfo(String tag, String message) { android.util.Log.i(tag, message); }
    @Override public void logDebug(String tag, String message) { android.util.Log.d(tag, message); }
    @Override public void logVerbose(String tag, String message) { android.util.Log.v(tag, message); }
    @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { android.util.Log.e(tag, message, e); }
    @Override public void logStackTrace(String tag, Exception e) { android.util.Log.e(tag, "stack trace", e); }

    int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    static String normalizeBaseUrl(String raw) {
        return WebTermUrls.normalizeBaseUrl(raw);
    }

    private ServerGroupHolder findHolderForServer(ServerConfig server) {
        for (ServerGroupHolder holder : mActiveGroups) {
            if (holder.server == server) return holder;
        }
        return null;
    }

    private void connectManagerWS(final ServerGroupHolder holder) {
        if (mClosed.get() || mSessionId != null || mSessionList == null || holder.server.url.isEmpty()) {
            closeManagerWS(holder);
            return;
        }
        if (!mActiveGroups.contains(holder)) return;
        if (holder.monitor == null) {
            holder.monitor = new ServerSessionMonitor(mHttp, mMainHandler, holder.server, new ServerSessionMonitor.Listener() {
                @Override
                public void onMonitorConnected() {
                    scheduleHomeRefresh(HOME_REFRESH_INITIAL_DELAY_MS);
                    runOnUiThread(() -> {
                        if (isActiveManagerHolder(holder)) {
                            holder.status.setText("🟢");
                            holder.status.setTextColor(Color.rgb(16, 185, 129));
                        }
                    });
                }

                @Override
                public void onMonitorSessions(JSONArray sessions) {
                    if (!isActiveManagerHolder(holder)) return;
                    holder.lastSessions = sessions;
                    runOnUiThread(() -> renderServerSessions(holder.server, holder.lastSessions, holder.subList));
                }

                @Override
                public void onMonitorSession(JSONObject session) {
                    if (isActiveManagerHolder(holder)) upsertLocalSession(holder, session);
                }

                @Override
                public void onMonitorSessionClosed(String sessionId) {
                    if (isActiveManagerHolder(holder)) removeLocalSession(holder, sessionId);
                }

                @Override
                public void onMonitorPollingFallback() {
                    runOnUiThread(() -> {
                        if (isActiveManagerHolder(holder)) {
                            holder.status.setText("🟡");
                            holder.status.setTextColor(Color.rgb(245, 158, 11));
                        }
                    });
                    scheduleHomeRefresh(HOME_REFRESH_INITIAL_DELAY_MS);
                }
            });
        }
        holder.monitor.start();
    }

    private void closeManagerWS(ServerGroupHolder holder) {
        if (holder.monitor != null) {
            holder.monitor.stop();
        }
    }

    private boolean isActiveManagerHolder(ServerGroupHolder holder) {
        return !mClosed.get()
            && holder.monitor != null
            && holder.monitor.isEnabled()
            && mSessionId == null
            && mSessionList != null
            && mActiveGroups.contains(holder);
    }

    private void upsertLocalSession(ServerGroupHolder holder, JSONObject newData) {
        if (holder.lastSessions == null) {
            holder.lastSessions = new JSONArray();
        }
        String id = newData.optString("id");
        if (id == null) return;

        boolean found = false;
        for (int i = 0; i < holder.lastSessions.length(); i++) {
            JSONObject s = holder.lastSessions.optJSONObject(i);
            if (s != null && id.equals(s.optString("id"))) {
                try {
                    holder.lastSessions.put(i, newData);
                } catch (JSONException ignored) {}
                found = true;
                break;
            }
        }
        if (!found) {
            holder.lastSessions.put(newData);
        }
        runOnUiThread(() -> renderServerSessions(holder.server, holder.lastSessions, holder.subList));
    }

    private void removeLocalSession(ServerGroupHolder holder, String id) {
        removeCachedTerminal(holder.server.url, id);
        if (holder.lastSessions == null) return;
        JSONArray newArr = new JSONArray();
        for (int i = 0; i < holder.lastSessions.length(); i++) {
            JSONObject s = holder.lastSessions.optJSONObject(i);
            if (s != null && !id.equals(s.optString("id"))) {
                newArr.put(s);
            }
        }
        holder.lastSessions = newArr;
        runOnUiThread(() -> renderServerSessions(holder.server, holder.lastSessions, holder.subList));
    }

    interface LoginCallback {
        void onReady(String baseUrl, String cookie);

        void onError(String message);
    }

    private interface SessionCreateCallback {
        void onReady(String sessionId);

        void onError(String message);
    }

    private final class NativeTerminalViewClient implements TerminalViewClient {
        @Override public float onScale(float scale) { return Math.max(0.75f, Math.min(2.0f, scale)); }
        @Override public void onSingleTapUp(MotionEvent e) { showKeyboard(); }
        @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
        @Override public boolean shouldEnforceCharBasedInput() { return false; }
        @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
        @Override public boolean isTerminalViewSelected() { return true; }
        @Override public void copyModeChanged(boolean copyMode) {}
        @Override public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) { return false; }
        @Override public boolean onKeyUp(int keyCode, KeyEvent e) { return false; }
        @Override public boolean onLongPress(MotionEvent event) { return false; }
        @Override public boolean readControlKey() { return mCtrlDown; }
        @Override public boolean readAltKey() { return false; }
        @Override public boolean readShiftKey() { return false; }
        @Override public boolean readFnKey() { return false; }
        @Override public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
            mCtrlDown = false;
            return false;
        }
        @Override public void onEmulatorSet() {}
        @Override public void logError(String tag, String message) { MainActivity.this.logError(tag, message); }
        @Override public void logWarn(String tag, String message) { MainActivity.this.logWarn(tag, message); }
        @Override public void logInfo(String tag, String message) { MainActivity.this.logInfo(tag, message); }
        @Override public void logDebug(String tag, String message) { MainActivity.this.logDebug(tag, message); }
        @Override public void logVerbose(String tag, String message) { MainActivity.this.logVerbose(tag, message); }
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { MainActivity.this.logStackTraceWithMessage(tag, message, e); }
        @Override public void logStackTrace(String tag, Exception e) { MainActivity.this.logStackTrace(tag, e); }
    }

    int getSavedFontSize() {
        return mConfigStore.getFontSize();
    }

    String getSavedFontType() {
        return mConfigStore.getFontType();
    }

    Typeface getTypefaceByName(String type) {
        if ("sans-serif".equals(type)) return Typeface.SANS_SERIF;
        if ("serif".equals(type)) return Typeface.SERIF;
        if ("default".equals(type)) return Typeface.DEFAULT;
        return Typeface.MONOSPACE;
    }

    private void showSettingsDialog() {
        SettingsDialogHelper.show(this);
    }

    String getFontDisplayName(String fontType) {
        if ("sans-serif".equals(fontType)) return "Sans Serif";
        if ("serif".equals(fontType)) return "Serif";
        if ("default".equals(fontType)) return "Default";
        return "Monospace";
    }

    void saveFontSize(int size) {
        mConfigStore.saveFontSize(size);
    }

    void saveFontType(String type) {
        mConfigStore.saveFontType(type);
    }
}
