package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.Test;

public final class RemoteTerminalModelTerminalCommitTest {
  @Test
  public void historyPushReusesExactScreenBody() throws Exception {
    RemoteTerminalModel model = baseline();
    TerminalLine before = model.lineStore().line(10);

    assertTrue(model.applyTerminalCommit(scrollCommit(
        1, 2, 301, 10, 3, V2ModelTestData.line(12, 1, 0, "c"))));

    HistoryLineRef ref = model.historyIndex().ref(301);
    assertEquals(10, ref.lineId);
    assertEquals(3, ref.lineVersion);
    assertSame(before, model.lineStore().line(10));
    assertEquals(SlotState.LOADED, history(model).slotStateAt(300));
  }

  @Test
  public void historyPushWithoutBodyKeepsPositionBinding() throws Exception {
    RemoteTerminalModel model = baseline();

    assertTrue(model.applyTerminalCommit(scrollCommit(
        1, 2, 301, 99, 1, V2ModelTestData.line(12, 1, 0, "c"))));

    assertEquals(new HistoryLineRef(99, 1), model.historyIndex().ref(301));
    assertNull(model.lineStore().line(99));
    assertEquals(SlotState.UNLOADED, history(model).slotStateAt(300));
  }

  @Test
  public void mismatchedVersionCannotReuseOldScreenBody() throws Exception {
    RemoteTerminalModel model = baseline();

    assertTrue(model.applyTerminalCommit(scrollCommit(
        1, 2, 301, 10, 4, V2ModelTestData.line(12, 1, 0, "c"))));

    assertEquals(new HistoryLineRef(10, 4), model.historyIndex().ref(301));
    assertNull(model.lineStore().line(10));
    assertEquals(SlotState.UNLOADED, history(model).slotStateAt(300));
  }

  @Test
  public void rangeFillsOnlyMatchingHistoryReferenceAndWakesRender() throws Exception {
    RemoteTerminalModel model = baseline();
    model.applyTerminalCommit(scrollCommit(
        1, 2, 301, 99, 2, V2ModelTestData.line(12, 1, 0, "c")));
    model.consumeRenderUpdate();

    assertTrue(model.applyHistoryRange(range(
        V2ModelTestData.line(99, 2, 301, "body")), 301, 301, 301));
    assertEquals("body", history(model).lineBySeq(301).at(0).text);
    assertTrue(model.renderPublicationPendingForTest());

    assertFalse(model.applyHistoryRange(range(
        V2ModelTestData.line(100, 2, 301, "stale")), 301, 301, 301));
    assertEquals(99, model.historyIndex().ref(301).lineId);
    assertEquals("body", history(model).lineBySeq(301).at(0).text);
  }

  @Test
  public void sameReferenceAndVersionWithDifferentBodyIsRejected() throws Exception {
    RemoteTerminalModel model = baseline();
    model.applyTerminalCommit(scrollCommit(
        1, 2, 301, 99, 2, V2ModelTestData.line(12, 1, 0, "c")));
    assertTrue(model.applyHistoryRange(range(
        V2ModelTestData.line(99, 2, 301, "body")), 301, 301, 301));

    try {
      model.applyHistoryRange(range(
          V2ModelTestData.line(99, 2, 301, "changed")), 301, 301, 301);
      fail("same LineID/version with changed body accepted");
    } catch (IllegalArgumentException expected) {
      assertEquals("body", history(model).lineBySeq(301).at(0).text);
    }
  }

  @Test
  public void staleGenerationRangeCannotMutateModel() throws Exception {
    RemoteTerminalModel model = baseline();
    model.applyTerminalCommit(scrollCommit(
        1, 2, 301, 99, 2, V2ModelTestData.line(12, 1, 0, "c")));

    HistoryRangeResult stale = new HistoryRangeResult(
        "r", "i1", 1, 2, HistoryRangeResult.Status.OK,
        new HistoryExtent(1, 301),
        Collections.singletonList(V2ModelTestData.line(99, 2, 301, "late")), 0);
    assertFalse(model.applyHistoryRange(stale, 301, 301, 301));
    assertNull(model.lineStore().line(99));
  }

