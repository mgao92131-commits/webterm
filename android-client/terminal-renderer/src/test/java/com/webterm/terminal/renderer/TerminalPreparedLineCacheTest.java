package com.webterm.terminal.renderer;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineBodyRecord;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderLine;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;

import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class TerminalPreparedLineCacheTest {
  private RemoteTerminalRenderer renderer;
  private RemoteTerminalModel.RenderSnapshot snapshot;
  private TerminalPalette palette;

  @Before
  public void setUp() {
    renderer = new RemoteTerminalRenderer();
    renderer.setFontMetrics(10f, 20f, 15f);
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline("instance", 1)));
    snapshot = model.renderSnapshot();
    palette = TerminalPalette.defaults();
  }

  @Test
  public void preparedLineHitIsIndependentFromRenderNodeLifetime() {
    TerminalPreparedLineCache cache = new TerminalPreparedLineCache(4, 1024 * 1024);
    cache.beginFrame(snapshot, renderer, 0xFF000000, 1, 1, 1);
    RenderLine line = line(1, 1, "hello");
    PreparedTerminalLine first = cache.getOrPrepare(
        line, renderer, snapshot.columns, palette, 0xFF000000);

    // 模拟 RenderNode 被淘汰后再次回到同一行：CPU 结果仍由独立 cache 持有。
    PreparedTerminalLine second = cache.get(line);
    assertSame(first, second);
    assertTrue(cache.hitCountForTest() > 0);
  }

  @Test
  public void lineVersionChangeDoesNotReusePreparedResult() {
    TerminalPreparedLineCache cache = new TerminalPreparedLineCache(4, 1024 * 1024);
    cache.beginFrame(snapshot, renderer, 0xFF000000, 1, 1, 1);
    PreparedTerminalLine first = cache.getOrPrepare(
        line(1, 1, "a"), renderer, snapshot.columns, palette, 0xFF000000);
    PreparedTerminalLine second = cache.getOrPrepare(
        line(1, 2, "b"), renderer, snapshot.columns, palette, 0xFF000000);

    assertNotSame(first, second);
    assertTrue(cache.missCountForTest() >= 2);
  }

  @Test
  public void visualGenerationClearsPreparedLines() {
    TerminalPreparedLineCache cache = new TerminalPreparedLineCache(4, 1024 * 1024);
    cache.beginFrame(snapshot, renderer, 0xFF000000, 1, 1, 1);
    PreparedTerminalLine first = cache.getOrPrepare(
        line(1, 1, "a"), renderer, snapshot.columns, palette, 0xFF000000);

    cache.beginFrame(snapshot, renderer, 0xFF000000, 2, 1, 1);
    PreparedTerminalLine second = cache.getOrPrepare(
        line(1, 1, "a"), renderer, snapshot.columns, palette, 0xFF000000);

    assertNotSame(first, second);
    assertTrue(cache.sizeForTest() == 1);
  }

  @Test
  public void metadataIsCachedWithoutCompilingLine() {
    TerminalPreparedLineCache cache = new TerminalPreparedLineCache(4, 1024 * 1024);
    cache.beginFrame(snapshot, renderer, 0xFF000000, 1, 1, 1);
    RenderLine line = line(1, 1, "blink");
    int first = cache.visibleBlinkKinds(line, renderer, snapshot.columns, palette, 0xFF000000);
    int second = cache.visibleBlinkKinds(line, renderer, snapshot.columns, palette, 0xFF000000);

    assertTrue(first == second);
    assertTrue(cache.metadataMissCountForTest() == 1);
    assertTrue(cache.metadataHitCountForTest() == 1);
  }

  private static ScreenBaseline baseline(String instance, long layoutEpoch) {
    RenderLine line = line(1, 1, "x");
    List<LineKey> screenRows = Collections.singletonList(line.key());
    List<LineBodyRecord> screenBodies = Collections.singletonList(
        new LineBodyRecord(line.key(), line.body()));
    return new ScreenBaseline(
        "session", instance, layoutEpoch, 1, 1,
        1, 1, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY, Collections.emptyList(), screenRows, screenBodies,
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults());
  }

  private static RenderLine line(long id, long version, String text) {
    return new RenderLine(
        new LineKey(id, version),
        new LineBody(1, false, new CellValue[] {
            new CellValue(text, (byte) 1, null, null)
        }));
  }
}
