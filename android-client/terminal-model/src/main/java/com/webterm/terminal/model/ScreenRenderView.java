package com.webterm.terminal.model;

import java.util.Arrays;

/** rowIndex → RenderLine 的不可变屏幕渲染视图。 */
public final class ScreenRenderView {
  private static final ScreenRenderView EMPTY = new ScreenRenderView(new RenderLine[0]);
  private final RenderLine[] rows;

  public ScreenRenderView(RenderLine[] rows) {
    if (rows == null) throw new IllegalArgumentException("screen render rows missing");
    for (RenderLine row : rows) {
      if (row == null) throw new IllegalArgumentException("screen render row missing");
    }
    this.rows = Arrays.copyOf(rows, rows.length);
  }

  public static ScreenRenderView empty() { return EMPTY; }
  public int size() { return rows.length; }
  public RenderLine lineAt(int row) { return rows[row]; }
  public RenderLine[] copyLines() { return Arrays.copyOf(rows, rows.length); }
}