  @Test
  public void resizePopRemovesTailBindingBeforeLineReturnsToScreen() throws Exception {
    RemoteTerminalModel model = baseline();
    model.applyTerminalCommit(scrollCommit(
        1, 2, 301, 10, 3, V2ModelTestData.line(12, 1, 0, "c")));

    TerminalCommit pop = new TerminalCommit(
        "i1", 1, 2, 3, 1, 1, DictionaryEntries.EMPTY, null,
        new ScreenMutation(null, Collections.singletonList(
            new ScreenRowWrite(0, V2ModelTestData.line(10, 3, 0, "a")))),
        new HistoryMutation(new HistoryExtent(1, 300), Collections.emptyList()),
        null, null, null);
    assertTrue(model.applyTerminalCommit(pop));
    assertNull(model.historyIndex().ref(301));
    assertEquals(10, model.renderSnapshot().screen[0].id);
  }

  @Test
  public void sameHistorySeqAuthoritativelyRebindsAndInvalidatesOldBody() throws Exception {
    RemoteTerminalModel model = baseline();
    assertTrue(model.applyTerminalCommit(scrollCommit(
        1, 2, 301, 10, 3, V2ModelTestData.line(12, 1, 0, "c"))));
    assertEquals(SlotState.LOADED, history(model).slotStateAt(300));

    TerminalCommit rebound = new TerminalCommit(
        "i1", 1, 2, 3, 1, 1, DictionaryEntries.EMPTY, null,
        null,
        new HistoryMutation(new HistoryExtent(1, 301),
            Collections.singletonList(new HistoryPush(301, 2001, 1))),
        null, null, null);
    assertTrue(model.applyTerminalCommit(rebound));
    assertEquals(new HistoryLineRef(2001, 1), model.historyIndex().ref(301));
    assertEquals(SlotState.UNLOADED, history(model).slotStateAt(300));

    assertTrue(model.applyHistoryRange(range(
        V2ModelTestData.line(2001, 1, 301, "new")), 301, 301, 301));
    assertEquals("new", history(model).lineBySeq(301).at(0).text);
  }

  @Test
  public void oldRangeAfterAuthoritativeRebindIsIgnoredThenNewBodyLoads() throws Exception {
    RemoteTerminalModel model = baseline();
    assertTrue(model.applyHistoryRange(new HistoryRangeResult(
        "old-seed", "i1", 1, 1, HistoryRangeResult.Status.OK,
        new HistoryExtent(1, 300),
        Collections.singletonList(V2ModelTestData.line(1001, 1, 100, "old")), 0),
        100, 100, 100));
    assertTrue(model.applyTerminalCommit(new TerminalCommit(
        "i1", 1, 1, 2, 1, 1, DictionaryEntries.EMPTY, null, null,
        new HistoryMutation(new HistoryExtent(1, 300),
            Collections.singletonList(new HistoryPush(100, 2001, 1))),
        null, null, null)));

    assertFalse(model.applyHistoryRange(new HistoryRangeResult(
        "late-old", "i1", 1, 1, HistoryRangeResult.Status.OK,
        new HistoryExtent(1, 300),
        Collections.singletonList(V2ModelTestData.line(1001, 1, 100, "old")), 0),
        100, 100, 100));
    assertEquals(new HistoryLineRef(2001, 1), model.historyIndex().ref(100));
    assertEquals(SlotState.UNLOADED, history(model).slotStateAt(99));

    assertTrue(model.applyHistoryRange(new HistoryRangeResult(
        "new", "i1", 1, 1, HistoryRangeResult.Status.OK,
        new HistoryExtent(1, 300),
        Collections.singletonList(V2ModelTestData.line(2001, 1, 100, "new")), 0),
        100, 100, 100));
    assertEquals("new", history(model).lineBySeq(100).at(0).text);
  }

