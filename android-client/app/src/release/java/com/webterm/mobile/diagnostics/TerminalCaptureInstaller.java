package com.webterm.mobile.diagnostics;

import android.content.Context;

/** Release 不安装真实捕获实现；TerminalCapture 保持 NOOP，无 ring buffer 内存。 */
public final class TerminalCaptureInstaller {
    private TerminalCaptureInstaller() {}

    public static void install(Context context) {
        // Release intentionally installs nothing.
    }
}
