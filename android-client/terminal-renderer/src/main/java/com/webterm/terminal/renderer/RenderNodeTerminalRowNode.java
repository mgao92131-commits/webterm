package com.webterm.terminal.renderer;

import android.graphics.Canvas;
import android.graphics.RenderNode;

import androidx.annotation.NonNull;

/**
 * 基于 Android {@link RenderNode} 的 {@link TerminalRowNode} 生产实现。
 */
final class RenderNodeTerminalRowNode implements TerminalRowNode {
  private final RenderNode renderNode;

  RenderNodeTerminalRowNode(@NonNull String name) {
    this.renderNode = new RenderNode(name);
  }

  @Override
  public void setPosition(int left, int top, int right, int bottom) {
    renderNode.setPosition(left, top, right, bottom);
  }

  @NonNull
  @Override
  public Canvas beginRecording(int width, int height) {
    return renderNode.beginRecording(width, height);
  }

  @Override
  public void endRecording() {
    renderNode.endRecording();
  }

  @Override
  public boolean hasDisplayList() {
    return renderNode.hasDisplayList();
  }

  @Override
  public void draw(@NonNull Canvas canvas, float y) {
    canvas.save();
    canvas.translate(0f, y);
    canvas.drawRenderNode(renderNode);
    canvas.restore();
  }
}
