package com.webterm.terminal.model;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public final class RemoteTerminalModelTerminalCommitTest {
  @Test
  public void oneLineScrollMarksOnlyWrittenRowsDirty() throws Exception {
    RemoteTerminalModel model = baselineModel();
    model.consumeRenderUpdate();
    assertTrue(model.applyTerminalCommit(new TerminalCommit(
        "instance", 1, 1, 1, 2,
        new ScreenMutation(new ScreenScroll(0, 3, 1),
            Collections.singletonList(new ScreenRowWrite(2, line(20, 1, 0, "new")))),
        null, null, null, null)));

    RenderUpdate update = model.consumeRenderUpdate();
    assertNotNull(update);
    assertFalse(update.dirty.fullInvalidate);
    assertEquals(1, update.dirty.screenScrollRows);
    assertEquals(bitSetOf(2), update.dirty.exposedScreenRows);
    assertEquals(bitSetOf(2), update.dirty.changedScreenRows);
  }

  @Test
  public void twoForwardScrollCommitsMergeWithoutFullInvalidate() throws Exception {
    RemoteTerminalModel model = baselineModel();
    model.consumeRenderUpdate();
    assertTrue(model.applyTerminalCommit(scrollCommit(1, 2, 20, 1)));
    assertTrue(model.applyTerminalCommit(scrollCommit(2, 3, 21, 1)));

    RenderUpdate merged = model.consumeRenderUpdate();
    assertNotNull(merged);
    assertFalse(merged.dirty.fullInvalidate);
    assertEquals(2, merged.dirty.screenScrollRows);
    assertEquals(bitSetOf(1, 2), merged.dirty.exposedScreenRows);
    assertEquals(bitSetOf(1, 2), merged.dirty.changedScreenRows);

    assertTrue(model.applyTerminalCommit(scrollCommit(3, 4, 22, -1)));
    assertTrue(model.applyTerminalCommit(scrollCommit(4, 5, 23, 1)));
    assertTrue(model.consumeRenderUpdate().dirty.fullInvalidate);
  }

  @Test
  public void scrollWriteAndHistoryAppendPublishOnce() throws Exception {
    RemoteTerminalModel model = baselineModel();
    model.consumeRenderUpdate();
    TerminalCommit commit = new TerminalCommit(
        "instance", 1, 1, 1, 2,
        new ScreenMutation(new ScreenScroll(0, 3, 1),
            Collections.singletonList(new ScreenRowWrite(2, line(20, 1, 0, "new")))),
        new HistoryMutation(new HistoryExtent(1, 1),
            Collections.singletonList(line(10, 7, 1, "history-domain-copy"))),
        new TerminalCursor(2, 0, true, TerminalCursor.Shape.BLOCK, false),
        TerminalModes.defaults(), TerminalPalette.defaults());

    assertTrue(model.applyTerminalCommit(commit));
    RenderUpdate update = model.consumeRenderUpdate();
    assertNotNull(update);
    assertNull(model.consumeRenderUpdate());
    assertEquals(2, model.screenRevision);
    assertEquals(1, model.historySize());
    assertEquals(1, update.state.tailAppendedLines);
    assertEquals(11, update.snapshot.screen[0].id);
    assertEquals(12, update.snapshot.screen[1].id);
    assertEquals(20, update.snapshot.screen[2].id);
  }

  @Test
  public void invalidHistoryKeepsScreenHistoryMetaAndRevisionUnchanged() throws Exception {
    RemoteTerminalModel model = baselineModel();
    model.consumeRenderUpdate();
    RemoteTerminalModel.RenderSnapshot before = model.renderSnapshot();
    TerminalCursor beforeCursor = model.cursor();
    TerminalCommit commit = new TerminalCommit(
        "instance", 1, 1, 1, 2,
        new ScreenMutation(null,
            Collections.singletonList(new ScreenRowWrite(0, line(30, 1, 0, "changed")))),
        new HistoryMutation(new HistoryExtent(1, 2), Arrays.asList(
            line(40, 1, 2, "ok"), line(41, 1, 1, "out-of-order"))),
        new TerminalCursor(1, 1, true, TerminalCursor.Shape.BAR, false),
        null, null);
    try {
      model.applyTerminalCommit(commit);
      fail("invalid history accepted");
    } catch (RemoteTerminalModel.RevisionGapException expected) {
      // expected
    }
    assertSame(before, model.renderSnapshot());
    assertSame(beforeCursor, model.cursor());
    assertEquals(0, model.historySize());
    assertEquals(1, model.screenRevision);
    assertNull(model.consumeRenderUpdate());
  }

  @Test
  public void logicalHistoryGrowthUsesExtentNotBoundedBodies() throws Exception {
    RemoteTerminalModel model = baselineModel();
    model.consumeRenderUpdate();
    java.util.List<TerminalLine> tail = new java.util.ArrayList<>();
    for (long seq = 9873; seq <= 10000; seq++) tail.add(line(10000 + seq, 1, seq, "h"));
    TerminalCommit commit = new TerminalCommit(
        "instance", 1, 1, 1, 2, null,
        new HistoryMutation(new HistoryExtent(1, 10000), tail), null, null, null);
    assertTrue(model.applyTerminalCommit(commit));
    assertEquals(10000, model.consumeRenderUpdate().state.tailAppendedLines);
  }

  @Test
  public void historyExtentFirstRegressionRejectedAtomically() throws Exception {
    RemoteTerminalModel model = baselineModel(new HistoryExtent(20, 100));
    assertCommitRejectedAtomically(model, historyCommit(
        1, 2, new HistoryExtent(1, 100), Collections.emptyList()));
  }

  @Test
  public void historyExtentLastRegressionRejectedAtomically() throws Exception {
    RemoteTerminalModel model = baselineModel(new HistoryExtent(20, 100));
    assertCommitRejectedAtomically(model, historyCommit(
        1, 2, new HistoryExtent(20, 90), Collections.emptyList()));
  }

  @Test
  public void trimOnlyHistoryMutationAccepted() throws Exception {
    RemoteTerminalModel model = baselineModel(new HistoryExtent(1, 100));
    model.consumeRenderUpdate();

    assertTrue(model.applyTerminalCommit(historyCommit(
        1, 2, new HistoryExtent(20, 100), Collections.emptyList())));

    assertEquals(new HistoryExtent(20, 100), model.displayExtent());
    assertEquals(2, model.screenRevision);
    assertNotNull(model.consumeRenderUpdate());
    assertNull(model.consumeRenderUpdate());
  }

  @Test
  public void boundedTailBodiesMayStartAfterOldLastPlusOne() throws Exception {
    RemoteTerminalModel model = baselineModel(new HistoryExtent(1, 100));
    model.consumeRenderUpdate();
    java.util.List<TerminalLine> tail = new java.util.ArrayList<>();
    for (long seq = 873; seq <= 1000; seq++) {
      tail.add(line(10000 + seq, 1, seq, "tail"));
    }

    assertTrue(model.applyTerminalCommit(historyCommit(
        1, 2, new HistoryExtent(1, 1000), tail)));

    RenderUpdate update = model.consumeRenderUpdate();
    assertNotNull(update);
    assertEquals(900, update.state.tailAppendedLines);
    assertEquals(new HistoryExtent(1, 1000), model.displayExtent());
  }

  @Test
  public void oldHistorySeqInCommitRejectedAtomically() throws Exception {
    RemoteTerminalModel model = baselineModel(new HistoryExtent(1, 100));
    assertCommitRejectedAtomically(model, historyCommit(
        1, 2, new HistoryExtent(1, 105),
        Arrays.asList(line(1000, 1, 100, "old"), line(1001, 1, 105, "new"))));
  }

  @Test
  public void invalidScreenWriteDoesNotCommitPreparedHistory() throws Exception {
    RemoteTerminalModel model = baselineModel();
    model.consumeRenderUpdate();
    TerminalCommit commit = new TerminalCommit(
        "instance", 1, 1, 1, 2,
        new ScreenMutation(null,
            Collections.singletonList(new ScreenRowWrite(3, line(30, 1, 0, "bad-row")))),
        new HistoryMutation(new HistoryExtent(1, 1),
            Collections.singletonList(line(40, 1, 1, "history"))),
        null, null, null);
    try {
      model.applyTerminalCommit(commit);
      fail("invalid screen row accepted");
    } catch (RemoteTerminalModel.RevisionGapException expected) {
      // expected
    }
    assertEquals(0, model.historySize());
    assertEquals(1, model.screenRevision);
    assertNull(model.consumeRenderUpdate());
  }

  @Test
  public void conflictingLoadedHistoryBodyIsRejectedAtomically() throws Exception {
    RemoteTerminalModel model = baselineModel();
    model.consumeRenderUpdate();
    assertTrue(model.applyTerminalCommit(new TerminalCommit(
        "instance", 1, 1, 1, 2, null,
        new HistoryMutation(new HistoryExtent(1, 1),
            Collections.singletonList(line(40, 1, 1, "first"))),
        null, null, null)));
    model.consumeRenderUpdate();
    try {
      model.applyTerminalCommit(new TerminalCommit(
          "instance", 1, 1, 2, 3, null,
          new HistoryMutation(new HistoryExtent(1, 1),
              Collections.singletonList(line(41, 1, 1, "different"))),
          null, null, null));
      fail("conflicting history body accepted");
    } catch (RemoteTerminalModel.RevisionGapException expected) {
      // expected
    }
    assertEquals(2, model.screenRevision);
    assertEquals(1, model.historySize());
    assertNull(model.consumeRenderUpdate());
  }

  @Test(expected = RemoteTerminalModel.RevisionGapException.class)
  public void revisionGapIsRejected() throws Exception {
    RemoteTerminalModel model = baselineModel();
    model.applyTerminalCommit(new TerminalCommit(
        "instance", 1, 1, 0, 2, null,
        new HistoryMutation(new HistoryExtent(1, 1), Collections.emptyList()),
        null, null, null));
  }

  private static RemoteTerminalModel baselineModel() {
    return baselineModel(HistoryExtent.INITIAL_EMPTY);
  }

  private static RemoteTerminalModel baselineModel(HistoryExtent historyExtent) {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(new ScreenBaseline(
        "session", "instance", 1, 1, 1, 3, 1, TerminalBufferKind.MAIN,
        historyExtent, Collections.emptyList(),
        Arrays.asList(line(10, 1, 0, "a"), line(11, 1, 0, "b"), line(12, 1, 0, "c")),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults())));
    return model;
  }

  private static TerminalCommit historyCommit(long baseRevision, long revision,
                                               HistoryExtent extent,
                                               java.util.List<TerminalLine> lines) {
    return new TerminalCommit(
        "instance", 1, 1, baseRevision, revision,
        new ScreenMutation(null,
            Collections.singletonList(new ScreenRowWrite(0, line(30, 1, 0, "changed")))),
        new HistoryMutation(extent, lines),
        new TerminalCursor(1, 1, true, TerminalCursor.Shape.BAR, false),
        new TerminalModes(true, true, true, TerminalModes.MouseTracking.ANY_EVENT,
            TerminalModes.MouseEncoding.SGR, true),
        new TerminalPalette(TerminalColor.rgb(0x112233), TerminalColor.rgb(0x445566),
            TerminalColor.rgb(0x778899)));
  }

  private static void assertCommitRejectedAtomically(
      RemoteTerminalModel model, TerminalCommit commit) throws Exception {
    model.consumeRenderUpdate();
    RemoteTerminalModel.RenderSnapshot before = model.renderSnapshot();
    HistoryExtent beforeExtent = model.displayExtent();
    TerminalCursor beforeCursor = model.cursor();
    TerminalModes beforeModes = model.modes();
    TerminalPalette beforePalette = model.palette();
    int beforeHistorySize = model.historySize();
    long beforeRevision = model.screenRevision;
    try {
      model.applyTerminalCommit(commit);
      fail("invalid history mutation accepted");
    } catch (RemoteTerminalModel.RevisionGapException expected) {
      // expected
    }
    assertSame(before, model.renderSnapshot());
    assertEquals(beforeExtent, model.displayExtent());
    assertSame(beforeCursor, model.cursor());
    assertSame(beforeModes, model.modes());
    assertSame(beforePalette, model.palette());
    assertEquals(beforeHistorySize, model.historySize());
    assertEquals(beforeRevision, model.screenRevision);
    assertNull(model.consumeRenderUpdate());
  }

  private static TerminalLine line(long id, long version, long historySeq, String text) {
    return V2ModelTestData.line(id, version, historySeq, text);
  }

  private static TerminalCommit scrollCommit(
      long baseRevision, long revision, long lineId, int deltaRows) {
    int exposedRow = deltaRows > 0 ? 2 : 0;
    return new TerminalCommit(
        "instance", 1, 1, baseRevision, revision,
        new ScreenMutation(new ScreenScroll(0, 3, deltaRows),
            Collections.singletonList(new ScreenRowWrite(
                exposedRow, line(lineId, 1, 0, "scroll-" + revision)))),
        null, null, null, null);
  }

  private static java.util.BitSet bitSetOf(int... rows) {
    java.util.BitSet result = new java.util.BitSet();
    for (int row : rows) result.set(row);
    return result;
  }
}
