package com.webterm.terminal.renderer;

import android.graphics.Canvas;

import androidx.annotation.NonNull;

/**
 * 终端屏幕行缓存的绘制单元抽象。生产环境由 {@link RenderNodeTerminalRowNode} 实现，
 * 单元测试可注入 fake 以避免依赖真实 Android RenderNode 原生调用。
 */
interface TerminalRowNode {
  void setPosition(int left, int top, int right, int bottom);

  @NonNull
  Canvas beginRecording(int width, int height);

  void endRecording();

  void draw(@NonNull Canvas canvas, float y);
}
