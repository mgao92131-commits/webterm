package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class TerminalDecorationPainterTest {
  private static final int BACKGROUND = 0xFF000000;
  private static final int UNDERLINE = 0xFF00FF00;
  private static final int STRIKE = 0xFFFF0000;

  @Test
  public void everyUnderlineKindProducesInkInsideTheSpan() {
    TerminalDecorationPainter painter = new TerminalDecorationPainter();
    for (ResolvedTerminalStyle.UnderlineKind kind : new ResolvedTerminalStyle.UnderlineKind[] {
        ResolvedTerminalStyle.UnderlineKind.SINGLE,
        ResolvedTerminalStyle.UnderlineKind.DOUBLE,
        ResolvedTerminalStyle.UnderlineKind.CURLY,
        ResolvedTerminalStyle.UnderlineKind.DOTTED,
        ResolvedTerminalStyle.UnderlineKind.DASHED
    }) {
      Bitmap bitmap = bitmap();
      ResolvedTerminalStyle style = style(kind, false, UNDERLINE, STRIKE);
      painter.draw(new Canvas(bitmap), style, 4, 36, 0, 20);

      assertTrue(kind + " must draw pixels", countInk(bitmap, 4, 36, 0, 20) > 0);
      assertEquals(kind + " must not draw outside the span", 0,
          countInk(bitmap, 0, 4, 0, 20) + countInk(bitmap, 36, 40, 0, 20));
      bitmap.recycle();
    }
  }

  @Test
  public void underlineAndStrikeKeepIndependentColors() {
    Bitmap bitmap = bitmap();
    ResolvedTerminalStyle style = style(
        ResolvedTerminalStyle.UnderlineKind.SINGLE, false, UNDERLINE, STRIKE);
    new TerminalDecorationPainter().draw(new Canvas(bitmap), style, 4, 36, 0, 20);

    assertTrue("underline must use its own color",
        containsColor(bitmap, UNDERLINE, 4, 36, 16, 20));
    assertTrue("strike must restore the foreground color",
        containsColor(bitmap, STRIKE, 4, 36, 8, 13));
    bitmap.recycle();
  }

  @Test
  public void hiddenStyleProducesNoDecoration() {
    Bitmap bitmap = bitmap();
    ResolvedTerminalStyle style = style(
        ResolvedTerminalStyle.UnderlineKind.DOUBLE, true, UNDERLINE, STRIKE);
    new TerminalDecorationPainter().draw(new Canvas(bitmap), style, 4, 36, 0, 20);
    assertEquals(0, countInk(bitmap, 0, bitmap.getWidth(), 0, bitmap.getHeight()));
    bitmap.recycle();
  }

  @Test
  public void plainStyleSkipsDecorationCanvasOperations() {
    CountingCanvas canvas = new CountingCanvas();
    ResolvedTerminalStyle style = new ResolvedTerminalStyle();

    new TerminalDecorationPainter().draw(canvas, style, 0, 20, 0, 20);

    assertEquals(0, canvas.saveOps);
    assertEquals(0, canvas.clipOps);
    assertEquals(0, canvas.restoreOps);
  }

  @Test
  public void underlineShapesHaveDifferentRasterSignatures() {
    int[] signatures = new int[5];
    ResolvedTerminalStyle.UnderlineKind[] kinds = {
        ResolvedTerminalStyle.UnderlineKind.SINGLE,
        ResolvedTerminalStyle.UnderlineKind.DOUBLE,
        ResolvedTerminalStyle.UnderlineKind.CURLY,
        ResolvedTerminalStyle.UnderlineKind.DOTTED,
        ResolvedTerminalStyle.UnderlineKind.DASHED
    };
    for (int i = 0; i < kinds.length; i++) {
      Bitmap bitmap = bitmap();
      ResolvedTerminalStyle style = style(kinds[i], false, UNDERLINE, STRIKE);
      new TerminalDecorationPainter().draw(new Canvas(bitmap), style, 4, 36, 0, 20);
      signatures[i] = signature(bitmap, 4, 36, 0, 20);
      bitmap.recycle();
    }
    for (int i = 0; i < signatures.length; i++) {
      for (int j = i + 1; j < signatures.length; j++) {
        assertNotEquals("underline kinds must remain distinguishable", signatures[i], signatures[j]);
      }
    }
  }

  @Test
  public void decorationPatternIsIndependentOfSpanSplits() {
    ResolvedTerminalStyle.UnderlineKind[] kinds = {
        ResolvedTerminalStyle.UnderlineKind.SINGLE,
        ResolvedTerminalStyle.UnderlineKind.DOUBLE,
        ResolvedTerminalStyle.UnderlineKind.CURLY,
        ResolvedTerminalStyle.UnderlineKind.DOTTED,
        ResolvedTerminalStyle.UnderlineKind.DASHED
    };
    for (ResolvedTerminalStyle.UnderlineKind kind : kinds) {
      Bitmap expected = bitmap(96, 20);
      new TerminalDecorationPainter().draw(new Canvas(expected),
          style(kind, false, UNDERLINE, STRIKE), 0, 88, 0, 20);
      for (int split : new int[] {22, 44, 66}) {
        Bitmap actual = bitmap(96, 20);
        TerminalDecorationPainter painter = new TerminalDecorationPainter();
        ResolvedTerminalStyle splitStyle = style(kind, false, UNDERLINE, STRIKE);
        painter.draw(new Canvas(actual), splitStyle, 0, split, 0, 20);
        painter.draw(new Canvas(actual), splitStyle, split, 88, 0, 20);
        assertDecorationMasksEqual(kind + " split=" + split, expected, actual);
        actual.recycle();
      }
      expected.recycle();
    }
  }

  @Test
  public void dottedDecorationStaysInsideAccumulatedGeometryEdges() {
    for (float cellWidth : new float[] {7.3f, 10f, 22f}) {
      TerminalCellGeometry geometry = new TerminalCellGeometry();
      geometry.update(cellWidth, 20f, 15f);
      int left = geometry.columnEdgePx(1);
      int right = geometry.columnEdgePx(2);
      Bitmap bitmap = bitmap(Math.max(80, right + 10), 20);
      new TerminalDecorationPainter().draw(new Canvas(bitmap),
          style(ResolvedTerminalStyle.UnderlineKind.DOTTED, false, UNDERLINE, STRIKE),
          left, right, 0, 20);
      assertEquals("dotted decoration must be clipped for cell width " + cellWidth, 0,
          countInk(bitmap, 0, left, 0, 20)
              + countInk(bitmap, right, bitmap.getWidth(), 0, 20));
      bitmap.recycle();
    }
  }

  private static ResolvedTerminalStyle style(
      ResolvedTerminalStyle.UnderlineKind kind, boolean hidden, int underlineColor, int foreground) {
    ResolvedTerminalStyle style = new ResolvedTerminalStyle();
    style.underlineKind = kind;
    style.hidden = hidden;
    style.underlineColor = underlineColor;
    style.foreground = foreground;
    style.strike = true;
    return style;
  }

  private static Bitmap bitmap() {
    return bitmap(40, 20);
  }

  private static Bitmap bitmap(int width, int height) {
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(BACKGROUND);
    return bitmap;
  }

  private static void assertDecorationMasksEqual(String label, Bitmap expected, Bitmap actual) {
    assertEquals(label + " width", expected.getWidth(), actual.getWidth());
    assertEquals(label + " height", expected.getHeight(), actual.getHeight());
    for (int y = 0; y < expected.getHeight(); y++) {
      for (int x = 0; x < expected.getWidth(); x++) {
        assertEquals(label + " decoration mask differs at " + x + "," + y,
            expected.getPixel(x, y) != BACKGROUND,
            actual.getPixel(x, y) != BACKGROUND);
      }
    }
  }

  private static int countInk(Bitmap bitmap, int left, int right, int top, int bottom) {
    int count = 0;
    for (int y = top; y < bottom; y++) {
      for (int x = left; x < right; x++) {
        if (bitmap.getPixel(x, y) != BACKGROUND) count++;
      }
    }
    return count;
  }

  private static int signature(Bitmap bitmap, int left, int right, int top, int bottom) {
    int result = 1;
    for (int y = top; y < bottom; y++) {
      for (int x = left; x < right; x++) {
        result = 31 * result + bitmap.getPixel(x, y);
      }
    }
    return result;
  }

  private static boolean containsColor(Bitmap bitmap, int color,
                                       int left, int right, int top, int bottom) {
    int rgb = color & 0x00FFFFFF;
    for (int y = top; y < bottom; y++) {
      for (int x = left; x < right; x++) {
        int pixel = bitmap.getPixel(x, y) & 0x00FFFFFF;
        if (pixel == rgb) return true;
        if (rgb == 0x00FF00 && (pixel & 0x00FF00) != 0 && (pixel & 0xFF00FF) == 0) {
          return true;
        }
        if (rgb == 0xFF0000 && (pixel & 0xFF0000) != 0 && (pixel & 0x00FFFF) == 0) {
          return true;
        }
      }
    }
    return false;
  }

  private static final class CountingCanvas extends Canvas {
    int saveOps;
    int clipOps;
    int restoreOps;

    CountingCanvas() {
      super(Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888));
    }

    @Override
    public int save() {
      saveOps++;
      return super.save();
    }

    @Override
    public boolean clipRect(int left, int top, int right, int bottom) {
      clipOps++;
      return super.clipRect(left, top, right, bottom);
    }

    @Override
    public void restoreToCount(int saveCount) {
      restoreOps++;
      super.restoreToCount(saveCount);
    }
  }
}
