package com.webterm.mobile.diagnostics;

import android.content.Context;
import com.webterm.core.contract.diagnostics.DiagnosticSink;
import com.webterm.core.contract.diagnostics.Diagnostics;

import java.util.Map;

public final class DiagnosticsInstaller {
    public static void install(Context context) {
        if (XLogDiagnostics.init(context)) {
            Diagnostics.install(new XLogDiagnosticSink());
            Diagnostics.info("app", "diagnostics_initialized", Map.of("mode", "diag"));
        } else {
            Diagnostics.install(DiagnosticSink.NO_OP);
        }
    }
}
