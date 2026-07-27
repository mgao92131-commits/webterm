package com.webterm.mobile.diagnostics;

import com.webterm.core.contract.diagnostics.DiagnosticLevel;
import com.webterm.core.contract.diagnostics.DiagnosticSink;

import java.util.Map;

/** 将诊断事件写入进程级内存 Ring，不落盘。 */
public final class MemoryDiagnosticSink implements DiagnosticSink {
    private final DiagnosticMemoryRing ring;

    MemoryDiagnosticSink(DiagnosticMemoryRing ring) {
        this.ring = ring;
    }

    @Override
    public void record(DiagnosticLevel level, String area, String event, Map<String, ?> fields) {
        try {
            ring.record(level, area, event, fields);
        } catch (Throwable ignored) {
            // 隔离 sink 异常，避免影响主流程。
        }
    }
}
