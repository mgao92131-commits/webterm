package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class RemoteTerminalSemanticProjectionTest {
  @Test
  public void productionBaselineAndRangePublishFromCatalogAndBodyCache() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline()));
    assertTrue(model.renderSnapshot().history instanceof SemanticHistoryRenderView);
    // Baseline 仅携带屏幕正文；冷历史绑定无正文。
    assertEquals(1, model.bodyCache().bodyCount());
    assertNull(model.bodyCache().body(new LineKey(101, 1)));
    assertEquals(SlotState.UNLOADED,
        model.renderSnapshot().history.slotStateAt(0));

    HistoryBodyResult result = model.applyHistoryBody(
        range(1, 101, "history"),
        request(1, 1));

    assertTrue(result instanceof HistoryBodyResult.Applied);
    int index = model.renderSnapshot().history.findSeqIndex(1);
    assertEquals(
        "history",
        model.renderSnapshot().history.renderLineAt(index).at(0).text());
    assertEquals(2, model.bodyCache().bodyCount());
    assertEquals(new LineKey(101, 1), model.historyCatalog().key(1));
    assertEquals(new HistoryExtent(1, 2), model.projectionReadView().mainHistoryExtent);
  }

  @Test
  public void staleHttpBodyCannotReplaceWsAuthoritativeRebind() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline()));
    assertTrue(model.applyTerminalCommit(SemanticTestData.commitLegacy(
        "i1", 1, 1, 2, 1, 1, TerminalBufferKind.MAIN,
        SemanticTestData.upserts(new ScreenLineContent(
            new LineKey(201, 1), body("ws"))),
        null,
        new HistoryMutation(
            new HistoryExtent(1, 2),
            Collections.singletonList(new HistoryPush(1, new LineKey(201, 1)))),
        null, null, null)));

    HistoryBodyResult stale = model.applyHistoryBody(
        range(1, 101, "old"),
        request(1, 1));

    assertTrue(stale instanceof HistoryBodyResult.StaleIgnored);
    assertEquals(
        SlotState.LOADED,
        model.renderSnapshot().history.slotStateAt(0));
  }

  @Test
  public void historyBodyPreservesItsOwnPhysicalColumns() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline(80)));
    CellValue[] cells = new CellValue[200];
    Arrays.fill(cells, CellValue.EMPTY);
    cells[199] = new CellValue("tail", (byte) 1, null, null);
    LineKey key = new LineKey(101, 1);

    HistoryBodyResult result = model.applyHistoryBody(
        new HistoryRangeResult(
            "r", "i1", 1, 1, HistoryRangeResult.Status.OK,
            new HistoryExtent(1, 2),
            Collections.singletonList(new HistoryBodyEntry(
                1, key, new LineBody(200, false, cells))),
            0),
        request(1, 1));

    assertTrue(result instanceof HistoryBodyResult.Applied);
    assertEquals(200, model.bodyCache().body(key).physicalColumns);
    assertEquals(200, model.renderSnapshot().history.renderLineAt(0).length());
    assertEquals("tail", model.renderSnapshot().history.renderLineAt(0).at(199).text());
  }

  @Test
  public void baselineAlwaysClearsOldHistoryCatalogAndBodies() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline()));
    assertTrue(model.applyHistoryBody(range(1, 101, "history"), request(1, 1))
        instanceof HistoryBodyResult.Applied);
    CellValue[] replacementCells = new CellValue[80];
    Arrays.fill(replacementCells, CellValue.EMPTY);
    replacementCells[0] = new CellValue("replacement", (byte) 1, null, null);

    assertTrue(model.applyBaseline(SemanticTestData.baselineLegacy(
        "s1", "i1", 1, 9, 1, 1, 1, 80,
        TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY,
        Collections.emptyList(),
        Collections.singletonList(new ScreenLineContent(
            new LineKey(9000, 1), new LineBody(80, false, replacementCells))),
        TerminalCursor.hidden(),
        TerminalModes.defaults(),
        TerminalPalette.defaults())));

    assertNull(model.historyCatalog().key(1));
    assertNull(model.bodyCache().body(new LineKey(101, 1)));
    assertEquals(1, model.bodyCache().bodyCount());
    assertEquals(HistoryExtent.INITIAL_EMPTY, model.historyCatalog().extent());
  }

  @Test
  public void commitAppliesFiveThousandHistoryPushesWithoutBatchLimit() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(SemanticTestData.baselineLegacy(
        "s1", "i1", 1, 1, 1, 1, 1, 1,
        TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY,
        Collections.emptyList(),
        Collections.singletonList(new ScreenLineContent(
            new LineKey(9000, 1), body("screen"))),
        TerminalCursor.hidden(),
        TerminalModes.defaults(),
        TerminalPalette.defaults())));
    List<HistoryPush> pushes = new ArrayList<>(5000);
    List<LineBodyRecord> upserts = new ArrayList<>(5000);
    for (int seq = 1; seq <= 5000; seq++) {
      LineKey key = new LineKey(10_000L + seq, 1);
      pushes.add(new HistoryPush(seq, key));
      upserts.add(new LineBodyRecord(key, body("h")));
    }

    assertTrue(model.applyTerminalCommit(SemanticTestData.commitLegacy(
        "i1", 1, 1, 2, 1, 1, TerminalBufferKind.MAIN, upserts, null,
        new HistoryMutation(new HistoryExtent(1, 5000), pushes),
        null, null, null)));

    assertEquals(5000, model.historyCatalog().extent().logicalSize());
    assertEquals(new LineKey(15_000, 1), model.historyCatalog().key(5000));
  }

  @Test
  public void rangeAppliesFiveThousandBodiesWithoutPageOrByteLimit() {
    HistoryBudget generous = new HistoryBudget(
        6000, 6000, 16L << 20, 16L << 20);
    RemoteTerminalModel model = new RemoteTerminalModel(generous);
    List<HistoryPush> bindings = new ArrayList<>(5000);
    List<HistoryBodyEntry> bodies = new ArrayList<>(5000);
    for (int seq = 1; seq <= 5000; seq++) {
      LineKey key = new LineKey(20_000L + seq, 1);
      bindings.add(new HistoryPush(seq, key));
      bodies.add(new HistoryBodyEntry(seq, key, body("x")));
    }
    assertTrue(model.applyBaseline(SemanticTestData.baselineLegacy(
        "s1", "i1", 1, 1, 1, 1, 1, 1,
        TerminalBufferKind.MAIN,
        new HistoryExtent(1, 5000), bindings,
        Collections.singletonList(new ScreenLineContent(
            new LineKey(9000, 1), body("screen"))),
        TerminalCursor.hidden(),
        TerminalModes.defaults(),
        TerminalPalette.defaults())));

    HistoryBodyResult result = model.applyHistoryBody(
        new HistoryRangeResult(
            "large", "i1", 1, 1, HistoryRangeResult.Status.OK,
            new HistoryExtent(1, 5000), bodies, 0),
        request(1, 5000));

    assertTrue(result instanceof HistoryBodyResult.Applied);
    assertEquals(5000, ((HistoryBodyResult.Applied) result).appliedLineCount());
    assertEquals(5000, model.bodyCache().loadedHistoryCount());
  }

  private static ScreenBaseline baseline() {
    return baseline(1);
  }

  private static ScreenBaseline baseline(int columns) {
    CellValue[] screenCells = new CellValue[columns];
    Arrays.fill(screenCells, CellValue.EMPTY);
    screenCells[0] = new CellValue("screen", (byte) 1, null, null);
    return SemanticTestData.baselineLegacy(
        "s1", "i1", 1, 1, 1, 1, 1, columns,
        TerminalBufferKind.MAIN,
        new HistoryExtent(1, 2),
        Arrays.asList(
            new HistoryPush(1, new LineKey(101, 1)),
            new HistoryPush(2, new LineKey(102, 1))),
        Collections.singletonList(new ScreenLineContent(
            new LineKey(1000, 1),
            new LineBody(columns, false, screenCells))),
        TerminalCursor.hidden(),
        TerminalModes.defaults(),
        TerminalPalette.defaults());
  }

  private static HistoryRangeResult range(long seq, long lineId, String text) {
    return new HistoryRangeResult(
        "r", "i1", 1, 1, HistoryRangeResult.Status.OK,
        new HistoryExtent(1, 2),
        Collections.singletonList(new HistoryBodyEntry(
            seq, new LineKey(lineId, 1), body(text))),
        0);
  }

  private static HistoryRequestContext request(long from, long to) {
    return new HistoryRequestContext(
        new ProjectionIdentity("i1", 1, 1), from, to, from);
  }

  private static LineBody body(String text) {
    return new LineBody(1, false, new CellValue[] {
        new CellValue(text, (byte) 1, null, null)
    });
  }
}
