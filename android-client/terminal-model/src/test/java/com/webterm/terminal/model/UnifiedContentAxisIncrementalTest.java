package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import org.junit.Test;

public final class UnifiedContentAxisIncrementalTest {
  @Test
  public void loadingOnePageRebuildsOnlyOnePage() throws Exception {
    Fixture fx = fixture(300);
    HistoryAxisPage before = fx.axis.pageForTest(0);
    assertNotNull(before);

    applyBodies(fx.model, 1, 50);
    UnifiedContentAxis after = fx.model.renderSnapshot().contentAxis;
    assertNotSame(before, after.pageForTest(0));
    assertSame(fx.axis.pageForTest(1), after.pageForTest(1));
  }

  @Test
  public void loadingRangeAcrossTwoPagesRebuildsTwoPages() throws Exception {
    Fixture fx = fixture(300);
    HistoryAxisPage page0 = fx.axis.pageForTest(0);
    HistoryAxisPage page1 = fx.axis.pageForTest(1);
    HistoryAxisPage page2 = fx.axis.pageForTest(2);

    applyBodies(fx.model, 120, 140);
    UnifiedContentAxis after = fx.model.renderSnapshot().contentAxis;
    assertNotSame(page0, after.pageForTest(0));
    assertNotSame(page1, after.pageForTest(1));
    assertSame(page2, after.pageForTest(2));
  }

  @Test
  public void unchangedPagesRetainObjectIdentity() throws Exception {
    Fixture fx = fixture(400);
    HistoryAxisPage page2 = fx.axis.pageForTest(2);
    applyBodies(fx.model, 1, 10);
    assertSame(page2, fx.model.renderSnapshot().contentAxis.pageForTest(2));
  }

  @Test
  public void tenThousandRowHistoryLoadsOneHundredRowsIncrementally() throws Exception {
    Fixture fx = fixture(10_000);
    long beforeScanned = TerminalRenderMetrics.historyAxisRowsScanned();
    applyBodies(fx.model, 5000, 5099);
    long scanned = TerminalRenderMetrics.historyAxisRowsScanned() - beforeScanned;
    assertTrue("scanned=" + scanned, scanned > 0 && scanned <= 256);
  }

  @Test
  public void appendOneLineSamePageDoesNotRebuildWholeHistory() throws Exception {
    Fixture fx = fixture(300);
    HistoryAxisPage page0 = fx.axis.pageForTest(0);
    HistoryAxisPage page1 = fx.axis.pageForTest(1);
    long beforeFullRebuilds = TerminalRenderMetrics.historyAxisFullRebuildCount();
    long beforeScanned = TerminalRenderMetrics.historyAxisRowsScanned();

    assertTrue(fx.model.applyTerminalCommit(appendHistoryCommit(301)));
    fx.model.consumeRenderUpdate();

    UnifiedContentAxis after = fx.model.renderSnapshot().contentAxis;
    assertSame(page0, after.pageForTest(0));
    assertSame(page1, after.pageForTest(1));
    assertTrue("new tail page must be rebuilt", after.pageForTest(2) != fx.axis.pageForTest(2));
    assertEquals(0,
        TerminalRenderMetrics.historyAxisFullRebuildCount() - beforeFullRebuilds);
    assertTrue("tail append should scan at most two pages",
        TerminalRenderMetrics.historyAxisRowsScanned() - beforeScanned <= 256);
    assertEquals(301, after.historyRowCount());
  }

  @Test
  public void appendAcrossPageBoundaryKeepsExistingPages() throws Exception {
    Fixture fx = fixture(256);
    HistoryAxisPage page0 = fx.axis.pageForTest(0);
    HistoryAxisPage page1 = fx.axis.pageForTest(1);
    long beforeFullRebuilds = TerminalRenderMetrics.historyAxisFullRebuildCount();

    assertTrue(fx.model.applyTerminalCommit(appendHistoryCommit(257)));
    fx.model.consumeRenderUpdate();

    UnifiedContentAxis after = fx.model.renderSnapshot().contentAxis;
    assertSame(page0, after.pageForTest(0));
    assertSame(page1, after.pageForTest(1));
    assertNotNull(after.pageForTest(2));
    assertEquals(0,
        TerminalRenderMetrics.historyAxisFullRebuildCount() - beforeFullRebuilds);
    assertEquals(257, after.historyRowCount());
  }

  @Test
  public void evictionRangesKeepContentAxisResidencyConsistent() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel(1, 1);
    assertTrue(model.applyBaseline(baseline(256, 1)));
    model.consumeRenderUpdate();

    HistoryBodyResult result = model.applyHistoryBody(
        new HistoryRangeResult(
            "evict", "i1", 1, 1, HistoryRangeResult.Status.OK,
            new HistoryExtent(1, 256), allHistoryEntries(1, 256), 0),
        new HistoryRequestContext(
            new ProjectionIdentity("i1", 1, 1), 1, 256, 1));
    assertTrue(result instanceof HistoryBodyResult.Applied);
    HistoryBodyResult.Applied applied = (HistoryBodyResult.Applied) result;
    assertTrue("small budget must evict at least one page",
        !applied.evictedRanges().isEmpty());

