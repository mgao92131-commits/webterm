package com.webterm.feature.terminal.domain;

import android.app.Activity;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.TextView;

import com.webterm.core.cache.CachedTerminal;
import com.webterm.core.cache.TerminalCacheCoordinator;
import com.webterm.core.cache.TerminalDiskCache;
import com.webterm.ui.common.command.SessionCommandController;
import com.webterm.core.api.SessionIdentity;
import com.webterm.feature.terminal.TerminalConnectionStatusView;
import com.webterm.feature.terminal.TerminalScreenBuilder;
import com.webterm.feature.terminal.WebTermTerminalSessionClient;
import com.webterm.feature.terminal.WebTermTerminalViewClient;

import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.util.concurrent.atomic.AtomicBoolean;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

public final class TerminalLifecycleController {
    private static final int TRANSCRIPT_ROWS = 10000;
    private static final String PERF_TAG = "TerminalPerf";
    private static final long SLOW_RENDER_MS = 16L;

    private final Activity activity;
    private final Host host;
    private final TerminalRuntimeState terminalState;
    private final AtomicBoolean closed;
    private final TerminalConnectionStatusView connectionStatus;
    private final TerminalCacheCoordinator terminalCache;
    private final TerminalConnection terminalConnection;
    private final TerminalTitleSynchronizer titleSynchronizer;
    private final SessionCommandController sessionCommands;

    private TerminalSession terminalSession;
    private TerminalView terminalView;
    private View terminalRoot;
    private View terminalViewport;
    private View quickBar;
    private Button ctrlButton;
    private TextView terminalTitle;
    private TextView terminalSubtitle;
    private boolean terminalAttachStarted;
    private boolean ctrlDown;
    private WebTermTerminalSessionClient activeSessionClient;
    private final TerminalProjection projection = new TerminalProjection();

    @AssistedInject
    public TerminalLifecycleController(
        @Assisted Activity activity,
        @Assisted Host host,
        @Assisted TerminalRuntimeState terminalState,
        @Assisted AtomicBoolean closed,
        @Assisted TerminalConnectionStatusView connectionStatus,
        TerminalCacheCoordinator terminalCache,
        @Assisted TerminalConnection terminalConnection,
        @Assisted TerminalTitleSynchronizer titleSynchronizer,
        @Assisted SessionCommandController sessionCommands
    ) {
        this.activity = activity;
        this.host = host;
        this.terminalState = terminalState;
        this.closed = closed;
        this.connectionStatus = connectionStatus;
        this.terminalCache = terminalCache;
        this.terminalConnection = terminalConnection;
        this.titleSynchronizer = titleSynchronizer;
        this.sessionCommands = sessionCommands;
    }

    @AssistedFactory
    public interface Factory {
        TerminalLifecycleController create(
            Activity activity,
            Host host,
            TerminalRuntimeState terminalState,
            AtomicBoolean closed,
            TerminalConnectionStatusView connectionStatus,
            TerminalConnection terminalConnection,
            TerminalTitleSynchronizer titleSynchronizer,
            SessionCommandController sessionCommands
        );
    }

    public boolean hasSession() {
        return terminalState.hasSession();
    }

    public boolean hasActiveTerminal() {
        return terminalView != null && terminalSession != null;
    }

    public TerminalView terminalView() { return terminalView; }
    public TerminalSession terminalSession() { return terminalSession; }
    public TerminalConnection terminalConnection() { return terminalConnection; }
    public Button ctrlButton() { return ctrlButton; }

    public boolean readCtrlKey() { return ctrlDown; }

    public void setCtrlKey(boolean down) {
        ctrlDown = down;
        if (ctrlButton != null) {
            activity.runOnUiThread(() -> TerminalScreenBuilder.updateCtrlButtonState(activity, ctrlButton, down));
        }
    }

    public void write(String data) {
        if (terminalSession != null) terminalSession.write(data);
    }

    public void appendOutput(byte[] data) {
        if (terminalSession != null) terminalSession.appendOutput(data);
    }

    public void appendOutput(String data) {
        if (terminalSession != null) terminalSession.appendOutput(data);
    }

