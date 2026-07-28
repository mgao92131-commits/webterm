package com.webterm.mobile.diagnostics;

import com.webterm.core.contract.diagnostics.DiagnosticIdHasher;
import com.webterm.core.contract.diagnostics.DiagnosticLevel;

import org.json.JSONException;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * 进程级诊断事件内存 Ring：应用启动时创建，进程结束前始终使用同一缓冲区。
 * 达到条数或字节上限后丢弃最旧记录，并累计 {@link #droppedEntryCount()}。
 */
public final class DiagnosticMemoryRing {
    public static final int MAX_ENTRIES = 5000;
    public static final long MAX_BYTES = 5L * 1024 * 1024;

    private static volatile DiagnosticMemoryRing instance;

    private final String runId;
    private final Object lock = new Object();
    private final List<DiagnosticEntry> entries = new ArrayList<>();
    private long totalBytes = 0;
    private long nextSeq = 1;
    private long droppedEntryCount = 0;

    private DiagnosticMemoryRing(String runId) {
        this.runId = runId;
    }

    public static DiagnosticMemoryRing getInstance() {
        if (instance == null) {
            synchronized (DiagnosticMemoryRing.class) {
                if (instance == null) {
                    instance = new DiagnosticMemoryRing(newRunId());
                }
            }
        }
        return instance;
    }

    static void resetForTest() {
        instance = null;
    }

    public String runId() {
        return runId;
    }

    public void record(DiagnosticLevel level, String area, String event, Map<String, ?> fields) {
        String safeLevel = level != null ? level.name().toLowerCase(Locale.US) : "info";
        String safeArea = area != null && !area.isEmpty() ? area : "core";
        String safeEvent = event != null ? event : "";
        Map<String, Object> copy = copyFields(fields);
        synchronized (lock) {
            DiagnosticEntry entry = buildEntry(safeLevel, safeArea, safeEvent, copy, null);
            entries.add(entry);
            totalBytes += entry.encodedSize;
            trimLocked();
        }
    }

    public List<DiagnosticEntry> snapshot() {
        synchronized (lock) {
            return new ArrayList<>(entries);
        }
    }

    public int entryCount() {
        synchronized (lock) {
            return entries.size();
        }
    }

    public long totalBytes() {
        synchronized (lock) {
            return totalBytes;
        }
    }

    public long droppedEntryCount() {
        synchronized (lock) {
            return droppedEntryCount;
        }
    }

    /** Ring 截断与窗口元数据，供 android-state.json 的 eventRing 段。 */
    public RingStats ringStats() {
        synchronized (lock) {
            if (entries.isEmpty()) {
                return new RingStats(0, totalBytes, droppedEntryCount, 0L, 0L, "", "");
            }
            DiagnosticEntry oldest = entries.get(0);
            DiagnosticEntry newest = entries.get(entries.size() - 1);
            return new RingStats(
                entries.size(), totalBytes, droppedEntryCount,
                oldest.seq, newest.seq, oldest.time, newest.time);
        }
    }

    public static final class RingStats {
        public final int entryCount;
        public final long totalBytes;
        public final long droppedEntryCount;
        public final long oldestSeq;
        public final long newestSeq;
        public final String oldestAt;
        public final String newestAt;

        RingStats(int entryCount, long totalBytes, long droppedEntryCount,
                  long oldestSeq, long newestSeq, String oldestAt, String newestAt) {
            this.entryCount = entryCount;
            this.totalBytes = totalBytes;
            this.droppedEntryCount = droppedEntryCount;
            this.oldestSeq = oldestSeq;
            this.newestSeq = newestSeq;
            this.oldestAt = oldestAt != null ? oldestAt : "";
            this.newestAt = newestAt != null ? newestAt : "";
        }
    }

    /**
     * 仅允许在持有 {@link #lock} 时调用：分配 seq 并构建条目。
     */
    private DiagnosticEntry buildEntry(String level, String source, String event,
                                       Map<String, Object> fields, String message) {
        long seq = nextSeq++;
        String time = isoNow();
        DiagnosticEntry draft = new DiagnosticEntry(runId, seq, time, level, source, event, fields, message, 0);
        int encoded = estimateEncodedSize(draft);
        return new DiagnosticEntry(runId, seq, time, level, source, event, fields, message, encoded);
    }

    private void trimLocked() {
        while (entries.size() > MAX_ENTRIES) {
            dropOldestLocked();
        }
        while (entries.size() > 0 && totalBytes > MAX_BYTES) {
            dropOldestLocked();
        }
    }

    private void dropOldestLocked() {
        DiagnosticEntry oldest = entries.remove(0);
        totalBytes -= oldest.encodedSize;
        droppedEntryCount++;
    }

    private static Map<String, Object> copyFields(Map<String, ?> fields) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> entry : fields.entrySet()) {
            copy.put(entry.getKey(), entry.getValue());
        }
        return copy;
    }

    private static int estimateEncodedSize(DiagnosticEntry entry) {
        try {
            String line = entry.toJson().toString();
            return line.length() + 1;
        } catch (JSONException e) {
            return 64;
        }
    }

    static String newRunId() {
        long millis = System.currentTimeMillis();
        String suffix = DiagnosticIdHasher.randomSalt().substring(0, 8);
        return millis + "-" + suffix;
    }

    static String isoNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
