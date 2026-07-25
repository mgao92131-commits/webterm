package com.webterm.terminal.renderer;

/**
 * RemoteTerminalView 一次 RenderUpdate 的刷新决策。
 *
 * <p>把 "无法局部刷新" 和 "完全不需要刷新" 两种含义拆开，避免 history-only
 * 更新在 followTail 状态下触发无意义整屏重绘。</p>
 */
enum InvalidationResult {
  /** 本次没有可见变化，不调用 invalidate。 */
  NONE,
  /** 已提交一个或多个局部矩形刷新。 */
  PARTIAL,
  /** 刷新整个实时终端屏幕区域，但不刷新整个 View 的无关区域。 */
  SCREEN_REGION,
  /** 必须刷新整个 View。 */
  FULL
}