  @Test
  public void historyBodyKeepsItsPhysicalWidthAcrossCurrentResize() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    TerminalCell[] screenCells = new TerminalCell[80];
    TerminalCell[] historyCells = new TerminalCell[200];
    java.util.Arrays.fill(screenCells, TerminalCell.EMPTY);
    java.util.Arrays.fill(historyCells, TerminalCell.EMPTY);
    historyCells[199] = new TerminalCell("z", (byte) 1, null, null);
    assertTrue(model.applyBaseline(new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 1, DictionaryEntries.EMPTY, 1, 80,
        TerminalBufferKind.MAIN, new HistoryExtent(1, 1),
        Collections.singletonList(new TerminalLine(10, 1, 0, false, screenCells)),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults())));

    assertTrue(model.applyHistoryRange(new HistoryRangeResult(
        "wide", "i1", 1, 1, HistoryRangeResult.Status.OK,
        new HistoryExtent(1, 1),
        Collections.singletonList(new TerminalLine(20, 1, 1, false, historyCells)), 0),
        1, 1, 1));
    TerminalLine stored = history(model).lineBySeq(1);
    assertEquals(200, stored.length());
    assertEquals("z", stored.at(199).text);
    assertEquals(200, model.lineStore().line(20).length());
  }

  @Test
  public void fiveThousandHistoryPushesAreAppliedWithoutLimit() throws Exception {
    RemoteTerminalModel model = baseline();
    ArrayList<HistoryPush> pushes = new ArrayList<>(5000);
    for (long seq = 301; seq <= 5300; seq++) {
      pushes.add(new HistoryPush(seq, 100_000 + seq, 1));
    }
    assertTrue(model.applyTerminalCommit(new TerminalCommit(
        "i1", 1, 1, 2, 1, 1, DictionaryEntries.EMPTY, null, null,
        new HistoryMutation(new HistoryExtent(1, 5300), pushes), null, null, null)));
    assertEquals(new HistoryLineRef(105_300, 1), model.historyIndex().ref(5300));
  }

  @Test
  public void authoritativeBatchCanSwapMultipleExistingPositions() throws Exception {
    RemoteTerminalModel model = baseline();
    assertTrue(model.applyHistoryRange(new HistoryRangeResult(
        "seed", "i1", 1, 1, HistoryRangeResult.Status.OK,
        new HistoryExtent(1, 300),
        Arrays.asList(
            V2ModelTestData.line(2001, 1, 299, "a"),
            V2ModelTestData.line(2002, 1, 300, "b")), 0),
        299, 299, 300));

    assertTrue(model.applyTerminalCommit(new TerminalCommit(
        "i1", 1, 1, 2, 1, 1, DictionaryEntries.EMPTY, null, null,
        new HistoryMutation(new HistoryExtent(1, 300), Arrays.asList(
            new HistoryPush(299, 2002, 1),
            new HistoryPush(300, 2001, 1))), null, null, null)));
    assertEquals(new HistoryLineRef(2002, 1), model.historyIndex().ref(299));
    assertEquals(new HistoryLineRef(2001, 1), model.historyIndex().ref(300));
    assertEquals("b", history(model).lineBySeq(299).at(0).text);
    assertEquals("a", history(model).lineBySeq(300).at(0).text);
  }

  private static RemoteTerminalModel baseline() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 1, DictionaryEntries.EMPTY, 2, 1,
        TerminalBufferKind.MAIN, new HistoryExtent(1, 300),
        Arrays.asList(
            V2ModelTestData.line(10, 3, 0, "a"),
            V2ModelTestData.line(11, 1, 0, "b")),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults())));
    return model;
  }

  private static TerminalCommit scrollCommit(
      long baseRevision, long revision, long seq, long lineId, long lineVersion,
      TerminalLine replacement) {
    return new TerminalCommit(
        "i1", 1, baseRevision, revision, 1, 1, DictionaryEntries.EMPTY, null,
        new ScreenMutation(new ScreenScroll(0, 2, 1),
            Collections.singletonList(new ScreenRowWrite(1, replacement))),
        new HistoryMutation(new HistoryExtent(1, seq),
            Collections.singletonList(new HistoryPush(seq, lineId, lineVersion))),
        null, null, null);
  }

  private static HistoryRangeResult range(TerminalLine line) {
    return new HistoryRangeResult(
        "r", "i1", 1, 1, HistoryRangeResult.Status.OK,
        new HistoryExtent(1, 301), Collections.singletonList(line), 0);
  }

  private static PagedTerminalHistorySnapshot history(RemoteTerminalModel model) {
    return (PagedTerminalHistorySnapshot) model.renderSnapshot().history;
  }
}
