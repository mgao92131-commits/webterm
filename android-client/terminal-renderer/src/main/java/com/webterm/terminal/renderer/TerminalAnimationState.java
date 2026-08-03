package com.webterm.terminal.renderer;

/**
 * 终端渲染层当前帧的动态可见性。
 *
 * <p>静态 RenderNode 录制使用 blink=false 的状态；光标和 blink 前景在节点回放后
 * 使用真实状态作为覆盖层绘制。这样动画不会修改行节点的 display list。</p>
 */
record TerminalAnimationState(
    boolean cursorOn,
    boolean slowBlinkOn,
    boolean fastBlinkOn
) {
  static TerminalAnimationState cursorOnly(boolean cursorOn) {
    // 兼容旧 render() 重载时，文字 blink 仍保持可见；动态层由 View 入口传入真实相位。
    return new TerminalAnimationState(cursorOn, true, true);
  }

  static TerminalAnimationState staticContent() {
    return new TerminalAnimationState(true, false, false);
  }

  boolean foregroundVisible(CompiledTerminalLine.CompiledStyle style) {
    if (style == null || style.hidden()) return false;
    if (style.blinkFast()) return fastBlinkOn;
    if (style.blinkSlow()) return slowBlinkOn;
    return true;
  }

  boolean blinkForegroundVisible(CompiledTerminalLine.CompiledStyle style) {
    return style != null
        && style.hasBlink()
        && foregroundVisible(style);
  }
}