    RemoteTerminalModel.RenderSnapshot snapshot = model.renderSnapshot();
    for (int index = 0; index < 256; index++) {
      SlotState state = snapshot.history.slotStateAt(index);
      UnifiedContentAxis.Item item = snapshot.contentAxis.itemAtRow(index);
      if (state == SlotState.LOADED) {
        assertEquals(UnifiedContentAxis.Kind.LOADED_LINE, item.kind);
      } else {
        assertEquals(UnifiedContentAxis.Kind.MISSING_HISTORY_RANGE, item.kind);
        assertTrue("evicted body must not remain in the axis", item.line == null);
      }
    }
  }

  @Test
  public void disjointBodyFillAndTailAppendRebuildOnlyAffectedPages() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline(512, 1)));
    model.consumeRenderUpdate();
    UnifiedContentAxis before = model.renderSnapshot().contentAxis;
    HistoryAxisPage page1 = before.pageForTest(1);
    HistoryAxisPage page2 = before.pageForTest(2);
    HistoryAxisPage page3 = before.pageForTest(3);

    QueueExecutor executor = new QueueExecutor();
    model.bindRenderPublicationExecutor(executor);
    assertTrue(model.applyHistoryBody(
        new HistoryRangeResult(
            "fill", "i1", 1, 1, HistoryRangeResult.Status.OK,
            new HistoryExtent(1, 512), allHistoryEntries(1, 10), 0),
        new HistoryRequestContext(
            new ProjectionIdentity("i1", 1, 1), 1, 10, 1))
        instanceof HistoryBodyResult.Applied);
    assertTrue(model.applyTerminalCommit(appendHistoryCommit(513)));
    assertEquals(1, executor.size());
    executor.runAll();

    UnifiedContentAxis after = model.renderSnapshot().contentAxis;
    assertSame(page1, after.pageForTest(1));
    assertSame(page2, after.pageForTest(2));
    assertSame(page3, after.pageForTest(3));
    assertEquals(513, after.historyRowCount());
    assertEquals(UnifiedContentAxis.Kind.LOADED_LINE, after.itemAtRow(0).kind);
    assertNotNull(after.pageForTest(4));
  }

  @Test
  public void generationChangeForcesFullHistoryRebuild() throws Exception {
    Fixture fx = fixture(200);
    long before = TerminalRenderMetrics.historyAxisFullRebuildCount();
    assertTrue(fx.model.applyBaseline(baseline(200, 2)));
    assertTrue(TerminalRenderMetrics.historyAxisFullRebuildCount() > before);
  }

  @Test
  public void rowOfLineIdUsesCatalogIndex() throws Exception {
    Fixture fx = fixture(50);
    assertEquals(Long.valueOf(0), fx.axis.rowOfLineId(900));
    assertEquals(Long.valueOf(49), fx.axis.rowOfLineId(949));
  }

  private static void applyBodies(RemoteTerminalModel model, long fromSeq, long toSeq) {
    List<HistoryBodyEntry> entries = new ArrayList<>();
    for (long seq = fromSeq; seq <= toSeq; seq++) {
      entries.add(new HistoryBodyEntry(
          seq, new LineKey(900 + seq - 1, 1), SemanticTestData.body("h" + seq)));
    }
    HistoryBodyResult result = model.applyHistoryBody(
        new HistoryRangeResult(
            "r", "i1", 1, 1, HistoryRangeResult.Status.OK,
            new HistoryExtent(1, Math.max(toSeq, 1)),
            entries,
            0),
        new HistoryRequestContext(new ProjectionIdentity("i1", 1, 1), fromSeq, toSeq, fromSeq));
    assertTrue(result instanceof HistoryBodyResult.Applied);
  }

  private static List<HistoryBodyEntry> allHistoryEntries(long fromSeq, long toSeq) {
    List<HistoryBodyEntry> entries = new ArrayList<>();
    for (long seq = fromSeq; seq <= toSeq; seq++) {
      entries.add(new HistoryBodyEntry(
          seq, new LineKey(900 + seq - 1, 1), SemanticTestData.body("h" + seq)));
    }
    return entries;
  }

  private static TerminalCommit appendHistoryCommit(long newSeq) {
    LineKey key = new LineKey(20_000 + newSeq, 1);
    return SemanticTestData.commitLegacy(
        "i1", 1, 1, 2, 1, 1, TerminalBufferKind.MAIN,
        List.of(new LineBodyRecord(key, SemanticTestData.body("append" + newSeq))),
        null,
        new HistoryMutation(
            new HistoryExtent(1, newSeq),
            List.of(new HistoryPush(newSeq, key))),
        null, null, null);
  }

  private static Fixture fixture(int historyRows) {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline(historyRows, 1)));
    model.consumeRenderUpdate();
    applyBodies(model, 1, historyRows);
    model.consumeRenderUpdate();
    return new Fixture(model, model.renderSnapshot().contentAxis);
  }

  private static ScreenBaseline baseline(int historyRows, long layoutEpoch) {
    List<HistoryPush> history = new ArrayList<>();
    for (long seq = 1; seq <= historyRows; seq++) {
      history.add(new HistoryPush(seq, new LineKey(900 + seq - 1, 1)));
    }
    List<ScreenLineContent> screen = new ArrayList<>();
    for (int row = 0; row < 4; row++) {
      screen.add(new ScreenLineContent(
          new LineKey(100 + row, 1),
          new LineBody(1, false, new CellValue[] {
              new CellValue("r" + row, (byte) 1, null, null)
          })));
    }
    return SemanticTestData.baselineLegacy(
        "s1", "i1", layoutEpoch, 1, 1, 1, 4, 1,
        TerminalBufferKind.MAIN,
        new HistoryExtent(1, historyRows),
        history,
        screen,
        TerminalCursor.hidden(),
        TerminalModes.defaults(),
        TerminalPalette.defaults());
  }

  private static final class Fixture {
    final RemoteTerminalModel model;
    final UnifiedContentAxis axis;
    Fixture(RemoteTerminalModel model, UnifiedContentAxis axis) {
      this.model = model;
      this.axis = axis;
    }
  }

  private static final class QueueExecutor implements Executor {
    private final Queue<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }

    int size() { return tasks.size(); }

    void runAll() {
      Runnable task;
      while ((task = tasks.poll()) != null) task.run();
    }
  }
}
