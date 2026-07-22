package com.webterm.terminal.model.capture;

/** 一次现场保存的结果。filePath 为本地 ZIP 绝对路径（经 FileProvider 分享）；失败时 error 为稳定说明。 */
public final class CaptureResult {
    public final boolean success;
    public final String filePath;
    public final String captureId;
    public final String error;

    private CaptureResult(boolean success, String filePath, String captureId, String error) {
        this.success = success;
        this.filePath = filePath;
        this.captureId = captureId == null ? "" : captureId;
        this.error = error;
    }

    public static CaptureResult ok(String filePath, String captureId) {
        return new CaptureResult(true, filePath, captureId, null);
    }

    public static CaptureResult failure(String captureId, String error) {
        return new CaptureResult(false, null, captureId, error);
    }
}
