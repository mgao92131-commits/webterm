package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.BitSet;

public final class RemoteTerminalModelScrollDetectionTest {

  private static TerminalLine line(long id, int cols) {
    TerminalCell[] cells = new TerminalCell[cols];
    Arrays.fill(cells, TerminalCell.EMPTY);
    return new TerminalLine(id, false, cells);
  }

  private static ScreenPatchV2 patch(long[] layout, long baseRevision, long screenRevision,
                                     int columns) {
    java.util.List<TerminalLine> updates = new java.util.ArrayList<>();
    for (long id : layout) {
      updates.add(line(id, columns));
    }
    return new ScreenPatchV2(
        "term-1", 1L, 1L, baseRevision, screenRevision, layout, updates,
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(),
        TerminalBufferKind.MAIN, "", "");
  }

  @Test
  public void scrollUpOneRowMarksOnlyExposedRow() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    ScreenBaseline baseline = new ScreenBaseline(
        "session-1", "term-1", 1L, 1L, 1L, 5, 10, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY, Arrays.asList(),
        Arrays.asList(line(1, 10), line(2, 10), line(3, 10), line(4, 10), line(5, 10)),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(),
        "", "");
    assertTrue(model.applyBaseline(baseline));
    model.consumeRenderUpdate(); // 消费 Baseline 的 fullInvalidate，避免与后续 Patch 合并

    // 新 layout：整体向上滚动一行，底部暴露 id=6。
    model.applyScreenPatch(patch(new long[]{2, 3, 4, 5, 6}, 1L, 2L, 10));

