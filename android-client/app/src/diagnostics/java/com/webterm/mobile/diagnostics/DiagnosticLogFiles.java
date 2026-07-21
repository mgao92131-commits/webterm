package com.webterm.mobile.diagnostics;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DiagnosticLogFiles {
    private static final String TAG = "DiagnosticLogFiles";
    private static final String LOG_DIR_NAME = "diagnostics";
    /**
     * 启动批次保留上限。当前版本使用固定全局文件名（webterm.log + .bak.1~3），
     * 只占一个批次；该约束主要用于把旧版本「按启动命名」的遗留批次整体清退。
     */
    private static final int MAX_LOG_SESSIONS = 4;
    /** 日志总量预算：最多 4 个文件、合计不超过 4 MiB，与单文件 1 MiB 滚动预算一致。 */
    static final int MAX_LOG_FILES = 4;
    static final long MAX_TOTAL_BYTES = 4L * 1024 * 1024;

    private DiagnosticLogFiles() {}

    public static File directory(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), LOG_DIR_NAME);
    }

    public static List<File> list(Context context) {
        File dir = directory(context);
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }
        List<File> list = new ArrayList<>(Arrays.asList(files));
        Collections.sort(list, new Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                long diff = f1.lastModified() - f2.lastModified();
                if (diff != 0) {
                    return diff < 0 ? -1 : 1;
                }
                return f1.getName().compareTo(f2.getName());
            }
        });
        return list;
    }

    public static long totalBytes(Context context) {
        long total = 0;
        for (File file : list(context)) {
            if (file.isFile()) {
                total += file.length();
            }
        }
        return total;
    }

    public static void clear(Context context) {
        File dir = directory(context);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    if (!file.delete()) {
                        Log.w(TAG, "Failed to delete log file: " + file.getAbsolutePath());
                    }
                }
            }
        }
    }

    public static void trim(Context context) {
        try {
            List<File> deletions = planDeletions(list(context));
            for (File file : deletions) {
                if (!file.delete()) {
                    Log.w(TAG, "Failed to trim old log file: " + file.getAbsolutePath());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error trimming diagnostic files", t);
        }
    }

    /**
     * 给定按 lastModified 从旧到新排序的文件列表，计算应删除的集合。
     * 两类约束同时生效并取并集：
     * 1. 启动批次约束：按 sessionKey 分组，只保留最近 MAX_LOG_SESSIONS 批；
     * 2. 总量约束：文件数不超过 MAX_LOG_FILES 且合计不超过 MAX_TOTAL_BYTES，
     *    超出时从最旧文件开始删（.bak.n 与主文件同等对待）。
     */
    static List<File> planDeletions(List<File> sortedOldestFirst) {
        List<File> files = new ArrayList<>();
        for (File file : sortedOldestFirst) {
            if (file.isFile()) {
                files.add(file);
            }
        }
        List<File> deletions = new ArrayList<>();

        // 约束 1：超出的最旧批次整体删除。
        Map<String, List<File>> sessions = new LinkedHashMap<>();
        for (File file : files) {
            String session = sessionKey(file.getName());
            List<File> sessionFiles = sessions.get(session);
            if (sessionFiles == null) {
                sessionFiles = new ArrayList<>();
                sessions.put(session, sessionFiles);
            }
            sessionFiles.add(file);
        }
        List<File> survivors = new ArrayList<>(files);
        if (sessions.size() > MAX_LOG_SESSIONS) {
            int sessionsToDelete = sessions.size() - MAX_LOG_SESSIONS;
            int deleted = 0;
            for (List<File> sessionFiles : sessions.values()) {
                if (deleted++ >= sessionsToDelete) break;
                deletions.addAll(sessionFiles);
                survivors.removeAll(sessionFiles);
            }
        }

        // 约束 2：幸存文件仍超总量/数量时，从最旧开始逐个删。
        long totalBytes = 0;
        for (File file : survivors) {
            totalBytes += file.length();
        }
        int index = 0;
        while (index < survivors.size()
            && (survivors.size() - index > MAX_LOG_FILES || totalBytes > MAX_TOTAL_BYTES)) {
            File oldest = survivors.get(index++);
            totalBytes -= oldest.length();
            deletions.add(oldest);
        }
        return deletions;
    }

    /** XLog 的文件大小备份命名为 {@code <base>.bak.<n>}，它们必须归入同一启动批次。 */
    static String sessionKey(String name) {
        return name.replaceFirst("\\.bak\\.[0-9]+$", "");
    }
}
