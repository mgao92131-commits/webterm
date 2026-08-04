package com.webterm.terminal.renderer;

/**
 * 一段 TextSpan 在当前字体和 cell 几何下的可复用布局结果。
 *
 * <p>布局只依赖 span 内容、字体和几何，不依赖行的屏幕 Y 坐标或动态前景色，因此可以
 * 在 RenderNode 被淘汰后继续服务于新的录制、Canvas fallback 和动态覆盖层。</p>
 */
final class PreparedTextLayout {
  final boolean drawWholeRun;
  final float[] clusterDrawX;
  final float[] clusterScale;
  final long estimatedBytes;

  PreparedTextLayout(boolean drawWholeRun, float[] clusterDrawX, float[] clusterScale) {
    this.drawWholeRun = drawWholeRun;
    this.clusterDrawX = clusterDrawX;
    this.clusterScale = clusterScale;
    this.estimatedBytes = 48L
        + (clusterDrawX == null ? 0L : clusterDrawX.length * Float.BYTES)
        + (clusterScale == null ? 0L : clusterScale.length * Float.BYTES);
  }

  boolean hasClusterLayout() {
    return clusterDrawX != null && clusterScale != null;
  }
}
