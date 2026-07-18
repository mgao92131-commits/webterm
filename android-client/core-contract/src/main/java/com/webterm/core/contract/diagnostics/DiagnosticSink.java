package com.webterm.core.contract.diagnostics;

import java.util.Map;

public interface DiagnosticSink {
    void record(DiagnosticLevel level, String area, String event, Map<String, ?> fields);

    /** Allows hot paths to avoid building fields when diagnostics are not installed. */
    default boolean isEnabled(DiagnosticLevel level) {
        return true;
    }

    DiagnosticSink NO_OP = new DiagnosticSink() {
        @Override
        public void record(DiagnosticLevel level, String area, String event, Map<String, ?> fields) {
            // No-op, do nothing
        }

        @Override
        public boolean isEnabled(DiagnosticLevel level) {
            return false;
        }
    };
}
