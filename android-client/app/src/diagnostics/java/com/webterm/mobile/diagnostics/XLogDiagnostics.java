package com.webterm.mobile.diagnostics;

import android.content.Context;
import android.util.Log;
import com.elvishew.xlog.LogConfiguration;
import com.elvishew.xlog.LogLevel;
import com.elvishew.xlog.XLog;
import com.elvishew.xlog.printer.AndroidPrinter;
import com.elvishew.xlog.printer.Printer;
import com.elvishew.xlog.printer.file.FilePrinter;
import com.elvishew.xlog.printer.file.backup.FileSizeBackupStrategy2;
import java.io.File;

public final class XLogDiagnostics {
    private static final String TAG = "XLogDiagnostics";
    /** 单个日志文件超过 1 MiB 即滚动备份；单次启动最多主文件 + 3 个 .bak，与总量预算一致。 */
    private static final long MAX_FILE_BYTES = 1024L * 1024L;
    private static final int MAX_BACKUP_INDEX = 3;
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
                .backupStrategy(new FileSizeBackupStrategy2(MAX_FILE_BYTES, MAX_BACKUP_INDEX))
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
