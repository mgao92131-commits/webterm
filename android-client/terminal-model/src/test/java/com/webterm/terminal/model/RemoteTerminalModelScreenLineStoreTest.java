package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class RemoteTerminalModelScreenLineStoreTest {
  @Test
  public void patchWithoutLayoutUpdatesCurrentScreenLine() throws Exception {
    RemoteTerminalModel model = modelWithRows(3, 1_000_000L);

    model.applyScreenPatch(patch(1, 2, null,
        Collections.singletonList(line(10_001, 2, 0, "updated"))));

    assertEquals("updated", model.renderSnapshot().screen[0].at(0).text);
    assertEquals(3, model.screenLineStoreSize());
  }

  @Test
  public void historyLineWithOldScreenIdDoesNotPolluteScreenStore() throws Exception {
    RemoteTerminalModel model = modelWithRows(3, 1_000_000L);
    model.applyScreenPatch(patch(1, 2, new long[] {10_002, 10_003, 20_000},
        Collections.singletonList(line(20_000, 1, 0, "new"))));

    assertTrue(model.applyHistoryDelta(new HistoryDelta(
        "i1", 1, 1, new HistoryExtent(1, 1_000_001L),
        Collections.singletonList(line(10_001, 99, 1_000_001L, "history")))));
    model.applyScreenPatch(patch(2, 3, null,
        Collections.singletonList(line(10_002, 2, 0, "screen"))));

    assertEquals("screen", model.renderSnapshot().screen[0].at(0).text);
    assertEquals(3, model.screenLineStoreSize());
  }

  @Test
  public void layoutCannotResolveLineOwnedOnlyByHistory() throws Exception {
    RemoteTerminalModel model = modelWithRows(3, 1_000_000L);
    model.applyScreenPatch(patch(1, 2, new long[] {10_002, 10_003, 20_000},
        Collections.singletonList(line(20_000, 1, 0, "new"))));
    assertTrue(model.applyHistoryDelta(new HistoryDelta(
        "i1", 1, 1, new HistoryExtent(1, 1_000_001L),
        Collections.singletonList(line(10_001, 99, 1_000_001L, "history")))));

    try {
      model.applyScreenPatch(patch(2, 3, new long[] {10_001, 10_003, 20_000},
          Collections.emptyList()));
    } catch (RemoteTerminalModel.RevisionGapException expected) {
      assertEquals(2, model.screenRevision);
      assertEquals(3, model.screenLineStoreSize());
      return;
    }
    throw new AssertionError("history-only line id must not resolve a screen layout");
  }

  @Test
  public void baselineReplacementAndRevisionGapKeepStoreBounded() throws Exception {
    RemoteTerminalModel model = modelWithRows(4, 1_000_000L);
    assertEquals(4, model.screenLineStoreSize());

    try {
      model.applyScreenPatch(patch(9, 10, null, Collections.emptyList()));
    } catch (RemoteTerminalModel.RevisionGapException expected) {
      // A rejected patch must not stage rows into the active store.
    }
    assertEquals(4, model.screenLineStoreSize());

    assertTrue(model.applyBaseline(baseline(2, 2, 2, 1_000_000L)));
    assertEquals(2, model.screenLineStoreSize());
  }

  private static RemoteTerminalModel modelWithRows(int rows, long historyLastSeq) {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline(rows, 1, 1, historyLastSeq)));
    return model;
  }

  static ScreenBaseline baseline(
      int rows, long revision, long generation, long historyLastSeq) {
    List<TerminalLine> screen = new ArrayList<>();
    for (int row = 0; row < rows; row++) {
      screen.add(line(10_001 + row, 1, 0, "s" + row));
    }
    List<TerminalLine> tail = Collections.singletonList(
        line(9_000, 1, historyLastSeq, "tail"));
    return new ScreenBaseline(
        "s1", "i1", 1, revision, generation, rows, 1,
        TerminalBufferKind.MAIN, new HistoryExtent(1, historyLastSeq), tail, screen,
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(), "", "");
  }

  private static ScreenPatchV2 patch(
      long baseRevision, long screenRevision, long[] layout, List<TerminalLine> updates) {
    return new ScreenPatchV2(
        "i1", 1, 1, baseRevision, screenRevision, layout, updates,
        null, null, null, null, null, null);
  }

  static TerminalLine line(long id, long version, long historySeq, String text) {
    return V2ModelTestData.line(id, version, historySeq, text);
  }
}
