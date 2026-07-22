package com.webterm.terminal.model.capture;

/**
 * 现场捕获身份：把同一次问题在各阶段的数据关联起来。所有字段在捕获开始时固定。
 * 纯 Java，不依赖 Activity/View/Go control server/具体文件格式。
 */
public final class CaptureIdentity {
    public final String captureId;
    public final String sessionId;
    public final String clientInstanceId;
    public final String terminalInstanceId;
    public final long layoutEpoch;
    public final long androidModelRevision;
    public final long androidRenderedRevision;

    public CaptureIdentity(String captureId, String sessionId, String clientInstanceId,
                           String terminalInstanceId, long layoutEpoch,
                           long androidModelRevision, long androidRenderedRevision) {
        this.captureId = captureId == null ? "" : captureId;
        this.sessionId = sessionId == null ? "" : sessionId;
        this.clientInstanceId = clientInstanceId == null ? "" : clientInstanceId;
        this.terminalInstanceId = terminalInstanceId == null ? "" : terminalInstanceId;
        this.layoutEpoch = layoutEpoch;
        this.androidModelRevision = androidModelRevision;
        this.androidRenderedRevision = androidRenderedRevision;
    }

    /** 返回复制并刷新 revision/layoutEpoch 后的身份（保存时取当前值）。 */
    public CaptureIdentity withRevisions(long modelRevision, long renderedRevision, long epoch) {
        return new CaptureIdentity(captureId, sessionId, clientInstanceId, terminalInstanceId,
                epoch, modelRevision, renderedRevision);
    }

    /** 返回替换 captureId 后的身份副本。 */
    public CaptureIdentity withCaptureId(String newCaptureId) {
        return new CaptureIdentity(newCaptureId, sessionId, clientInstanceId, terminalInstanceId,
                layoutEpoch, androidModelRevision, androidRenderedRevision);
    }
}