    public void showTerminal(
        String baseUrl, String cookie, String sessionId,
        String termTitle, String sessionName, String createdAt, String instanceId,
        String relayDeviceId, String cwd,
        WebTermTerminalViewClient.Host viewClientHost,
        WebTermTerminalSessionClient sessionClient,
        Runnable onBack
    ) {
        String normalizedInstanceId = SessionIdentity.normalizePart(instanceId);
        String normalizedCreatedAt = SessionIdentity.normalizePart(createdAt);
        CachedTerminal cached = terminalCache == null ? null : terminalCache.getMemory(baseUrl, sessionId, normalizedInstanceId, normalizedCreatedAt);
        final TerminalDiskCache.RestoreResult[] diskRestore = new TerminalDiskCache.RestoreResult[1];
        if (cached == null && terminalCache != null && !SessionIdentity.cacheKey(baseUrl, sessionId, normalizedInstanceId, normalizedCreatedAt).isEmpty()) {
            diskRestore[0] = terminalCache.restore(baseUrl, sessionId, normalizedInstanceId, normalizedCreatedAt);
        }
        TerminalLaunchState launchState = TerminalLaunchState.resolve(
            sessionId, termTitle, sessionName, cwd, normalizedCreatedAt, normalizedInstanceId, cached, diskRestore[0]
        );
        terminalState.setServerSession(baseUrl, cookie, sessionId, relayDeviceId);
        closed.set(false);
        terminalState.applyLaunchState(launchState);

        TerminalScreenBuilder.Result terminalScreen = TerminalScreenBuilder.build(
            activity,
            launchState.headerTitle,
            launchState.headerSubtitle,
            host.getSavedFontSize(),
            host.getTypefaceByName(host.getSavedFontType()),
            new WebTermTerminalViewClient(viewClientHost),
            onBack,
            host::requestTerminalReconnect,
            () -> setCtrlKey(!ctrlDown),
            this::write
        );
        terminalRoot = terminalScreen.root;
        terminalView = terminalScreen.terminalView;
        terminalViewport = terminalScreen.terminalViewport;
        quickBar = terminalScreen.quickBar;
        ctrlButton = terminalScreen.ctrlButton;
        activeSessionClient = sessionClient;
        terminalAttachStarted = false;
        terminalTitle = terminalScreen.title;
        terminalSubtitle = terminalScreen.subtitle;
        terminalSubtitle.setText(launchState.headerSubtitle);
        connectionStatus.bind(terminalScreen.statusIndicator, terminalScreen.retryButton, terminalScreen.reconnectOverlay);
        host.installTerminalInsets(terminalRoot);
        if (cached != null && cached.terminalSession != null) {
            // Reuse the cached emulator state, but re-bind the callbacks to the
            // current connection/UI. Otherwise the stale mClient/mExternalIOClient
            // will silently drop input and screen-update notifications.
            terminalSession = cached.terminalSession;
            terminalSession.updateTerminalSessionClient(sessionClient);
            terminalSession.updateExternalIOClient(sessionClient);
        } else {
            terminalSession = TerminalSession.createExternalSession(TRANSCRIPT_ROWS, sessionClient, sessionClient);
        }
        if (cached == null && projection.hasReplayMaterial()) {
            terminalSession.appendOutput(projection.stateBytes());
            terminalSession.appendOutput(projection.outputBytes());
        }
        host.setContentRoot(terminalRoot);
        terminalRoot.post(host::updateKeyboardAvoidance);
        attachTerminalWhenLaidOut();
    }

    public void closeTerminal(boolean closeRemote) {
        String closingBaseUrl = terminalState.baseUrl();
        String closingSessionId = terminalState.sessionId();
        hideKeyboard();
        if (!closeRemote) {
            cacheCurrentTerminal();
        }
        closed.set(true);
        releaseTerminalConnection(closeRemote);
        if (terminalSession != null && closeRemote) {
            terminalSession.finishIfRunning();
        }
        terminalSession = null;
        terminalView = null;
        connectionStatus.clear();
        terminalTitle = null;
        terminalSubtitle = null;
        terminalRoot = null;
        terminalViewport = null;
        quickBar = null;
        ctrlButton = null;
        activeSessionClient = null;
        projection.clear();
        terminalAttachStarted = false;
        ctrlDown = false;
        terminalState.clearTerminalDetails();
        if (titleSynchronizer != null) titleSynchronizer.reset();
        if (closeRemote && closingSessionId != null) {
            removeCachedTerminal(closingBaseUrl, closingSessionId);
            deleteSession(closingSessionId);
        }
        terminalState.clearServerSession();
    }

    public void detachTerminalView() {
        hideKeyboard();
        cacheCurrentTerminal();
        if (terminalConnection != null) terminalConnection.detach();
        connectionStatus.clear();
        terminalView = null;
        terminalRoot = null;
        terminalViewport = null;
        quickBar = null;
        ctrlButton = null;
        terminalTitle = null;
        terminalSubtitle = null;
        terminalAttachStarted = false;
    }

