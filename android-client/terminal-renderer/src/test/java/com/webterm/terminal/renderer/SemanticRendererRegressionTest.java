package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.HistoryBodyEntry;
import com.webterm.terminal.model.HistoryBodyResult;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.HistoryPush;
import com.webterm.terminal.model.HistoryRangeResult;
import com.webterm.terminal.model.HistoryRequestContext;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineBodyRecord;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.ProjectionIdentity;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.ScreenLineContent;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalViewportState;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class SemanticRendererRegressionTest {
  @Test
  public void scrollAwayAndReturnToTailStillDrawsTerminalContent() {
    RemoteTerminalModel model = loadedModel(20);
    RemoteTerminalRenderer renderer = renderer();
    TerminalViewportState viewport = new TerminalViewportState();

    CountingCanvas baseline = new CountingCanvas(200, 80);
    renderer.render(baseline, model.renderSnapshot(), viewport, true);
    assertTrue(baseline.textOps > 0);

    viewport.scrollBy(20, 40, model.renderSnapshot(), 20f);
    CountingCanvas scrolled = new CountingCanvas(200, 80);
    renderer.render(scrolled, model.renderSnapshot(), viewport, true);
    assertTrue(scrolled.textOps > 0);

    viewport.followTail(TerminalBufferKind.MAIN);
    CountingCanvas returned = new CountingCanvas(200, 80);
    renderer.render(returned, model.renderSnapshot(), viewport, true);
    assertTrue(returned.textOps > 0);
  }

  @Test
  public void rangeLoadWakesHistoryAndPreservesWidePhysicalBody() {
    RemoteTerminalModel model = loadedModel(200);
    assertEquals(200, model.renderSnapshot().history.renderLineAt(0).body().physicalColumns);
    CountingCanvas canvas = new CountingCanvas(800, 80);
    renderer().render(canvas, model.renderSnapshot(), new TerminalViewportState(), true);
    assertTrue(canvas.textOps > 0);
  }

  private static RemoteTerminalModel loadedModel(int historyColumns) {
    CellValue[] screenCells = cells(80, "screen");
    LineKey historyKey = new LineKey(10, 1);
    LineKey screenKey = new LineKey(20, 1);
    LineBody screenBody = new LineBody(80, false, screenCells);
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(new ScreenBaseline(
        "s", "i", 1, 1, 1,
        1, 80, TerminalBufferKind.MAIN,
        new HistoryExtent(1, 1),
        Collections.singletonList(new HistoryPush(1, historyKey)),
        Collections.singletonList(screenKey),
        Collections.singletonList(new LineBodyRecord(screenKey, screenBody)),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults())));
    HistoryBodyResult result = model.applyHistoryBody(
        new HistoryRangeResult(
            "r", "i", 1, 1, HistoryRangeResult.Status.OK,
            new HistoryExtent(1, 1),
            Collections.singletonList(new HistoryBodyEntry(
                1, historyKey,
                new LineBody(historyColumns, false, cells(historyColumns, "history")))),
            0),
        new HistoryRequestContext(new ProjectionIdentity("i", 1, 1), 1, 1, 1));
    assertTrue(result instanceof HistoryBodyResult.Applied);
    return model;
  }

  private static CellValue[] cells(int columns, String text) {
    CellValue[] cells = new CellValue[columns];
    java.util.Arrays.fill(cells, CellValue.EMPTY);
    cells[0] = new CellValue(text, (byte) 1, null, null);
    return cells;
  }

  private static RemoteTerminalRenderer renderer() {
    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer();
    renderer.setFontMetrics(10f, 20f, 15f);
    return renderer;
  }

  private static final class CountingCanvas extends Canvas {
    int textOps;

    CountingCanvas(int width, int height) {
      super(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888));
    }

    @Override
    public void drawText(String text, float x, float y, Paint paint) {
      textOps++;
    }

    @Override
    public void drawTextRun(CharSequence text, int start, int end,
                            int contextStart, int contextEnd, float x, float y,
                            boolean isRtl, Paint paint) {
      textOps++;
    }
  }
}
