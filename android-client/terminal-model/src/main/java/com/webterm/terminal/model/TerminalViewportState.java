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

  /**
   * Moves the viewport in bottom-relative pixels while retaining the same
   * bounded-coordinate semantics as the legacy TerminalView's mTopRow.
   *
   * <p>Keeping the upper bound in state is important: the renderer also
   * clamps defensively, but an unbounded stored offset creates invisible
   * overscroll that must be consumed before a reverse gesture moves pixels.</p>
   */
  public void scrollBy(int deltaPixels, int maxScrollOffsetPixels) {
    int maxOffset = Math.max(0, maxScrollOffsetPixels);
    long requestedOffset = (long) scrollOffsetPixels + deltaPixels;
    scrollOffsetPixels = (int) Math.max(0L, Math.min((long) maxOffset, requestedOffset));
    // Match Termux mTopRow == 0: reaching the bottom immediately resumes tail-following.
    followTail = scrollOffsetPixels == 0;
  }

  public void resetForSnapshot() {
    followTail = true;
    anchorHistoryLineId = null;
    anchorPixelOffset = 0;
    scrollOffsetPixels = 0;
    selection = null;
    loadingOlderHistory = false;
  }
}
