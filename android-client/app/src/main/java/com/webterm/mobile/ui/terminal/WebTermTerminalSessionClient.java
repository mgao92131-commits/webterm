package com.webterm.mobile.ui.terminal;

import android.app.Activity;

import com.webterm.mobile.domain.terminal.TerminalClipboardController;
import com.webterm.mobile.domain.terminal.TerminalTitleSynchronizer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

public final class WebTermTerminalSessionClient implements TerminalSessionClient, TerminalSession.ExternalIOClient {
    private final Activity activity;
    private final Host host;
    private final TerminalClipboardController clipboardController;
    private final TerminalTitleSynchronizer titleSynchronizer;

    public WebTermTerminalSessionClient(
        Activity activity,
        Host host,
        TerminalClipboardController clipboardController,
        TerminalTitleSynchronizer titleSynchronizer
    ) {
        this.activity = activity;
        this.host = host;
        this.clipboardController = clipboardController;
        this.titleSynchronizer = titleSynchronizer;
    }

    @Override
    public void onTerminalInput(String data) {
        host.onTerminalInput(data);
    }

    @Override
    public void onTerminalResize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        host.onTerminalResize(columns, rows);
    }

    @Override
    public void onTextChanged(@NonNull TerminalSession changedSession) {
        host.onTerminalTextChanged();
    }

    @Override
    public void onTitleChanged(@NonNull TerminalSession changedSession) {
        titleSynchronizer.onTitleChanged(changedSession, host.terminalTitleView());
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
        clipboardController.copy(text);
    }

    @Override
    public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
        clipboardController.paste(session);
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

    public interface Host {
        void onTerminalInput(String data);
        void onTerminalResize(int columns, int rows);
        void onTerminalTextChanged();
        android.widget.TextView terminalTitleView();
    }
}
