package com.webterm.core.contract.diagnostics;

import java.util.Collections;
import java.util.Map;

public final class Diagnostics {
    private static volatile DiagnosticSink currentSink = DiagnosticSink.NO_OP;

    private Diagnostics() {}

    public static void install(DiagnosticSink sink) {
        if (sink == null) {
            currentSink = DiagnosticSink.NO_OP;
        } else {
            currentSink = sink;
        }
    }

    static void reset() {
        currentSink = DiagnosticSink.NO_OP;
    }

    public static void debug(String area, String event) {
        log(DiagnosticLevel.DEBUG, area, event, null);
    }

    public static void debug(String area, String event, Map<String, ?> fields) {
        log(DiagnosticLevel.DEBUG, area, event, fields);
    }

    public static void info(String area, String event) {
        log(DiagnosticLevel.INFO, area, event, null);
    }

    public static void info(String area, String event, Map<String, ?> fields) {
        log(DiagnosticLevel.INFO, area, event, fields);
    }

    public static void warn(String area, String event) {
        log(DiagnosticLevel.WARN, area, event, null);
    }

    public static void warn(String area, String event, Map<String, ?> fields) {
        log(DiagnosticLevel.WARN, area, event, fields);
    }

    public static void error(String area, String event) {
        log(DiagnosticLevel.ERROR, area, event, null);
    }

    public static void error(String area, String event, Map<String, ?> fields) {
        log(DiagnosticLevel.ERROR, area, event, fields);
    }

    public static boolean isEnabled(DiagnosticLevel level) {
        try {
            return currentSink.isEnabled(level);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void log(DiagnosticLevel level, String area, String event, Map<String, ?> fields) {
        Map<String, ?> safeFields = (fields != null) ? fields : Collections.emptyMap();
        try {
            currentSink.record(level, area, event, safeFields);
        } catch (Throwable t) {
            // Isolate exceptions from sinks to prevent application crashes
        }
    }
}
