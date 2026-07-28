package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class RemoteTerminalModelCommitScaleTest {
  @Test
  public void largeLogicalHistoryProcessesOnlyBoundedCommitBodies() throws Exception {
    final int rows = 40;
    final int commits = 1_000;
    RemoteTerminalModel model = new RemoteTerminalModel(
        new HistoryBudget(64, 128, Long.MAX_VALUE, Long.MAX_VALUE));
    List<TerminalLine> screen = new ArrayList<>();
    for (int row = 0; row < rows; row++) {
      screen.add(line(10_000 + row, 1, 0, "row"));
    }
    assertTrue(model.applyBaseline(new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 1, DictionaryEntries.EMPTY,
        rows, 80, TerminalBufferKind.MAIN,
        new HistoryExtent(1, 1_000_000), screen,
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults())));
    model.consumeRenderUpdate();

    long revision = 1;
    long nextLineId = 20_000;
    long nextHistorySeq = 1_000_000;
    for (int iteration = 0; iteration < commits; iteration++) {
      RemoteTerminalModel.RenderSnapshot snapshot = model.renderSnapshot();
      nextHistorySeq++;
      assertTrue(model.applyTerminalCommit(new TerminalCommit(
          "i1", 1, revision, revision + 1, 1, 1,
          DictionaryEntries.EMPTY, null,
          new ScreenMutation(new ScreenScroll(0, rows, 1),
              Collections.singletonList(new ScreenRowWrite(
                  rows - 1, line(nextLineId++, 1, 0, "new")))),
          new HistoryMutation(new HistoryExtent(1, nextHistorySeq),
              Collections.singletonList(new HistoryPush(
                  nextHistorySeq, snapshot.screen[0].id, snapshot.screen[0].version))),
          null, null, null)));
      revision++;
      model.consumeRenderUpdate();
      assertTrue(model.loadedHistoryLineCountForTest() <= 128);
      assertTrue(model.loadedLineIdentityCountForTest() <= rows + 128);
    }

    assertEquals(1_001_000, model.historySize());
    assertEquals(commits, model.screenRevision - 1);
  }

  private static TerminalLine line(long id, long version, long historySeq, String text) {
    return V2ModelTestData.line(id, version, historySeq, text);
  }
}
