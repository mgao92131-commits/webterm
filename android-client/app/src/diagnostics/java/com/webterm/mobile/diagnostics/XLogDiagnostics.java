package com.webterm.mobile.diagnostics;

import android.content.Context;
import android.util.Log;
import com.elvishew.xlog.LogConfiguration;
import com.elvishew.xlog.LogLevel;
import com.elvishew.xlog.XLog;
import com.elvishew.xlog.printer.AndroidPrinter;
import com.elvishew.xlog.printer.Printer;
import com.elvishew.xlog.printer.file.FilePrinter;
import java.io.File;

public final class XLogDiagnostics {
    private static final String TAG = "XLogDiagnostics";
    private static volatile boolean initialized = false;

    private XLogDiagnostics() {}

    public static synchronized boolean init(Context context) {
        if (initialized) {
            return true;
        }
        try {
            Context appContext = context.getApplicationContext();
            File logDir = DiagnosticLogFiles.directory(appContext);
            if (!logDir.exists() && !logDir.mkdirs()) {
                throw new IllegalStateException("Failed to create diagnostics log directory: " + logDir);
            }
            DiagnosticLogFiles.trim(appContext);

            LogConfiguration config = new LogConfiguration.Builder()
                .logLevel(LogLevel.ALL)
                .tag("WebTermDiag")
                .build();

            Printer androidPrinter = new AndroidPrinter();
            Printer filePrinter = new FilePrinter.Builder(logDir.getAbsolutePath())
                .fileNameGenerator(new LaunchLogFileNameGenerator(System.currentTimeMillis()))
                .build();

            XLog.init(config, androidPrinter, filePrinter);
            initialized = true;
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize XLog", t);
            return false;
        }
    }
}
