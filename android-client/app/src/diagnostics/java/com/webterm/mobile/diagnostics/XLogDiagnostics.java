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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class XLogDiagnostics {
    private static final String TAG = "XLogDiagnostics";
    /** 单个日志文件超过 1 MiB 即滚动备份；单次启动最多主文件 + 3 个 .bak，与总量预算一致。 */
    private static final long MAX_FILE_BYTES = 1024L * 1024L;
    private static final int MAX_BACKUP_INDEX = 3;
    /**
     * 运行期 trim 周期。XLog 没有滚动完成回调，无法靠回调触发清理；
     * 用周期任务兜底：旧启动批次写入时也能在分钟级内回落到容量预算内。
     */
    private static final long TRIM_INTERVAL_MILLIS = 60_000L;
    private static volatile boolean initialized = false;
    private static ScheduledExecutorService trimExecutor;

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
            startPeriodicTrim(appContext);
            initialized = true;
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize XLog", t);
            return false;
        }
    }

    /** 每 60s 后台 trim 一次，保证运行期间日志目录始终处于文件数/字节预算内。 */
    private static void startPeriodicTrim(Context appContext) {
        trimExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "webterm-diag-trim");
            thread.setDaemon(true);
            return thread;
        });
        trimExecutor.scheduleWithFixedDelay(() -> DiagnosticLogFiles.trim(appContext),
            TRIM_INTERVAL_MILLIS, TRIM_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }
}
