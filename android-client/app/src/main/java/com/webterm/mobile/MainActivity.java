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
import android.widget.TextView;
import android.widget.Toast;

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
    private static final int TRANSCRIPT_ROWS = 20000;
    private static final String PREFS = "webterm";
    private static final String DEFAULT_URL = "http://100.121.115.14:8081";
    private static final String DEFAULT_USER = "gao";

    private final OkHttpClient mHttp = new OkHttpClient();
    private final AtomicBoolean mClosed = new AtomicBoolean(false);
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private TerminalView mTerminalView;
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
    private LinearLayout mSessionList;
    private TextView mSessionStatus;
    private TextView mConnectionStatus;
    private TextView mTerminalTitle;
    private TextView mTerminalSubtitle;
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
        showLogin();
    }

    @Override
    protected void onDestroy() {
        mClosed.set(true);
        mMainHandler.removeCallbacksAndMessages(null);
        if (mWebSocket != null) mWebSocket.close(1000, "activity closed");
        if (mTerminalSession != null) mTerminalSession.finishIfRunning();
        mHttp.dispatcher().cancelAll();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mSessionId != null && mBaseUrl != null && mCookie != null) {
            showSessionHome(mBaseUrl, mCookie);
            return;
        }
        super.onBackPressed();
    }

    private void showLogin() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(48), dp(24), dp(24));
        root.setBackgroundColor(Color.rgb(9, 11, 15));
        installRootInsets(root, dp(24), dp(48), dp(24), dp(24), true);

        TextView title = new TextView(this);
        title.setText("WebTerm Mobile");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("Native Android terminal client");
        subtitle.setTextColor(Color.rgb(148, 163, 184));
        subtitle.setTextSize(14);
        subtitle.setPadding(0, dp(8), 0, dp(24));
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

        EditText url = input("Server URL");
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(prefs.getString("url", DEFAULT_URL));
        root.addView(url, matchWrap());

        EditText user = input("Username");
        user.setInputType(InputType.TYPE_CLASS_TEXT);
        user.setText(prefs.getString("user", DEFAULT_USER));
        root.addView(user, matchWrap());

        EditText password = input("Password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText(prefs.getString("password", ""));
        root.addView(password, matchWrap());

        Button connect = new Button(this);
        connect.setText("Connect");
        root.addView(connect, matchWrap());

        ProgressBar progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView status = new TextView(this);
        status.setTextColor(Color.rgb(248, 113, 113));
        status.setPadding(0, dp(12), 0, 0);
        root.addView(status, matchWrap());

        connect.setOnClickListener((v) -> {
            String serverUrl = normalizeBaseUrl(url.getText().toString());
            String username = user.getText().toString().trim();
            String pass = password.getText().toString();
            if (serverUrl.isEmpty() || username.isEmpty() || pass.isEmpty()) {
                status.setText("Enter server URL, username, and password.");
                return;
            }
            prefs.edit().putString("url", serverUrl).putString("user", username).apply();
            status.setText("");
            progress.setVisibility(View.VISIBLE);
            connect.setEnabled(false);
            login(serverUrl, username, pass, new LoginCallback() {
                @Override
                public void onReady(String baseUrl, String cookie) {
                    runOnUiThread(() -> {
                        prefs.edit()
                            .putString("url", serverUrl)
                            .putString("user", username)
                            .putString("password", pass)
                            .apply();
                        progress.setVisibility(View.GONE);
                        showSessionHome(baseUrl, cookie);
                    });
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() -> {
                        progress.setVisibility(View.GONE);
                        connect.setEnabled(true);
                        status.setText(message);
                    });
                }
            });
        });

        setContentView(root);
    }

    private void showSessionHome(String baseUrl, String cookie) {
        closeCurrentTerminal(false);
        mBaseUrl = baseUrl;
        mCookie = cookie;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(16));
        root.setBackgroundColor(Color.rgb(0, 43, 54));
        installRootInsets(root, dp(20), dp(28), dp(20), dp(16), true);

        LinearLayout topbar = new LinearLayout(this);
        topbar.setOrientation(LinearLayout.HORIZONTAL);
        topbar.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("WebTerm");
        title.setTextColor(Color.rgb(253, 246, 227));
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView user = new TextView(this);
        user.setText(prefs.getString("user", DEFAULT_USER));
        user.setTextColor(Color.rgb(147, 161, 161));
        user.setTextSize(13);
        heading.addView(title, new LinearLayout.LayoutParams(-1, -2));
        heading.addView(user, new LinearLayout.LayoutParams(-1, -2));
        topbar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setAllCaps(false);
        styleActionButton(refresh, false);
        Button create = new Button(this);
        create.setText("新建终端");
        create.setAllCaps(false);
        styleActionButton(create, true);
        actions.addView(refresh, new LinearLayout.LayoutParams(dp(76), dp(42)));
        LinearLayout.LayoutParams createLp = new LinearLayout.LayoutParams(dp(104), dp(42));
        createLp.setMargins(dp(8), 0, 0, 0);
        actions.addView(create, createLp);
        topbar.addView(actions, new LinearLayout.LayoutParams(-2, -2));
        root.addView(topbar, new LinearLayout.LayoutParams(-1, dp(58)));

        mSessionStatus = new TextView(this);
        mSessionStatus.setTextColor(Color.rgb(148, 163, 184));
        mSessionStatus.setPadding(0, dp(8), 0, dp(12));
        root.addView(mSessionStatus, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scrollView = new ScrollView(this);
        mSessionList = new LinearLayout(this);
        mSessionList.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(mSessionList, new ScrollView.LayoutParams(-1, -2));
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1));

        refresh.setOnClickListener((v) -> loadSessions());
        create.setOnClickListener((v) -> createSession(mBaseUrl, mCookie, new SessionCreateCallback() {
            @Override
            public void onReady(String sessionId) {
                runOnUiThread(() -> showTerminal(mBaseUrl, mCookie, sessionId));
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> setSessionStatus(message, true));
            }
        }));

        setContentView(root);
        loadSessions();
    }

    private void loadSessions() {
        if (mBaseUrl == null || mCookie == null || mSessionList == null) return;
        setSessionStatus("Loading sessions...", false);
        Request request = new Request.Builder()
            .url(mBaseUrl + "/api/sessions")
            .header("Cookie", mCookie)
            .get()
            .build();
        mHttp.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> setSessionStatus("Load failed: " + e.getMessage(), true));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    String text = response.body().string();
                    if (!response.isSuccessful()) {
                        runOnUiThread(() -> setSessionStatus("Load failed: " + response.code() + " " + text, true));
                        return;
                    }
                    try {
                        JSONArray sessions = new JSONArray(text);
                        runOnUiThread(() -> renderSessions(sessions));
                    } catch (JSONException e) {
                        runOnUiThread(() -> setSessionStatus("Session list error: " + e.getMessage(), true));
                    }
                }
            }
        });
    }

    private void renderSessions(JSONArray sessions) {
        if (mSessionList == null) return;
        mSessionList.removeAllViews();
        setSessionStatus(sessions.length() + " session" + (sessions.length() == 1 ? "" : "s"), false);
        if (sessions.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("还没有终端");
            empty.setTextColor(Color.rgb(147, 161, 161));
            empty.setTextSize(16);
            empty.setPadding(0, dp(24), 0, 0);
            mSessionList.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session == null) continue;
            addSessionRow(session);
        }
    }

    private void addSessionRow(JSONObject session) {
        String id = session.optString("id");
        String rawTermTitle = session.optString("termTitle", "").trim();
        final String termTitle = rawTermTitle.isEmpty() ? "Terminal" : rawTermTitle;
        final String nameText = session.optString("name", "").trim();
        String status = session.optString("status", "unknown");
        int clients = session.optInt("clients", 0);
        String size = session.optInt("cols", 0) + "x" + session.optInt("rows", 0);
        String cwd = session.optString("cwd", "");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(panelBackground());

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = new TextView(this);
        titleView.setText(termTitle);
        titleView.setTextColor(Color.rgb(253, 246, 227));
        titleView.setTextSize(15);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = new Button(this);
        close.setText("x");
        close.setAllCaps(false);
        close.setTextSize(14);
        close.setTextColor(Color.rgb(147, 161, 161));
        close.setPadding(0, 0, 0, 0);
        close.setBackgroundColor(Color.TRANSPARENT);
        header.addView(close, new LinearLayout.LayoutParams(dp(32), dp(32)));
        row.addView(header, new LinearLayout.LayoutParams(-1, -2));

        if (!nameText.isEmpty()) {
            TextView name = new TextView(this);
            name.setText(nameText);
            name.setTextColor(Color.rgb(147, 161, 161));
            name.setTextSize(12);
            name.setPadding(0, dp(2), 0, 0);
            row.addView(name, new LinearLayout.LayoutParams(-1, -2));
        }

        if (session.optBoolean("recentInputHidden", false)) {
            TextView recent = recentInputView("敏感输入已隐藏");
            row.addView(recent, new LinearLayout.LayoutParams(-1, -2));
        } else {
            JSONArray recentLines = session.optJSONArray("recentInputLines");
            String recentText = recentInputText(recentLines);
            if (!recentText.isEmpty()) {
                TextView recent = recentInputView(recentText);
                row.addView(recent, new LinearLayout.LayoutParams(-1, -2));
            }
        }

        TextView meta = new TextView(this);
        meta.setText(cwd + "\n" + id + "  " + status + "  clients:" + clients + "  " + size);
        meta.setTextColor(Color.rgb(101, 123, 131));
        meta.setTextSize(11);
        meta.setPadding(0, dp(6), 0, 0);
        row.addView(meta, new LinearLayout.LayoutParams(-1, -2));

        row.setOnClickListener((v) -> showTerminal(mBaseUrl, mCookie, id, termTitle, nameText));
        close.setOnClickListener((v) -> deleteSession(id));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(10));
        mSessionList.addView(row, lp);
    }

    private void setSessionStatus(String message, boolean error) {
        if (mSessionStatus == null) return;
        mSessionStatus.setText(message);
        mSessionStatus.setTextColor(error ? Color.rgb(248, 113, 113) : Color.rgb(148, 163, 184));
    }

    private void styleActionButton(Button button, boolean primary) {
        button.setTextSize(13);
        button.setTextColor(primary ? Color.rgb(0, 43, 54) : Color.rgb(238, 232, 213));
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(primary ? Color.rgb(38, 139, 210) : Color.TRANSPARENT);
        drawable.setStroke(dp(1), primary ? Color.rgb(38, 139, 210) : Color.rgb(88, 110, 117));
        drawable.setCornerRadius(dp(4));
        button.setBackground(drawable);
        button.setPadding(dp(8), 0, dp(8), 0);
    }

    private GradientDrawable panelBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(Color.rgb(7, 54, 66));
        drawable.setStroke(dp(1), Color.rgb(88, 110, 117));
        drawable.setCornerRadius(dp(8));
        return drawable;
    }

    private TextView recentInputView(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.rgb(238, 232, 213));
        view.setTextSize(12);
        view.setTypeface(Typeface.MONOSPACE);
        view.setMaxLines(2);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setPadding(0, dp(6), 0, 0);
        return view;
    }

    private String recentInputText(@Nullable JSONArray lines) {
        if (lines == null || lines.length() == 0) return "";
        StringBuilder text = new StringBuilder();
        int start = Math.max(0, lines.length() - 2);
        for (int i = start; i < lines.length(); i++) {
            String line = lines.optString(i, "").trim();
            if (line.isEmpty()) continue;
            if (text.length() > 0) text.append('\n');
            text.append(line);
        }
        return text.toString();
    }

    private void deleteSession(String sessionId) {
        setSessionStatus("Closing " + sessionId + "...", false);
        String encodedId = URLEncoder.encode(sessionId, StandardCharsets.UTF_8).replace("+", "%20");
        Request request = new Request.Builder()
            .url(mBaseUrl + "/api/sessions/" + encodedId)
            .header("Cookie", mCookie)
            .delete()
            .build();
        mHttp.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> setSessionStatus("Close failed: " + e.getMessage(), true));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (response) {
                    if (!response.isSuccessful()) {
                        String text = response.body().string();
                        runOnUiThread(() -> setSessionStatus("Close failed: " + response.code() + " " + text, true));
                        return;
                    }
                    runOnUiThread(MainActivity.this::loadSessions);
                }
            }
        });
    }

    private void showTerminal(String baseUrl, String cookie, String sessionId) {
        showTerminal(baseUrl, cookie, sessionId, "Terminal", "");
    }

    private void showTerminal(String baseUrl, String cookie, String sessionId, String termTitle, String sessionName) {
        mBaseUrl = baseUrl;
        mCookie = cookie;
        mSessionId = sessionId;
        String headerTitle = termTitle == null || termTitle.trim().isEmpty() ? "Terminal" : termTitle.trim();
        String headerSubtitle = sessionName == null || sessionName.trim().isEmpty() ? sessionId : sessionName.trim();
        mClosed.set(false);
        mReconnectAttempts = 0;
        mReconnectScheduled = false;

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
        topBar.setBackgroundColor(Color.rgb(7, 54, 66));
        Button sessions = new Button(this);
        sessions.setText("返回");
        sessions.setAllCaps(false);
        styleActionButton(sessions, false);
        mTerminalTitle = new TextView(this);
        mTerminalTitle.setText(headerTitle);
        mTerminalTitle.setTextColor(Color.rgb(238, 232, 213));
        mTerminalTitle.setGravity(Gravity.CENTER_VERTICAL);
        mTerminalTitle.setTextSize(15);
        mTerminalTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mTerminalTitle.setSingleLine(true);
        mTerminalTitle.setEllipsize(TextUtils.TruncateAt.END);
        mTerminalSubtitle = new TextView(this);
        mTerminalSubtitle.setText(headerSubtitle);
        mTerminalSubtitle.setTextColor(Color.rgb(147, 161, 161));
        mTerminalSubtitle.setTextSize(11);
        mTerminalSubtitle.setSingleLine(true);
        mTerminalSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        mConnectionStatus = new TextView(this);
        mConnectionStatus.setText("Disconnected");
        mConnectionStatus.setTextColor(Color.rgb(248, 113, 113));
        mConnectionStatus.setTextSize(11);
        mConnectionStatus.setGravity(Gravity.CENTER);
        mConnectionStatus.setSingleLine(true);
        mConnectionStatus.setEllipsize(TextUtils.TruncateAt.END);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setPadding(dp(10), 0, dp(8), 0);
        labels.addView(mTerminalTitle, new LinearLayout.LayoutParams(-1, 0, 1));
        labels.addView(mTerminalSubtitle, new LinearLayout.LayoutParams(-1, 0, 1));
        topBar.addView(sessions, new LinearLayout.LayoutParams(dp(72), dp(40)));
        topBar.addView(labels, new LinearLayout.LayoutParams(0, dp(44), 1));
        topBar.addView(mConnectionStatus, new LinearLayout.LayoutParams(dp(108), dp(36)));
        content.addView(topBar, new LinearLayout.LayoutParams(-1, dp(54)));

        mTerminalView = new TerminalView(this, null);
        mTerminalView.setFocusable(true);
        mTerminalView.setFocusableInTouchMode(true);
        mTerminalView.setTextSize(28);
        mTerminalView.setTypeface(Typeface.MONOSPACE);
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

        mTerminalSession = TerminalSession.createExternalSession(TRANSCRIPT_ROWS, this, this);
        mTerminalView.attachSession(mTerminalSession);
        mTerminalView.requestFocus();
        sessions.setOnClickListener((v) -> showSessionHome(mBaseUrl, mCookie));
        root.post(() -> {
            if (mTerminalView != null) mTerminalView.updateSize();
            showKeyboard();
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
        mClosed.set(true);
        mMainHandler.removeCallbacksAndMessages(null);
        mConnected = false;
        mReconnectScheduled = false;
        mSocketGeneration++;
        if (mWebSocket != null) {
            mWebSocket.close(1000, "leaving terminal");
            mWebSocket = null;
        }
        if (mTerminalSession != null) {
            mTerminalSession.finishIfRunning();
            mTerminalSession = null;
        }
        mTerminalView = null;
        mConnectionStatus = null;
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
        if (closeRemote && mSessionId != null) deleteSession(mSessionId);
        mSessionId = null;
        mLastSeq = 0;
    }

    private View createQuickBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(8), dp(7), dp(8), dp(7));
        bar.setBackgroundColor(Color.rgb(7, 54, 66));

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
        button.setTextColor(Color.rgb(238, 232, 213));
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
        drawable.setColor(Color.rgb(0, 43, 54));
        drawable.setStroke(dp(1), active ? Color.rgb(38, 139, 210) : Color.rgb(88, 110, 117));
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

    private void login(String baseUrl, String username, String password, LoginCallback callback) {
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
                sendCurrentResize();
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
        mMainHandler.postDelayed(() -> {
            mReconnectScheduled = false;
            if (!mClosed.get() && !mConnected) connectWebSocket();
        }, delayMs);
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
            if (mConnectionStatus == null) return;
            mConnectionStatus.setText(text);
            mConnectionStatus.setTextColor(connected ? Color.rgb(74, 222, 128) : Color.rgb(248, 113, 113));
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

    private EditText input(String hint) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setSingleLine(true);
        editText.setTextColor(Color.WHITE);
        editText.setHintTextColor(Color.rgb(100, 116, 139));
        editText.setBackgroundColor(Color.rgb(15, 23, 42));
        editText.setPadding(dp(12), 0, dp(12), 0);
        return editText;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.setMargins(0, 0, 0, dp(12));
        return lp;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String normalizeBaseUrl(String raw) {
        String value = String.valueOf(raw == null ? "" : raw).trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) return "";
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "http://" + value;
        return value;
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

    private interface LoginCallback {
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
