package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;

public final class RemoteTerminalModelHistoryDeltaTest {
  @Test
  public void deltaUpdatesExtentWithoutChangingScreenRevision() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applyBaseline(V2ModelTestData.baseline(1, 1));
    assertTrue(model.applyHistoryDelta(new HistoryDelta(
        "i1", 1, 1, new HistoryExtent(2, 301),
        Collections.singletonList(V2ModelTestData.line(301, 1, 301, "n")))));
    assertEquals(1, model.screenRevision);
    assertEquals(new HistoryExtent(2, 301), model.displayExtent());
    PagedTerminalHistorySnapshot history =
        (PagedTerminalHistorySnapshot) model.renderSnapshot().history;
    assertEquals(301, history.lineBySeq(301).historySeq);
  }
}
