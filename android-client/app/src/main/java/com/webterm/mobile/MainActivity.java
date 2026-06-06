package com.webterm.mobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.PopupMenu;
import android.app.AlertDialog;
import android.content.DialogInterface;

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

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public final class MainActivity extends Activity implements TerminalSessionClient, TerminalSession.ExternalIOClient {

    private static final String TAG = "WebTermMobile";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final byte MSG_INPUT = 0x01;
    private static final byte MSG_OUTPUT = 0x02;
    private static final byte MSG_RESIZE = 0x03;
    private static final byte MSG_HELLO = 0x04;
    private static final byte MSG_INFO = 0x05;
    private static final byte MSG_EXIT = 0x06;
    private static final byte MSG_PING = 0x07;
    private static final byte MSG_PONG = 0x08;
    private static final byte MSG_TITLE = 0x09;
    private static final int TRANSCRIPT_ROWS = 20000;
    private static final int MAX_TERM_TITLE_CHARS = 256;
    private static final long HOME_REFRESH_INITIAL_DELAY_MS = 3000L;
    private static final long HOME_REFRESH_MAX_DELAY_MS = 60000L;
    private static final long RESIZE_DEBOUNCE_MS = 100L;
    private static final String PREFS = "webterm";
    private static final String DEFAULT_URL = "http://100.121.115.14:8081";
    private static final String DEFAULT_USER = "gao";

    private final OkHttpClient mHttp = new OkHttpClient();
    private final AtomicBoolean mClosed = new AtomicBoolean(false);
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private String mLastUploadedTitle = "";
    private String mPendingTitle = "";
    private final Runnable mUploadTitleRunnable = this::uploadTerminalTitle;
    private final Runnable mSendResizeRunnable = this::sendCurrentResizeNow;

    TerminalView mTerminalView;
    private TerminalSession mTerminalSession;
    private WebSocket mWebSocket;
    private String mCookie;
    private String mBaseUrl;
    private String mSessionId;
    private long mLastSeq;
    private boolean mConnected;
    private boolean mCtrlDown;
    private int mReconnectAttempts;
    private int mSocketGeneration;
    private boolean mReconnectScheduled;
    private boolean mInForeground = true;
    private long mHomeRefreshDelayMs = HOME_REFRESH_INITIAL_DELAY_MS;
    private final java.util.Map<String, CachedTerminal> mTerminalCache = new java.util.HashMap<>();
    private LinearLayout mSessionList;
    private TextView mSessionStatus;
    private View mConnectionStatusIndicator;
    private ImageButton mRetryButton;
    private AlphaAnimation mConnectingAnimation;
    private final Runnable mReconnectRunnable = () -> {
        mReconnectScheduled = false;
        if (!mClosed.get() && !mConnected) connectWebSocket();
    };
    private TextView mTerminalTitle;
    private TextView mTerminalSubtitle;

    private static final class CachedTerminal {
        final String baseUrl;
        final String sessionId;
        TerminalSession terminalSession;
        String cookie;
        String termTitle;
        String sessionName;
        long lastSeq;
        int columns;
        int rows;

        CachedTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String sessionName, TerminalSession terminalSession) {
            this.baseUrl = baseUrl;
            this.cookie = cookie;
            this.sessionId = sessionId;
            this.termTitle = termTitle;
            this.sessionName = sessionName;
            this.terminalSession = terminalSession;
        }
    }

    private static final class ServerGroupHolder {
        final ServerConfig server;
        final LinearLayout subList;
        final TextView status;
        WebSocket managerWS = null;
        JSONArray lastSessions = null;
        boolean wsConnected = false;
        boolean managerReconnectEnabled = true;
        int reconnectAttempts = 0;
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
                if (!holder.wsConnected) {
                    needsFallbackRefresh = true;
                    loadSessionsForServer(holder.server, holder.subList, holder.status);
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
    private int mImeOverlap;
    private int mTerminalColumns;
    private int mTerminalRows;
    private int mSentResizeColumns;
    private int mSentResizeRows;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        loadServersFromPrefs();
        showSessionHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mInForeground = true;
        if (mSessionId != null && mTerminalSession != null && mWebSocket == null) {
            mClosed.set(false);
            mReconnectAttempts = 0;
            mReconnectScheduled = false;
            connectWebSocket();
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
        if (mWebSocket != null) mWebSocket.close(1000, "activity closed");
        if (mTerminalSession != null) mTerminalSession.finishIfRunning();
        for (CachedTerminal cached : mTerminalCache.values()) {
            if (cached.terminalSession != null && cached.terminalSession != mTerminalSession) cached.terminalSession.finishIfRunning();
        }
        mTerminalCache.clear();
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
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String json = prefs.getString("servers_list", "");
        if (!json.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    mServers.add(ServerConfig.fromJSON(arr.getJSONObject(i)));
                }
            } catch (JSONException e) {
                Log.e(TAG, "Failed to parse servers list", e);
            }
        }

        // 向下兼容迁移
        if (mServers.isEmpty()) {
            String oldUrl = prefs.getString("url", "");
            if (!oldUrl.isEmpty()) {
                String oldUser = prefs.getString("user", "");
                String oldPassword = prefs.getString("password", "");
                ServerConfig legacy = new ServerConfig(
                    "srv_" + System.currentTimeMillis(),
                    "主电脑",
                    oldUrl,
                    "",
                    oldUser,
                    oldPassword
                );
                mServers.add(legacy);
                saveServersToPrefs();
            }
        }
    }

    void saveServersToPrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        JSONArray arr = new JSONArray();
        for (ServerConfig s : mServers) {
            try {
                arr.put(s.toJSON());
            } catch (JSONException ignored) {}
        }
        prefs.edit().putString("servers_list", arr.toString()).apply();
    }

    void showSessionHome() {
        closeCurrentTerminal(false);
        mClosed.set(false);
        mBaseUrl = null;
        mCookie = null;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(16));
        root.setBackgroundColor(Color.rgb(15, 15, 18));
        installRootInsets(root, dp(20), dp(24), dp(20), dp(16), true);

        LinearLayout topbar = new LinearLayout(this);
        topbar.setOrientation(LinearLayout.HORIZONTAL);
        topbar.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("WebTerm");
        title.setTextColor(Color.rgb(243, 244, 246));
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView subtitle = new TextView(this);
        subtitle.setText("多端会话聚合大厅");
        subtitle.setTextColor(Color.rgb(156, 163, 175));
        subtitle.setTextSize(12);
        heading.addView(title, new LinearLayout.LayoutParams(-1, -2));
        heading.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));
        topbar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton moreBtn = new ImageButton(this);
        moreBtn.setImageResource(com.webterm.mobile.R.drawable.ic_more_vert);
        moreBtn.setColorFilter(Color.rgb(243, 244, 246));
        GradientDrawable moreBg = new GradientDrawable();
        moreBg.setShape(GradientDrawable.RECTANGLE);
        moreBg.setColor(Color.TRANSPARENT);
        moreBg.setCornerRadius(dp(20));
        moreBg.setStroke(dp(1), Color.rgb(55, 65, 81));
        moreBtn.setBackground(moreBg);
        moreBtn.setPadding(0, 0, 0, 0);
        moreBtn.setOnClickListener((v) -> {
            PopupMenu popup = new PopupMenu(this, moreBtn);
            popup.getMenu().add(0, 1, 0, "➕ 添加电脑");
            popup.getMenu().add(0, 2, 0, "⚙️ 终端设置");
            popup.getMenu().add(0, 3, 0, "🔄 刷新列表");
            popup.setOnMenuItemClickListener((item) -> {
                if (item.getItemId() == 1) {
                    showAddServerDialog(null);
                    return true;
                } else if (item.getItemId() == 2) {
                    showSettingsDialog();
                    return true;
                } else if (item.getItemId() == 3) {
                    loadMultiSessions();
                    return true;
                }
                return false;
            });
            popup.show();
        });

        topbar.addView(moreBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));
        root.addView(topbar, new LinearLayout.LayoutParams(-1, dp(58)));

        ScrollView scrollView = new ScrollView(this);
        mSessionList = new LinearLayout(this);
        mSessionList.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(mSessionList, new ScrollView.LayoutParams(-1, -2));
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
        resetHomeRefreshBackoff();
        loadMultiSessions();
        scheduleHomeRefresh(HOME_REFRESH_INITIAL_DELAY_MS);
    }

    private void loadMultiSessions() {
        resetHomeRefreshBackoff();
        if (mSessionList == null) return;
        for (ServerGroupHolder holder : mActiveGroups) {
            closeManagerWS(holder);
        }
        mActiveGroups.clear();
        mSessionList.removeAllViews();

        if (mServers.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("📺 暂无保存的电脑\n点击右上角 ➕ 按钮添加电脑");
            empty.setTextColor(Color.rgb(147, 161, 161));
            empty.setTextSize(15);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(80), dp(20), dp(80));
            mSessionList.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }

        for (ServerConfig s : mServers) {
            renderServerGroup(s);
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
            if (!holder.wsConnected) {
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

    private void renderServerGroup(ServerConfig server) {
        boolean collapsed = mServerCollapsed.containsKey(server.id) && Boolean.TRUE.equals(mServerCollapsed.get(server.id));

        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(0, 0, 0, dp(12));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(6), dp(8), dp(6), dp(8));

        TextView arrow = new TextView(this);
        arrow.setText(collapsed ? "▶ " : "▼ ");
        arrow.setTextColor(Color.rgb(156, 163, 175));
        arrow.setTextSize(14);
        header.addView(arrow, new LinearLayout.LayoutParams(-2, -2));

        TextView nameView = new TextView(this);
        nameView.setText(server.name);
        nameView.setTextColor(Color.rgb(243, 244, 246));
        nameView.setTextSize(16);
        nameView.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(nameView, new LinearLayout.LayoutParams(0, -2, 1));

        TextView status = new TextView(this);
        status.setText("Connecting...");
        status.setTextColor(Color.rgb(245, 158, 11));
        status.setTextSize(12);
        status.setPadding(0, 0, dp(8), 0);
        header.addView(status, new LinearLayout.LayoutParams(-2, -2));

        // 新建终端会话按钮 [+]
        TextView addSessionBtn = new TextView(this);
        addSessionBtn.setText("+");
        addSessionBtn.setTextColor(Color.rgb(16, 185, 129));
        addSessionBtn.setTextSize(15);
        addSessionBtn.setTypeface(Typeface.DEFAULT_BOLD);
        addSessionBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable addBtnBg = new GradientDrawable();
        addBtnBg.setShape(GradientDrawable.RECTANGLE);
        addBtnBg.setColor(Color.argb(25, 16, 185, 129));
        addBtnBg.setCornerRadius(dp(4));
        addSessionBtn.setBackground(addBtnBg);
        header.addView(addSessionBtn, new LinearLayout.LayoutParams(-2, -2));

        // 间距 View
        View space = new View(this);
        header.addView(space, new LinearLayout.LayoutParams(dp(8), 1));

        // 服务器配置折叠菜单按钮 ⋮
        TextView menuBtn = new TextView(this);
        menuBtn.setText("⋮");
        menuBtn.setTextColor(Color.rgb(156, 163, 175));
        menuBtn.setTextSize(18);
        menuBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        header.addView(menuBtn, new LinearLayout.LayoutParams(-2, -2));

        group.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout subList = new LinearLayout(this);
        subList.setOrientation(LinearLayout.VERTICAL);
        subList.setPadding(dp(8), 0, 0, 0);
        subList.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        group.addView(subList, new LinearLayout.LayoutParams(-1, -2));

        mSessionList.addView(group, new LinearLayout.LayoutParams(-1, -2));

        header.setOnClickListener((v) -> {
            boolean current = mServerCollapsed.containsKey(server.id) && Boolean.TRUE.equals(mServerCollapsed.get(server.id));
            mServerCollapsed.put(server.id, !current);
            subList.setVisibility(!current ? View.GONE : View.VISIBLE);
            arrow.setText(!current ? "▶ " : "▼ ");
        });

        addSessionBtn.setOnClickListener((v) -> {
            createSessionOnServer(server);
        });

        menuBtn.setOnClickListener((v) -> {
            PopupMenu popup = new PopupMenu(this, menuBtn);
            popup.getMenu().add(0, 1, 0, "✏️ 修改配置");
            popup.getMenu().add(0, 2, 0, "❌ 移除电脑");
            popup.setOnMenuItemClickListener((item) -> {
                if (item.getItemId() == 1) {
                    showAddServerDialog(server);
                    return true;
                } else if (item.getItemId() == 2) {
                    new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                        .setTitle("确认移除电脑")
                        .setMessage("确定要从列表中移除该服务器吗？")
                        .setPositiveButton("移除", (dialog, which) -> {
                            mServers.remove(server);
                            saveServersToPrefs();
                            loadMultiSessions();
                        })
                        .setNegativeButton("取消", null)
                        .show();
                    return true;
                }
                return false;
            });
            popup.show();
        });

        mActiveGroups.add(new ServerGroupHolder(server, subList, status));
        ServerGroupHolder holder = mActiveGroups.get(mActiveGroups.size() - 1);
        loadSessionsForServer(server, subList, status);
        connectManagerWS(holder);
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

    private void loadSessionsForServer(ServerConfig server, LinearLayout subList, TextView status) {
        if (server.url.isEmpty()) return;
        status.setText("Connecting...");
        status.setTextColor(Color.rgb(245, 158, 11));

        if (server.cookie != null && !server.cookie.isEmpty()) {
            fetchSessionsDirectly(server, subList, status);
        } else if (server.password != null && !server.password.isEmpty()) {
            silentLoginAndFetch(server, subList, status);
        } else {
            status.setText("🔴 未登录");
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
        Request request = new Request.Builder()
            .url(server.url + "/api/sessions")
            .header("Cookie", server.cookie)
            .get()
            .build();
        mHttp.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> {
                    status.setText("🔴 离线");
                    status.setTextColor(Color.rgb(239, 68, 68));
                    if (!shouldKeepExistingList(subList)) {
                        renderErrorItem(server, subList, status, e.getMessage());
                    }
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    String text = response.body().string();
                    if (response.code() == 401 && server.password != null && !server.password.isEmpty()) {
                        silentLoginAndFetch(server, subList, status);
                        return;
                    }
                    if (!response.isSuccessful()) {
                        runOnUiThread(() -> {
                            status.setText("🔴 离线");
                            status.setTextColor(Color.rgb(239, 68, 68));
                            if (!shouldKeepExistingList(subList)) {
                                renderErrorItem(server, subList, status, "HTTP " + response.code());
                            }
                        });
                        return;
                    }
                    try {
                        JSONArray sessions = new JSONArray(text);
                        runOnUiThread(() -> {
                            status.setText("🟢 在线 (" + sessions.length() + ")");
                            status.setTextColor(Color.rgb(16, 185, 129));
                            ServerGroupHolder holder = findHolderForServer(server);
                            if (holder != null) {
                                holder.lastSessions = sessions;
                            }
                            renderServerSessions(server, sessions, subList);
                        });
                    } catch (JSONException e) {
                        runOnUiThread(() -> {
                            status.setText("🔴 异常");
                            status.setTextColor(Color.rgb(239, 68, 68));
                            if (!shouldKeepExistingList(subList)) {
                                renderErrorItem(server, subList, status, "JSON 解析错误");
                            }
                        });
                    }
                }
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
                runOnUiThread(() -> {
                    status.setText("🔴 离线");
                    status.setTextColor(Color.rgb(239, 68, 68));
                    if (!shouldKeepExistingList(subList)) {
                        renderErrorItem(server, subList, status, "登录失败: " + message);
                    }
                });
            }
        });
    }

    private void renderErrorItem(ServerConfig server, LinearLayout subList, TextView status, String error) {
        subList.removeAllViews();

        LinearLayout container = new LinearLayout(this);
        container.setTag("error_item");
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);
        container.setPadding(0, dp(16), 0, dp(16));

        TextView errText = new TextView(this);
        errText.setText("无法连接到服务器: " + error);
        errText.setTextColor(Color.rgb(156, 163, 175));
        errText.setTextSize(13);
        errText.setGravity(Gravity.CENTER);
        errText.setPadding(0, 0, 0, dp(10));
        container.addView(errText);

        TextView retryBtn = new TextView(this);
        retryBtn.setText("🔄 重新连接");
        retryBtn.setTextColor(Color.rgb(243, 244, 246));
        retryBtn.setTextSize(13);
        retryBtn.setGravity(Gravity.CENTER);
        retryBtn.setPadding(dp(20), dp(8), dp(20), dp(8));

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setShape(GradientDrawable.RECTANGLE);
        btnBg.setColor(Color.rgb(45, 45, 52));
        btnBg.setStroke(dp(1), Color.rgb(75, 85, 99));
        btnBg.setCornerRadius(dp(6));
        retryBtn.setBackground(btnBg);

        retryBtn.setOnClickListener((v) -> loadSessionsForServer(server, subList, status));

        container.addView(retryBtn, new LinearLayout.LayoutParams(-2, -2));
        subList.addView(container, new LinearLayout.LayoutParams(-1, -2));
    }

    private void renderServerSessions(ServerConfig server, JSONArray sessions, LinearLayout subList) {
        java.util.Set<String> newIds = new java.util.HashSet<>();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session != null) {
                newIds.add(session.optString("id"));
            }
        }
        removeMissingCachedSessionsForServer(server.url, newIds);

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
            TextView empty = new TextView(this);
            empty.setTag("empty_item");
            empty.setText("还没有终端会话");
            empty.setTextColor(Color.rgb(156, 163, 175));
            empty.setTextSize(13);
            empty.setPadding(dp(12), dp(12), dp(12), dp(12));
            subList.addView(empty);
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
        SessionRowHelper.addSessionRow(this, session, server, subList);
    }

    private void showAddServerDialog(final ServerConfig existingServer) {
        ServerConfigDialogHelper.show(this, existingServer);
    }

    void showRenameDialog(ServerConfig server, String sessionId, String oldName) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(UIUtils.dp(this, 24), UIUtils.dp(this, 24), UIUtils.dp(this, 24), UIUtils.dp(this, 24));

        GradientDrawable containerBg = new GradientDrawable();
        containerBg.setShape(GradientDrawable.RECTANGLE);
        containerBg.setColor(Color.rgb(30, 30, 36));
        containerBg.setCornerRadius(UIUtils.dp(this, 12));
        containerBg.setStroke(UIUtils.dp(this, 1), Color.rgb(55, 65, 81));
        container.setBackground(containerBg);

        TextView titleView = new TextView(this);
        titleView.setText("✏️ 重命名会话");
        titleView.setTextColor(Color.rgb(243, 244, 246));
        titleView.setTextSize(18);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setPadding(0, 0, 0, UIUtils.dp(this, 16));
        container.addView(titleView);

        EditText input = UIUtils.createInput(this, "输入新名称");
        input.setText(oldName);
        input.requestFocus();
        container.addView(input, UIUtils.matchWrap(this));

        LinearLayout btnBar = new LinearLayout(this);
        btnBar.setOrientation(LinearLayout.HORIZONTAL);
        btnBar.setGravity(Gravity.END);
        btnBar.setPadding(0, UIUtils.dp(this, 8), 0, 0);

        Button cancelBtn = new Button(this);
        cancelBtn.setText("取消");
        UIUtils.styleDialogButton(this, cancelBtn, false);

        Button submitBtn = new Button(this);
        submitBtn.setText("保存");
        UIUtils.styleDialogButton(this, submitBtn, true);

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(UIUtils.dp(this, 100), UIUtils.dp(this, 40));
        btnLp.setMargins(UIUtils.dp(this, 12), 0, 0, 0);

        btnBar.addView(cancelBtn, new LinearLayout.LayoutParams(UIUtils.dp(this, 80), UIUtils.dp(this, 40)));
        btnBar.addView(submitBtn, btnLp);
        container.addView(btnBar);

        builder.setView(container);
        final AlertDialog dialog = builder.create();
        dialog.show();

        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));

        cancelBtn.setOnClickListener((v) -> dialog.dismiss());

        submitBtn.setOnClickListener((v) -> {
            String newName = input.getText().toString().trim();
            if (newName.equals(oldName)) {
                dialog.dismiss();
                return;
            }

            submitBtn.setEnabled(false);
            cancelBtn.setEnabled(false);

            JSONObject body = new JSONObject();
            try {
                body.put("name", newName);
            } catch (JSONException ignored) {}

            String encodedId = URLEncoder.encode(sessionId, StandardCharsets.UTF_8).replace("+", "%20");
            Request request = new Request.Builder()
                .url(server.url + "/api/sessions/" + encodedId)
                .header("Cookie", server.cookie)
                .patch(RequestBody.create(body.toString(), JSON))
                .build();

            mHttp.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "重命名失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    });
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    try (response) {
                        runOnUiThread(() -> {
                            if (response.isSuccessful()) {
                                showSessionHome();
                            } else {
                                Toast.makeText(MainActivity.this, "重命名失败, code: " + response.code(), Toast.LENGTH_SHORT).show();
                            }
                            dialog.dismiss();
                        });
                    }
                }
            });
        });
    }

    void showCloseConfirmDialog(ServerConfig server, String sessionId) {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("❌ 关闭终端会话")
            .setMessage("确定要关闭该终端会话吗？这将会终结其在服务器上的后台进程。")
            .setPositiveButton("关闭", (dialog, which) -> {
                String encodedId = URLEncoder.encode(sessionId, StandardCharsets.UTF_8).replace("+", "%20");
                Request request = new Request.Builder()
                    .url(server.url + "/api/sessions/" + encodedId)
                    .header("Cookie", server.cookie)
                    .delete()
                    .build();

                mHttp.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "关闭失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        try (response) {
                            if (response.isSuccessful()) {
                                removeCachedTerminal(server.url, sessionId);
                                runOnUiThread(() -> {
                                    showSessionHome();
                                });
                            } else {
                                runOnUiThread(() -> Toast.makeText(MainActivity.this, "关闭失败, code: " + response.code(), Toast.LENGTH_SHORT).show());
                            }
                        }
                    }
                });
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void deleteSession(String sessionId) {
        if (mBaseUrl == null || mCookie == null) return;
        String encodedId = URLEncoder.encode(sessionId, StandardCharsets.UTF_8).replace("+", "%20");
        Request request = new Request.Builder()
            .url(mBaseUrl + "/api/sessions/" + encodedId)
            .header("Cookie", mCookie)
            .delete()
            .build();
        mHttp.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Close failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    if (!response.isSuccessful()) {
                        String text = response.body().string();
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Close failed: " + response.code() + " " + text, Toast.LENGTH_SHORT).show());
                        return;
                    }
                    runOnUiThread(MainActivity.this::showSessionHome);
                }
            }
        });
    }

    void showTerminal(String baseUrl, String cookie, String sessionId) {
        showTerminal(baseUrl, cookie, sessionId, "Terminal", "");
    }

    void showTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String sessionName) {
        mMainHandler.removeCallbacks(mHomeRefreshRunnable);
        for (ServerGroupHolder holder : mActiveGroups) {
            closeManagerWS(holder);
        }
        CachedTerminal cached = mTerminalCache.get(terminalCacheKey(baseUrl, sessionId));
        mBaseUrl = baseUrl;
        mCookie = cookie;
        mSessionId = sessionId;
        String headerTitle = cached != null && cached.termTitle != null && !cached.termTitle.trim().isEmpty()
            ? cached.termTitle.trim()
            : (termTitle == null || termTitle.trim().isEmpty() ? "Terminal" : termTitle.trim());
        String headerSubtitle = cached != null && cached.sessionName != null && !cached.sessionName.trim().isEmpty()
            ? cached.sessionName.trim()
            : (sessionName == null || sessionName.trim().isEmpty() ? sessionId : sessionName.trim());
        mClosed.set(false);
        mReconnectAttempts = 0;
        mReconnectScheduled = false;
        mLastSeq = cached != null ? cached.lastSeq : 0;
        mTerminalColumns = cached != null ? cached.columns : 0;
        mTerminalRows = cached != null ? cached.rows : 0;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        mTerminalRoot = root;
        installRootInsets(root, 0, 0, 0, 0, false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.BLACK);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(6), dp(8), dp(6));
        topBar.setBackgroundColor(Color.rgb(30, 30, 36));

        ImageButton sessions = new ImageButton(this);
        sessions.setImageResource(com.webterm.mobile.R.drawable.ic_arrow_back);
        sessions.setColorFilter(Color.rgb(243, 244, 246));
        GradientDrawable sessionsBg = new GradientDrawable();
        sessionsBg.setShape(GradientDrawable.RECTANGLE);
        sessionsBg.setColor(Color.TRANSPARENT);
        sessionsBg.setCornerRadius(dp(20));
        sessionsBg.setStroke(dp(1), Color.rgb(55, 65, 81));
        sessions.setBackground(sessionsBg);
        sessions.setPadding(0, 0, 0, 0);

        mTerminalTitle = new TextView(this);
        mTerminalTitle.setText(headerTitle);
        mTerminalTitle.setTextColor(Color.rgb(243, 244, 246));
        mTerminalTitle.setGravity(Gravity.CENTER_VERTICAL);
        mTerminalTitle.setTextSize(15);
        mTerminalTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mTerminalTitle.setSingleLine(true);
        mTerminalTitle.setEllipsize(TextUtils.TruncateAt.END);

        mTerminalSubtitle = new TextView(this);
        mTerminalSubtitle.setText(headerSubtitle);
        mTerminalSubtitle.setTextColor(Color.rgb(156, 163, 175));
        mTerminalSubtitle.setTextSize(11);
        mTerminalSubtitle.setSingleLine(true);
        mTerminalSubtitle.setEllipsize(TextUtils.TruncateAt.END);

        if (mConnectingAnimation == null) {
            mConnectingAnimation = new AlphaAnimation(1.0f, 0.2f);
            mConnectingAnimation.setDuration(600);
            mConnectingAnimation.setRepeatMode(Animation.REVERSE);
            mConnectingAnimation.setRepeatCount(Animation.INFINITE);
        }

        LinearLayout statusContainer = new LinearLayout(this);
        statusContainer.setOrientation(LinearLayout.HORIZONTAL);
        statusContainer.setGravity(Gravity.CENTER_VERTICAL);

        mRetryButton = new ImageButton(this);
        mRetryButton.setImageResource(com.webterm.mobile.R.drawable.ic_refresh);
        mRetryButton.setColorFilter(Color.rgb(243, 244, 246));
        mRetryButton.setVisibility(View.GONE);
        GradientDrawable retryBg = new GradientDrawable();
        retryBg.setShape(GradientDrawable.RECTANGLE);
        retryBg.setColor(Color.TRANSPARENT);
        retryBg.setStroke(dp(1), Color.rgb(55, 65, 81));
        retryBg.setCornerRadius(dp(18));
        mRetryButton.setBackground(retryBg);
        mRetryButton.setPadding(0, 0, 0, 0);
        mRetryButton.setOnClickListener((v) -> {
            mMainHandler.removeCallbacks(mReconnectRunnable);
            mReconnectScheduled = false;
            connectWebSocket();
        });

        mConnectionStatusIndicator = new View(this);
        GradientDrawable indicatorBg = new GradientDrawable();
        indicatorBg.setShape(GradientDrawable.OVAL);
        indicatorBg.setColor(Color.rgb(239, 68, 68));
        mConnectionStatusIndicator.setBackground(indicatorBg);

        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        retryLp.setMargins(0, 0, dp(10), 0);
        statusContainer.addView(mRetryButton, retryLp);

        LinearLayout.LayoutParams indicatorLp = new LinearLayout.LayoutParams(dp(12), dp(12));
        indicatorLp.setMargins(0, 0, dp(6), 0);
        statusContainer.addView(mConnectionStatusIndicator, indicatorLp);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setPadding(dp(10), 0, dp(8), 0);
        labels.addView(mTerminalTitle, new LinearLayout.LayoutParams(-1, 0, 1));
        labels.addView(mTerminalSubtitle, new LinearLayout.LayoutParams(-1, 0, 1));

        topBar.addView(sessions, new LinearLayout.LayoutParams(dp(40), dp(40)));
        topBar.addView(labels, new LinearLayout.LayoutParams(0, dp(44), 1));
        topBar.addView(statusContainer, new LinearLayout.LayoutParams(-2, -2));
        content.addView(topBar, new LinearLayout.LayoutParams(-1, dp(54)));

        mTerminalView = new TerminalView(this, null);
        mTerminalView.setFocusable(true);
        mTerminalView.setFocusableInTouchMode(true);
        mTerminalView.setTextSize(getSavedFontSize());
        mTerminalView.setTypeface(getTypefaceByName(getSavedFontType()));
        mTerminalView.setTerminalViewClient(new NativeTerminalViewClient());
        FrameLayout terminalViewport = new FrameLayout(this);
        terminalViewport.setClipChildren(true);
        terminalViewport.setClipToPadding(true);
        terminalViewport.setBackgroundColor(Color.BLACK);
        mTerminalViewport = terminalViewport;
        terminalViewport.addView(mTerminalView, new FrameLayout.LayoutParams(-1, -1));
        content.addView(terminalViewport, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        mQuickBar = createQuickBar();
        root.addView(mQuickBar, new LinearLayout.LayoutParams(-1, dp(92)));
        setContentView(root);
        root.post(this::updateKeyboardAvoidance);

        mTerminalSession = cached != null && cached.terminalSession != null
            ? cached.terminalSession
            : TerminalSession.createExternalSession(TRANSCRIPT_ROWS, this, this);
        mTerminalView.attachSession(mTerminalSession);
        mTerminalView.requestFocus();
        sessions.setOnClickListener((v) -> showSessionHome());
        root.post(() -> {
            if (mTerminalView != null) mTerminalView.updateSize();
            connectWebSocket();
        });
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
            cacheCurrentTerminal();
        }
        mClosed.set(true);
        closeCurrentWebSocket("leaving terminal");
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
        mImeOverlap = 0;
        mTerminalColumns = 0;
        mTerminalRows = 0;
        mSentResizeColumns = 0;
        mSentResizeRows = 0;
        mMainHandler.removeCallbacks(mUploadTitleRunnable);
        mMainHandler.removeCallbacks(mReconnectRunnable);
        mMainHandler.removeCallbacks(mSendResizeRunnable);
        mLastUploadedTitle = "";
        mPendingTitle = "";
        if (closeRemote && closingSessionId != null) {
            removeCachedTerminal(closingBaseUrl, closingSessionId);
            deleteSession(closingSessionId);
        }
        mSessionId = null;
        mLastSeq = 0;
    }

    private void pauseCurrentTerminalConnection() {
        cacheCurrentTerminal();
        closeCurrentWebSocket("activity paused");
    }

    private void closeCurrentWebSocket(String reason) {
        mConnected = false;
        mReconnectScheduled = false;
        mSocketGeneration++;
        mMainHandler.removeCallbacks(mReconnectRunnable);
        mMainHandler.removeCallbacks(mSendResizeRunnable);
        mMainHandler.removeCallbacks(mUploadTitleRunnable);
        WebSocket ws = mWebSocket;
        mWebSocket = null;
        if (ws != null) {
            ws.close(1000, reason);
        }
    }

    private void cacheCurrentTerminal() {
        if (mBaseUrl == null || mSessionId == null || mTerminalSession == null) return;
        String key = terminalCacheKey(mBaseUrl, mSessionId);
        String title = mTerminalTitle == null ? "" : String.valueOf(mTerminalTitle.getText());
        String subtitle = mTerminalSubtitle == null ? "" : String.valueOf(mTerminalSubtitle.getText());
        CachedTerminal cached = mTerminalCache.get(key);
        if (cached == null) {
            cached = new CachedTerminal(mBaseUrl, mCookie, mSessionId, title, subtitle, mTerminalSession);
            mTerminalCache.put(key, cached);
        }
        cached.cookie = mCookie;
        cached.termTitle = title;
        cached.sessionName = subtitle;
        cached.terminalSession = mTerminalSession;
        cached.lastSeq = mLastSeq;
        cached.columns = mTerminalColumns;
        cached.rows = mTerminalRows;
    }

    private void removeCachedTerminal(String baseUrl, String sessionId) {
        CachedTerminal cached = mTerminalCache.remove(terminalCacheKey(baseUrl, sessionId));
        if (cached != null && cached.terminalSession != null && cached.terminalSession != mTerminalSession) {
            cached.terminalSession.finishIfRunning();
        }
    }

    private void removeMissingCachedSessionsForServer(String baseUrl, java.util.Set<String> liveSessionIds) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        java.util.List<String> staleKeys = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, CachedTerminal> entry : mTerminalCache.entrySet()) {
            CachedTerminal cached = entry.getValue();
            if (normalizedBaseUrl.equals(normalizeBaseUrl(cached.baseUrl)) && !liveSessionIds.contains(cached.sessionId)) {
                staleKeys.add(entry.getKey());
            }
        }
        for (String key : staleKeys) {
            CachedTerminal cached = mTerminalCache.remove(key);
            if (cached != null && cached.terminalSession != null && cached.terminalSession != mTerminalSession) {
                cached.terminalSession.finishIfRunning();
            }
        }
    }

    private static String terminalCacheKey(String baseUrl, String sessionId) {
        return normalizeBaseUrl(baseUrl) + "#" + String.valueOf(sessionId == null ? "" : sessionId);
    }

    private View createQuickBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(8), dp(7), dp(8), dp(7));
        bar.setBackgroundColor(Color.rgb(20, 20, 24));

        LinearLayout firstRow = quickBarRow();
        LinearLayout secondRow = quickBarRow();
        addKey(firstRow, "Ctrl", () -> mCtrlDown = true);
        addKey(firstRow, "Ctrl C", () -> writeTerminal("\003"));
        addKey(firstRow, "Shift Tab", () -> writeTerminal("\033[Z"));
        addKey(firstRow, "Esc", () -> writeTerminal("\033"));
        addKey(firstRow, "Tab", () -> writeTerminal("\t"));
        addKey(secondRow, "/", () -> writeTerminal("/"));
        addKey(secondRow, "←", () -> writeTerminal("\033[D"));
        addKey(secondRow, "↓", () -> writeTerminal("\033[B"));
        addKey(secondRow, "↑", () -> writeTerminal("\033[A"));
        addKey(secondRow, "→", () -> writeTerminal("\033[C"));
        bar.addView(firstRow, new LinearLayout.LayoutParams(-1, 0, 1));
        bar.addView(secondRow, new LinearLayout.LayoutParams(-1, 0, 1));
        return bar;
    }

    private LinearLayout quickBarRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private void addKey(LinearLayout row, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setTextColor(Color.rgb(243, 244, 246));
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setBackground(quickBarButtonBackground(false));
        button.setOnClickListener((v) -> {
            if (mTerminalView != null) mTerminalView.requestFocus();
            action.run();
            showKeyboard();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        row.addView(button, lp);
    }

    private GradientDrawable quickBarButtonBackground(boolean active) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(Color.rgb(15, 15, 18));
        drawable.setStroke(dp(1), active ? Color.rgb(99, 102, 241) : Color.rgb(55, 65, 81));
        drawable.setCornerRadius(dp(4));
        return drawable;
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
        JSONObject login = new JSONObject();
        try {
            login.put("username", username);
            login.put("password", password);
        } catch (JSONException e) {
            callback.onError(e.getMessage());
            return;
        }
        Request loginRequest = new Request.Builder()
            .url(baseUrl + "/api/login")
            .post(RequestBody.create(login.toString(), JSON))
            .build();
        mHttp.newCall(loginRequest).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError("Login failed: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    if (!response.isSuccessful()) {
                        callback.onError("Login failed: " + response.code() + " " + response.body().string());
                        return;
                    }
                    String cookie = firstCookie(response);
                    if (cookie.isEmpty()) {
                        callback.onError("Login did not return an auth cookie.");
                        return;
                    }
                    callback.onReady(baseUrl, cookie);
                }
            }
        });
    }

    private void createSession(String baseUrl, String cookie, SessionCreateCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("name", "Android");
        } catch (JSONException ignored) {
        }
        Request request = new Request.Builder()
            .url(baseUrl + "/api/sessions")
            .header("Cookie", cookie)
            .post(RequestBody.create(body.toString(), JSON))
            .build();
        mHttp.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError("Session failed: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    String text = response.body().string();
                    if (!response.isSuccessful()) {
                        callback.onError("Session failed: " + response.code() + " " + text);
                        return;
                    }
                    try {
                        String id = new JSONObject(text).getString("id");
                        callback.onReady(id);
                    } catch (JSONException e) {
                        callback.onError("Session response error: " + e.getMessage());
                    }
                }
            }
        });
    }

    private void connectWebSocket() {
        if (mSessionId == null || mBaseUrl == null || mCookie == null) return;
        if (mWebSocket != null) {
            closeCurrentWebSocket("reconnecting");
        }
        setConnectionStatus("Connecting", false);
        mReconnectScheduled = false;
        int generation = ++mSocketGeneration;
        String encodedId = URLEncoder.encode(mSessionId, StandardCharsets.UTF_8).replace("+", "%20");
        Request request = new Request.Builder()
            .url(toWebSocketUrl(mBaseUrl) + "/ws/sessions/" + encodedId)
            .header("Cookie", mCookie)
            .build();
        mWebSocket = mHttp.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                if (generation != mSocketGeneration) {
                    webSocket.close(1000, "stale socket");
                    return;
                }
                mConnected = true;
                mReconnectScheduled = false;
                mSentResizeColumns = 0;
                mSentResizeRows = 0;
                Log.i(TAG, "websocket open gen=" + generation + " code=" + response.code());
                setConnectionStatus("Connected", true);
                sendCurrentResizeNow();
                sendBinary(MSG_HELLO, new JSONObjectBuilder().put("lastSeq", mLastSeq).build().toString().getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
                if (generation != mSocketGeneration) return;
                handleServerMessage(bytes.toByteArray());
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                if (generation != mSocketGeneration) return;
                mConnected = false;
                String reason = describeFailure(t, response);
                Log.e(TAG, "websocket failure gen=" + generation + " reason=" + reason, t);
                if (!mClosed.get()) scheduleReconnect(reason);
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                if (generation != mSocketGeneration) return;
                mConnected = false;
                String description = "Connection closed: " + code + (reason.isEmpty() ? "" : " " + reason);
                Log.w(TAG, "websocket closed gen=" + generation + " reason=" + description);
                if (!mClosed.get()) scheduleReconnect(description);
            }
        });
    }

    private void scheduleReconnect(String reason) {
        if (mClosed.get() || mSessionId == null || mCookie == null || mBaseUrl == null) return;
        if (mReconnectScheduled) return;
        int attempt = ++mReconnectAttempts;
        if (attempt > 8) {
            setConnectionStatus("Failed: " + reason, false);
            return;
        }
        long delayMs = Math.min(1000L * attempt, 5000L);
        mReconnectScheduled = true;
        setConnectionStatus("Disconnected; reconnecting in " + delayMs + "ms", false);
        mMainHandler.postDelayed(mReconnectRunnable, delayMs);
    }

    private void handleServerMessage(byte[] frame) {
        if (frame.length == 0) return;
        byte type = frame[0];
        byte[] payload = Arrays.copyOfRange(frame, 1, frame.length);
        if (type == MSG_OUTPUT) {
            if (payload.length >= 8) {
                long seq = readUint64(payload, 0);
                if (seq <= mLastSeq) return;
                mLastSeq = seq;
                appendOutput(Arrays.copyOfRange(payload, 8, payload.length));
            } else {
                appendOutput(payload);
            }
            return;
        }
        if (type == MSG_INFO) {
            try {
                updateTerminalInfo(controlPayload(payload));
            } catch (JSONException ignored) {
            }
            return;
        }
        if (type == MSG_PONG) {
            return;
        }
        try {
            JSONObject msg = controlPayload(payload);
            if (type == MSG_EXIT) {
                appendStatus("Remote session exited");
                removeCachedTerminal(mBaseUrl, mSessionId);
                if (mTerminalSession != null) mTerminalSession.notifyExternalSessionFinished(msg.optInt("code", 0));
            }
        } catch (JSONException e) {
            appendStatus("Bad message: " + e.getMessage());
        }
    }

    private JSONObject controlPayload(byte[] payload) throws JSONException {
        if (payload == null || payload.length == 0) return new JSONObject();
        String text = new String(payload, StandardCharsets.UTF_8).trim();
        if (text.isEmpty() || "null".equals(text)) return new JSONObject();
        return new JSONObject(text);
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

    private void setConnectionStatus(String text, boolean connected) {
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

    private void updateTerminalInfo(JSONObject info) {
        String termTitle = info.optString("termTitle", "").trim();
        String name = info.optString("name", "").trim();
        runOnUiThread(() -> {
            if (mTerminalTitle != null && !termTitle.isEmpty()) mTerminalTitle.setText(termTitle);
            if (mTerminalSubtitle != null && !name.isEmpty()) mTerminalSubtitle.setText(name);
        });
    }

    private String describeFailure(Throwable t, @Nullable Response response) {
        StringBuilder message = new StringBuilder();
        message.append(t.getClass().getSimpleName());
        if (t.getMessage() != null && !t.getMessage().trim().isEmpty()) {
            message.append(": ").append(t.getMessage().trim());
        }
        if (response != null) {
            message.append(" (HTTP ").append(response.code()).append(")");
        }
        return message.toString();
    }

    @Override
    public void onTerminalInput(String data) {
        sendBinary(MSG_INPUT, data.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void onTerminalResize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mTerminalColumns = columns;
        mTerminalRows = rows;
        sendCurrentResize();
    }

    private void sendCurrentResize() {
        mMainHandler.removeCallbacks(mSendResizeRunnable);
        if (mWebSocket == null || !mConnected) return;
        mMainHandler.postDelayed(mSendResizeRunnable, RESIZE_DEBOUNCE_MS);
    }

    private void sendCurrentResizeNow() {
        mMainHandler.removeCallbacks(mSendResizeRunnable);
        int columns = mTerminalColumns;
        int rows = mTerminalRows;
        if (columns <= 0 || rows <= 0) return;
        if (columns == mSentResizeColumns && rows == mSentResizeRows) return;
        sendBinary(MSG_RESIZE, new JSONObjectBuilder()
            .put("cols", columns)
            .put("rows", rows)
            .build().toString().getBytes(StandardCharsets.UTF_8));
        if (mWebSocket != null && mConnected) {
            mSentResizeColumns = columns;
            mSentResizeRows = rows;
        }
    }

    private void sendBinary(byte type, byte[] payload) {
        WebSocket ws = mWebSocket;
        if (ws == null || !mConnected) return;
        byte[] frame = new byte[1 + (payload == null ? 0 : payload.length)];
        frame[0] = type;
        if (payload != null) System.arraycopy(payload, 0, frame, 1, payload.length);
        ws.send(ByteString.of(frame));
    }

    private static long readUint64(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[offset + i] & 0xffL);
        }
        return value;
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
        if (mWebSocket == null || !mConnected) {
            return;
        }
        byte[] titleBytes = titleToUpload.getBytes(StandardCharsets.UTF_8);
        sendBinary(MSG_TITLE, titleBytes);
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
        String value = String.valueOf(raw == null ? "" : raw).trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) return "";
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "http://" + value;
        return value;
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
        holder.managerReconnectEnabled = true;
        if (holder.managerWS != null) return;

        String wsUrl = toWebSocketUrl(holder.server.url) + "/ws/sessions";
        Request request = new Request.Builder()
            .url(wsUrl)
            .header("Cookie", holder.server.cookie != null ? holder.server.cookie : "")
            .build();

        holder.managerWS = mHttp.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                if (!isActiveManagerHolder(holder)) {
                    webSocket.close(1000, "stale manager socket");
                    return;
                }
                holder.wsConnected = true;
                holder.reconnectAttempts = 0;
                scheduleHomeRefresh(HOME_REFRESH_INITIAL_DELAY_MS);
                runOnUiThread(() -> {
                    holder.status.setText("🟢 实时");
                    holder.status.setTextColor(Color.rgb(16, 185, 129));
                });
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                if (!isActiveManagerHolder(holder)) return;
                try {
                    JSONObject msg = new JSONObject(text);
                    String type = msg.optString("type");
                    if ("sessions".equals(type)) {
                        JSONArray arr = msg.optJSONArray("data");
                        holder.lastSessions = arr != null ? arr : new JSONArray();
                        runOnUiThread(() -> renderServerSessions(holder.server, holder.lastSessions, holder.subList));
                    } else if ("session".equals(type)) {
                        JSONObject sessionData = msg.optJSONObject("data");
                        if (sessionData != null) {
                            upsertLocalSession(holder, sessionData);
                        }
                    } else if ("session-closed".equals(type)) {
                        String id = msg.optString("id");
                        if (id != null) {
                            removeLocalSession(holder, id);
                        }
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "Failed to parse manager WS message", e);
                }
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                holder.wsConnected = false;
                if (holder.managerWS == webSocket) {
                    holder.managerWS = null;
                }
                runOnUiThread(() -> {
                    if (isActiveManagerHolder(holder)) {
                        holder.status.setText("🟡 轮询中");
                        holder.status.setTextColor(Color.rgb(245, 158, 11));
                    }
                });
                scheduleHomeRefresh(HOME_REFRESH_INITIAL_DELAY_MS);
                scheduleManagerWSReconnect(holder);
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                holder.wsConnected = false;
                if (holder.managerWS == webSocket) {
                    holder.managerWS = null;
                }
                runOnUiThread(() -> {
                    if (isActiveManagerHolder(holder)) {
                        holder.status.setText("🟡 轮询中");
                        holder.status.setTextColor(Color.rgb(245, 158, 11));
                    }
                });
                scheduleHomeRefresh(HOME_REFRESH_INITIAL_DELAY_MS);
                scheduleManagerWSReconnect(holder);
            }
        });
    }

    private void scheduleManagerWSReconnect(final ServerGroupHolder holder) {
        if (!isActiveManagerHolder(holder)) return;
        int attempt = ++holder.reconnectAttempts;
        long delayMs = Math.min(1000L * attempt, 8000L);
        mMainHandler.postDelayed(() -> {
            if (isActiveManagerHolder(holder)) {
                connectManagerWS(holder);
            }
        }, delayMs);
    }

    private void closeManagerWS(ServerGroupHolder holder) {
        holder.managerReconnectEnabled = false;
        if (holder.managerWS != null) {
            holder.managerWS.close(1000, "closing page");
            holder.managerWS = null;
        }
        holder.wsConnected = false;
    }

    private boolean isActiveManagerHolder(ServerGroupHolder holder) {
        return !mClosed.get()
            && holder.managerReconnectEnabled
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

    private static String toWebSocketUrl(String baseUrl) {
        if (baseUrl.startsWith("https://")) return "wss://" + baseUrl.substring("https://".length());
        if (baseUrl.startsWith("http://")) return "ws://" + baseUrl.substring("http://".length());
        return "ws://" + baseUrl;
    }

    private static String firstCookie(Response response) {
        for (String header : response.headers("Set-Cookie")) {
            int semicolon = header.indexOf(';');
            return semicolon >= 0 ? header.substring(0, semicolon) : header;
        }
        return "";
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
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        return prefs.getInt("terminal_font_size", 28);
    }

    String getSavedFontType() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        return prefs.getString("terminal_font_type", "monospace");
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
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putInt("terminal_font_size", size).apply();
    }

    void saveFontType(String type) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putString("terminal_font_type", type).apply();
    }

    private static final class JSONObjectBuilder {
        private final JSONObject object = new JSONObject();

        JSONObjectBuilder put(String key, Object value) {
            try {
                object.put(key, value);
            } catch (JSONException ignored) {
            }
            return this;
        }

        JSONObject build() {
            return object;
        }
    }
}
