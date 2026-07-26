package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.ScreenMutation;
import com.webterm.terminal.model.ScreenRowWrite;
import com.webterm.terminal.model.TerminalCommit;
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
    cache.endFrame();
    begin(cache, snapshot, 1, 1, 1);

    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.RECORDED,
        cache.drawOrRecord(canvas, changed, 0f, false));
    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.HIT,
        cache.drawOrRecord(canvas, changed, 0f, false));
    assertEquals(2, node.beginRecordingCount);
    assertEquals(3, node.drawCount);
  }

  @Test
  public void pinnedStaleEntryFallsBackWithoutOverwritingThisFramesDisplayList() {
    RemoteTerminalModel.RenderSnapshot snapshot = snapshot(5, 10, 1L, "instance-1");
    TerminalLineRenderNodeCache cache = newCache();
    begin(cache, snapshot, 1, 1, 1);
    TerminalLine original = textLine(500, 1, 10, "A");
    TerminalLine changed = textLine(500, 1, 10, "B");
    TerminalRenderMetrics.Snapshot metricsBefore = TerminalRenderMetrics.snapshot();

    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.RECORDED,
        cache.drawOrRecord(canvas, original, 0f, false));
    FakeNode node = (FakeNode) cache.nodeForLineForTest(500);
    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.UNAVAILABLE,
        cache.drawOrRecord(canvas, changed, 20f, true));

    assertSame(node, cache.nodeForLineForTest(500));
    assertSame(original, cache.recordedLineForTest(500));
    assertEquals(1, node.beginRecordingCount);
    assertEquals(1, node.drawCount);
    cache.endFrame();
    assertEquals(metricsBefore.rowCachePinnedConflictCount + 1,
        TerminalRenderMetrics.snapshot().rowCachePinnedConflictCount);

    begin(cache, snapshot, 1, 1, 1);
    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.RECORDED,
        cache.drawOrRecord(canvas, changed, 0f, true));
    assertEquals(2, node.beginRecordingCount);
    assertSame(changed, cache.recordedLineForTest(500));
    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.HIT,
        cache.drawOrRecord(canvas, changed, 20f, false));
    assertEquals(2, node.beginRecordingCount);
  }

  @Test
  public void sameIdAndVersionWithDifferentContentRerecordsInNextFrame() {
    RemoteTerminalModel.RenderSnapshot snapshot = snapshot(5, 10, 1L, "instance-1");
    TerminalLineRenderNodeCache cache = newCache();
    TerminalLine first = textLine(500, 1, 10, "A");
    TerminalLine changed = textLine(500, 1, 10, "B");
    begin(cache, snapshot, 1, 1, 1);
    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.RECORDED,
        cache.drawOrRecord(canvas, first, 0f, false));
    FakeNode node = (FakeNode) cache.nodeForLineForTest(500);
    cache.endFrame();

    begin(cache, snapshot, 1, 1, 1);
    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.RECORDED,
        cache.drawOrRecord(canvas, changed, 0f, true));
    assertEquals(2, node.beginRecordingCount);
    assertSame(changed, cache.recordedLineForTest(500));
  }

  @Test
  public void sameImmutableObjectUsesFastHitAcrossFrames() {
    RemoteTerminalModel.RenderSnapshot snapshot = snapshot(5, 10, 1L, "instance-1");
    TerminalLineRenderNodeCache cache = newCache();
    TerminalLine line = textLine(500, 1, 10, "A");
    begin(cache, snapshot, 1, 1, 1);
    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.RECORDED,
        cache.drawOrRecord(canvas, line, 0f, false));
    FakeNode node = (FakeNode) cache.nodeForLineForTest(500);
    cache.endFrame();

    begin(cache, snapshot, 1, 1, 1);
    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.HIT,
        cache.drawOrRecord(canvas, line, 0f, false));
    assertEquals(1, node.beginRecordingCount);
  }

  @Test
  public void equivalentReconstructedLineHitsWithoutRerecording() {
    RemoteTerminalModel.RenderSnapshot snapshot = snapshot(5, 10, 1L, "instance-1");
    TerminalLineRenderNodeCache cache = newCache();
    TerminalLine first = textLine(500, 1, 10, "A");
    TerminalLine reconstructed = textLine(500, 1, 10, "A");
    assertNotSame(first, reconstructed);
    begin(cache, snapshot, 1, 1, 1);
    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.RECORDED,
        cache.drawOrRecord(canvas, first, 0f, false));
    FakeNode node = (FakeNode) cache.nodeForLineForTest(500);
    cache.endFrame();

    begin(cache, snapshot, 1, 1, 1);
    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.HIT,
        cache.drawOrRecord(canvas, reconstructed, 0f, true));
    assertEquals(1, node.beginRecordingCount);
    assertSame(first, cache.recordedLineForTest(500));
  }

  @Test
  public void modelVersionInvariantKeepsReplayHitAndRerecordsOnlyHigherVersion() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline(1, 10)));
    model.consumeRenderUpdate();
    TerminalLineRenderNodeCache cache = newCache();
    RemoteTerminalModel.RenderSnapshot first = model.renderSnapshot();
    TerminalLine original = first.screen[0];
    begin(cache, first, 1, 1, 1);
    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.RECORDED,
        cache.drawOrRecord(canvas, original, 0f, false));
    FakeNode node = (FakeNode) cache.nodeForLineForTest(original.id);
    int recordsAfterBaseline = totalRecordings();
    cache.endFrame();

    TerminalLine replay = new TerminalLine(
        original.id, original.version, 0, original.wrapped,
        Arrays.copyOf(original.cells, original.cells.length));
    model.applyTerminalCommit(patch(1, 2, replay));
    assertNotNull(model.consumeRenderUpdate());
    RemoteTerminalModel.RenderSnapshot replaySnapshot = model.renderSnapshot();
    assertSame(original, replaySnapshot.screen[0]);
    begin(cache, replaySnapshot, 1, 1, 1);
    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.HIT,
        cache.drawOrRecord(canvas, replaySnapshot.screen[0], 0f, false));
    assertEquals(recordsAfterBaseline, totalRecordings());
    cache.endFrame();

    try {
      model.applyTerminalCommit(patch(2, 3,
          textLine(original.id, original.version, 10, "changed-without-version")));
      throw new AssertionError("same-version content change must be rejected");
    } catch (RemoteTerminalModel.RevisionGapException expected) {
      assertEquals(2, model.screenRevision);
      assertSame(original, model.renderSnapshot().screen[0]);
    }

    model.applyTerminalCommit(patch(2, 3,
        textLine(original.id, original.version + 1, 10, "changed")));
    assertTrue(model.consumeRenderUpdate().dirty.changedScreenRows.get(0));
    RemoteTerminalModel.RenderSnapshot changedSnapshot = model.renderSnapshot();
    assertEquals("changed", changedSnapshot.screen[0].at(0).text);
    begin(cache, changedSnapshot, 1, 1, 1);
    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.RECORDED,
        cache.drawOrRecord(canvas, changedSnapshot.screen[0], 0f, false));
    assertSame(node, cache.nodeForLineForTest(original.id));
    assertEquals(recordsAfterBaseline + 1, totalRecordings());
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
  public void ordinaryFullRenderKeepsExistingNodeWhenVisualIdentityIsUnchanged() {
    RemoteTerminalModel.RenderSnapshot snapshot = snapshot(5, 10, 1L, "instance-1");
    TerminalLineRenderNodeCache cache = newCache();
    begin(cache, snapshot, 1, 1, 1);
    TerminalLine line = snapshot.screen[0];
    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.RECORDED,
        cache.drawOrRecord(canvas, line, 0f, false));
    TerminalRowNode recorded = cache.nodeForLineForTest(line.id);
    int recordings = totalRecordings();
    cache.endFrame();

    // fullInvalidate 只改变 View 的受损区域；传给缓存的视觉 generation 保持不变。
    begin(cache, snapshot, 1, 1, 1);
    assertSame(recorded, cache.nodeForLineForTest(line.id));
    assertEquals(TerminalLineRenderNodeCache.LineDrawResult.HIT,
        cache.drawOrRecord(canvas, line, 0f, false));
    assertEquals(recordings, totalRecordings());
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
    assertEquals(1, cache.victimScanCountForTest());
    assertEquals(cache.capacityForTest(), cache.victimScannedEntriesForTest());
    assertEquals(1, cache.allPinnedFallbackCountForTest());
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

  private static ScreenBaseline baseline(int rows, int columns) {
    List<TerminalLine> screen = new ArrayList<>(rows);
    for (int row = 0; row < rows; row++) {
      screen.add(textLine(1L + row, 1, columns, "original"));
    }
    return new ScreenBaseline(
        "session-1", "instance-1", 1L, 1L, 1L, rows, columns,
        TerminalBufferKind.MAIN, HistoryExtent.INITIAL_EMPTY, Collections.emptyList(), screen,
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(), "", "");
  }

  private static TerminalCommit patch(long baseRevision, long revision, TerminalLine line) {
    return new TerminalCommit(
        "instance-1", 1L, 1L, baseRevision, revision,
        new ScreenMutation(null, Collections.singletonList(new ScreenRowWrite(0, line))),
        null, null, null, null);
  }

  private static TerminalLine textLine(
      long id, long version, int columns, String text) {
    TerminalCell[] cells = new TerminalCell[columns];
    cells[0] = new TerminalCell(text, (byte) 1, null, null);
    for (int column = 1; column < columns; column++) cells[column] = TerminalCell.EMPTY;
    return new TerminalLine(id, version, 0, false, cells);
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