    public void disposeTerminal(String reason) {
        hideKeyboard();
        closed.set(true);
        releaseTerminalConnection(true);
        if (terminalSession != null) {
            terminalSession.finishIfRunning();
        }
        terminalSession = null;
        terminalView = null;
        connectionStatus.clear();
        terminalTitle = null;
        terminalSubtitle = null;
        terminalRoot = null;
        terminalViewport = null;
        quickBar = null;
        ctrlButton = null;
        activeSessionClient = null;
        projection.clear();
        terminalAttachStarted = false;
        ctrlDown = false;
        terminalState.clearTerminalDetails();
        if (titleSynchronizer != null) titleSynchronizer.reset();
        terminalState.clearServerSession();
    }

    public void pauseCurrentConnection() {
        cacheCurrentTerminal();
        if (terminalConnection != null) terminalConnection.detach();
    }

    public void connectTerminal() {
        if (terminalConnection == null || !terminalState.hasSession() || terminalState.baseUrl() == null || terminalState.cookie() == null) return;
        terminalConnection.updateSize(terminalState.columns(), terminalState.rows());
        // If TerminalConnection has no active channel but RelayMuxSessionManager still has one,
        // openTerminalChannel will reattach. Otherwise it creates a new channel.
        // Use cached lastSeq only as seed when TerminalConnection has no lastSeq yet.
        long seq = projection.requiresFreshState()
            ? 0
            : (terminalConnection.getLastSeq() > 0 ? terminalConnection.getLastSeq() : terminalState.lastSeq());
        terminalConnection.connect(terminalState.baseUrl(), terminalState.cookie(), terminalState.sessionId(), seq, terminalState.relayDeviceId());
    }

    public void reconnectFresh(String cookie) {
        if (terminalConnection == null || !terminalState.hasSession()) return;
        terminalState.updateCookie(cookie);
        terminalConnection.updateSize(terminalState.columns(), terminalState.rows());
        terminalConnection.reconnectFresh(cookie);
    }

