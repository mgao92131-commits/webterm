package com.webterm.mobile.diagnostics;

import com.webterm.core.contract.diagnostics.Diagnostics;

import java.util.Map;

public final class DiagnosticsInstaller {
    public static void install(android.content.Context context) {
        if (DiagnosticsMemory.init()) {
            Diagnostics.info("app", "diagnostics_initialized", Map.of("mode", "diag"));
        }
    }
}
