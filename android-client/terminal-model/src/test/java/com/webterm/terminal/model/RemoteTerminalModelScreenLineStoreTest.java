package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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
  public void screenToHistoryMigrationPreservesLineIdentityAndContent() throws Exception {
    RemoteTerminalModel model = modelWithRows(3, 1_000_000L);
    model.applyScreenPatch(patch(1, 2, new long[] {10_002, 10_003, 20_000},
        Collections.singletonList(line(20_000, 1, 0, "new"))));
    assertEquals(1, model.pendingHistoryMigrationCountForTest());

    assertTrue(model.applyHistoryDelta(new HistoryDelta(
        "i1", 1, 1, new HistoryExtent(1, 1_000_001L),
        Collections.singletonList(line(10_001, 1, 1_000_001L, "s0")))));
    model.applyScreenPatch(patch(2, 3, null,
        Collections.singletonList(line(10_002, 2, 0, "screen"))));

    assertEquals("screen", model.renderSnapshot().screen[0].at(0).text);
    assertEquals(3, model.screenLineStoreSize());
    assertEquals(Long.valueOf(1_000_001L), model.loadedHistorySeqForLineIdForTest(10_001));
    assertEquals(model.screenLineStoreSize() + model.loadedHistoryLineCountForTest(),
        model.loadedLineIdentityCountForTest());
    assertEquals(0, model.pendingHistoryMigrationCountForTest());
  }

  @Test
  public void migrationWithSameIdVersionButDifferentContentIsRejectedTransactionally() throws Exception {
    RemoteTerminalModel model = modelWithRows(3, 1_000_000L);
    long loadedBefore = model.loadedHistoryLineCountForTest();
    model.applyScreenPatch(patch(1, 2, new long[] {10_002, 10_003, 20_000},
        Collections.singletonList(line(20_000, 1, 0, "new"))));

    try {
      model.applyHistoryDelta(new HistoryDelta(
          "i1", 1, 1, new HistoryExtent(1, 1_000_001L),
          Collections.singletonList(line(10_001, 1, 1_000_001L, "B"))));
    } catch (IllegalStateException expected) {
      assertEquals(2, model.screenRevision);
      assertEquals(loadedBefore, model.loadedHistoryLineCountForTest());
      assertEquals(new HistoryExtent(1, 1_000_000L), model.displayExtent());
      assertEquals(1, model.pendingHistoryMigrationCountForTest());
      assertNull(model.loadedHistorySeqForLineIdForTest(10_001));
      return;
    }
    throw new AssertionError("migration must preserve LineID/version content identity");
  }

  @Test
  public void migrationVersionChangeIsRejectedByImmutableScrollbackContract() throws Exception {
    RemoteTerminalModel model = modelWithRows(3, 1_000_000L);
    model.applyScreenPatch(patch(1, 2, new long[] {10_002, 10_003, 20_000},
        Collections.singletonList(line(20_000, 1, 0, "new"))));

    try {
      model.applyHistoryDelta(new HistoryDelta(
          "i1", 1, 1, new HistoryExtent(1, 1_000_001L),
          Collections.singletonList(line(10_001, 2, 1_000_001L, "s0"))));
    } catch (IllegalStateException expected) {
      assertEquals(1, model.pendingHistoryMigrationCountForTest());
      assertNull(model.loadedHistorySeqForLineIdForTest(10_001));
      return;
    }
    throw new AssertionError("Go scrollback Push preserves LineVersion during migration");
  }

  @Test
  public void failedMigrationBatchDoesNotConsumeAnyPendingIdentity() throws Exception {
    RemoteTerminalModel model = modelWithRows(3, 1_000_000L);
    long loadedBefore = model.loadedHistoryLineCountForTest();
    model.applyScreenPatch(patch(1, 2, new long[] {20_000, 20_001, 10_003},
        java.util.Arrays.asList(line(20_000, 1, 0, "new0"), line(20_001, 1, 0, "new1"))));
    assertEquals(2, model.pendingHistoryMigrationCountForTest());

    try {
      model.applyHistoryDelta(new HistoryDelta(
          "i1", 1, 1, new HistoryExtent(1, 1_000_002L),
          java.util.Arrays.asList(
              line(10_001, 1, 1_000_001L, "s0"),
              line(10_002, 1, 1_000_002L, "bad"))));
    } catch (IllegalStateException expected) {
      assertEquals(loadedBefore, model.loadedHistoryLineCountForTest());
      assertNull(model.loadedHistorySeqForLineIdForTest(10_001));
      assertNull(model.loadedHistorySeqForLineIdForTest(10_002));
      assertEquals(2, model.pendingHistoryMigrationCountForTest());
      return;
    }
    throw new AssertionError("failed batch must not partly consume migrations");
  }

  @Test
  public void pendingMigrationsAreBoundedByRevisionWindowAndCapacity() throws Exception {
    RemoteTerminalModel model = modelWithRows(1, 1_000_000L);
    long currentId = 10_001;
    for (int revision = 2; revision <= 48; revision++) {
      long nextId = 20_000L + revision;
      model.applyScreenPatch(patch(revision - 1, revision, new long[] {nextId},
          Collections.singletonList(line(nextId, 1, 0, "n" + revision))));
      currentId = nextId;
    }

    assertEquals(currentId, model.renderSnapshot().screen[0].id);
    assertEquals(1, model.screenLineStoreSize());
    assertTrue(model.pendingHistoryMigrationCountForTest() <= 9);
    assertTrue(model.pendingHistoryMigrationCountForTest() <= 32);
  }

  @Test
  public void successfulBaselineClearsPendingMigrationState() throws Exception {
    RemoteTerminalModel model = modelWithRows(3, 1_000_000L);
    model.applyScreenPatch(patch(1, 2, new long[] {10_002, 10_003, 20_000},
        Collections.singletonList(line(20_000, 1, 0, "new"))));
    assertEquals(1, model.pendingHistoryMigrationCountForTest());

    assertTrue(model.applyBaseline(baseline(3, 3, 1, 1_000_000L)));
    assertEquals(0, model.pendingHistoryMigrationCountForTest());
  }

  @Test
  public void duplicateLoadedHistoryLineIdIsRejectedWithoutPartialCommit() {
    RemoteTerminalModel model = modelWithRows(3, 1_000_000L);
    model.consumeRenderUpdate();
    RemoteTerminalModel.RenderSnapshot before = model.renderSnapshot();
    long loadedBefore = model.loadedHistoryLineCountForTest();

    try {
      model.applyHistoryDelta(new HistoryDelta(
          "i1", 1, 1, new HistoryExtent(1, 1_000_002L),
          java.util.Arrays.asList(
              line(500, 1, 1_000_001L, "A"),
              line(500, 1, 1_000_002L, "B"))));
    } catch (IllegalStateException expected) {
      assertSame(before, model.renderSnapshot());
      assertEquals(loadedBefore, model.loadedHistoryLineCountForTest());
      assertNull(model.loadedHistorySeqForLineIdForTest(500));
      assertEquals(new HistoryExtent(1, 1_000_000L), model.displayExtent());
      return;
    }
    throw new AssertionError("one loaded LineID must not own two history positions");
  }

  @Test
  public void duplicateLoadedHistoryLineIdWithSameContentIsStillRejected() {
    RemoteTerminalModel model = modelWithRows(3, 1_000_000L);

    try {
      model.applyHistoryDelta(new HistoryDelta(
          "i1", 1, 1, new HistoryExtent(1, 1_000_002L),
          java.util.Arrays.asList(
              line(500, 1, 1_000_001L, "A"),
              line(500, 1, 1_000_002L, "A"))));
    } catch (IllegalStateException expected) {
      assertEquals(1, model.loadedHistoryLineCountForTest());
      assertNull(model.loadedHistorySeqForLineIdForTest(500));
      return;
    }
    throw new AssertionError("LineID uniqueness is positional, not content based");
  }

  @Test
  public void loadedHistoryCannotClaimCurrentScreenLineId() {
    RemoteTerminalModel model = modelWithRows(3, 1_000_000L);
    TerminalLine original = model.renderSnapshot().screen[0];
    long loadedBefore = model.loadedHistoryLineCountForTest();

    try {
      model.applyHistoryDelta(new HistoryDelta(
          "i1", 1, 1, new HistoryExtent(1, 1_000_001L),
          Collections.singletonList(line(original.id, 1, 1_000_001L, "different"))));
    } catch (IllegalStateException expected) {
      assertSame(original, model.renderSnapshot().screen[0]);
      assertEquals(loadedBefore, model.loadedHistoryLineCountForTest());
      assertNull(model.loadedHistorySeqForLineIdForTest(original.id));
      return;
    }
    throw new AssertionError("loaded history must not claim a current screen LineID");
  }

  @Test
  public void sameProjectionBaselineRejectsScreenConflictWithRetainedLoadedPageTransactionally() {
    RemoteTerminalModel model = modelWithRows(3, 1_000_000L);
    assertTrue(model.applyHistoryDelta(new HistoryDelta(
        "i1", 1, 1, new HistoryExtent(1, 1_000_000L),
        Collections.singletonList(line(500, 1, 900_000L, "loaded")))));
    model.consumeRenderUpdate();
    RemoteTerminalModel.RenderSnapshot before = model.renderSnapshot();
    long loadedBefore = model.loadedHistoryLineCountForTest();
    List<TerminalLine> replacementScreen = java.util.Arrays.asList(
        line(500, 1, 0, "screen-conflict"),
        line(20_002, 1, 0, "s1"),
        line(20_003, 1, 0, "s2"));

    assertFalse(model.applyBaseline(new ScreenBaseline(
        "s1", "i1", 1, 2, 1, 3, 1, TerminalBufferKind.MAIN,
        new HistoryExtent(1, 1_000_000L),
        Collections.singletonList(line(9_000, 1, 1_000_000L, "tail")),
        replacementScreen, TerminalCursor.hidden(), TerminalModes.defaults(),
        TerminalPalette.defaults(), "replacement", "")));

    assertSame(before, model.renderSnapshot());
    assertEquals(1, model.screenRevision);
    assertEquals(loadedBefore, model.loadedHistoryLineCountForTest());
    assertEquals(Long.valueOf(900_000L), model.loadedHistorySeqForLineIdForTest(500));
  }

  @Test
  public void layoutCannotResolveLineOwnedOnlyByHistory() throws Exception {
    RemoteTerminalModel model = modelWithRows(3, 1_000_000L);
    model.applyScreenPatch(patch(1, 2, new long[] {10_002, 10_003, 20_000},
        Collections.singletonList(line(20_000, 1, 0, "new"))));
    assertTrue(model.applyHistoryDelta(new HistoryDelta(
        "i1", 1, 1, new HistoryExtent(1, 1_000_001L),
        Collections.singletonList(line(10_001, 1, 1_000_001L, "s0")))));

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

  @Test
  public void identicalSameVersionReplayIsIdempotentAndDoesNotDirtyRow() throws Exception {
    RemoteTerminalModel model = modelWithRows(3, 1_000_000L);
    model.consumeRenderUpdate();
    RemoteTerminalModel.RenderSnapshot published = model.renderSnapshot();
    TerminalLine original = published.screen[0];

    assertFalse(model.applyScreenPatch(patch(1, 2, null,
        Collections.singletonList(line(original.id, original.version, 0, "s0")))));

    assertEquals(2, model.screenRevision);
    assertFalse(model.renderPublicationPendingForTest());
    assertNull(model.consumeRenderUpdate());
    assertNull(model.consumeRenderUpdate());
    assertSame(published, model.renderSnapshot());
    assertSame(published, model.renderSnapshot());
    assertSame(original, model.renderSnapshot().screen[0]);
    assertEquals(3, model.screenLineStoreSize());
  }

  @Test
  public void realPatchAfterRevisionOnlyReplayPublishesLatestRevisionOnce() throws Exception {
    RemoteTerminalModel model = modelWithRows(3, 1_000_000L);
    model.consumeRenderUpdate();
    TerminalLine original = model.renderSnapshot().screen[0];
    assertFalse(model.applyScreenPatch(patch(1, 2, null,
        Collections.singletonList(line(original.id, original.version, 0, "s0")))));

    assertTrue(model.applyScreenPatch(patch(2, 3, null,
        Collections.singletonList(line(original.id, original.version + 1, 0, "changed")))));

    RenderUpdate update = model.consumeRenderUpdate();
    assertEquals(3, update.snapshot.screenRevision);
    assertEquals("changed", update.snapshot.screen[0].at(0).text);
    assertNull(model.consumeRenderUpdate());
    assertFalse(model.renderPublicationPendingForTest());
  }

  @Test
  public void sameVersionDifferentContentIsRejectedWithoutPartialCommit() throws Exception {
    RemoteTerminalModel model = modelWithRows(3, 1_000_000L);
    TerminalLine original = model.renderSnapshot().screen[0];

    try {
      model.applyScreenPatch(patch(1, 2, null,
          Collections.singletonList(line(original.id, original.version, 0, "changed"))));
    } catch (RemoteTerminalModel.RevisionGapException expected) {
      assertEquals("screen.v2 line content changed without version increment",
          expected.getMessage());
      assertEquals(1, model.screenRevision);
      assertSame(original, model.renderSnapshot().screen[0]);
      assertEquals("s0", model.renderSnapshot().screen[0].at(0).text);
      assertEquals(3, model.screenLineStoreSize());
      return;
    }
    throw new AssertionError("same-version content change must be rejected");
  }

  @Test
  public void higherVersionContentChangeMarksOnlyUpdatedRow() throws Exception {
    RemoteTerminalModel model = modelWithRows(3, 1_000_000L);
    model.consumeRenderUpdate();

    model.applyScreenPatch(patch(1, 2, null,
        Collections.singletonList(line(10_001, 2, 0, "changed"))));

    RenderUpdate update = model.consumeRenderUpdate();
    assertEquals(2, model.screenRevision);
    assertEquals("changed", model.renderSnapshot().screen[0].at(0).text);
    assertTrue(update.dirty.changedScreenRows.get(0));
    assertEquals(1, update.dirty.changedScreenRows.cardinality());
  }

  @Test
  public void invalidBaselineRowsAreRejectedBeforeExistingProjectionChanges() {
    RemoteTerminalModel model = modelWithRows(3, 100L);
    RemoteTerminalModel.RenderSnapshot before = model.renderSnapshot();

    List<TerminalLine> invalidHistory = Collections.singletonList(
        new TerminalLine(20_000, 1, 100, false, new TerminalCell[] {null}));
    assertFalse(model.applyBaseline(new ScreenBaseline(
        "s1", "i1", 1, 2, 2, 1, 1, TerminalBufferKind.MAIN,
        new HistoryExtent(1, 100), invalidHistory,
        Collections.singletonList(line(30_000, 1, 0, "screen")),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(), "", "")));
    assertProjectionUnchanged(model, before);

    List<TerminalLine> invalidScreen = Collections.singletonList(
        new TerminalLine(30_000, 1, 0, false, new TerminalCell[] {null}));
    assertFalse(model.applyBaseline(new ScreenBaseline(
        "s1", "i1", 1, 2, 2, 1, 1, TerminalBufferKind.MAIN,
        new HistoryExtent(1, 100), Collections.singletonList(line(20_000, 1, 100, "tail")),
        invalidScreen, TerminalCursor.hidden(), TerminalModes.defaults(),
        TerminalPalette.defaults(), "", "")));
    assertProjectionUnchanged(model, before);
  }

  @Test
  public void baselinePaddingProducesCompleteRowsWithoutLogicalHistoryScan() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    TerminalLine shortHistory = new TerminalLine(9_000, 1, 1_000_000L, false,
        new TerminalCell[] {TerminalCell.EMPTY});
    TerminalLine shortScreen = new TerminalLine(10_001, 1, 0, false,
        new TerminalCell[] {TerminalCell.EMPTY});

    assertTrue(model.applyBaseline(new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 1, 3, TerminalBufferKind.MAIN,
        new HistoryExtent(1, 1_000_000L), Collections.singletonList(shortHistory),
        Collections.singletonList(shortScreen), TerminalCursor.hidden(), TerminalModes.defaults(),
        TerminalPalette.defaults(), "", "")));

    assertEquals(1, model.loadedHistoryLineCountForTest());
    assertEquals(3, model.renderSnapshot().history.lineAt(999_999).length());
    assertEquals(3, model.renderSnapshot().screen[0].length());
  }

  private static void assertProjectionUnchanged(
      RemoteTerminalModel model, RemoteTerminalModel.RenderSnapshot before) {
    assertEquals(before.instanceId, model.instanceId);
    assertEquals(before.layoutEpoch, model.layoutEpoch);
    assertEquals(before.screenRevision, model.screenRevision);
    assertEquals(before.screen.length, model.screenLineStoreSize());
    for (int row = 0; row < before.screen.length; row++) {
      assertSame(before.screen[row], model.renderSnapshot().screen[row]);
    }
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
