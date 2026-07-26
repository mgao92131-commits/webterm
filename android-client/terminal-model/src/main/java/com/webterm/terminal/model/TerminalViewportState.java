package com.webterm.terminal.model;

/**
 * Viewport 状态。与远端模型分离，属于 UI 状态。
 */
public final class TerminalViewportState {
  public TerminalSelection selection;
  public boolean loadingOlderHistory;
  private ViewportPosition mainViewportPosition = ViewportPosition.followTail();
  private ViewportPosition alternateViewportPosition = ViewportPosition.followTail();

  public ViewportPosition position(TerminalBufferKind buffer) {
    return buffer == TerminalBufferKind.ALTERNATE
        ? alternateViewportPosition : mainViewportPosition;
  }

  public void setPosition(TerminalBufferKind buffer, ViewportPosition position) {
    if (position == null) throw new IllegalArgumentException("viewport position missing");
    if (buffer == TerminalBufferKind.ALTERNATE) alternateViewportPosition = position;
    else mainViewportPosition = position;
  }

  public boolean isFollowTail(TerminalBufferKind buffer) {
    return position(buffer).isFollowTail();
  }

  public void followTail(TerminalBufferKind buffer) {
    setPosition(buffer, ViewportPosition.followTail());
  }

  public void anchorLine(TerminalBufferKind buffer, long lineId, int pixelOffset) {
    setPosition(buffer, ViewportPosition.lineAnchor(lineId, pixelOffset));
  }

  /**
   * 由同一个 immutable RenderSnapshot 解析 LineAnchor，得到 bottom-relative offset。
   * Anchor 不存在时返回 0，由调用方按失效提示回落到 FollowTail。
   */
  public int derivedScrollOffsetPixels(
      RemoteTerminalModel.RenderSnapshot snapshot, float lineHeight, int maxOffset) {
    if (snapshot == null || lineHeight <= 0f) return 0;
    ViewportPosition position = position(snapshot.activeBuffer);
    if (position.isFollowTail()) return 0;
    ViewportPosition.LineAnchor anchor = (ViewportPosition.LineAnchor) position;
    Long row = snapshot.contentAxis.rowOfLineId(anchor.lineId);
    if (row == null) return 0;
    long raw = Math.round(anchor.pixelOffset
        + (snapshot.contentAxis.historyRowCount() - row) * lineHeight);
    return (int) Math.max(0L, Math.min((long) Math.max(0, maxOffset), raw));
  }

  /**
   * 在统一内容轴中移动并立即重新锚定可定位 LineID。缺失范围没有伪造行对象，
   * 此时选择视口内下一个已加载/活动行作为稳定锚点。
   */
  public void scrollBy(
      int deltaPixels, int maxOffset,
      RemoteTerminalModel.RenderSnapshot snapshot, float lineHeight) {
    if (snapshot == null || lineHeight <= 0f) return;
    int current = derivedScrollOffsetPixels(snapshot, lineHeight, maxOffset);
    long requested = (long) current + deltaPixels;
    int next = (int) Math.max(0L, Math.min((long) Math.max(0, maxOffset), requested));
    if (next == 0) {
      followTail(snapshot.activeBuffer);
      return;
    }
    long topRow = (long) Math.floor(
        snapshot.contentAxis.historyRowCount() - next / (double) lineHeight);
    topRow = Math.max(0, Math.min(snapshot.contentAxis.rowCount() - 1, topRow));
    UnifiedContentAxis.Item anchorItem = null;
    for (UnifiedContentAxis.Item item : snapshot.contentAxis.items()) {
      if (item.line != null && item.startRow >= topRow) {
        anchorItem = item;
        break;
      }
    }
    if (anchorItem == null) {
      for (int i = snapshot.contentAxis.items().size() - 1; i >= 0; i--) {
        UnifiedContentAxis.Item item = snapshot.contentAxis.items().get(i);
        if (item.line != null) {
          anchorItem = item;
          break;
        }
      }
    }
    if (anchorItem == null) {
      followTail(snapshot.activeBuffer);
      return;
    }
    int pixelOffset = Math.round(
        next + (anchorItem.startRow - snapshot.contentAxis.historyRowCount()) * lineHeight);
    anchorLine(snapshot.activeBuffer, anchorItem.lineId, pixelOffset);
  }
}
