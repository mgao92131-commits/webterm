package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineBodyRecord;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.StyleValue;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalColor;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalSelection;
import com.webterm.terminal.model.TerminalViewportState;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 记录当前 renderer 的 Canvas 操作形态；不把普通字体的 glyph 轮廓作为正确性断言。
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class RemoteTerminalRendererCharacterizationTest {
  @Test
  public void plainAsciiUsesContextualTextRun() {
    TerminalCompatibilityFixtures.Fixture fixture = fixture("ascii-ligature-sequences");
    CountingCanvas canvas = new CountingCanvas(320, 40);
    render(fixture, canvas, new TerminalViewportState(), TerminalCursor.hidden());

    assertTrue("ASCII fixture must draw text", canvas.textOps > 0);
    assertTrue("ASCII must use drawTextRun", canvas.textRunOps > 0);
    assertEquals("ordinary text must not use legacy drawText", 0, canvas.legacyTextOps);
  }

  @Test
  public void nonAsciiGraphemesPreserveClusterMapping() {
    TerminalCompatibilityFixtures.Fixture fixture = fixture("emoji");
    CountingCanvas canvas = new CountingCanvas(160, 40);
    render(fixture, canvas, new TerminalViewportState(), TerminalCursor.hidden());

    assertTrue("emoji graphemes must use contextual drawTextRun", canvas.textRunOps > 0);
    assertEquals("emoji must not use legacy String drawText", 0, canvas.legacyTextOps);
    assertTrue("emoji run must retain context range", canvas.contextualRunOps > 0);
  }

  @Test
  public void aPlainBlankCellDoesNotIssueGlyphDraw() {
    CountingCanvas canvas = new CountingCanvas(20, 40);
    render(new CellValue[] {CellValue.EMPTY}, canvas,
        new TerminalViewportState(), TerminalCursor.hidden());

    assertEquals(0, canvas.textOps);
  }

  @Test
  public void hiddenTextSkipsGlyphButKeepsStyledBackground() {
    StyleValue hidden = new StyleValue(
        TerminalColor.rgb(0xFFFFFF), TerminalColor.rgb(0x183050), null, 1 << 11);
    CountingCanvas canvas = new CountingCanvas(20, 40);
    render(new CellValue[] {new CellValue("X", (byte) 1, hidden, null)}, canvas,
        new TerminalViewportState(), TerminalCursor.hidden());

    assertEquals(0, canvas.textOps);
    assertTrue("hidden text background must still be drawn", canvas.rectOps > 0);
  }

  @Test
  public void cursorAndSelectionAddOverlayCanvasOperations() {
    CellValue[] cells = new CellValue[] {
        new CellValue("A", (byte) 1, null, null),
        new CellValue("B", (byte) 1, null, null),
        new CellValue("C", (byte) 1, null, null)
    };
    TerminalCursor cursor = new TerminalCursor(
        0, 0, true, TerminalCursor.Shape.BLOCK, false);
    CountingCanvas withoutSelection = new CountingCanvas(40, 40);
    render(cells, withoutSelection, new TerminalViewportState(), cursor);

    TerminalViewportState selectedViewport = new TerminalViewportState();
    selectedViewport.selection = new TerminalSelection(
        new TerminalSelection.Anchor(0, 0, 1),
        new TerminalSelection.Anchor(0, 0, 3));
    CountingCanvas withSelection = new CountingCanvas(40, 40);
    render(cells, withSelection, selectedViewport, cursor);

    assertTrue("cursor/selection must produce extra overlay operations",
        withSelection.rectOps > withoutSelection.rectOps);
  }

  @Test
  public void blinkDoesNotSetFakeBoldOrRaiseIndexedColorOnEitherPath() {
    int slowBlink = 1 << 8;
    int fastBlink = 1 << 9;
    TerminalColor lowIndexed = TerminalColor.indexed(1);
    int expectedColor = RemoteTerminalRenderer.resolveColor(
        TerminalPalette.defaults(), lowIndexed);

    StyleValue slow = new StyleValue(lowIndexed, TerminalColor.DEFAULT_BG, null, slowBlink);
    CountingCanvas ascii = new CountingCanvas(80, 40);
    render(new CellValue[] {
        new CellValue("a", (byte) 1, slow, null),
        new CellValue("b", (byte) 1, slow, null),
        new CellValue("c", (byte) 1, slow, null)
    }, ascii, new TerminalViewportState(), TerminalCursor.hidden(),
        new TerminalAnimationState(true, true, false));
    assertTrue(ascii.textRunOps > 0);
    assertFalse("slow blink must not become fake bold", ascii.lastFakeBold);
    assertEquals("slow blink must not become bright ANSI color",
        expectedColor, ascii.lastTextColor);

    StyleValue fast = new StyleValue(lowIndexed, TerminalColor.DEFAULT_BG, null, fastBlink);
    CountingCanvas cell = new CountingCanvas(80, 40);
    render(new CellValue[] {
        new CellValue("界", (byte) 2, fast, null)
    }, cell, new TerminalViewportState(), TerminalCursor.hidden(),
        new TerminalAnimationState(true, false, true));
    assertTrue(cell.textRunOps > 0);
    assertFalse("fast blink must not become fake bold", cell.lastFakeBold);
    assertEquals("fast blink must not become bright ANSI color",
        expectedColor, cell.lastTextColor);
  }

  private static void render(TerminalCompatibilityFixtures.Fixture fixture,
                             CountingCanvas canvas, TerminalViewportState viewport,
                             TerminalCursor cursor) {
    render(fixture.cells(), canvas, viewport, cursor);
  }

  private static void render(CellValue[] cells, CountingCanvas canvas,
                             TerminalViewportState viewport, TerminalCursor cursor) {
    render(cells, canvas, viewport, cursor, TerminalAnimationState.cursorOnly(true));
  }

  private static void render(CellValue[] cells, CountingCanvas canvas,
                             TerminalViewportState viewport, TerminalCursor cursor,
                             TerminalAnimationState animationState) {
    RemoteTerminalModel model = model(cells, cursor);
    RemoteTerminalRenderer renderer = renderer();
    renderer.render(canvas, model.renderSnapshot(), viewport, animationState, null);
  }

  private static RemoteTerminalRenderer renderer() {
    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer();
    renderer.updateFont(14f, Typeface.MONOSPACE);
    float cellWidth = renderer.getCellWidth();
    if (cellWidth <= 0f) cellWidth = 10f;
    renderer.setFontMetrics(cellWidth, 20f, 15f);
    return renderer;
  }

  private static RemoteTerminalModel model(CellValue[] cells, TerminalCursor cursor) {
    CellValue[] copy = Arrays.copyOf(cells, cells.length);
    LineKey key = new LineKey(1, 1);
    LineBody body = new LineBody(copy.length, false, copy);
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(new ScreenBaseline(
        "characterization", "instance", 1, 1, 1,
        1, copy.length, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY, Collections.emptyList(),
        List.of(key), List.of(new LineBodyRecord(key, body)),
        cursor, TerminalModes.defaults(), TerminalPalette.defaults())));
    return model;
  }

  private static TerminalCompatibilityFixtures.Fixture fixture(String name) {
    for (TerminalCompatibilityFixtures.Fixture fixture
        : TerminalCompatibilityFixtures.all()) {
      if (fixture.name().equals(name)) return fixture;
    }
    throw new AssertionError("fixture not found: " + name);
  }

  private static final class CountingCanvas extends Canvas {
    int textOps;
    int textRunOps;
    int contextualRunOps;
    int legacyTextOps;
    int rectOps;
    int lineOps;
    boolean lastFakeBold;
    int lastTextColor;

    CountingCanvas(int width, int height) {
      super(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888));
    }

    @Override
    public void drawText(String text, float x, float y, Paint paint) {
      textOps++;
      legacyTextOps++;
      lastFakeBold = paint.isFakeBoldText();
      lastTextColor = paint.getColor();
      super.drawText(text, x, y, paint);
    }

    @Override
    public void drawText(
        CharSequence text, int start, int end, float x, float y, Paint paint) {
      textOps++;
      legacyTextOps++;
      lastFakeBold = paint.isFakeBoldText();
      lastTextColor = paint.getColor();
      super.drawText(text, start, end, x, y, paint);
    }

    @Override
    public void drawTextRun(CharSequence text, int start, int end,
                            int contextStart, int contextEnd, float x, float y,
                            boolean isRtl, Paint paint) {
      textOps++;
      textRunOps++;
      if (start != contextStart || end != contextEnd) contextualRunOps++;
      lastFakeBold = paint.isFakeBoldText();
      lastTextColor = paint.getColor();
    }

    @Override
    public void drawRect(float left, float top, float right, float bottom, Paint paint) {
      rectOps++;
      super.drawRect(left, top, right, bottom, paint);
    }

    @Override
    public void drawLine(float startX, float startY, float stopX, float stopY, Paint paint) {
      lineOps++;
      super.drawLine(startX, startY, stopX, stopY, paint);
    }
  }
}
