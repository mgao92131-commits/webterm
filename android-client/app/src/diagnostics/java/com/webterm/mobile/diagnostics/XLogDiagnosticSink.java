package com.webterm.mobile.diagnostics;

import com.elvishew.xlog.XLog;
import com.webterm.core.contract.diagnostics.DiagnosticFormatter;
import com.webterm.core.contract.diagnostics.DiagnosticLevel;
import com.webterm.core.contract.diagnostics.DiagnosticSink;
import java.util.Map;

public final class XLogDiagnosticSink implements DiagnosticSink {

    @Override
    public void record(DiagnosticLevel level, String area, String event, Map<String, ?> fields) {
        try {
            String formatted = DiagnosticFormatter.format(area, event, fields);
            DiagnosticLevel safeLevel = (level != null) ? level : DiagnosticLevel.DEBUG;
            switch (safeLevel) {
                case DEBUG:
                    XLog.d(formatted);
                    break;
                case INFO:
                    XLog.i(formatted);
                    break;
                case WARN:
                    XLog.w(formatted);
                    break;
                case ERROR:
                    XLog.e(formatted);
                    break;
            }
        } catch (Throwable t) {
            // Isolate log writing exception
        }
    }
}
