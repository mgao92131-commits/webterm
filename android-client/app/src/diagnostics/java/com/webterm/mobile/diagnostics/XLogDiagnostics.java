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
    /** 单个日志文件超过 1 MiB 即滚动备份；全局固定主文件 + 3 个 .bak，与总量预算一致。 */
    private static final long MAX_FILE_BYTES = 1024L * 1024L;
    private static final int MAX_BACKUP_INDEX = 3;
    /**
     * 运行期 trim 周期。XLog 没有滚动完成回调，无法靠回调触发清理；
     * 固定全局文件已由 XLog 在滚动时删除最旧备份（见下），周期 trim 只是兜底：
     * 清理旧版本遗留的按启动命名文件，并在分钟级内把目录回落到容量预算内。
     */
    private static final long TRIM_INTERVAL_MILLIS = 60_000L;
    private static volatile boolean initialized = false;
    private static ScheduledExecutorService trimExecutor;

    private XLogDiagnostics() {}

    /**
     * 初始化诊断日志。所有应用启动共享同一组固定文件名（webterm.log 与
     * webterm.log.bak.1~3）：XLog 的 {@link FileSizeBackupStrategy2} 在单个文件超过
     * {@link #MAX_FILE_BYTES} 时滚动，并在滚动时删除最旧的 .bak.{@link #MAX_BACKUP_INDEX}，
     * 因此目录在 XLog 层就恒定为「≤4 文件、合计约 ≤4 MiB + 单条日志误差」的真上限，
     * 不再依赖周期 trim 才回落到预算内。周期 trim 仅用于清理旧版本遗留文件。
     */
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
                .fileNameGenerator(new FixedLogFileNameGenerator())
                .backupStrategy(new FileSizeBackupStrategy2(MAX_FILE_BYTES, MAX_BACKUP_INDEX))
                .build();

            XLog.init(config, androidPrinter, filePrinter);
            startPeriodicTrim(() -> DiagnosticLogFiles.trim(appContext));
            initialized = true;
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize XLog", t);
            return false;
        }
    }

    /**
     * 启动周期 trim。幂等：重复调用会先停掉既有 executor 再重建，保证任何时刻
     * 只有一个 webterm-diag-trim 线程，不会因为重复初始化而泄漏线程。
     * 抽出为接受 {@link Runnable} 的包级方法，便于在无 Android Context 的单测中
     * 验证「多次初始化不产生多个 executor」。
     */
    static synchronized ScheduledExecutorService startPeriodicTrim(Runnable trimTask) {
        stopPeriodicTrim();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "webterm-diag-trim");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(trimTask,
            TRIM_INTERVAL_MILLIS, TRIM_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        trimExecutor = executor;
        return executor;
    }

    /** 停止周期 trim（幂等）。 */
    static synchronized void stopPeriodicTrim() {
        if (trimExecutor != null) {
            trimExecutor.shutdownNow();
            trimExecutor = null;
        }
    }

    /**
     * 测试用关闭入口：停止周期 trim 线程并复位初始化标记，避免测试或进程内
     * 重复初始化累积 executor。生产代码经进程退出自然回收守护线程。
     */
    static synchronized void shutdownForTest() {
        stopPeriodicTrim();
        initialized = false;
    }
}
