package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class UnifiedContentAxisTest {
  @Test
  public void loadedHistoryMissingRangeAndActiveRowsShareOneOrderedAxis() {
    RemoteTerminalModel model = model();
    UnifiedContentAxis axis = model.renderSnapshot().contentAxis;

    assertEquals(102, axis.rowCount());
    assertEquals(UnifiedContentAxis.Kind.LOADED_LINE, axis.itemAtRow(0).kind);
    assertEquals(UnifiedContentAxis.Kind.LOADED_LINE, axis.itemAtRow(1).kind);
    assertEquals(UnifiedContentAxis.Kind.MISSING_HISTORY_RANGE, axis.itemAtRow(2).kind);
    assertEquals(3, axis.itemAtRow(2).fromHistorySeq);
    assertEquals(100, axis.itemAtRow(99).toHistorySeq);
    assertEquals(UnifiedContentAxis.Kind.ACTIVE_LINE, axis.itemAtRow(100).kind);
    assertEquals(Long.valueOf(100), axis.rowOfLineKey(new LineKey(10, 1)));
  }

  @Test
  public void pushMovesTheSameLineBodyFromScreenToHistory() throws Exception {
    RemoteTerminalModel model = model();
    LineBody before = model.bodyCache().body(new LineKey(10, 1));
    assertTrue(model.applyTerminalCommit(SemanticTestData.commitLegacy(
        "i1", 1, 1, 2, 1, 1, TerminalBufferKind.MAIN,
        SemanticTestData.upserts(SemanticTestData.screen(12, 1, "c")),
        new ScreenMutation(new ScreenScroll(0, 2, 1),
            Collections.singletonList(ScreenRowWrite.fromLine(
                1, SemanticTestData.screen(12, 1, "c")))),
        new HistoryMutation(new HistoryExtent(1, 101),
            Collections.singletonList(
                new HistoryPush(101, new LineKey(10, 1)))),
        null, null, null)));

    assertEquals(new LineKey(10, 1), model.historyCatalog().key(101));
    assertSame(before, model.bodyCache().body(new LineKey(10, 1)));
    assertEquals(Long.valueOf(100),
        model.renderSnapshot().contentAxis.rowOfLineKey(new LineKey(10, 1)));
    assertNotNull(model.renderSnapshot().contentAxis.itemAtRow(100).line);
  }

  private static RemoteTerminalModel model() {
    List<HistoryPush> bindings = new ArrayList<>();
    for (long seq = 1; seq <= 100; seq++) {
      bindings.add(new HistoryPush(seq, new LineKey(100 + seq, 1)));
    }
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(SemanticTestData.baselineLegacy(
        "s1", "i1", 1, 1, 1, 1,
        2, 1, TerminalBufferKind.MAIN,
        new HistoryExtent(1, 100), bindings,
        java.util.Arrays.asList(
            SemanticTestData.screen(10, 1, "a"),
            SemanticTestData.screen(11, 1, "b")),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults())));
    HistoryBodyResult result = model.applyHistoryBody(
        new HistoryRangeResult(
            "r", "i1", 1, 1, HistoryRangeResult.Status.OK,
            new HistoryExtent(1, 100),
            java.util.Arrays.asList(
                new HistoryBodyEntry(1, new LineKey(101, 1), SemanticTestData.body("h1")),
                new HistoryBodyEntry(2, new LineKey(102, 1), SemanticTestData.body("h2"))),
            0),
        new HistoryRequestContext(new ProjectionIdentity("i1", 1, 1), 1, 2, 1));
    assertTrue(result instanceof HistoryBodyResult.Applied);
    return model;
  }
}
