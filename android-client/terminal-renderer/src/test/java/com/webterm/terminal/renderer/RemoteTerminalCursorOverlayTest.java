package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalColor;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalViewportState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * RenderNode 行缓存模式下光标覆盖层（drawCursorOverlayForRow）的回归测试。
 *
 * <p>静态正文由假节点吞掉，主 Canvas 上只会留下覆盖层的 draw 调用，
 * 因此可以精确断言块光标的反色字形重绘和宽字符整格高亮。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public final class RemoteTerminalCursorOverlayTest {

  private static final float CELL_WIDTH = 1f;
  private static final float LINE_HEIGHT = 20f;
  private static final float BASELINE_OFFSET = 15f;
  /** topInset = lineHeight - baselineOffset。 */
  private static final float ROW0_Y = 5f;

  private static final int FG = 0xFFAABBCC;
  private static final int BG = 0xFF010203;
  private static final int CURSOR = 0xFF445566;

  private RemoteTerminalRenderer renderer;
  private TerminalPalette palette;

  @Before
  public void setUp() {
    renderer = new RemoteTerminalRenderer();
    // Robolectric legacy Paint 把每个 ASCII 字形测为 1px，cellWidth=1 时不触发缩放路径。
    renderer.setFontMetrics(CELL_WIDTH, LINE_HEIGHT, BASELINE_OFFSET);
    palette = new TerminalPalette(TerminalColor.rgb(0xAABBCC), TerminalColor.rgb(0x010203),
        TerminalColor.rgb(0x445566), false, Collections.<Integer, Integer>emptyMap(), 1L);
  }

  @Test
  public void blockCursorRedrawsGlyphWithReversedColors() {
    TerminalCell[] cells = emptyCells(10);
    cells[2] = new TerminalCell("A", (byte) 1, null, null);
    RecordingCanvas canvas = renderWithBlockCursor(cells, 10, new TerminalCursor(
        1, 2, true, TerminalCursor.Shape.BLOCK, false));

    // drawCell 的块光标路径：反色背景矩形 → 不透明光标矩形 → 反色前景字形。
    assertEquals(3, canvas.events.size());
    assertRect(canvas.events.get(0), 2f, ROW0_Y + LINE_HEIGHT, 3f,
        ROW0_Y + 2 * LINE_HEIGHT, FG);
    assertRect(canvas.events.get(1), 2f, ROW0_Y + LINE_HEIGHT, 3f,
        ROW0_Y + 2 * LINE_HEIGHT, CURSOR);
    DrawEvent text = canvas.events.get(2);
    assertTrue(text.isText);
    assertEquals("A", text.text);
    assertEquals(2f, text.left, 0.001f);
    assertEquals(ROW0_Y + LINE_HEIGHT + BASELINE_OFFSET, text.top, 0.001f);
    assertEquals(BG, text.color);
  }

  @Test
  public void blockCursorOnWideSpacerHighlightsBothCellsAndRedrawsGlyph() {
    TerminalCell[] cells = emptyCells(10);
    cells[2] = new TerminalCell("好", (byte) 2, null, null);
    cells[3] = TerminalCell.SPACER;
    // 光标落在宽字符右半（spacer 列）：高亮必须覆盖 2 格，并重绘宽字符字形。
    RecordingCanvas canvas = renderWithBlockCursor(cells, 10, new TerminalCursor(
        1, 3, true, TerminalCursor.Shape.BLOCK, false));

    assertEquals(3, canvas.events.size());
    assertRect(canvas.events.get(0), 2f, ROW0_Y + LINE_HEIGHT, 4f,
        ROW0_Y + 2 * LINE_HEIGHT, FG);
    assertRect(canvas.events.get(1), 2f, ROW0_Y + LINE_HEIGHT, 4f,
        ROW0_Y + 2 * LINE_HEIGHT, CURSOR);
    DrawEvent text = canvas.events.get(2);
    assertTrue(text.isText);
    assertEquals("好", text.text);
    assertEquals(BG, text.color);
  }

  @Test
  public void underlineCursorOnWideSpacerSpansBothCells() {
    TerminalCell[] cells = emptyCells(10);
    cells[2] = new TerminalCell("好", (byte) 2, null, null);
    cells[3] = TerminalCell.SPACER;
    RecordingCanvas canvas = renderWithBlockCursor(cells, 10, new TerminalCursor(
        1, 3, true, TerminalCursor.Shape.UNDERLINE, false));

    assertEquals(1, canvas.events.size());
    assertRect(canvas.events.get(0), 2f, ROW0_Y + LINE_HEIGHT + LINE_HEIGHT * 3f / 4f, 4f,
        ROW0_Y + 2 * LINE_HEIGHT, CURSOR);
  }

  @Test
  public void blockCursorOnEmptyCellDrawsNoGlyph() {
    // 光标落在空白 cell（model 会把短行补齐为 EMPTY）：只画反色背景与光标矩形，不重绘字形。
    TerminalCell[] cells = emptyCells(10);
    RecordingCanvas canvas = renderWithBlockCursor(cells, 10, new TerminalCursor(
        1, 7, true, TerminalCursor.Shape.BLOCK, false));

    assertEquals(2, canvas.events.size());
    assertRect(canvas.events.get(0), 7f, ROW0_Y + LINE_HEIGHT, 8f,
        ROW0_Y + 2 * LINE_HEIGHT, FG);
    assertRect(canvas.events.get(1), 7f, ROW0_Y + LINE_HEIGHT, 8f,
        ROW0_Y + 2 * LINE_HEIGHT, CURSOR);
  }

  // ------------------------------------------------------------------ fixtures

  private static TerminalCell[] emptyCells(int cols) {
    TerminalCell[] cells = new TerminalCell[cols];
    Arrays.fill(cells, TerminalCell.EMPTY);
    return cells;
  }

  /**
   * 构造 3 行屏幕，第 1 行为 cursorLineCells，其余为空白行；走 RenderNode 缓存路径渲染，
   * 返回只记录覆盖层 draw 调用的主 Canvas。
   */
  private RecordingCanvas renderWithBlockCursor(TerminalCell[] cursorLineCells, int columns,
                                                TerminalCursor cursor) {
    int rows = 3;
    TerminalLine[] screen = new TerminalLine[rows];
    screen[0] = new TerminalLine(1, false, emptyCells(columns));
    screen[1] = new TerminalLine(2, false, cursorLineCells);
    screen[2] = new TerminalLine(3, false, emptyCells(columns));
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applyBaseline(new ScreenBaseline(
        "session-1", "term-1", 1L, 1L, 1L, 1, false, com.webterm.terminal.model.DictionaryEntries.EMPTY, rows, columns, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY, Arrays.<TerminalLine>asList(), Arrays.asList(screen),
        cursor, TerminalModes.defaults(), palette));

    model.requestFullRender();
    RenderUpdate update = model.consumeRenderUpdate();
    if (update == null) throw new AssertionError("expected initial RenderUpdate");
    TerminalLineRenderNodeCache cache = new TerminalLineRenderNodeCache(
        name -> new TerminalLineRenderNodeCacheTest.FakeNode(name));
    int canvasBackground = RemoteTerminalRenderer.resolveColor(palette,
        palette.reverseVideo ? palette.defaultFg : palette.defaultBg);
    cache.beginFrame(update.snapshot, renderer, palette, canvasBackground, 1, 1, 1);

    RecordingCanvas canvas = new RecordingCanvas(
        (int) (columns * CELL_WIDTH * 10), (int) (rows * LINE_HEIGHT + 10));
    renderer.render(canvas, model.renderSnapshot(), new TerminalViewportState(), true, cache);
    cache.endFrame();
    return canvas;
  }

  private static void assertRect(DrawEvent event, float left, float top, float right,
                                 float bottom, int color) {
    assertTrue("expected rect, got text " + event.text, !event.isText);
    assertEquals(left, event.left, 0.001f);
    assertEquals(top, event.top, 0.001f);
    assertEquals(right, event.right, 0.001f);
    assertEquals(bottom, event.bottom, 0.001f);
    assertEquals(color, event.color);
  }

  private static final class DrawEvent {
    final boolean isText;
    final float left;
    final float top;
    final float right;
    final float bottom;
    final int color;
    final String text;

    DrawEvent(boolean isText, float left, float top, float right, float bottom, int color,
              String text) {
      this.isText = isText;
      this.left = left;
      this.top = top;
      this.right = right;
      this.bottom = bottom;
      this.color = color;
      this.text = text;
    }
  }

  /** 只记录 draw 调用、不实际光栅化的 Canvas；save/scale 为空操作，避免依赖原生矩阵。 */
  private static final class RecordingCanvas extends Canvas {
    final List<DrawEvent> events = new ArrayList<>();

    RecordingCanvas(int width, int height) {
      super(Bitmap.createBitmap(Math.max(1, width), Math.max(1, height),
          Bitmap.Config.ARGB_8888));
    }

    @Override
    public boolean isHardwareAccelerated() {
      return true;
    }

    @Override
    public void drawRect(float left, float top, float right, float bottom, Paint paint) {
      events.add(new DrawEvent(false, left, top, right, bottom, paint.getColor(), null));
    }

    @Override
    public void drawText(String text, float x, float y, Paint paint) {
      events.add(new DrawEvent(true, x, y, 0f, 0f, paint.getColor(), text));
    }

    @Override
    public void drawText(CharSequence text, int start, int end, float x, float y, Paint paint) {
      events.add(new DrawEvent(true, x, y, 0f, 0f, paint.getColor(),
          text.subSequence(start, end).toString()));
    }

    @Override
    public void drawColor(int color) {
      // 整屏底色不属于覆盖层断言范围。
    }

    @Override
    public int save() {
      return 1;
    }

    @Override
    public void restore() {
    }

    @Override
    public void scale(float sx, float sy) {
    }
  }
}
