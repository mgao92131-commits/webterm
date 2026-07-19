package com.webterm.mobile.diagnostics;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.webterm.core.session.traffic.NetworkTrafficStats;
import com.webterm.terminal.model.TerminalRenderMetrics;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Debug/Diag 专用：只导出有界的本地诊断日志，不包含终端正文或 PTY 捕获。 */
public final class DiagnosticLogExporter {
    private static final int BUFFER_SIZE = 8 * 1024;

    private DiagnosticLogExporter() {}

    public static boolean isAvailable() {
        return true;
    }

    public static void share(Activity activity) {
        Activity target = activity;
        new Thread(() -> {
            try {
                File archive = createArchive(target);
                target.runOnUiThread(() -> shareArchive(target, archive));
            } catch (IOException e) {
                target.runOnUiThread(() -> Toast.makeText(target, "暂无可导出的诊断日志", Toast.LENGTH_SHORT).show());
            }
        }, "webterm-diagnostic-export").start();
    }

    private static File createArchive(Activity activity) throws IOException {
        List<File> logs = DiagnosticLogFiles.list(activity);
        if (logs.isEmpty()) {
            throw new IOException("no diagnostic logs");
        }

        File exportDir = new File(activity.getCacheDir(), "diagnostics-export");
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            throw new IOException("cannot create export directory");
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File archive = new File(exportDir, "webterm-diagnostics-" + timestamp + ".zip");
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            for (File log : logs) {
                if (!log.isFile()) continue;
                output.putNextEntry(new ZipEntry(log.getName()));
                try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(log))) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }
                }
                output.closeEntry();
            }
            output.putNextEntry(new ZipEntry("network-traffic-summary.txt"));
            output.write(buildTrafficSummary().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return archive;
    }

    private static String buildTrafficSummary() {
        NetworkTrafficStats.Snapshot network = NetworkTrafficStats.snapshot();
        TerminalRenderMetrics.Snapshot screen = TerminalRenderMetrics.snapshot();
        StringBuilder sb = new StringBuilder();
        sb.append("WebTerm Network Traffic Summary\n");
        sb.append("================================\n");
        sb.append("uidRxBytes=").append(network.uid.rxBytes).append('\n');
        sb.append("uidTxBytes=").append(network.uid.txBytes).append('\n');
        sb.append("websocketRxFrames=").append(network.websocket.rxFrames).append('\n');
        sb.append("websocketRxBytes=").append(network.websocket.rxBytes).append('\n');
        sb.append("websocketTxFrames=").append(network.websocket.txFrames).append('\n');
        sb.append("websocketTxBytes=").append(network.websocket.txBytes).append('\n');
        sb.append("screenSnapshotCount=").append(screen.snapshotFrameCount).append('\n');
        sb.append("screenSnapshotBytes=").append(screen.snapshotFrameBytes).append('\n');
        sb.append("screenPatchCount=").append(screen.patchFrameCount).append('\n');
        sb.append("screenPatchBytes=").append(screen.patchFrameBytes).append('\n');
        sb.append("screenHistoryPageCount=").append(screen.historyPageFrameCount).append('\n');
        sb.append("screenHistoryPageBytes=").append(screen.historyPageFrameBytes).append('\n');
        sb.append("screenHistoryTrimCount=").append(screen.historyTrimFrameCount).append('\n');
        sb.append("screenHistoryTrimBytes=").append(screen.historyTrimFrameBytes).append('\n');
        sb.append("screenOtherCount=").append(screen.otherFrameCount).append('\n');
        sb.append("screenOtherBytes=").append(screen.otherFrameBytes).append('\n');
        return sb.toString();
    }

    private static void shareArchive(Activity activity, File archive) {
        if (activity.isFinishing()) return;
        Uri uri = FileProvider.getUriForFile(activity,
            activity.getPackageName() + ".diagnostics", archive);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("application/zip");
        send.putExtra(Intent.EXTRA_SUBJECT, "WebTerm 诊断日志");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.setClipData(ClipData.newRawUri("WebTerm 诊断日志", uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(Intent.createChooser(send, "导出诊断日志"));
    }
}
