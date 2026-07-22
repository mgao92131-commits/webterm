package com.webterm.mobile.diagnostics;

import android.content.Context;

import com.webterm.terminal.model.capture.TerminalCapture;

/**
 * Debug/Diag 专用：在应用启动时安装真实捕获控制器。release 由同名 stub 取代（不安装，
 * TerminalCapture 保持 NOOP）。
 */
public final class TerminalCaptureInstaller {
    private TerminalCaptureInstaller() {}

    public static void install(Context context) {
        TerminalCapture.install(new RealTerminalCaptureController(context));
    }
}
