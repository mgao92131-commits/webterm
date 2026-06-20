package com.webterm.mobile;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

final class CrashReporter {
    private static final String TAG = "CrashReporter";
    private static final String CRASH_DIR = "crash-logs";
    private static final int MAX_LOG_FILES = 10;

    private CrashReporter() {}

    static void install(Context context) {
        Context appContext = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            writeCrash(appContext, thread, throwable);
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            } else {
                System.exit(2);
            }
        });
    }

    static String readLatestCrash(Context context) {
        File latest = latestCrashFile(context);
        if (latest == null) return null;
        try {
            byte[] bytes;
            try (java.io.FileInputStream in = new java.io.FileInputStream(latest);
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                while (true) {
                    int read = in.read(buffer);
                    if (read < 0) break;
                    out.write(buffer, 0, read);
                }
                bytes = out.toByteArray();
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(TAG, "Failed to read latest crash log", e);
            return null;
        }
    }

    private static void writeCrash(Context context, Thread thread, Throwable throwable) {
        File dir = crashDir(context);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create crash log dir: " + dir);
            return;
        }

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
        File file = new File(dir, "crash-" + stamp + ".txt");
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(buildCrashText(context, thread, throwable, stamp).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "Failed to write crash log", e);
        }
        trimOldLogs(dir);
    }

    private static String buildCrashText(Context context, Thread thread, Throwable throwable, String stamp) {
        StringWriter stack = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stack));

        StringBuilder out = new StringBuilder();
        out.append("WebTerm Android crash log\n");
        out.append("timestamp: ").append(stamp).append('\n');
        out.append("thread: ").append(thread == null ? "unknown" : thread.getName()).append('\n');
        appendAppInfo(context, out);
        out.append("android: ").append(Build.VERSION.RELEASE)
            .append(" (sdk ").append(Build.VERSION.SDK_INT).append(")\n");
        out.append("device: ").append(Build.MANUFACTURER).append(' ')
            .append(Build.MODEL).append(" / ").append(Build.DEVICE).append('\n');
        out.append("abis: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append("\n\n");
        out.append(stack);
        return out.toString();
    }

    private static void appendAppInfo(Context context, StringBuilder out) {
        PackageManager pm = context.getPackageManager();
        try {
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                info = pm.getPackageInfo(context.getPackageName(), PackageManager.PackageInfoFlags.of(0));
            } else {
                info = pm.getPackageInfo(context.getPackageName(), 0);
            }
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode()
                : info.versionCode;
            out.append("package: ").append(context.getPackageName()).append('\n');
            out.append("version: ").append(info.versionName).append(" (").append(versionCode).append(")\n");
        } catch (PackageManager.NameNotFoundException e) {
            out.append("package: ").append(context.getPackageName()).append('\n');
            out.append("version: unknown\n");
        }
    }

    private static File latestCrashFile(Context context) {
        File[] files = crashDir(context).listFiles((dir, name) -> name.endsWith(".txt"));
        if (files == null || files.length == 0) return null;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        return files[0];
    }

    private static File crashDir(Context context) {
        return new File(context.getFilesDir(), CRASH_DIR);
    }

    private static void trimOldLogs(File dir) {
        File[] files = dir.listFiles((parent, name) -> name.endsWith(".txt"));
        if (files == null || files.length <= MAX_LOG_FILES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (int i = MAX_LOG_FILES; i < files.length; i++) {
            if (!files[i].delete()) {
                Log.w(TAG, "Failed to delete old crash log: " + files[i]);
            }
        }
    }
}
