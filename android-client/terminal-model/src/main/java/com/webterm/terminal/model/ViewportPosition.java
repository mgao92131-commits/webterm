package com.webterm.terminal.model;

/** Viewport 唯一持久位置；像素滚动量必须由它与 RenderSnapshot 派生。 */
public abstract class ViewportPosition {
  private ViewportPosition() {}

  public static final class FollowTail extends ViewportPosition {
    private static final FollowTail INSTANCE = new FollowTail();
    private FollowTail() {}
  }

  public static final class LineAnchor extends ViewportPosition {
    public final long lineId;
    /** 该行顶部相对于 viewport 顶部的像素偏移。 */
    public final int pixelOffset;

    public LineAnchor(long lineId, int pixelOffset) {
      if (lineId <= 0) throw new IllegalArgumentException("lineId must be positive");
      this.lineId = lineId;
      this.pixelOffset = pixelOffset;
    }
  }

  public static ViewportPosition followTail() {
    return FollowTail.INSTANCE;
  }

  public static LineAnchor lineAnchor(long lineId, int pixelOffset) {
    return new LineAnchor(lineId, pixelOffset);
  }

  public final boolean isFollowTail() {
    return this instanceof FollowTail;
  }
}
