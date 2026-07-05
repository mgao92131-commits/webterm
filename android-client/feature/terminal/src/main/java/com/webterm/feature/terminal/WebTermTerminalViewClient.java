package com.webterm.feature.terminal;

import android.view.KeyEvent;
import android.view.MotionEvent;

import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalViewClient;

public final class WebTermTerminalViewClient implements TerminalViewClient {
    private final Host host;

    public WebTermTerminalViewClient(Host host) {
        this.host = host;
    }

    @Override public float onScale(float scale) { return Math.max(0.75f, Math.min(2.0f, scale)); }
    @Override public void onSingleTapUp(MotionEvent e) { host.onTerminalViewTapped(); }
    @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
    @Override public boolean shouldEnforceCharBasedInput() { return false; }
    @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
    @Override public boolean isTerminalViewSelected() { return true; }
    @Override public void copyModeChanged(boolean copyMode) {}
    @Override public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) { return false; }
    @Override public boolean onKeyUp(int keyCode, KeyEvent e) { return false; }
    @Override public boolean onLongPress(MotionEvent event) { return false; }
    @Override public boolean readControlKey() { return host.readTerminalControlKey(); }
    @Override
    public void clearControlKey() {
        host.clearTerminalControlKey();
    }
    @Override public boolean readAltKey() { return false; }
    @Override public boolean readShiftKey() { return false; }
    @Override public boolean readFnKey() { return false; }
    @Override public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        host.clearTerminalControlKey();
        return false;
    }
    @Override public void onEmulatorSet() {}
    @Override public void logError(String tag, String message) { android.util.Log.e(tag, message); }
    @Override public void logWarn(String tag, String message) { android.util.Log.w(tag, message); }
    @Override public void logInfo(String tag, String message) { android.util.Log.i(tag, message); }
    @Override public void logDebug(String tag, String message) { android.util.Log.d(tag, message); }
    @Override public void logVerbose(String tag, String message) { android.util.Log.v(tag, message); }
    @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { android.util.Log.e(tag, message, e); }
    @Override public void logStackTrace(String tag, Exception e) { android.util.Log.e(tag, "stack trace", e); }

    public interface Host {
        void onTerminalViewTapped();
        boolean readTerminalControlKey();
        void clearTerminalControlKey();
    }
}
