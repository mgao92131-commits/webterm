package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class TerminalTextPainterTest {
  @Test
  public void naturallyAlignedAsciiUsesOneContextualRun() {
    ControlledTextRunAdvancesPaint paint = new ControlledTextRunAdvancesPaint(
        new float[] {10f, 10f, 10f});
    TerminalCellGeometry geometry = geometry(10f, 20f, 15f);
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
    assertEquals(1, paint.textRunAdvanceCalls.size());
    assertEquals(0, paint.runAdvanceCalls.size());
  }

  @Test
  public void mismatchFallsBackToClustersButKeepsFullContext() {
    ControlledTextRunAdvancesPaint paint = new ControlledTextRunAdvancesPaint(
        new float[] {9f, 9f, 9f});
    TerminalCellGeometry geometry = geometry(10f, 20f, 15f);
    CompiledTerminalLine.TextSpan span = textSpan(
        0, "abc", new int[] {0, 1, 2}, new int[] {0, 1, 2},
        new byte[] {1, 1, 1}, new boolean[] {true, true, true});
    CountingCanvas canvas = new CountingCanvas();

    new TerminalTextPainter().draw(canvas, span, geometry, 0f, paint);

    assertEquals(3, canvas.drawTextRunCount);
    assertEquals(0, canvas.lastContextStart);
    assertEquals(3, canvas.lastContextEnd);
    assertTrue(canvas.sawClusterRange);
    assertEquals(1, paint.textRunAdvanceCalls.size());
    assertEquals(0, paint.runAdvanceCalls.size());
  }

  @Test
  public void wholeRunUsesAbsolutePrefixOffsetsInFullContext() {
    ControlledTextRunAdvancesPaint paint = new ControlledTextRunAdvancesPaint(
        new float[] {10f, 10f, 20f, 10f});
    TerminalCellGeometry geometry = geometry(10f, 20f, 15f);
    CompiledTerminalLine.TextSpan span = textSpan(
        0, "abcd", new int[] {0, 1, 2, 3}, new int[] {0, 1, 2, 4},
        new byte[] {1, 1, 2, 1}, new boolean[] {false, false, false, false});
    CountingCanvas canvas = new CountingCanvas();

    new TerminalTextPainter().draw(canvas, span, geometry, 0f, paint);

    assertEquals(1, canvas.drawTextRunCount);
    assertEquals(1, paint.textRunAdvanceCalls.size());
    assertEquals(0, paint.runAdvanceCalls.size());
    TextRunAdvancesCall call = paint.textRunAdvanceCalls.get(0);
    assertEquals(0, call.start());
    assertEquals(4, call.end());
    assertEquals(0, call.contextStart());
    assertEquals(4, call.contextEnd());
    assertEquals(0, call.advancesIndex());
  }

  @Test
  public void clusterFallbackUsesOneBatchAdvanceAndFullContext() {
    ControlledTextRunAdvancesPaint paint = new ControlledTextRunAdvancesPaint(
        new float[] {9f, 11f, 20f, 10f});
    TerminalCellGeometry geometry = geometry(10f, 20f, 15f);
    CompiledTerminalLine.TextSpan span = textSpan(
        0, "abcd", new int[] {0, 1, 2, 3}, new int[] {0, 1, 2, 4},
        new byte[] {1, 1, 2, 1}, new boolean[] {false, false, false, false});
    CountingCanvas canvas = new CountingCanvas();

    new TerminalTextPainter().draw(canvas, span, geometry, 0f, paint);

    assertEquals(4, canvas.drawTextRunCount);
    assertEquals(1, paint.textRunAdvanceCalls.size());
    assertEquals(0, paint.runAdvanceCalls.size());
    for (int i = 0; i < 4; i++) {
      assertEquals(0, canvas.contextRanges.get(i).contextStart());
      assertEquals(4, canvas.contextRanges.get(i).contextEnd());
      assertEquals(i, canvas.contextRanges.get(i).start());
      assertEquals(i + 1, canvas.contextRanges.get(i).end());
    }
  }

  @Test
  public void zeroBatchAdvanceUsesControlledLegacyFallback() {
    ControlledAdvancePaint paint = new ControlledAdvancePaint(
        new float[] {10f, 20f, 30f}, new float[] {10f, 10f, 10f});
    TerminalCellGeometry geometry = geometry(10f, 20f, 15f);
    CompiledTerminalLine.TextSpan span = textSpan(
        0, "abc", new int[] {0, 1, 2}, new int[] {0, 1, 2},
        new byte[] {1, 1, 1}, new boolean[] {false, false, false});
    CountingCanvas canvas = new CountingCanvas();

    new TerminalTextPainter().draw(canvas, span, geometry, 0f, paint);

    assertEquals(1, paint.textRunAdvanceCalls.size());
    assertEquals(3, paint.runAdvanceCalls.size());
    assertEquals(1, canvas.drawTextRunCount);
  }

  @Test
  public void combiningClusterUsesSummedUtf16Advances() {
    ControlledTextRunAdvancesPaint paint = new ControlledTextRunAdvancesPaint(
        new float[] {10f, 0f});
    TerminalCellGeometry geometry = geometry(10f, 20f, 15f);
    CompiledTerminalLine.TextSpan span = textSpan(
        0, "e\u0301", new int[] {0}, new int[] {0}, new byte[] {1},
        new boolean[] {false});
    CountingCanvas canvas = new CountingCanvas();

    new TerminalTextPainter().draw(canvas, span, geometry, 0f, paint);

    assertEquals(1, canvas.drawTextRunCount);
    assertEquals(1, paint.textRunAdvanceCalls.size());
    assertEquals(0, paint.runAdvanceCalls.size());
  }

  @Test
  public void scratchAdvanceArraysAreReusedForLaterSpans() {
    ControlledTextRunAdvancesPaint paint = new ControlledTextRunAdvancesPaint(
        new float[] {10f, 10f, 10f});
    TerminalCellGeometry geometry = geometry(10f, 20f, 15f);
    TerminalTextPainter painter = new TerminalTextPainter();
    CountingCanvas canvas = new CountingCanvas();

    painter.draw(canvas, textSpan(0, "abc", new int[] {0, 1, 2},
        new int[] {0, 1, 2}, new byte[] {1, 1, 1},
        new boolean[] {false, false, false}), geometry, 0f, paint);
    painter.draw(canvas, textSpan(0, "a", new int[] {0}, new int[] {0},
        new byte[] {1}, new boolean[] {false}), geometry, 0f, paint);

    assertEquals(2, paint.textRunAdvanceCalls.size());
  }

  @Test
  public void styleAndFontStateAreAppliedForEachSpan() {
    ControlledAdvancePaint paint = new ControlledAdvancePaint(
        new float[] {10f}, new float[] {10f});
    TerminalCellGeometry geometry = geometry(10f, 20f, 15f);
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
    final List<TextRunRange> contextRanges = new ArrayList<>();

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
      contextRanges.add(new TextRunRange(start, end, contextStart, contextEnd));
      if (end - start < contextEnd - contextStart) sawClusterRange = true;
    }
  }

  private record RunAdvanceCall(
      int start, int end, int contextStart, int contextEnd, int offset) {}

  private record TextRunRange(int start, int end, int contextStart, int contextEnd) {}

  private record TextRunAdvancesCall(
      int start, int end, int contextStart, int contextEnd, int advancesIndex) {}

  private static class RecordingPaint extends Paint {
    boolean recordedFakeBold;
    float recordedSkewX;
    int recordedColor;
    final List<RunAdvanceCall> runAdvanceCalls = new ArrayList<>();
    final List<TextRunAdvancesCall> textRunAdvanceCalls = new ArrayList<>();

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

    @Override
    public float getRunAdvance(CharSequence text, int start, int end,
                               int contextStart, int contextEnd, boolean isRtl, int offset) {
      runAdvanceCalls.add(new RunAdvanceCall(start, end, contextStart, contextEnd, offset));
      return super.getRunAdvance(text, start, end, contextStart, contextEnd, isRtl, offset);
    }
  }

  private static final class ControlledTextRunAdvancesPaint extends RecordingPaint {
    private final float[] advances;

    ControlledTextRunAdvancesPaint(float[] advances) {
      this.advances = advances;
    }

    @Override
    public float getTextRunAdvances(char[] text, int start, int count,
                                    int contextStart, int contextEnd, boolean isRtl,
                                    float[] output, int advancesIndex) {
      textRunAdvanceCalls.add(new TextRunAdvancesCall(
          start, start + count, contextStart, contextEnd, advancesIndex));
      for (int i = 0; i < count; i++) {
        output[advancesIndex + i] = advances[i];
      }
      float total = 0f;
      for (float advance : advances) total += advance;
      return total;
    }
  }

  private static final class ControlledAdvancePaint extends RecordingPaint {
    private final float[] prefixAdvances;
    private final float[] clusterAdvances;

    ControlledAdvancePaint(float[] prefixAdvances, float[] clusterAdvances) {
      this.prefixAdvances = prefixAdvances;
      this.clusterAdvances = clusterAdvances;
    }

    @Override
    public float getTextRunAdvances(char[] text, int start, int count,
                                    int contextStart, int contextEnd, boolean isRtl,
                                    float[] output, int advancesIndex) {
      textRunAdvanceCalls.add(new TextRunAdvancesCall(
          start, start + count, contextStart, contextEnd, advancesIndex));
      for (int i = 0; i < count; i++) output[advancesIndex + i] = 0f;
      return 0f;
    }

    @Override
    public float getRunAdvance(CharSequence text, int start, int end,
                               int contextStart, int contextEnd, boolean isRtl, int offset) {
      runAdvanceCalls.add(new RunAdvanceCall(start, end, contextStart, contextEnd, offset));
      if (start == 0 && end == text.length()) {
        return prefixAdvances[offset - 1];
      }
      return clusterAdvances[start];
    }
  }
}
