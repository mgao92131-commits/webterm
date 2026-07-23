package com.webterm.terminal.model.capture;

/**
 * 捕获点 E：RemoteTerminalView 的只读诊断快照。由 View 在主线程通过 captureDiagnostics()
 * 产出；诊断代码不得直接修改 View 状态。全部为几何/身份只读字段。
 */
public final class CapturedViewState {
    public final long capturedAtMillis;
    public final int viewWidth;
    public final int viewHeight;
    public final int paddingLeft;
    public final int paddingTop;
    public final int paddingRight;
    public final int paddingBottom;
    public final float fontSizeSp;
    public final String typefaceDescription;
    public final float cellWidth;
    public final float lineHeight;
    public final float baseline;
    public final int scrollOffsetPixels;
    public final boolean followTail;
    public final String contentStreamIntent;
    public final int liveScreenExitOffsetPixels;
    public final boolean pureHistory;
    public final boolean keyboardVisible;
    public final long renderedScreenRevision;
    public final long renderedLayoutEpoch;
    public final String renderedInstanceId;
    public final boolean cursorBlinkOn;
    public final boolean hasSelection;

    public CapturedViewState(long capturedAtMillis, int viewWidth, int viewHeight,
                             int paddingLeft, int paddingTop, int paddingRight, int paddingBottom,
                             float fontSizeSp, String typefaceDescription, float cellWidth,
                             float lineHeight, float baseline, int scrollOffsetPixels,
                             boolean followTail, String contentStreamIntent,
                             int liveScreenExitOffsetPixels, boolean pureHistory,
                             boolean keyboardVisible,
                             long renderedScreenRevision, long renderedLayoutEpoch,
                             String renderedInstanceId, boolean cursorBlinkOn, boolean hasSelection) {
        this.capturedAtMillis = capturedAtMillis;
        this.viewWidth = viewWidth;
        this.viewHeight = viewHeight;
        this.paddingLeft = paddingLeft;
        this.paddingTop = paddingTop;
        this.paddingRight = paddingRight;
        this.paddingBottom = paddingBottom;
        this.fontSizeSp = fontSizeSp;
        this.typefaceDescription = typefaceDescription == null ? "" : typefaceDescription;
        this.cellWidth = cellWidth;
        this.lineHeight = lineHeight;
        this.baseline = baseline;
        this.scrollOffsetPixels = scrollOffsetPixels;
        this.followTail = followTail;
        this.contentStreamIntent = contentStreamIntent == null ? "" : contentStreamIntent;
        this.liveScreenExitOffsetPixels = liveScreenExitOffsetPixels;
        this.pureHistory = pureHistory;
        this.keyboardVisible = keyboardVisible;
        this.renderedScreenRevision = renderedScreenRevision;
        this.renderedLayoutEpoch = renderedLayoutEpoch;
        this.renderedInstanceId = renderedInstanceId == null ? "" : renderedInstanceId;
        this.cursorBlinkOn = cursorBlinkOn;
        this.hasSelection = hasSelection;
    }
}
