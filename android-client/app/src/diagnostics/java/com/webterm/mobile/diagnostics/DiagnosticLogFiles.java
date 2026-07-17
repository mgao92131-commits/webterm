package com.webterm.mobile.diagnostics;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class DiagnosticLogFiles {
    private static final String TAG = "DiagnosticLogFiles";
    private static final String LOG_DIR_NAME = "diagnostics";
    private static final int MAX_LOG_FILES = 4;

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
            if (files.size() <= MAX_LOG_FILES) {
                return;
            }
            int filesToDelete = files.size() - MAX_LOG_FILES;
            for (int i = 0; i < filesToDelete; i++) {
                File file = files.get(i);
                if (file.isFile()) {
                    if (!file.delete()) {
                        Log.w(TAG, "Failed to trim old log file: " + file.getAbsolutePath());
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error trimming diagnostic files", t);
        }
    }
}
