package com.webterm.terminal.model.capture;

import com.webterm.terminal.model.HistoryExtent;

/**
 * 捕获点 C：model.applyBaseline/applyPatch 成功后的模型摘要。仅保存身份与投影健康度等
 * 轻量字段（在 modelExecutor 上做有界字段读取，不做 JSON/IO）；完整画面状态经 RenderSnapshot
 * 在捕获点 D 记录。
 */
public final class CapturedModelState {
    public final long capturedAtMillis;
    public final String instanceId;
    public final long layoutEpoch;
    public final long screenRevision;
    public final int rows;
    public final int columns;
    public final int activeBuffer; // 0=main, 1=alternate
    public final boolean projectionComplete;
    public final boolean afterBaseline;
    public final HistoryExtent displayHistoryExtent;
    public final HistoryExtent remoteHistoryExtent;
    /** 是否属于重要事件（如 Baseline），不受 ModelState 限频影响。 */
    public final boolean force;

    public CapturedModelState(long capturedAtMillis, String instanceId, long layoutEpoch,
                              long screenRevision,
                              int rows, int columns, int activeBuffer,
                              boolean projectionComplete, boolean afterBaseline,
                              HistoryExtent displayHistoryExtent,
                              HistoryExtent remoteHistoryExtent) {
        this(capturedAtMillis, instanceId, layoutEpoch, screenRevision,
            rows, columns, activeBuffer, projectionComplete, afterBaseline,
            displayHistoryExtent, remoteHistoryExtent, afterBaseline);
    }

    public CapturedModelState(long capturedAtMillis, String instanceId, long layoutEpoch,
                              long screenRevision,
                              int rows, int columns, int activeBuffer,
                              boolean projectionComplete, boolean afterBaseline,
                              HistoryExtent displayHistoryExtent,
                              HistoryExtent remoteHistoryExtent,
                              boolean force) {
        this.capturedAtMillis = capturedAtMillis;
        this.instanceId = instanceId == null ? "" : instanceId;
        this.layoutEpoch = layoutEpoch;
        this.screenRevision = screenRevision;
        this.rows = rows;
        this.columns = columns;
        this.activeBuffer = activeBuffer;
        this.projectionComplete = projectionComplete;
        this.afterBaseline = afterBaseline;
        this.displayHistoryExtent = displayHistoryExtent == null
                ? HistoryExtent.INITIAL_EMPTY : displayHistoryExtent;
        this.remoteHistoryExtent = remoteHistoryExtent == null
                ? HistoryExtent.INITIAL_EMPTY : remoteHistoryExtent;
        this.force = force;
    }
}
