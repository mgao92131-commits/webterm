package com.webterm.mobile.diagnostics;

import com.elvishew.xlog.printer.file.naming.FileNameGenerator;

/** 诊断日志只有一个活动文件；XLog 按 .1 至 .3 做有界备份。 */
public final class FixedLogFileNameGenerator implements FileNameGenerator {
    @Override
    public boolean isFileNameChangeable() {
        return false;
    }

    @Override
    public String generateFileName(int logLevel, long timestamp) {
        return "webterm.log";
    }
}
