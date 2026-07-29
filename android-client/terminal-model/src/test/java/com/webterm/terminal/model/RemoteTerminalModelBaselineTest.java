package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;

public final class RemoteTerminalModelBaselineTest {
  @Test
  public void baselineAtomicallyBuildsSparseProjection() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(V2ModelTestData.baseline(1, 1)));
    assertEquals(new HistoryExtent(1, 300), model.displayExtent());
    assertEquals(-1, model.firstCachedHistorySeq());
    assertEquals("a", model.renderSnapshot().screen[0].at(0).text);
  }

  @Test
  public void rejectedBaselineDoesNotPartiallyReplaceProjection() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(V2ModelTestData.baseline(1, 1)));
    ScreenBaseline invalid = new ScreenBaseline(
        "s1", "i2", 2, 2, 2, 2, 1, 1,
        TerminalBufferKind.MAIN, new HistoryExtent(1, 1),
        Collections.emptyList(),
        Collections.singletonList(new ScreenLineContent(
            new LineKey(2000, 1),
            new LineBody(1, false, new CellValue[] {
                new CellValue("b", (byte) 1, null, null)
            }))),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults());
    try {
      model.applyBaseline(invalid);
    } catch (RuntimeException expected) {
      // expected
    }
    assertEquals("i1", model.instanceId);
    assertEquals("a", model.renderSnapshot().screen[0].at(0).text);
  }

  @Test
  public void everyBaselineClearsResidentHistoryAndPositionBindings() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(V2ModelTestData.baseline(1, 1)));
    assertTrue(model.applyHistoryRange(new HistoryRangeResult(
        "r", "i1", 1, 1, HistoryRangeResult.Status.OK,
        new HistoryExtent(1, 300),
        Collections.singletonList(V2ModelTestData.line(173, 1, 173, "h")), 0),
        173, 173, 173));
    assertEquals(new HistoryLineRef(173, 1), model.historyIndex().ref(173));

    ScreenBaseline preserve = new ScreenBaseline(
        "s1", "i1", 2, 2, 1, 1,
        DictionaryEntries.EMPTY, 1, 1, TerminalBufferKind.MAIN,
        new HistoryExtent(1, 300),
        Collections.singletonList(V2ModelTestData.line(2000, 2, 0, "new")),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults());
    assertTrue(model.applyBaseline(preserve));

    PagedTerminalHistorySnapshot after =
        (PagedTerminalHistorySnapshot) model.renderSnapshot().history;
    assertNull(after.lineBySeq(173));
    assertNull(model.historyIndex().ref(173));
    assertEquals(new HistoryExtent(1, 300), model.historyIndex().extent());
    assertEquals("new", model.renderSnapshot().screen[0].at(0).text);
  }

}
