package com.webterm.mobile.diagnostics;

import android.util.Log;

import com.webterm.core.contract.diagnostics.DiagnosticSink;
import com.webterm.core.contract.diagnostics.Diagnostics;

import java.util.Map;

/** 初始化进程级内存诊断 Ring 并安装 sink。 */
public final class DiagnosticsMemory {
    private static final String TAG = "DiagnosticsMemory";
    private static volatile boolean initialized = false;

    private DiagnosticsMemory() {}

    public static synchronized boolean init() {
        if (initialized) {
            return true;
        }
        try {
            DiagnosticMemoryRing ring = DiagnosticMemoryRing.getInstance();
            Diagnostics.install(new MemoryDiagnosticSink(ring));
            initialized = true;
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize memory diagnostics", t);
            return false;
        }
    }

    static synchronized void shutdownForTest() {
        initialized = false;
        DiagnosticMemoryRing.resetForTest();
        Diagnostics.install(DiagnosticSink.NO_OP);
    }
}
