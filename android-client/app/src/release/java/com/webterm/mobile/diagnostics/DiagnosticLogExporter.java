package com.webterm.mobile.diagnostics;

import android.app.Activity;

/** Release 不创建也不导出诊断日志。 */
public final class DiagnosticLogExporter {
    private DiagnosticLogExporter() {}

    public static boolean isAvailable() {
        return false;
    }

    public static void share(Activity activity) {
        // Release intentionally has no diagnostic log files.
    }
}
