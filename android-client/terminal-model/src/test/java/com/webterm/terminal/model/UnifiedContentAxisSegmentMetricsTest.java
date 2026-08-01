package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class UnifiedContentAxisSegmentMetricsTest {
  @Test
  public void segmentMetricsCountAllVisitedItems() {
    Fixture fx = fixture(300);
    long beforeBuilds = TerminalRenderMetrics.historyAxisSegmentBuildCount();
    long beforePages = TerminalRenderMetrics.historyAxisSegmentPagesVisited();
    long beforeVisited = TerminalRenderMetrics.historyAxisSegmentItemsVisited();
    long beforeCreated = TerminalRenderMetrics.historyAxisSegmentItemsCreated();

    applyBodies(fx.model, 1, 10);

    assertEquals(1, TerminalRenderMetrics.historyAxisSegmentBuildCount() - beforeBuilds);
    long pagesDelta = TerminalRenderMetrics.historyAxisSegmentPagesVisited() - beforePages;
    long visitedDelta = TerminalRenderMetrics.historyAxisSegmentItemsVisited() - beforeVisited;
    long createdDelta = TerminalRenderMetrics.historyAxisSegmentItemsCreated() - beforeCreated;
    assertTrue("pagesVisited=" + pagesDelta, pagesDelta >= 1 && pagesDelta <= 2);
    assertTrue("itemsVisited=" + visitedDelta, visitedDelta > 0 && visitedDelta <= 256);
    assertTrue("itemsCreated=" + createdDelta, createdDelta > 0 && createdDelta <= 256);
  }

  @Test
  public void segmentRebuildCostScalesWithHistorySize() {
    int[] sizes = {1_000, 5_000, 10_000};
    for (int historyRows : sizes) {
      Fixture fx = fixture(historyRows);
      long beforeNanos = TerminalRenderMetrics.historyAxisSegmentBuildNanos();
      long beforeCreated = TerminalRenderMetrics.historyAxisSegmentItemsCreated();
      long beforePages = TerminalRenderMetrics.historyAxisSegmentPagesVisited();
      long beforeVisited = TerminalRenderMetrics.historyAxisSegmentItemsVisited();
      long beforeBuilds = TerminalRenderMetrics.historyAxisSegmentBuildCount();

      long wallStart = System.nanoTime();
      applyBodies(fx.model, 500, 599);
      long wallNanos = System.nanoTime() - wallStart;

      long builds = TerminalRenderMetrics.historyAxisSegmentBuildCount() - beforeBuilds;
      long nanos = TerminalRenderMetrics.historyAxisSegmentBuildNanos() - beforeNanos;
      long created = TerminalRenderMetrics.historyAxisSegmentItemsCreated() - beforeCreated;
      long pages = TerminalRenderMetrics.historyAxisSegmentPagesVisited() - beforePages;
      long visited = TerminalRenderMetrics.historyAxisSegmentItemsVisited() - beforeVisited;

      assertEquals(1, builds);
      assertTrue("pages for " + historyRows, pages > 0 && pages <= 2);
      assertTrue("visited for " + historyRows, visited > 0 && visited <= 256);
      assertTrue("created for " + historyRows, created > 0 && created <= 256);
      // 观测用途：打印到 stdout 供本地对比，不设硬超时。
      System.out.println(
          "historyAxisSegments historyRows=" + historyRows
              + " wallMs=" + (wallNanos / 1_000_000.0)
              + " buildMs=" + (nanos / 1_000_000.0)
              + " pagesVisited=" + pages
              + " itemsVisited=" + visited
              + " itemsCreated=" + created);
    }
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

  private static Fixture fixture(int historyRows) {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline(historyRows, 1)));
    model.consumeRenderUpdate();
    applyBodies(model, 1, historyRows);
    model.consumeRenderUpdate();
    return new Fixture(model);
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

  private record Fixture(RemoteTerminalModel model) {}
}
