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
    /** 每次启动一份日志；旧版产生的 .bak.* 仍按原启动批次整体保留或清理。 */
    private static final int MAX_LOG_SESSIONS = 4;

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
            List<File> files = list(context);
            Map<String, List<File>> sessions = new LinkedHashMap<>();
            for (File file : files) {
                if (!file.isFile()) continue;
                String session = sessionKey(file.getName());
                List<File> sessionFiles = sessions.get(session);
                if (sessionFiles == null) {
                    sessionFiles = new ArrayList<>();
                    sessions.put(session, sessionFiles);
                }
                sessionFiles.add(file);
            }
            if (sessions.size() <= MAX_LOG_SESSIONS) {
                return;
            }
            int sessionsToDelete = sessions.size() - MAX_LOG_SESSIONS;
            int deleted = 0;
            for (List<File> sessionFiles : sessions.values()) {
                if (deleted++ >= sessionsToDelete) break;
                for (File file : sessionFiles) {
                    if (!file.delete()) {
                        Log.w(TAG, "Failed to trim old log file: " + file.getAbsolutePath());
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error trimming diagnostic files", t);
        }
    }

    /** XLog 的文件大小备份命名为 {@code <base>.bak.<n>}，它们必须归入同一启动批次。 */
    static String sessionKey(String name) {
        return name.replaceFirst("\\.bak\\.[0-9]+$", "");
    }
}
