package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public final class RemoteTerminalSemanticProjectionTest {
  @Test
  public void productionBaselineAndRangePublishFromCatalogAndBodyCache() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline()));
    assertTrue(model.renderSnapshot().history instanceof SemanticHistoryRenderView);

    HistoryBodyResult result = model.applyHistoryBody(
        range(1, 101, "history"),
        request(1, 1));

    assertTrue(result instanceof HistoryBodyResult.Applied);
    int index = model.renderSnapshot().history.findSeqIndex(1);
    assertEquals("history", model.renderSnapshot().history.lineAt(index).at(0).text);
    assertEquals(new HistoryExtent(1, 2), model.projectionReadView().mainHistoryExtent);
  }

  @Test
  public void staleHttpBodyCannotReplaceWsAuthoritativeRebind() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline()));
    assertTrue(model.applyTerminalCommit(new TerminalCommit(
        "i1", 1, 1, 2, 1, 1, DictionaryEntries.EMPTY, null, null,
        new HistoryMutation(
            new HistoryExtent(1, 2),
            Collections.singletonList(new HistoryPush(1, 201, 1))),
        null, null, null)));

    HistoryBodyResult stale = model.applyHistoryBody(
        range(1, 101, "old"),
        request(1, 1));

    assertTrue(stale instanceof HistoryBodyResult.StaleIgnored);
    assertEquals(
        SlotState.UNLOADED,
        model.renderSnapshot().history.slotStateAt(0));
  }

  private static ScreenBaseline baseline() {
    return new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 1, 1, 1,
        TerminalBufferKind.MAIN,
        new HistoryExtent(1, 2),
        Arrays.asList(
            new HistoryPush(1, 101, 1),
            new HistoryPush(2, 102, 1)),
        Collections.singletonList(new ScreenLineContent(
            new LineKey(1000, 1),
            body("screen"))),
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
