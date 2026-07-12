package com.webterm.terminal.model;

/**
 * Viewport 状态。与远端模型分离，属于 UI 状态。
 */
public final class TerminalViewportState {
  public boolean followTail = true;
  public Long anchorHistoryLineId;
  public int anchorPixelOffset;
  public int scrollOffsetPixels; // 从屏幕底部向上滚动的像素
  public TerminalSelection selection;
  public boolean loadingOlderHistory;

  public void resetForSnapshot() {
    followTail = true;
    anchorHistoryLineId = null;
    anchorPixelOffset = 0;
    selection = null;
    loadingOlderHistory = false;
  }
}

