package com.webterm.feature.terminal.domain;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.View;
import android.widget.TextView;

import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;
import com.webterm.feature.terminal.TerminalConnectionStatusView;
import com.webterm.feature.terminal.TerminalViewModel;
import com.webterm.feature.terminal.WebTermTerminalSessionClient;
import com.webterm.feature.terminal.WebTermTerminalViewClient;
import com.webterm.ui.common.command.SessionCommandController;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

public final class TerminalRuntime implements TerminalConnection.Listener,
    WebTermTerminalViewClient.Host, WebTermTerminalSessionClient.Host {

    public interface HookListener {
        void onHook(JSONObject ev);
        void onDownloadHook(String downloadId, String fileName, long fileSize, String sessionId);
    }

    private final Activity activity;
    private final TerminalRuntimeState state = new TerminalRuntimeState();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final TerminalConnectionStatusView connectionStatus = new TerminalConnectionStatusView();
    private final TerminalConnection connection;
    private final TerminalLifecycleController lifecycle;
    private final TerminalTitleSynchronizer titleSynchronizer;
    private final WebTermTerminalSessionClient sessionClient;

    private ViewHost currentViewHost;
    private Runnable onFinished;
    private HookListener hookListener;

    @AssistedInject
    public TerminalRuntime(
        @Assisted Activity activity,
        @Assisted SessionCommandController sessionCommands,
        TerminalConnection.Factory connectionFactory,
        TerminalLifecycleController.Factory lifecycleFactory,
        TerminalClipboardController.Factory clipboardFactory,
        TerminalTitleSynchronizer.Factory titleFactory
    ) {
        this.activity = activity;
        this.connection = connectionFactory.create(this);
        this.titleSynchronizer = titleFactory.create(() -> connection);
        TerminalClipboardController clipboard = clipboardFactory.create(activity, this);
        this.sessionClient = new WebTermTerminalSessionClient(activity, this, clipboard, titleSynchronizer);
        this.lifecycle = lifecycleFactory.create(
            activity, new DelegatingViewHost(), state, closed, connectionStatus,
            connection, titleSynchronizer, sessionCommands
        );
    }

    @AssistedFactory
    public interface Factory {
        TerminalRuntime create(Activity activity, SessionCommandController sessionCommands);
    }

    public void attach(TerminalViewModel.TerminalSessionArgs args, ViewHost viewHost,
                       Runnable onBack) {
        currentViewHost = viewHost;
        lifecycle.showTerminal(
            args.baseUrl, args.cookie, args.sessionId, args.termTitle,
            args.createdAt, args.instanceId, args.relayDeviceId, args.cwd,
            this, sessionClient, onBack
        );
    }

    public void setOnFinished(Runnable onFinished) {
        this.onFinished = onFinished;
    }

    public void setHookListener(HookListener listener) {
        this.hookListener = listener;
    }

    public void detachView() {
        lifecycle.detachTerminalView();
        currentViewHost = null;
    }

    public void close(boolean closeRemote) {
        lifecycle.closeTerminal(closeRemote);
        currentViewHost = null;
    }

    public void disposeLocal() {
        lifecycle.disposeTerminal("runtime disposed");
        currentViewHost = null;
    }

    public void pauseConnection() {
        lifecycle.pauseCurrentConnection();
    }

    public boolean hasSession() {
        return lifecycle.hasSession();
    }

    public boolean hasActiveTerminal() {
        return lifecycle.hasActiveTerminal();
    }

    public TerminalSession terminalSession() {
        return lifecycle.terminalSession();
    }

    public TerminalView terminalView() {
        return lifecycle.terminalView();
    }

    public View terminalRoot() {
        return lifecycle.terminalRoot();
    }

    public View terminalViewport() {
        return lifecycle.terminalViewport();
    }

    public View quickBar() {
        return lifecycle.quickBar();
    }

    public TerminalConnection connection() {
        return connection;
    }

    public TerminalRuntimeState state() {
        return state;
    }

    public boolean matches(String baseUrl, String sessionId, String relayDeviceId) {
        if (sessionId == null || state.sessionId() == null) return false;
        return normalizeBaseUrl(baseUrl).equals(normalizeBaseUrl(state.baseUrl()))
            && sessionId.equals(state.sessionId())
            && safe(relayDeviceId).equals(safe(state.relayDeviceId()));
    }

    public void connectTerminal() {
        lifecycle.connectTerminal();
    }

    public void reconnectFresh(String cookie) {
        lifecycle.reconnectFresh(cookie);
    }

    public void showKeyboard() {
        lifecycle.showKeyboard();
    }

    public boolean readCtrlKey() {
        return lifecycle.readCtrlKey();
    }

    public void setCtrlKey(boolean down) {
        lifecycle.setCtrlKey(down);
    }

    public void appendOutput(String data) {
        lifecycle.appendOutput(data);
    }

    public void updateFontSize(int size) {
        if (lifecycle.terminalView() != null) lifecycle.terminalView().setTextSize(size);
    }

    public void updateTypeface(Typeface typeface) {
        if (lifecycle.terminalView() != null) lifecycle.terminalView().setTypeface(typeface);
    }

    @Override
    public void onTerminalInput(String data) {
        connection.sendInput(data);
    }

    @Override
    public void onTerminalResize(int columns, int rows) {
        lifecycle.onTerminalResize(columns, rows);
    }

    @Override
    public void onTerminalTextChanged() {
        lifecycle.onTerminalTextChanged();
    }

    @Override
    public TextView terminalTitleView() {
        return lifecycle.terminalTitleView();
    }

    @Override
    public void onTerminalViewTapped() {
        lifecycle.showKeyboard();
    }

    @Override
    public boolean readTerminalControlKey() {
        return lifecycle.readCtrlKey();
    }

    @Override
    public void clearTerminalControlKey() {
        lifecycle.setCtrlKey(false);
    }

    @Override
    public void onConnectionStatus(TerminalConnection.State state, int reconnectAttempts) {
        boolean isP2P = connection.isP2PConnected();
        activity.runOnUiThread(() -> connectionStatus.update(state, reconnectAttempts, isP2P));
    }

    @Override
    public void onOutput(long seq, byte[] data) {
        lifecycle.onOutput(seq, data);
    }

    @Override
    public void onState(long seq, byte[] data) {
        lifecycle.onState(seq, data);
    }

    @Override
    public void onInfo(JSONObject info) {
        lifecycle.onInfo(info);
    }

    @Override
    public void onHook(JSONObject ev) {
        if (hookListener == null) return;
        activity.runOnUiThread(() -> hookListener.onHook(ev));
    }

    @Override
    public void onDownloadHook(String downloadId, String fileName, long fileSize, String sessionId) {
        if (hookListener == null) return;
        activity.runOnUiThread(() -> hookListener.onDownloadHook(downloadId, fileName, fileSize, sessionId));
    }

    @Override
    public void onExit(int code) {
        lifecycle.onExit(code);
        if (onFinished != null) onFinished.run();
    }

    @Override
    public void onProtocolError(String message) {
        lifecycle.appendOutput("\r\n[" + message + "]\r\n");
    }

    public interface ViewHost {
        int getSavedFontSize();
        String getSavedFontType();
        Typeface getTypefaceByName(String type);
        void installTerminalInsets(View root);
        void setContentRoot(View root);
        void updateKeyboardAvoidance();
        void requestTerminalReconnect();

        /** 顶栏「更多 → 上传文件」：转发给终端页 Fragment 发起 ACTION_OPEN_DOCUMENT。 */
        void requestFileUpload();
    }

    private final class DelegatingViewHost implements TerminalLifecycleController.Host {
        @Override public int getSavedFontSize() {
            return currentViewHost == null ? 14 : currentViewHost.getSavedFontSize();
        }
        @Override public String getSavedFontType() {
            return currentViewHost == null ? "monospace" : currentViewHost.getSavedFontType();
        }
        @Override public Typeface getTypefaceByName(String type) {
            return currentViewHost == null ? Typeface.MONOSPACE : currentViewHost.getTypefaceByName(type);
        }
        @Override public void installTerminalInsets(View root) {
            if (currentViewHost != null) currentViewHost.installTerminalInsets(root);
        }
        @Override public void setContentRoot(View root) {
            if (currentViewHost != null) currentViewHost.setContentRoot(root);
        }
        @Override public void updateKeyboardAvoidance() {
            if (currentViewHost != null) currentViewHost.updateKeyboardAvoidance();
        }
        @Override public void requestTerminalReconnect() {
            if (currentViewHost != null) currentViewHost.requestTerminalReconnect();
        }
        @Override public void requestFileUpload() {
            if (currentViewHost != null) currentViewHost.requestFileUpload();
        }
    }

    private static String normalizeBaseUrl(String raw) {
        String value = String.valueOf(raw == null ? "" : raw).trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) return "";
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "http://" + value;
        return value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
