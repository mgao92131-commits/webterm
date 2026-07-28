package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
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

    try {
      model.applyHistoryRange(range(
          V2ModelTestData.line(100, 2, 301, "conflict")), 301, 301, 301);
      fail("conflicting Range binding accepted");
    } catch (IllegalArgumentException expected) {
      assertEquals(99, model.historyIndex().ref(301).lineId);
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
