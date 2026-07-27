package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
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
    assertEquals(Long.valueOf(100), axis.rowOfLineId(10));
  }

  @Test
  public void promotionKeepsLineIdentityWhileMovingFromActiveRowsToHistoryIndex()
      throws Exception {
    RemoteTerminalModel model = model();
    TerminalLine before = model.lineStore().line(10);
    assertTrue(model.applyTerminalCommit(new TerminalCommit(
        "i1", 1, 1, 2, 1, 1, DictionaryEntries.EMPTY, null,
        new ScreenMutation(new ScreenScroll(0, 2, 1),
            Collections.singletonList(new ScreenRowWrite(
                1, V2ModelTestData.line(12, 1, 0, "c")))),
        HistoryMutation.fromLineData(new HistoryExtent(1, 101), Collections.emptyList(),
            Collections.singletonList(new HistoryPromotion(10, 1, 101)),
            100),
        null, null, null)));

    assertEquals(Long.valueOf(10), model.historyIndex().lineId(101));
    assertSame(before, model.lineStore().line(10));
    assertEquals(Long.valueOf(100), model.renderSnapshot().contentAxis.rowOfLineId(10));
    assertNotNull(model.renderSnapshot().contentAxis.itemAtRow(100).line);
  }

  private static RemoteTerminalModel model() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 1, false,
        DictionaryEntries.EMPTY, 2, 1, TerminalBufferKind.MAIN,
        new HistoryExtent(1, 100),
        Arrays.asList(
            V2ModelTestData.line(1, 1, 1, "h1"),
            V2ModelTestData.line(2, 1, 2, "h2")),
        Arrays.asList(
            V2ModelTestData.line(10, 1, 0, "a"),
            V2ModelTestData.line(11, 1, 0, "b")),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults())));
    return model;
  }
}
