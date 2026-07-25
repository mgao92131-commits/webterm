package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalRenderMetrics;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public final class TerminalLineRenderNodeCacheTest {
  private RemoteTerminalRenderer renderer;
  private TerminalPalette palette;
  private int canvasBackground;
  private Canvas canvas;
  private final List<FakeNode> createdNodes = new ArrayList<>();

  @Before
  public void setUp() {
    renderer = new RemoteTerminalRenderer();
    renderer.setFontMetrics(10f, 20f, 15f);
    palette = TerminalPalette.defaults();
    canvasBackground = RemoteTerminalRenderer.resolveColor(palette, palette.defaultBg);
    canvas = new Canvas(Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888));
    createdNodes.clear();
  }

  private TerminalLineRenderNodeCache newCache() {
    return new TerminalLineRenderNodeCache(name -> {
      FakeNode node = new FakeNode(name);
      createdNodes.add(node);
      return node;
    });
  }

  private void begin(TerminalLineRenderNodeCache cache, RemoteTerminalModel.RenderSnapshot snapshot,
                     int fontGeneration, int paletteGeneration, int styleGeneration) {
    cache.beginFrame(snapshot, renderer, palette, canvasBackground,
        fontGeneration, paletteGeneration, styleGeneration);
  }

  @Test
  public void baselineDoesNotRecordUntilVisibleLineIsDrawn() {
    RemoteTerminalModel.RenderSnapshot snapshot = snapshot(40, 80, 1L, "instance-1");
    TerminalLineRenderNodeCache cache = newCache();
    begin(cache, snapshot, 1, 1, 1);

    assertEquals(0, cache.sizeForTest());
    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.RECORDED,
        cache.drawOrRecord(canvas, snapshot.screen[0], 0f, false));
    assertEquals(1, cache.sizeForTest());
    assertEquals(1, createdNodes.size());
  }

  @Test
  public void sameLineAndVersionHitsAcrossScreenAndHistory() {
    RemoteTerminalModel.RenderSnapshot snapshot = snapshot(5, 10, 1L, "instance-1");
    TerminalLineRenderNodeCache cache = newCache();
    begin(cache, snapshot, 1, 1, 1);
    TerminalLine line = snapshot.screen[0];

    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.RECORDED,
        cache.drawOrRecord(canvas, line, 0f, false));
    TerminalRowNode node = cache.nodeForLineForTest(line.id);
    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.HIT,
        cache.drawOrRecord(canvas, line, 20f, true));
    assertSame(node, cache.nodeForLineForTest(line.id));
    assertEquals(2, ((FakeNode) node).drawCount);
  }

  @Test
  public void versionIncreaseRerecordsExactlyOnceThenHits() {
    RemoteTerminalModel.RenderSnapshot snapshot = snapshot(5, 10, 1L, "instance-1");
    TerminalLineRenderNodeCache cache = newCache();
    begin(cache, snapshot, 1, 1, 1);
    TerminalLine original = snapshot.screen[2];
    cache.drawOrRecord(canvas, original, 0f, false);
    FakeNode node = (FakeNode) cache.nodeForLineForTest(original.id);
    TerminalLine changed = line(original.id, original.version + 1, 10);

    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.RECORDED,
        cache.drawOrRecord(canvas, changed, 0f, false));
    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.HIT,
        cache.drawOrRecord(canvas, changed, 0f, false));
    assertEquals(2, node.beginRecordingCount);
    assertEquals(3, node.drawCount);
  }

  @Test
  public void oneLineScrollOnlyRecordsNewOrChangedLines() {
    RemoteTerminalModel.RenderSnapshot first = snapshot(40, 80, 1L, "instance-1");
    TerminalLineRenderNodeCache cache = newCache();
    begin(cache, first, 1, 1, 1);
    for (TerminalLine line : first.screen) cache.drawOrRecord(canvas, line, 0f, false);
    int recordsBefore = totalRecordings();
    cache.endFrame();

    RemoteTerminalModel.RenderSnapshot second = snapshot(40, 80, 1L, "instance-1", 2L);
    begin(cache, second, 1, 1, 1);
    for (TerminalLine line : second.screen) cache.drawOrRecord(canvas, line, 0f, false);

    assertEquals(1, totalRecordings() - recordsBefore);
    // 离开 screen 的那一行仍在 LRU 内，供它进入 history 时继续命中。
    assertEquals(41, cache.sizeForTest());
  }

  @Test
  public void visualGenerationAndLayoutIdentityClearAllNodes() {
    RemoteTerminalModel.RenderSnapshot first = snapshot(5, 10, 1L, "instance-1");
    TerminalLineRenderNodeCache cache = newCache();
    begin(cache, first, 1, 1, 1);
    cache.drawOrRecord(canvas, first.screen[0], 0f, false);
    TerminalRowNode oldNode = cache.nodeForLineForTest(first.screen[0].id);
    cache.endFrame();

    begin(cache, first, 2, 1, 1);
    assertEquals(0, cache.sizeForTest());
    cache.drawOrRecord(canvas, first.screen[0], 0f, false);
    assertNotSame(oldNode, cache.nodeForLineForTest(first.screen[0].id));
    cache.endFrame();

    RemoteTerminalModel.RenderSnapshot nextLayout = snapshot(5, 10, 2L, "instance-1");
    begin(cache, nextLayout, 2, 1, 1);
    assertEquals(0, cache.sizeForTest());
  }

  @Test
  public void capacityIsDynamicAndStrictlyBounded() {
    RemoteTerminalModel.RenderSnapshot snapshot = snapshot(100, 10, 1L, "instance-1");
    TerminalLineRenderNodeCache cache = newCache();
    begin(cache, snapshot, 1, 1, 1);
    assertEquals(384, cache.capacityForTest());

    for (int id = 1; id <= 600; id++) {
      cache.drawOrRecord(canvas, line(id, 1, 10), 0f, true);
    }
    assertEquals(384, cache.sizeForTest());
    assertTrue(createdNodes.size() <= 384);
  }

  @Test
  public void fullPinnedFrameFallsBackWithoutRewritingDrawnNodes() {
    RemoteTerminalModel.RenderSnapshot snapshot = snapshot(24, 10, 1L, "instance-1");
    TerminalLineRenderNodeCache cache = newCache();
    begin(cache, snapshot, 1, 1, 1);
    for (int id = 1; id <= cache.capacityForTest(); id++) {
      assertEquals(TerminalLineRenderNodeCache.LineDrawResult.RECORDED,
          cache.drawOrRecord(canvas, line(id, 1, 10), 0f, true));
    }
    int recordingsBefore = totalRecordings();

    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.UNAVAILABLE,
        cache.drawOrRecord(canvas, line(10_000L, 1, 10), 0f, true));
    assertEquals(recordingsBefore, totalRecordings());
    assertEquals(cache.capacityForTest(), cache.sizeForTest());
  }

  @Test
  public void placeholderNullLineNeverEntersCache() {
    TerminalLineRenderNodeCache cache = newCache();
    begin(cache, snapshot(5, 10, 1L, "instance-1"), 1, 1, 1);
    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.UNAVAILABLE,
        cache.drawOrRecord(canvas, null, 0f, true));
    assertEquals(0, cache.sizeForTest());
  }

  private int totalRecordings() {
    int total = 0;
    for (FakeNode node : createdNodes) total += node.beginRecordingCount;
    return total;
  }

  private static RemoteTerminalModel.RenderSnapshot snapshot(
      int rows, int columns, long layoutEpoch, String instanceId) {
    return snapshot(rows, columns, layoutEpoch, instanceId, 1L);
  }

  private static RemoteTerminalModel.RenderSnapshot snapshot(
      int rows, int columns, long layoutEpoch, String instanceId, long firstLineId) {
    List<TerminalLine> screen = new ArrayList<>(rows);
    for (int row = 0; row < rows; row++) screen.add(line(firstLineId + row, 1, columns));
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applyBaseline(new ScreenBaseline(
        "session-1", instanceId, layoutEpoch, 1L, 1L, rows, columns,
        TerminalBufferKind.MAIN, HistoryExtent.INITIAL_EMPTY, Collections.emptyList(), screen,
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(), "", ""));
    return model.renderSnapshot();
  }

  private static TerminalLine line(long id, long version, int columns) {
    TerminalCell[] cells = new TerminalCell[columns];
    Arrays.fill(cells, TerminalCell.EMPTY);
    return new TerminalLine(id, version, false, cells);
  }

  static final class FakeNode implements TerminalRowNode {
    final String name;
    int beginRecordingCount;
    int drawCount;

    FakeNode(String name) { this.name = name; }

    @Override public void setPosition(int left, int top, int right, int bottom) {}

    @Override public Canvas beginRecording(int width, int height) {
      beginRecordingCount++;
      return new Canvas(Bitmap.createBitmap(Math.max(1, width), Math.max(1, height),
          Bitmap.Config.ARGB_8888));
    }

    @Override public void endRecording() {}
    @Override public boolean hasDisplayList() { return beginRecordingCount > 0; }
    @Override public void draw(Canvas canvas, float y) { drawCount++; }
  }
}
