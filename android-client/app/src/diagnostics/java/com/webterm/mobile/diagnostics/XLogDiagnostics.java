package com.webterm.mobile.diagnostics;

import android.content.Context;
import android.util.Log;
import com.elvishew.xlog.LogConfiguration;
import com.elvishew.xlog.LogLevel;
import com.elvishew.xlog.XLog;
import com.elvishew.xlog.printer.AndroidPrinter;
import com.elvishew.xlog.printer.Printer;
import com.elvishew.xlog.printer.file.FilePrinter;
import com.elvishew.xlog.printer.file.backup.FileSizeBackupStrategy;
import com.elvishew.xlog.printer.file.clean.FileLastModifiedCleanStrategy;
import com.elvishew.xlog.printer.file.naming.DateFileNameGenerator;
import java.io.File;

public final class XLogDiagnostics {
    private static final String TAG = "XLogDiagnostics";
    private static volatile boolean initialized = false;

    private XLogDiagnostics() {}

    public static synchronized void init(Context context) {
        if (initialized) {
            return;
        }
        try {
            Context appContext = context.getApplicationContext();
            File logDir = DiagnosticLogFiles.directory(appContext);
            if (!logDir.exists() && !logDir.mkdirs()) {
                Log.w(TAG, "Failed to create diagnostics log directory: " + logDir);
            }

            DiagnosticLogFiles.trim(appContext);

            LogConfiguration config = new LogConfiguration.Builder()
                .logLevel(LogLevel.ALL)
                .tag("WebTermDiag")
                .build();

            Printer androidPrinter = new AndroidPrinter();
            Printer filePrinter = new FilePrinter.Builder(logDir.getAbsolutePath())
                .fileNameGenerator(new DateFileNameGenerator())
                .backupStrategy(new FileSizeBackupStrategy(1024 * 1024))
                .cleanStrategy(new FileLastModifiedCleanStrategy(3 * 24 * 60 * 60 * 1000L))
                .build();

            XLog.init(config, androidPrinter, filePrinter);
            initialized = true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize XLog", t);
        }
    }
}
