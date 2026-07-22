package com.webterm.terminal.model.capture;

/** 当前捕获状态快照（供 UI 显示“正在记录现场 00:12”）。 */
public final class CaptureStatus {
    public final boolean recording;
    public final String captureId;
    public final long startedAtMillis;

    public CaptureStatus(boolean recording, String captureId, long startedAtMillis) {
        this.recording = recording;
        this.captureId = captureId == null ? "" : captureId;
        this.startedAtMillis = startedAtMillis;
    }

    public static CaptureStatus idle() {
        return new CaptureStatus(false, "", 0L);
    }

    public long elapsedMillis(long nowMillis) {
        if (!recording || startedAtMillis <= 0) {
            return 0L;
        }
        return Math.max(0L, nowMillis - startedAtMillis);
    }
}
