package com.webterm.core.contract.diagnostics;

import java.util.Map;

public interface DiagnosticSink {
    void record(DiagnosticLevel level, String area, String event, Map<String, ?> fields);

    DiagnosticSink NO_OP = new DiagnosticSink() {
        @Override
        public void record(DiagnosticLevel level, String area, String event, Map<String, ?> fields) {
            // No-op, do nothing
        }
    };
}