    public void showKeyboard() {
        if (terminalView == null) return;
        terminalView.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    private void hideKeyboard() {
        View focused = activity.getCurrentFocus();
        View tokenView = terminalView != null ? terminalView : (focused != null ? focused : terminalRoot);
        if (tokenView == null) return;
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(tokenView.getWindowToken(), 0);
        tokenView.clearFocus();
    }

    public void onTerminalResize(int columns, int rows) {
        terminalState.updateSize(columns, rows);
        if (terminalConnection != null) terminalConnection.updateSize(columns, rows);
    }

    public void onTerminalTextChanged() {
        if (terminalView != null) terminalView.onScreenUpdated();
        host.updateKeyboardAvoidance();
    }

    public void onOutput(long seq, byte[] data) {
        long receivedAt = SystemClock.elapsedRealtime();
        activity.runOnUiThread(() -> {
            long uiStartedAt = SystemClock.elapsedRealtime();
            if (closed.get()) return;
            projection.recordOutput(seq, data);
            if (terminalSession == null) return;
            if (seq > 0) {
                terminalState.onOutput(seq, data);
                if (terminalConnection != null) terminalConnection.updateLastSeq(seq);
            }
            terminalSession.appendOutput(data);
            logRender("output", seq, data.length, receivedAt, uiStartedAt);
        });
    }

    public void onState(long seq, byte[] data) {
        long receivedAt = SystemClock.elapsedRealtime();
        activity.runOnUiThread(() -> {
            long uiStartedAt = SystemClock.elapsedRealtime();
            if (closed.get() || activeSessionClient == null) return;
            projection.recordState(seq, data);
            // Reuse the existing session instead of recreating it on every snapshot.
            // The snapshot itself starts with \x1b[3J\x1b[2J\x1b[H which clears
            // scrollback and screen, so replaying into the current emulator is safe.
            // Creating a new session re-initializes the emulator with the current view
            // size, which can mismatch the size the server used to generate the snapshot
            // (e.g. after keyboard/rotation changes) and produce a different layout.
            if (terminalSession == null || terminalSession.getEmulator() == null) {
                terminalSession = TerminalSession.createExternalSession(TRANSCRIPT_ROWS, activeSessionClient, activeSessionClient);
                if (terminalView != null) {
                    terminalView.attachSession(terminalSession);
                    terminalView.requestFocus();
                    terminalView.updateSize();
                }
            }
            if (seq > 0) {
                terminalState.onOutput(seq, data);
                if (terminalConnection != null) terminalConnection.updateLastSeq(seq);
            }
            terminalSession.appendOutput(data);
            if (terminalView != null) {
                terminalView.onScreenUpdated();
                host.updateKeyboardAvoidance();
            }
            logRender("state", seq, data.length, receivedAt, uiStartedAt);
        });
    }

    private void logRender(String kind, long seq, int bytes, long receivedAt, long uiStartedAt) {
        long now = SystemClock.elapsedRealtime();
        long queueMs = uiStartedAt - receivedAt;
        long renderMs = now - uiStartedAt;
        if ("state".equals(kind) || queueMs >= SLOW_RENDER_MS || renderMs >= SLOW_RENDER_MS) {
            Log.i(PERF_TAG, "render kind=" + kind
                + " session=" + terminalState.sessionId()
                + " seq=" + seq
                + " bytes=" + bytes
                + " mainQueueMs=" + queueMs
                + " renderMs=" + renderMs);
        }
    }

    public void onInfo(org.json.JSONObject info) {
        String termTitle = info.optString("termTitle", "").trim();
        String name = info.optString("name", "").trim();
        String instanceId = info.optString("instanceId", "").trim();
        String createdAt = info.optString("createdAt", "").trim();
        terminalState.updateIdentity(instanceId, createdAt);
        activity.runOnUiThread(() -> {
            if (terminalTitle != null && !termTitle.isEmpty()) terminalTitle.setText(termTitle);
            if (terminalSubtitle != null && !name.isEmpty()) terminalSubtitle.setText(name);
        });
    }

    public void onExit(int code) {
        appendOutput("\r\n[Remote session exited]\r\n");
        removeCachedTerminal(terminalState.baseUrl(), terminalState.sessionId());
        if (terminalSession != null) terminalSession.notifyExternalSessionFinished(code);
    }

    public TextView terminalTitleView() { return terminalTitle; }

    public View terminalRoot() { return terminalRoot; }
    public View terminalViewport() { return terminalViewport; }
    public View quickBar() { return quickBar; }

    private void attachTerminalWhenLaidOut() {
        if (terminalRoot == null || terminalView == null || terminalSession == null || terminalAttachStarted) return;
        ViewTreeObserver observer = terminalView.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (terminalView == null) {
                    removeLayoutListener(this);
                    return;
                }
                if (terminalView.getWidth() <= 0 || terminalView.getHeight() <= 0) return;
                removeLayoutListener(this);
                attachAndConnect();
            }
        });
        terminalView.post(() -> {
            if (terminalView != null && terminalView.getWidth() > 0 && terminalView.getHeight() > 0) {
                attachAndConnect();
            }
        });
    }

    private void removeLayoutListener(ViewTreeObserver.OnGlobalLayoutListener listener) {
        if (terminalView == null) return;
        ViewTreeObserver observer = terminalView.getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnGlobalLayoutListener(listener);
    }

    private void attachAndConnect() {
        if (terminalAttachStarted || terminalView == null || terminalSession == null) return;
        if (terminalView.getWidth() <= 0 || terminalView.getHeight() <= 0) return;
        terminalAttachStarted = true;
        terminalView.attachSession(terminalSession);
        terminalView.requestFocus();
        terminalView.updateSize();
        host.updateKeyboardAvoidance();
        if (projection.requiresFreshState() && terminalConnection != null) {
            terminalConnection.requestFreshState();
        }
        // 重新 attach：如果 RelayMuxSessionManager 仍持有该 channel，则复用并替换 listener；
        // 否则 openTerminalChannel 会新建 channel 并发送 hello。
        // hello 中的 lastSeq 优先取运行中 connection 的最新 seq，确保 ReplayAfter 不会重复。
        connectTerminal();
    }

    private void releaseTerminalConnection(boolean closeRemote) {
        if (titleSynchronizer != null) titleSynchronizer.cancel();
        if (terminalConnection == null) return;
        if (closeRemote) {
            terminalConnection.closeSession();
        } else {
            terminalConnection.detach();
        }
    }

    private void cacheCurrentTerminal() {
        if (terminalCache == null) return;
        terminalCache.saveCurrent(terminalState.snapshot(terminalTitle, terminalSubtitle, terminalSession));
    }

    private void removeCachedTerminal(String baseUrl, String sessionId) {
        if (terminalCache == null) return;
        if (terminalCache.removeTerminal(baseUrl, sessionId, terminalState.baseUrl(), terminalState.sessionId(), terminalSession)) {
            terminalState.clearPersistence();
        }
    }

    private void deleteSession(String sessionId) {
        if (sessionCommands != null) {
            sessionCommands.deleteCurrentSession(terminalState.baseUrl(), terminalState.cookie(), sessionId);
        }
    }

    public interface Host {
        int getSavedFontSize();
        String getSavedFontType();
        android.graphics.Typeface getTypefaceByName(String type);
        void installTerminalInsets(View root);
        void setContentRoot(View root);
        void updateKeyboardAvoidance();
        void requestTerminalReconnect();
    }
}
