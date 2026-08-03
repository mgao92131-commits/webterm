package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public final class TerminalTextPainterTest {
  @Test
  public void naturallyAlignedAsciiUsesOneContextualRun() {
    Paint paint = paint();
    TerminalCellGeometry geometry = geometry(paint.measureText("X"), 20f, 15f);
    CompiledTerminalLine.TextSpan span = textSpan(
        0, "abc", new int[] {0, 1, 2}, new int[] {0, 1, 2},
        new byte[] {1, 1, 1}, new boolean[] {false, false, false});
    CountingCanvas canvas = new CountingCanvas();

    new TerminalTextPainter().draw(canvas, span, geometry, 0f, paint);

    assertEquals(1, canvas.drawTextRunCount);
    assertEquals(0, canvas.lastStart);
    assertEquals(3, canvas.lastEnd);
    assertEquals(0, canvas.lastContextStart);
    assertEquals(3, canvas.lastContextEnd);
  }

  @Test
  public void mismatchFallsBackToClustersButKeepsFullContext() {
    Paint paint = paint();
    TerminalCellGeometry geometry = geometry(paint.measureText("X") + 2f, 20f, 15f);
    CompiledTerminalLine.TextSpan span = textSpan(
        0, "abc", new int[] {0, 1, 2}, new int[] {0, 1, 2},
        new byte[] {1, 1, 1}, new boolean[] {true, true, true});
    CountingCanvas canvas = new CountingCanvas();

    new TerminalTextPainter().draw(canvas, span, geometry, 0f, paint);

    assertEquals(3, canvas.drawTextRunCount);
    assertEquals(0, canvas.lastContextStart);
    assertEquals(3, canvas.lastContextEnd);
    assertTrue(canvas.sawClusterRange);
  }

  @Test
  public void combiningClusterIsNeverSplit() {
    Paint paint = paint();
    TerminalCellGeometry geometry = geometry(paint.measureText("X") + 2f, 20f, 15f);
    CompiledTerminalLine.TextSpan span = textSpan(
        0, "e\u0301", new int[] {0}, new int[] {0}, new byte[] {1},
        new boolean[] {false});
    CountingCanvas canvas = new CountingCanvas();

    new TerminalTextPainter().draw(canvas, span, geometry, 0f, paint);

    assertEquals(1, canvas.drawTextRunCount);
    assertEquals(0, canvas.lastStart);
    assertEquals(2, canvas.lastEnd);
    assertEquals(0, canvas.lastContextStart);
    assertEquals(2, canvas.lastContextEnd);
  }

  @Test
  public void styleAndFontStateAreAppliedForEachSpan() {
    Paint paint = paint();
    TerminalCellGeometry geometry = geometry(paint.measureText("X"), 20f, 15f);
    CompiledTerminalLine.CompiledStyle boldItalic = style(0xFFFF0000, true, true, false);
    CompiledTerminalLine.CompiledStyle plain = style(0xFF00FF00, false, false, false);
    CountingCanvas canvas = new CountingCanvas();
    TerminalTextPainter painter = new TerminalTextPainter();

    painter.draw(canvas, textSpan(0, "A", boldItalic, new int[] {0}, new int[] {0}),
        geometry, 0f, paint);
    assertTrue(((RecordingPaint) paint).recordedFakeBold);
    assertEquals(-0.35f, ((RecordingPaint) paint).recordedSkewX, 0.001f);
    assertEquals(0xFFFF0000, ((RecordingPaint) paint).recordedColor);

    painter.draw(canvas, textSpan(1, "B", plain, new int[] {0}, new int[] {1}),
        geometry, 0f, paint);
    assertFalse(((RecordingPaint) paint).recordedFakeBold);
    assertEquals(0f, ((RecordingPaint) paint).recordedSkewX, 0.001f);
    assertEquals(0xFF00FF00, ((RecordingPaint) paint).recordedColor);
  }

  @Test
  public void hiddenSpanDoesNotIssueTextRun() {
    Paint paint = paint();
    TerminalCellGeometry geometry = geometry(paint.measureText("X"), 20f, 15f);
    CompiledTerminalLine.CompiledStyle hidden = style(0xFFFFFFFF, false, false, true);
    CountingCanvas canvas = new CountingCanvas();

    new TerminalTextPainter().draw(canvas,
        textSpan(0, "secret", hidden, new int[] {0}, new int[] {0}),
        geometry, 0f, paint);

    assertEquals(0, canvas.drawTextRunCount);
  }

  private static Paint paint() {
    Paint paint = new RecordingPaint();
    paint.setAntiAlias(true);
    paint.setTextSize(14f);
    paint.setTypeface(Typeface.MONOSPACE);
    return paint;
  }

  private static TerminalCellGeometry geometry(float cellWidth, float lineHeight,
                                               float baseline) {
    TerminalCellGeometry geometry = new TerminalCellGeometry();
    geometry.update(cellWidth, lineHeight, baseline);
    return geometry;
  }

  private static CompiledTerminalLine.TextSpan textSpan(
      int startColumn,
      String text,
      int[] offsets,
      int[] columns,
      byte[] widths,
      boolean[] preserveAspect) {
    return textSpan(startColumn, text, style(0xFFFFFFFF, false, false, false),
        offsets, columns, widths, preserveAspect);
  }

  private static CompiledTerminalLine.TextSpan textSpan(
      int startColumn,
      String text,
      CompiledTerminalLine.CompiledStyle style,
      int[] offsets,
      int[] columns) {
    byte[] widths = new byte[offsets.length];
    boolean[] preserve = new boolean[offsets.length];
    java.util.Arrays.fill(widths, (byte) 1);
    return textSpan(startColumn, text, style, offsets, columns, widths, preserve);
  }

  private static CompiledTerminalLine.TextSpan textSpan(
      int startColumn,
      String text,
      CompiledTerminalLine.CompiledStyle style,
      int[] offsets,
      int[] columns,
      byte[] widths,
      boolean[] preserveAspect) {
    int columnCount = columns[columns.length - 1] + widths[widths.length - 1] - startColumn;
    return new CompiledTerminalLine.TextSpan(
        startColumn, columnCount, style, text, offsets, columns, widths, preserveAspect);
  }

  private static CompiledTerminalLine.CompiledStyle style(
      int color, boolean bold, boolean italic, boolean hidden) {
    return new CompiledTerminalLine.CompiledStyle(
        color, 0xFF000000, color, bold, false, italic, hidden, false, false, false,
        ResolvedTerminalStyle.UnderlineKind.NONE);
  }

  private static final class CountingCanvas extends Canvas {
    int drawTextRunCount;
    int lastStart;
    int lastEnd;
    int lastContextStart;
    int lastContextEnd;
    int lastColor;
    boolean lastFakeBold;
    float lastSkewX;
    boolean sawClusterRange;

    CountingCanvas() {
      super(Bitmap.createBitmap(240, 40, Bitmap.Config.ARGB_8888));
    }

    @Override
    public void drawTextRun(CharSequence text, int start, int end,
                            int contextStart, int contextEnd, float x, float y,
                            boolean isRtl, Paint paint) {
      drawTextRunCount++;
      lastStart = start;
      lastEnd = end;
      lastContextStart = contextStart;
      lastContextEnd = contextEnd;
      lastColor = paint.getColor();
      lastFakeBold = paint.isFakeBoldText();
      lastSkewX = paint.getTextSkewX();
      if (end - start < contextEnd - contextStart) sawClusterRange = true;
    }
  }

  private static final class RecordingPaint extends Paint {
    boolean recordedFakeBold;
    float recordedSkewX;
    int recordedColor;

    @Override
    public void setFakeBoldText(boolean fakeBoldText) {
      recordedFakeBold = fakeBoldText;
      super.setFakeBoldText(fakeBoldText);
    }

    @Override
    public void setTextSkewX(float skewX) {
      recordedSkewX = skewX;
      super.setTextSkewX(skewX);
    }

    @Override
    public void setColor(int color) {
      recordedColor = color;
      super.setColor(color);
    }
  }
}
