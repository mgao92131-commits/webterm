package com.webterm.mobile.diagnostics;

import android.content.Context;
import com.webterm.core.contract.diagnostics.DiagnosticSink;
import com.webterm.core.contract.diagnostics.Diagnostics;

public final class DiagnosticsInstaller {
    public static void install(Context context) {
        Diagnostics.install(DiagnosticSink.NO_OP);
    }
}
