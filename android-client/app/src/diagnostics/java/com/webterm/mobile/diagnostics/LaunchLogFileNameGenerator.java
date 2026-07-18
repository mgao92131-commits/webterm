package com.webterm.mobile.diagnostics;

import com.elvishew.xlog.printer.file.naming.FileNameGenerator;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 每次应用进程启动使用独立的诊断文件，避免跨启动日志混杂。 */
public final class LaunchLogFileNameGenerator implements FileNameGenerator {
    private final String fileName;

    public LaunchLogFileNameGenerator(long startedAtMs) {
        String startedAt = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
            .format(new Date(startedAtMs));
        this.fileName = "webterm-" + startedAt + ".log";
    }

    @Override
    public boolean isFileNameChangeable() {
        return false;
    }

    @Override
    public String generateFileName(int logLevel, long timestamp) {
        return fileName;
    }
}