    RenderUpdate update = model.consumeRenderUpdate();
    assertEquals(1, update.dirty.screenScrollRows);
    BitSet expected = new BitSet();
    expected.set(4);
    assertEquals(expected, update.dirty.changedScreenRows);
    assertEquals(expected, update.dirty.exposedScreenRows);
  }

  @Test
  public void scrollUpThreeRowsMarksBottomThreeRows() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    ScreenBaseline baseline = new ScreenBaseline(
        "session-1", "term-1", 1L, 1L, 1L, 5, 10, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY, Arrays.asList(),
        Arrays.asList(line(1, 10), line(2, 10), line(3, 10), line(4, 10), line(5, 10)),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(),
        "", "");
    assertTrue(model.applyBaseline(baseline));
    model.consumeRenderUpdate();

    model.applyScreenPatch(patch(new long[]{4, 5, 6, 7, 8}, 1L, 2L, 10));

    RenderUpdate update = model.consumeRenderUpdate();
    assertEquals(3, update.dirty.screenScrollRows);
    BitSet expected = new BitSet();
    expected.set(2);
    expected.set(3);
    expected.set(4);
    assertEquals(expected, update.dirty.changedScreenRows);
    assertEquals(expected, update.dirty.exposedScreenRows);
  }

  @Test
  public void middleRowContentChangeDoesNotScroll() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    ScreenBaseline baseline = new ScreenBaseline(
        "session-1", "term-1", 1L, 1L, 1L, 5, 10, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY, Arrays.asList(),
        Arrays.asList(line(1, 10), line(2, 10), line(3, 10), line(4, 10), line(5, 10)),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(),
        "", "");
    assertTrue(model.applyBaseline(baseline));
    model.consumeRenderUpdate();

    // 第 2 行（索引 2）内容变化：id 不变，version 升高。
    TerminalCell[] cells = new TerminalCell[10];
    Arrays.fill(cells, TerminalCell.EMPTY);
    TerminalLine updatedLine = new TerminalLine(3L, 2L, false, cells);
    long[] layout = new long[]{1, 2, 3, 4, 5};
    ScreenPatchV2 patch = new ScreenPatchV2(
        "term-1", 1L, 1L, 1L, 2L, layout, Arrays.asList(updatedLine),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(),
        TerminalBufferKind.MAIN, "", "");
    model.applyScreenPatch(patch);

    RenderUpdate update = model.consumeRenderUpdate();
    assertEquals(0, update.dirty.screenScrollRows);
    BitSet expected = new BitSet();
    expected.set(2);
    assertEquals(expected, update.dirty.changedScreenRows);
    assertTrue(update.dirty.exposedScreenRows.isEmpty());
  }

  @Test
  public void scrollUpWithRetainedLineVersionBumpMarksThatRow() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    ScreenBaseline baseline = new ScreenBaseline(
        "session-1", "term-1", 1L, 1L, 1L, 5, 10, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY, Arrays.asList(),
        Arrays.asList(line(1, 10), line(2, 10), line(3, 10), line(4, 10), line(5, 10)),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(),
        "", "");
    assertTrue(model.applyBaseline(baseline));
    model.consumeRenderUpdate();

    // 同一 Patch 同时携带位移后的 layout 与保留行（id=3，version 升高）的 lineUpdates。
    TerminalCell[] cells = new TerminalCell[10];
    Arrays.fill(cells, TerminalCell.EMPTY);
    ScreenPatchV2 patch = new ScreenPatchV2(
        "term-1", 1L, 1L, 1L, 2L,
        new long[]{2, 3, 4, 5, 6},
        Arrays.asList(new TerminalLine(3L, 2L, false, cells), line(6, 10)),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(),
        TerminalBufferKind.MAIN, "", "");
    model.applyScreenPatch(patch);

    RenderUpdate update = model.consumeRenderUpdate();
    assertEquals(1, update.dirty.screenScrollRows);
    BitSet expectedExposed = new BitSet();
    expectedExposed.set(4);
    assertEquals(expectedExposed, update.dirty.exposedScreenRows);
    // 保留行 id=3 位移后落在 row 1，version 升高必须标脏，否则行缓存复用旧录制。
    BitSet expectedChanged = new BitSet();
    expectedChanged.set(1);
    expectedChanged.set(4);
    assertEquals(expectedChanged, update.dirty.changedScreenRows);
    assertEquals(2L, update.snapshot.screen[1].version);
  }

  @Test
  public void scrollDownWithRetainedLineVersionBumpMarksThatRow() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    ScreenBaseline baseline = new ScreenBaseline(
        "session-1", "term-1", 1L, 1L, 1L, 5, 10, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY, Arrays.asList(),
        Arrays.asList(line(1, 10), line(2, 10), line(3, 10), line(4, 10), line(5, 10)),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(),
        "", "");
    assertTrue(model.applyBaseline(baseline));
    model.consumeRenderUpdate();

    // 向下滚动一行，顶部暴露 id=6；保留行 id=2 位移后落在 row 2 且 version 升高。
    TerminalCell[] cells = new TerminalCell[10];
    Arrays.fill(cells, TerminalCell.EMPTY);
    ScreenPatchV2 patch = new ScreenPatchV2(
        "term-1", 1L, 1L, 1L, 2L,
        new long[]{6, 1, 2, 3, 4},
        Arrays.asList(new TerminalLine(2L, 2L, false, cells), line(6, 10)),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(),
        TerminalBufferKind.MAIN, "", "");
    model.applyScreenPatch(patch);

    RenderUpdate update = model.consumeRenderUpdate();
    assertEquals(-1, update.dirty.screenScrollRows);
    BitSet expectedExposed = new BitSet();
    expectedExposed.set(0);
    assertEquals(expectedExposed, update.dirty.exposedScreenRows);
    BitSet expectedChanged = new BitSet();
    expectedChanged.set(0);
    expectedChanged.set(2);
    assertEquals(expectedChanged, update.dirty.changedScreenRows);
    assertEquals(2L, update.snapshot.screen[2].version);
  }

  @Test
  public void unrelatedLayoutFallsBackToRowDiff() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    ScreenBaseline baseline = new ScreenBaseline(
        "session-1", "term-1", 1L, 1L, 1L, 5, 10, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY, Arrays.asList(),
        Arrays.asList(line(1, 10), line(2, 10), line(3, 10), line(4, 10), line(5, 10)),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(),
        "", "");
    assertTrue(model.applyBaseline(baseline));
    model.consumeRenderUpdate();

    // 完全不相关的 layout：不是任何连续位移。
    model.applyScreenPatch(patch(new long[]{9, 8, 7, 6, 5}, 1L, 2L, 10));

    RenderUpdate update = model.consumeRenderUpdate();
    assertEquals(0, update.dirty.screenScrollRows);
    assertFalse(update.dirty.changedScreenRows.isEmpty());
  }

  @Test
  public void activeBufferChangeClearsScrollAndForcesFullInvalidate() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    ScreenBaseline baseline = new ScreenBaseline(
        "session-1", "term-1", 1L, 1L, 1L, 5, 10, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY, Arrays.asList(),
        Arrays.asList(line(1, 10), line(2, 10), line(3, 10), line(4, 10), line(5, 10)),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(),
        "", "");
    assertTrue(model.applyBaseline(baseline));
    model.consumeRenderUpdate();

    ScreenPatchV2 patch = new ScreenPatchV2(
        "term-1", 1L, 1L, 1L, 2L,
        new long[]{2, 3, 4, 5, 6}, Arrays.asList(line(6, 10)),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(),
        TerminalBufferKind.ALTERNATE, "", "");
    model.applyScreenPatch(patch);

    RenderUpdate update = model.consumeRenderUpdate();
    assertTrue(update.dirty.fullInvalidate);
    assertTrue(update.dirty.activeBufferChanged);
    assertEquals(0, update.dirty.screenScrollRows);
  }
}
