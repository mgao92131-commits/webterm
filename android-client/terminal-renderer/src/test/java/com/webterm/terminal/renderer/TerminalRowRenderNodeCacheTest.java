package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderDirtyState;
import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.ScreenPatchV2;
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
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public final class TerminalRowRenderNodeCacheTest {

  private static final String INSTANCE = "term-1";
  private static final long LAYOUT_EPOCH = 1L;
  private static final long STREAM_GENERATION = 1L;

  private RemoteTerminalRenderer renderer;
  private TerminalPalette palette;
  private int canvasBackground;
  private TerminalRenderMetrics.Snapshot baselineMetrics;
  private final AtomicInteger nodeIdGenerator = new AtomicInteger();
  private final List<FakeNode> createdNodes = new ArrayList<>();

  @Before
  public void setUp() {
    renderer = new RemoteTerminalRenderer();
    renderer.setFontMetrics(10f, 20f, 15f);
    palette = TerminalPalette.defaults();
    canvasBackground = RemoteTerminalRenderer.resolveColor(palette,
        palette.reverseVideo ? palette.defaultFg : palette.defaultBg);
    baselineMetrics = TerminalRenderMetrics.snapshot();
    createdNodes.clear();
  }

  private long hitDelta() {
    return TerminalRenderMetrics.snapshot().rowCacheHitCount - baselineMetrics.rowCacheHitCount;
  }

  private long recordDelta() {
    return TerminalRenderMetrics.snapshot().rowNodeRecordCount - baselineMetrics.rowNodeRecordCount;
  }

  private long reuseDelta() {
    return TerminalRenderMetrics.snapshot().rowNodeReuseCount - baselineMetrics.rowNodeReuseCount;
  }

  private long missDelta() {
    return TerminalRenderMetrics.snapshot().rowCacheMissCount - baselineMetrics.rowCacheMissCount;
  }

  private static TerminalLine line(long id, int version, int cols) {
    TerminalCell[] cells = new TerminalCell[cols];
    Arrays.fill(cells, TerminalCell.EMPTY);
    return new TerminalLine(id, version, false, cells);
  }

  private static List<TerminalLine> screenLines(long[] ids, int version, int cols) {
    List<TerminalLine> lines = new ArrayList<>(ids.length);
    for (long id : ids) lines.add(line(id, version, cols));
    return lines;
  }

  private TerminalRowRenderNodeCache newCache() {
    return new TerminalRowRenderNodeCache(name -> {
      FakeNode node = new FakeNode(name + "-" + nodeIdGenerator.incrementAndGet());
      createdNodes.add(node);
      return node;
    });
  }

  private RemoteTerminalModel modelWithScreen(int rows, int cols) {
    RemoteTerminalModel model = new RemoteTerminalModel();
    long[] ids = new long[rows];
    for (int i = 0; i < rows; i++) ids[i] = i + 1;
    List<TerminalLine> screen = screenLines(ids, 1, cols);
    ScreenBaseline baseline = new ScreenBaseline(
        "session-1", INSTANCE, LAYOUT_EPOCH, 1L, STREAM_GENERATION,
        rows, cols, TerminalBufferKind.MAIN, HistoryExtent.INITIAL_EMPTY,
        Collections.emptyList(), screen,
        TerminalCursor.hidden(), TerminalModes.defaults(), palette,
        "", "");
    model.applyBaseline(baseline);
    return model;
  }

  private static RenderUpdate initialUpdate(RemoteTerminalModel model) {
    model.requestFullRender();
    RenderUpdate update = model.consumeRenderUpdate();
    if (update == null) throw new AssertionError("expected initial RenderUpdate");
    return update;
  }

  private int prepare(TerminalRowRenderNodeCache cache, RenderUpdate update,
                      int fontGen, int paletteGen, int styleGen) {
    return cache.prepareFrame(update.snapshot, update.dirty, renderer, palette, canvasBackground,
        fontGen, paletteGen, styleGen);
  }

  private static RenderUpdate applyScrollUp(
      RemoteTerminalModel model, int rows, int cols, int scrollRows) {
    long[] layout = new long[rows];
    for (int i = 0; i < rows; i++) layout[i] = i + 1 + scrollRows;
    List<TerminalLine> updates = new ArrayList<>(scrollRows);
    for (int i = 0; i < scrollRows; i++) {
      updates.add(line(rows + 1 + i, 1, cols));
    }
    ScreenPatchV2 patch = new ScreenPatchV2(
        INSTANCE, LAYOUT_EPOCH, STREAM_GENERATION, 1L, 2L,
        layout, updates, null, null, null, null, null, null);
    try {
      model.applyScreenPatch(patch);
    } catch (RemoteTerminalModel.RevisionGapException e) {
      throw new AssertionError(e);
    }
    return model.consumeRenderUpdate();
  }

  @Test
  public void initialBaselineRecordsAllRows() {
    RemoteTerminalModel model = modelWithScreen(5, 10);
    RenderUpdate update = initialUpdate(model);
    TerminalRowRenderNodeCache cache = newCache();
    int recorded = prepare(cache, update, 1, 1, 1);
    assertEquals(5, cache.rowCount());
    assertEquals(5, recorded);
    assertEquals(5, recordDelta());
    for (FakeNode node : createdNodes) {
      assertEquals(1, node.setPositionCount);
      assertEquals(1, node.endRecordingCount);
    }
  }

  @Test
  public void sameLineAndVersionIsCacheHit() {
    RemoteTerminalModel model = modelWithScreen(5, 10);
    RenderUpdate update = initialUpdate(model);
    TerminalRowRenderNodeCache cache = newCache();
    prepare(cache, update, 1, 1, 1);

    Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    baselineMetrics = TerminalRenderMetrics.snapshot();
    assertTrue(cache.drawRow(canvas, 0, 0f, update.snapshot.screen[0]));
    assertEquals(1, hitDelta());
    assertEquals(1, ((FakeNode) cache.nodeForTest(0)).drawCount);
  }

  @Test
  public void lineVersionChangeRecordsOnlyThatRow() {
    RemoteTerminalModel model = modelWithScreen(5, 10);
    RenderUpdate first = initialUpdate(model);
    TerminalRowRenderNodeCache cache = newCache();
    prepare(cache, first, 1, 1, 1);

    TerminalLine updated = line(3L, 2, 10);
    ScreenPatchV2 patch = new ScreenPatchV2(
        INSTANCE, LAYOUT_EPOCH, STREAM_GENERATION, 1L, 2L,
        new long[] {1, 2, 3, 4, 5}, Collections.singletonList(updated),
        null, null, null, null, null, null);
    try {
      model.applyScreenPatch(patch);
    } catch (RemoteTerminalModel.RevisionGapException e) {
      throw new AssertionError(e);
    }
    RenderUpdate second = model.consumeRenderUpdate();
    baselineMetrics = TerminalRenderMetrics.snapshot();
    int recorded = prepare(cache, second, 1, 1, 1);
    assertEquals(1, recorded);
    assertEquals(1, recordDelta());
    assertEquals(2, ((FakeNode) cache.nodeForTest(2)).setPositionCount);
    assertEquals(1, ((FakeNode) cache.nodeForTest(0)).setPositionCount);
  }

  @Test
  public void singleRowScrollReusesAllButOne() {
    RemoteTerminalModel model = modelWithScreen(5, 10);
    RenderUpdate first = initialUpdate(model);
    TerminalRowRenderNodeCache cache = newCache();
    prepare(cache, first, 1, 1, 1);
    TerminalRowNode oldRow0 = cache.nodeForTest(0);
    TerminalRowNode oldRow1 = cache.nodeForTest(1);

    RenderUpdate scroll = applyScrollUp(model, 5, 10, 1);
    baselineMetrics = TerminalRenderMetrics.snapshot();
    int recorded = prepare(cache, scroll, 1, 1, 1);
    assertEquals(1, recorded);
    assertEquals(1, recordDelta());
    assertEquals(4, reuseDelta());
    // After scrolling up by one, old logical row 1 becomes new logical row 0.
    assertSame(oldRow1, cache.nodeForTest(0));
    assertNotSame(oldRow0, cache.nodeForTest(0));
  }

  @Test
  public void threeRowScrollReusesRemainingRows() {
    RemoteTerminalModel model = modelWithScreen(5, 10);
    RenderUpdate first = initialUpdate(model);
    TerminalRowRenderNodeCache cache = newCache();
    prepare(cache, first, 1, 1, 1);
    TerminalRowNode oldRow3 = cache.nodeForTest(3);

    RenderUpdate scroll = applyScrollUp(model, 5, 10, 3);
    baselineMetrics = TerminalRenderMetrics.snapshot();
    int recorded = prepare(cache, scroll, 1, 1, 1);
    assertEquals(3, recorded);
    assertEquals(3, recordDelta());
    assertEquals(2, reuseDelta());
    assertSame(oldRow3, cache.nodeForTest(0));
  }

  @Test
  public void scrollWithRetainedLineVersionBumpRecordsChangedRow() {
    RemoteTerminalModel model = modelWithScreen(5, 10);
    RenderUpdate first = initialUpdate(model);
    TerminalRowRenderNodeCache cache = newCache();
    prepare(cache, first, 1, 1, 1);

    // 向上滚动 1 行，底部暴露 id=6；保留行 id=3（位移后落在 row 1）version 升高。
    ScreenPatchV2 patch = new ScreenPatchV2(
        INSTANCE, LAYOUT_EPOCH, STREAM_GENERATION, 1L, 2L,
        new long[] {2, 3, 4, 5, 6}, Arrays.asList(line(3L, 2, 10), line(6L, 1, 10)),
        null, null, null, null, null, null);
    try {
      model.applyScreenPatch(patch);
    } catch (RemoteTerminalModel.RevisionGapException e) {
      throw new AssertionError(e);
    }
    RenderUpdate second = model.consumeRenderUpdate();
    baselineMetrics = TerminalRenderMetrics.snapshot();
    int recorded = prepare(cache, second, 1, 1, 1);
    // 滚动路径必须重录暴露行与 changed 保留行的并集。
    assertEquals(2, recorded);
    assertEquals(2, recordDelta());
    assertEquals(3, reuseDelta());
    assertEquals(2, ((FakeNode) cache.nodeForTest(1)).setPositionCount);
    assertEquals(2, ((FakeNode) cache.nodeForTest(4)).setPositionCount);
    assertEquals(1, ((FakeNode) cache.nodeForTest(0)).setPositionCount);
  }

  @Test
  public void staleSlotVersionMismatchFallsBackToDirectDraw() {
    RemoteTerminalModel model = modelWithScreen(5, 10);
    RenderUpdate first = initialUpdate(model);
    TerminalRowRenderNodeCache cache = newCache();
    prepare(cache, first, 1, 1, 1);

    // 行 id=3 version 升高；用空脏区模拟模型层漏标脏，缓存不重录任何行。
    ScreenPatchV2 patch = new ScreenPatchV2(
        INSTANCE, LAYOUT_EPOCH, STREAM_GENERATION, 1L, 2L,
        new long[] {1, 2, 3, 4, 5}, Collections.singletonList(line(3L, 2, 10)),
        null, null, null, null, null, null);
    try {
      model.applyScreenPatch(patch);
    } catch (RemoteTerminalModel.RevisionGapException e) {
      throw new AssertionError(e);
    }
    RenderUpdate second = model.consumeRenderUpdate();
    cache.prepareFrame(second.snapshot, new RenderDirtyState(), renderer, palette,
        canvasBackground, 1, 1, 1);

    Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    long staleBefore = TerminalRenderMetrics.snapshot().rowCacheStaleFallbackCount;
    // 未变化行（version 一致）仍命中缓存。
    assertTrue(cache.drawRow(canvas, 0, 0f, second.snapshot.screen[0]));
    assertEquals(1, ((FakeNode) cache.nodeForTest(0)).drawCount);
    // version 不一致的行兑底校验失败：返回 false 回退直接绘制，并计入 metrics。
    assertFalse(cache.drawRow(canvas, 2, 0f, second.snapshot.screen[2]));
    assertEquals(0, ((FakeNode) cache.nodeForTest(2)).drawCount);
    assertEquals(staleBefore + 1,
        TerminalRenderMetrics.snapshot().rowCacheStaleFallbackCount);
  }

  @Test
  public void fontGenerationChangeRecordsAllRows() {
    RemoteTerminalModel model = modelWithScreen(5, 10);
    RenderUpdate first = initialUpdate(model);
    TerminalRowRenderNodeCache cache = newCache();
    prepare(cache, first, 1, 1, 1);

    RenderDirtyState dirty = new RenderDirtyState();
    baselineMetrics = TerminalRenderMetrics.snapshot();
    int recorded = cache.prepareFrame(first.snapshot, dirty, renderer, palette, canvasBackground,
        2, 1, 1);
    assertEquals(5, recorded);
    assertEquals(5, recordDelta());
  }

  @Test
  public void geometryChangeRecordsAllRows() {
    RemoteTerminalModel model = modelWithScreen(5, 10);
    RenderUpdate first = initialUpdate(model);
    TerminalRowRenderNodeCache cache = newCache();
    prepare(cache, first, 1, 1, 1);

    ScreenBaseline baseline = new ScreenBaseline(
        "session-1", INSTANCE, LAYOUT_EPOCH + 1, 2L, STREAM_GENERATION,
        6, 10, TerminalBufferKind.MAIN, HistoryExtent.INITIAL_EMPTY,
        Collections.emptyList(), screenLines(new long[] {1, 2, 3, 4, 5, 6}, 1, 10),
        TerminalCursor.hidden(), TerminalModes.defaults(), palette, "", "");
    model.applyBaseline(baseline);
    RenderUpdate second = model.consumeRenderUpdate();
    baselineMetrics = TerminalRenderMetrics.snapshot();
    int recorded = prepare(cache, second, 1, 1, 1);
    assertEquals(6, recorded);
    assertEquals(6, recordDelta());
  }

  @Test
  public void cursorChangeDoesNotRecordRows() {
    RemoteTerminalModel model = modelWithScreen(5, 10);
    RenderUpdate first = initialUpdate(model);
    TerminalRowRenderNodeCache cache = newCache();
    prepare(cache, first, 1, 1, 1);

    RenderDirtyState dirty = new RenderDirtyState();
    dirty.cursorChanged = true;
    dirty.currentCursorRow = 2;
    baselineMetrics = TerminalRenderMetrics.snapshot();
    int recorded = cache.prepareFrame(first.snapshot, dirty, renderer, palette, canvasBackground,
        1, 1, 1);
    assertEquals(0, recorded);
    assertEquals(0, recordDelta());
  }

  @Test
  public void rowsAboveMaxDisablesCache() {
    RemoteTerminalModel model = modelWithScreen(513, 10);
    RenderUpdate update = initialUpdate(model);
    TerminalRowRenderNodeCache cache = newCache();
    prepare(cache, update, 1, 1, 1);
    // 超过槽位上限直接禁用缓存：rowCount 为 0，与屏幕行数不等，
    // 消费方走直接绘制回退，而不是截断到 MAX_ROWS 空转重录。
    assertEquals(0, cache.rowCount());
  }

  @Test
  public void selectionChangeDoesNotRecordRows() {
    RemoteTerminalModel model = modelWithScreen(5, 10);
    RenderUpdate first = initialUpdate(model);
    TerminalRowRenderNodeCache cache = newCache();
    prepare(cache, first, 1, 1, 1);

    RenderDirtyState dirty = new RenderDirtyState();
    baselineMetrics = TerminalRenderMetrics.snapshot();
    int recorded = cache.prepareFrame(first.snapshot, dirty, renderer, palette, canvasBackground,
        1, 1, 1);
    assertEquals(0, recorded);
    assertEquals(0, recordDelta());
  }

  static final class FakeNode implements TerminalRowNode {
    final String name;
    int setPositionCount;
    int beginRecordingCount;
    int endRecordingCount;
    int drawCount;
    Canvas lastCanvas;

    FakeNode(String name) {
      this.name = name;
    }

    @Override
    public void setPosition(int left, int top, int right, int bottom) {
      setPositionCount++;
    }

    @Override
    public Canvas beginRecording(int width, int height) {
      beginRecordingCount++;
      lastCanvas = new Canvas(Bitmap.createBitmap(Math.max(1, width), Math.max(1, height),
          Bitmap.Config.ARGB_8888));
      return lastCanvas;
    }

    @Override
    public void endRecording() {
      endRecordingCount++;
    }

    @Override
    public void draw(Canvas canvas, float y) {
      drawCount++;
    }
  }
}
