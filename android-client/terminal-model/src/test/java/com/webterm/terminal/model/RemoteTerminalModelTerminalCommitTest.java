package com.webterm.terminal.model;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import org.junit.Test;

public final class RemoteTerminalModelTerminalCommitTest {
  @Test
  public void stagingDoesNotMutateAndCommitPublishesExactlyOnce() throws Exception {
    RemoteTerminalModel model = baselineModel();
    model.consumeRenderUpdate();
    RemoteTerminalModel.RenderSnapshot before = model.renderSnapshot();

    RemoteTerminalModel.StagedCommit staged = model.stageCommit(scrollCommit(1, 2, 20, 1));

    assertSame(before, model.renderSnapshot());
    assertEquals(1, model.screenRevision);
    assertNull(model.consumeRenderUpdate());

    assertTrue(staged.commit());
    assertEquals(2, model.screenRevision);
    assertNotSame(before, model.renderSnapshot());
    assertNotNull(model.consumeRenderUpdate());
    assertNull(model.consumeRenderUpdate());
    try {
      staged.commit();
      fail("staged commit was reusable");
    } catch (IllegalStateException expected) {
      // expected
    }
  }

  @Test
  public void lineDataRandomUnicodeRoundTripsThroughStagedCommit() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(new ScreenBaseline(
        "session", "instance", 1, 1, 1, 1, false, DictionaryEntries.EMPTY,
        3, 3, TerminalBufferKind.MAIN, HistoryExtent.INITIAL_EMPTY,
        Collections.emptyList(),
        Arrays.asList(
            TerminalLine.empty(10, 3),
            TerminalLine.empty(11, 3),
            TerminalLine.empty(12, 3)),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults())));
    model.consumeRenderUpdate();
    Random random = new Random(0x5eedL);
    long revision = 1;
    for (int iteration = 0; iteration < 200; iteration++) {
      TerminalCell[] cells = new TerminalCell[3];
      switch (random.nextInt(4)) {
        case 0:
          cells[0] = new TerminalCell("A", (byte) 1, null, null);
          cells[1] = new TerminalCell(" ", (byte) 1, null, null);
          cells[2] = new TerminalCell("z", (byte) 1, null, null);
          break;
        case 1:
          cells[0] = new TerminalCell("界", (byte) 2, null, null);
          cells[1] = TerminalCell.SPACER;
          cells[2] = TerminalCell.EMPTY;
          break;
        case 2:
          cells[0] = new TerminalCell("e\u0301", (byte) 1, null, null);
          cells[1] = TerminalCell.EMPTY;
          cells[2] = TerminalCell.EMPTY;
          break;
        default:
          cells[0] = new TerminalCell("👩‍💻", (byte) 2, null, null);
          cells[1] = TerminalCell.SPACER;
          cells[2] = TerminalCell.EMPTY;
          break;
      }
      TerminalLine expected = new TerminalLine(1000 + iteration, 1, 0, false, cells);
      assertTrue(model.applyTerminalCommit(new TerminalCommit(
          "instance", 1, revision, revision + 1, 1, 1,
          DictionaryEntries.EMPTY, null,
          new ScreenMutation(null,
              Collections.singletonList(new ScreenRowWrite(0, expected))),
          null, null, null, null)));
      revision++;
      assertTrue(model.renderSnapshot().screen[0].sameContent(expected));
      model.consumeRenderUpdate();
    }
  }

  @Test
  public void scrollUpMissingExposedBottomRowIsRejectedAtomically() throws Exception {
    RemoteTerminalModel model = baselineModel();
    assertFailureAtomically(model, new TerminalCommit(
        "instance", 1, 1, 2, 1, 1, DictionaryEntries.EMPTY, null,
        new ScreenMutation(new ScreenScroll(0, 3, 1), Collections.emptyList()),
        null, null, null, null), CommitFailure.EXPOSED_ROW_MISSING);
  }

  @Test
  public void scrollDownMissingExposedTopRowIsRejectedAtomically() throws Exception {
    RemoteTerminalModel model = baselineModel();
    assertFailureAtomically(model, new TerminalCommit(
        "instance", 1, 1, 2, 1, 1, DictionaryEntries.EMPTY, null,
        new ScreenMutation(new ScreenScroll(0, 3, -1), Collections.emptyList()),
        null, null, null, null), CommitFailure.EXPOSED_ROW_MISSING);
  }

  @Test
  public void duplicateActiveLineIdIsRejectedAtomically() throws Exception {
    RemoteTerminalModel model = baselineModel();
    assertFailureAtomically(model, new TerminalCommit(
        "instance", 1, 1, 2, 1, 1, DictionaryEntries.EMPTY, null,
        new ScreenMutation(null,
            Collections.singletonList(new ScreenRowWrite(0, line(11, 1, 0, "b")))),
        null, null, null, null), CommitFailure.DUPLICATE_ACTIVE_LINE_ID);
  }

  @Test
  public void oneLineScrollMarksOnlyWrittenRowsDirty() throws Exception {
    RemoteTerminalModel model = baselineModel();
    model.consumeRenderUpdate();
    assertTrue(model.applyTerminalCommit(new TerminalCommit(
        "instance", 1, 1, 2, 1, 1, DictionaryEntries.EMPTY, null,
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
        "instance", 1, 1, 2, 1, 1, DictionaryEntries.EMPTY, null,
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
        "instance", 1, 1, 2, 1, 1, DictionaryEntries.EMPTY, null,
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
    for (long seq = 9937; seq <= 10000; seq++) tail.add(line(10000 + seq, 1, seq, "h"));
    TerminalCommit commit = new TerminalCommit(
        "instance", 1, 1, 2, 1, 1, DictionaryEntries.EMPTY, null, null,
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
    for (long seq = 937; seq <= 1000; seq++) {
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
        "instance", 1, 1, 2, 1, 1, DictionaryEntries.EMPTY, null,
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
        "instance", 1, 1, 2, 1, 1, DictionaryEntries.EMPTY, null, null,
        new HistoryMutation(new HistoryExtent(1, 1),
            Collections.singletonList(line(40, 1, 1, "first"))),
        null, null, null)));
    model.consumeRenderUpdate();
    try {
      model.applyTerminalCommit(new TerminalCommit(
          "instance", 1, 2, 3, 1, 1, DictionaryEntries.EMPTY, null, null,
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

  @Test
  public void mainAndAlternateSurfacesKeepIndependentRowsHistoryAndLineStores()
      throws Exception {
    RemoteTerminalModel model = baselineModel();
    model.consumeRenderUpdate();
    assertTrue(model.applyTerminalCommit(new TerminalCommit(
        "instance", 1, 1, 2, 1, 1, DictionaryEntries.EMPTY, null,
        new ScreenMutation(new ScreenScroll(0, 3, 1),
            Collections.singletonList(new ScreenRowWrite(2, line(20, 1, 0, "new")))),
        HistoryMutation.fromLineData(new HistoryExtent(1, 1), Collections.emptyList(),
            Collections.singletonList(new HistoryPromotion(10, 1, 1))),
        null, null, null)));
    TerminalLine archivedMainLine = model.lineStore().line(10);
    assertSame(archivedMainLine, model.lineStore().line(model.historyIndex().lineId(1)));
    assertSame(archivedMainLine,
        ((PagedTerminalHistorySnapshot) model.renderSnapshot().history).lineBySeq(1));

    assertTrue(model.applyTerminalCommit(bufferCommit(
        2, 3, TerminalBufferKind.ALTERNATE,
        line(100, 1, 0, "a"), line(101, 1, 0, "b"), line(102, 1, 0, "c"))));
    assertEquals(TerminalBufferKind.ALTERNATE, model.activeBuffer);
    assertEquals(0, model.historySize());
    assertNull(model.lineStore().line(10));

    assertTrue(model.applyTerminalCommit(bufferCommit(
        3, 4, TerminalBufferKind.MAIN,
        line(11, 1, 0, "b"), line(12, 1, 0, "c"), line(20, 1, 0, "new"))));
    assertEquals(TerminalBufferKind.MAIN, model.activeBuffer);
    assertEquals(1, model.historySize());
    assertEquals(Long.valueOf(10), model.historyIndex().lineId(1));
    assertSame(archivedMainLine, model.lineStore().line(10));
  }

  @Test(expected = RemoteTerminalModel.RevisionGapException.class)
  public void revisionGapIsRejected() throws Exception {
    RemoteTerminalModel model = baselineModel();
    model.applyTerminalCommit(new TerminalCommit(
        "instance", 1, 0, 2, 1, 1, DictionaryEntries.EMPTY, null, null,
        new HistoryMutation(new HistoryExtent(1, 1), Collections.emptyList()),
        null, null, null));
  }

  private static RemoteTerminalModel baselineModel() {
    return baselineModel(HistoryExtent.INITIAL_EMPTY);
  }

  private static RemoteTerminalModel baselineModel(HistoryExtent historyExtent) {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(new ScreenBaseline(
        "session", "instance", 1, 1, 1, 1, false, DictionaryEntries.EMPTY,
        3, 1, TerminalBufferKind.MAIN,
        historyExtent, Collections.emptyList(),
        Arrays.asList(line(10, 1, 0, "a"), line(11, 1, 0, "b"), line(12, 1, 0, "c")),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults())));
    return model;
  }

  private static TerminalCommit historyCommit(long baseRevision, long revision,
                                               HistoryExtent extent,
                                               java.util.List<TerminalLine> lines) {
    return new TerminalCommit(
        "instance", 1, baseRevision, revision, 1, 1, DictionaryEntries.EMPTY, null,
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

  private static void assertFailureAtomically(RemoteTerminalModel model,
                                              TerminalCommit commit,
                                              CommitFailure failure) throws Exception {
    model.consumeRenderUpdate();
    RemoteTerminalModel.RenderSnapshot before = model.renderSnapshot();
    try {
      model.applyTerminalCommit(commit);
      fail("invalid commit accepted");
    } catch (CommitValidationException expected) {
      assertEquals(failure, expected.failure);
    }
    assertSame(before, model.renderSnapshot());
    assertEquals(1, model.screenRevision);
    assertEquals(0, model.historySize());
    assertNull(model.consumeRenderUpdate());
  }

  private static TerminalLine line(long id, long version, long historySeq, String text) {
    return V2ModelTestData.line(id, version, historySeq, text);
  }

  private static TerminalCommit scrollCommit(
      long baseRevision, long revision, long lineId, int deltaRows) {
    int exposedRow = deltaRows > 0 ? 2 : 0;
    return new TerminalCommit(
        "instance", 1, baseRevision, revision, 1, 1, DictionaryEntries.EMPTY, null,
        new ScreenMutation(new ScreenScroll(0, 3, deltaRows),
            Collections.singletonList(new ScreenRowWrite(
                exposedRow, line(lineId, 1, 0, "scroll-" + revision)))),
        null, null, null, null);
  }

  private static TerminalCommit bufferCommit(
      long baseRevision, long revision, TerminalBufferKind buffer,
      TerminalLine... rows) {
    java.util.List<ScreenRowWrite> writes = new java.util.ArrayList<>();
    for (int row = 0; row < rows.length; row++) {
      writes.add(new ScreenRowWrite(row, rows[row]));
    }
    HistoryExtent extent = buffer == TerminalBufferKind.MAIN
        ? new HistoryExtent(1, 1) : HistoryExtent.INITIAL_EMPTY;
    return new TerminalCommit(
        "instance", 1, baseRevision, revision, 1, 1,
        DictionaryEntries.EMPTY, buffer,
        new ScreenMutation(null, writes),
        HistoryMutation.fromLineData(
            extent, Collections.emptyList(), Collections.emptyList()),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults());
  }

  private static java.util.BitSet bitSetOf(int... rows) {
    java.util.BitSet result = new java.util.BitSet();
    for (int row : rows) result.set(row);
    return result;
  }
}
