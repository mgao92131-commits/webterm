package com.webterm.terminal.renderer;

/** 终端 View 的只读渲染状态，用于渲染回归测试与运行指标校验。 */
public final class RenderDiagnostics {
  public final long observedAtMillis;
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
  public final String viewportPosition;
  public final int liveScreenExitOffsetPixels;
  public final boolean pureHistory;
  public final boolean keyboardVisible;
  public final long renderedScreenRevision;
  public final long renderedLayoutEpoch;
  public final String renderedInstanceId;
  public final boolean cursorBlinkOn;
  public final boolean hasSelection;

  public RenderDiagnostics(long observedAtMillis, int viewWidth, int viewHeight,
                           int paddingLeft, int paddingTop, int paddingRight, int paddingBottom,
                           float fontSizeSp, String typefaceDescription, float cellWidth,
                           float lineHeight, float baseline, int scrollOffsetPixels,
                           boolean followTail, String viewportPosition,
                           int liveScreenExitOffsetPixels, boolean pureHistory,
                           boolean keyboardVisible,
                           long renderedScreenRevision, long renderedLayoutEpoch,
                           String renderedInstanceId, boolean cursorBlinkOn,
                           boolean hasSelection) {
    this.observedAtMillis = observedAtMillis;
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
    this.viewportPosition = viewportPosition == null ? "" : viewportPosition;
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
