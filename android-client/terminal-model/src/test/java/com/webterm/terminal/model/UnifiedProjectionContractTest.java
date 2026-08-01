package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class UnifiedProjectionContractTest {
  @Test
  public void sameLineKeyCannotStoreDifferentSemanticBody() throws Exception {
    BodyCache cache = new BodyCache(HistoryBudget.defaults());
    LineKey key = new LineKey(10, 2);
    BodyCache.Editor editor = cache.edit();
    editor.putBody(key, body("a"));
    cache = editor.commit();

    try {
      cache.edit().putBody(key, body("b"));
      fail("same LineKey accepted a different body");
    } catch (CommitValidationException expected) {
      assertEquals(CommitFailure.LINE_CONTENT_CONFLICT, expected.failure);
    }
    assertEquals("a", cache.body(key).at(0).text());
  }

  @Test
  public void historyEvictionKeepsCatalogAndDropsOnlyBody() throws Exception {
    HistoryBudget tiny = new HistoryBudget(1, 1, 1 << 20, 1 << 20);
    TerminalSurfaceState surface = new TerminalSurfaceState(tiny);
    TerminalSurfaceTransaction tx = surface.beginTransaction();
    tx.historyCatalog().setExtent(new HistoryExtent(1, 129));
    LineKey first = new LineKey(1, 1);
    LineKey second = new LineKey(2, 1);
    tx.historyCatalog().bindNew(1, first).bindNew(129, second);
    tx.bodyCache().setHistoryExtent(new HistoryExtent(1, 129));
    tx.bodyCache().putHistory(1, first, body("a"));
    tx.bodyCache().putHistory(129, second, body("b"));
    tx.bodyCache().evictIfNeeded(EvictionPins.forAnchor(129));
    surface = tx.commit();

    assertEquals(first, surface.historyCatalog.key(1));
    assertEquals(second, surface.historyCatalog.key(129));
    assertEquals(1, surface.bodyCache.loadedHistoryCount());
    assertNotNull(surface.bodyCache.body(second));
  }

  @Test
  public void screenToHistoryReusesExactlyOneBody() throws Exception {
    LineKey key = new LineKey(10, 3);
    LineBody body = body("x");
    TerminalSurfaceState surface = new TerminalSurfaceState(HistoryBudget.defaults());
    TerminalSurfaceTransaction screen = surface.beginTransaction();
    screen.bodyCache().putBody(key, body);
    screen.activeRows(new ActiveRowLayout(new LineKey[] {key}));
    surface = screen.commit();

    TerminalSurfaceTransaction scroll = surface.beginTransaction();
    scroll.activeRows(ActiveRowLayout.empty());
    scroll.historyCatalog().setExtent(new HistoryExtent(1, 1)).bindNew(1, key);
    scroll.bodyCache().setHistoryExtent(new HistoryExtent(1, 1));
    scroll.bodyCache().markHistoryResident(1, key);
    surface = scroll.commit();

    assertSame(body, surface.bodyCache.body(key));
    assertEquals(key, surface.historyCatalog.key(1));
    assertEquals(key, surface.bodyCache.historyResidency().key(1));
  }

  @Test
  public void failedTransactionDoesNotMutateOriginalRoot() throws Exception {
    TerminalSurfaceState original = new TerminalSurfaceState(HistoryBudget.defaults());
    TerminalSurfaceTransaction tx = original.beginTransaction();
    LineKey missing = new LineKey(9, 1);
    tx.activeRows(new ActiveRowLayout(new LineKey[] {missing}));
    try {
      tx.commit();
      fail("transaction without body committed");
    } catch (IllegalStateException expected) {
      assertEquals(0, original.activeRows.size());
      assertNull(original.bodyCache.body(missing));
    }
  }

  @Test
  public void baselineBuildsCompleteWsCatalogBeforeAnyRange() {
    ScreenProjectionReducer reducer =
        new ScreenProjectionReducer(HistoryBudget.defaults());
    ScreenBaseline baseline = SemanticTestData.baselineLegacy(
        "s", "i", 1, 10, 1, 1,
        1, 1, TerminalBufferKind.MAIN,
        new HistoryExtent(5, 6),
        java.util.Arrays.asList(
            new HistoryPush(5, new LineKey(50, 1)),
            new HistoryPush(6, new LineKey(60, 2))),
        Collections.singletonList(new ScreenLineContent(
            new LineKey(70, 1), body("s"))),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults());

    ProjectionResult result = reducer.applyBaseline(baseline);
    assertTrue(result instanceof ProjectionResult.Applied);
    ProjectionState state = ((ProjectionResult.Applied) result).state();
    assertEquals(new LineKey(50, 1), state.mainSurface.historyCatalog.key(5));
    assertEquals(new LineKey(60, 2), state.mainSurface.historyCatalog.key(6));
    assertEquals(new LineKey(70, 1), state.mainSurface.activeRows.keyAt(0));
    assertNotNull(state.mainSurface.bodyCache.body(new LineKey(50, 1)));
    assertEquals(
        SlotState.UNLOADED,
        state.mainSurface.bodyCache.historyResidency().slotState(5));
  }

  @Test
  public void lateRangeAfterWsRebindIsStaleNotProjectionFault() throws Exception {
    TerminalSurfaceState surface = new TerminalSurfaceState(HistoryBudget.defaults());
    TerminalSurfaceTransaction initial = surface.beginTransaction();
    initial.historyCatalog().setExtent(new HistoryExtent(100, 100));
    initial.historyCatalog().bindNew(100, new LineKey(2001, 1));
    initial.bodyCache().setHistoryExtent(new HistoryExtent(100, 100));
    surface = initial.commit();

    HistoryRangeResult oldResponse = new HistoryRangeResult(
        "r", "i", 1, 1, HistoryRangeResult.Status.OK,
        new HistoryExtent(100, 100),
        Collections.singletonList(new HistoryBodyEntry(
            100, new LineKey(1001, 1), body("old"))), 0);
    HistoryBodyResult result = new HistoryBodyReducer().apply(
        oldResponse,
        new HistoryRequestContext(
            new ProjectionIdentity("i", 1, 1), 100, 100, 100),
        surface,
        EvictionPins.NONE);

    assertTrue(result instanceof HistoryBodyResult.StaleIgnored);
    assertEquals(new LineKey(2001, 1), surface.historyCatalog.key(100));
    assertNull(surface.bodyCache.body(new LineKey(1001, 1)));
  }

  @Test
  public void bodyConflictIsRejectedWithoutNeedsBaselineResult() throws Exception {
    LineKey key = new LineKey(10, 1);
    TerminalSurfaceState surface = new TerminalSurfaceState(HistoryBudget.defaults());
    TerminalSurfaceTransaction initial = surface.beginTransaction();
    initial.historyCatalog().setExtent(new HistoryExtent(1, 1)).bindNew(1, key);
    initial.bodyCache().setHistoryExtent(new HistoryExtent(1, 1));
    initial.bodyCache().putHistory(1, key, body("a"));
    surface = initial.commit();

    HistoryBodyResult result = new HistoryBodyReducer().apply(
        new HistoryRangeResult(
            "r", "i", 1, 1, HistoryRangeResult.Status.OK,
            new HistoryExtent(1, 1),
            Collections.singletonList(new HistoryBodyEntry(1, key, body("b"))), 0),
        new HistoryRequestContext(new ProjectionIdentity("i", 1, 1), 1, 1, 1),
        surface,
        EvictionPins.NONE);

    assertTrue(result instanceof HistoryBodyResult.Rejected);
    assertEquals(
        HistoryBodyFault.BODY_CONFLICT,
        ((HistoryBodyResult.Rejected) result).fault());
    assertEquals("a", surface.bodyCache.body(key).at(0).text());
  }

  @Test
  public void onlyScreenReducerCanReportRevisionGapNeedsBaseline() {
    ScreenProjectionReducer reducer =
        new ScreenProjectionReducer(HistoryBudget.defaults());
    ProjectionResult baseline = reducer.applyBaseline(SemanticTestData.baselineLegacy(
        "s", "i", 1, 10, 1, 1,
        1, 1, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY,
        Collections.emptyList(),
        Collections.singletonList(new ScreenLineContent(
            new LineKey(1, 1), body("x"))),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults()));
    ProjectionState state = ((ProjectionResult.Applied) baseline).state();
    ProjectionResult result = reducer.applyCommit(state, SemanticTestData.commitLegacy(
        "i", 1, 8, 11, 1, 1,
        null, null, null, null, null, null));
    assertTrue(result instanceof ProjectionResult.NeedsBaseline);
    assertEquals(
        ProjectionFault.REVISION_GAP,
        ((ProjectionResult.NeedsBaseline) result).fault());
  }

  @Test
  public void metadataOnlyCommitReusesBothTerminalSurfaces() {
    ScreenProjectionReducer reducer =
        new ScreenProjectionReducer(HistoryBudget.defaults());
    ProjectionResult baseline = reducer.applyBaseline(SemanticTestData.baselineLegacy(
        "s", "i", 1, 10, 1, 1,
        1, 1, TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY,
        Collections.emptyList(),
        Collections.singletonList(new ScreenLineContent(
            new LineKey(1, 1), body("x"))),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults()));
    ProjectionState before = ((ProjectionResult.Applied) baseline).state();

    ProjectionResult result = reducer.applyCommit(before, SemanticTestData.commitLegacy(
        "i", 1, 10, 11, 1, 1,
        null, null, null,
        new TerminalCursor(0, 0, true, TerminalCursor.Shape.BLOCK, false),
        null, null));
    ProjectionState after = ((ProjectionResult.Applied) result).state();

    assertSame(before.mainSurface, after.mainSurface);
    assertSame(before.alternateSurface, after.alternateSurface);
    assertEquals(11, after.screenRevision);
  }

  @Test
  public void twentyThousandHistoryBindingsSurviveOneThousandMetadataCommitsWithoutSurfaceCopies() {
    ScreenProjectionReducer reducer =
        new ScreenProjectionReducer(HistoryBudget.defaults());
    List<HistoryPush> bindings = new ArrayList<>(20_000);
    for (long seq = 1; seq <= 20_000; seq++) {
      bindings.add(new HistoryPush(seq, new LineKey(seq, 1)));
    }
    ProjectionState state = ((ProjectionResult.Applied) reducer.applyBaseline(
        SemanticTestData.baselineLegacy(
            "s", "i", 1, 1, 1, 1,
            1, 1, TerminalBufferKind.MAIN,
            new HistoryExtent(1, 20_000), bindings,
            Collections.singletonList(new ScreenLineContent(
                new LineKey(30_000, 1), body("x"))),
            TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults())))
        .state();
    TerminalSurfaceState originalMain = state.mainSurface;
    TerminalSurfaceState originalAlternate = state.alternateSurface;

    for (int i = 0; i < 1_000; i++) {
      ProjectionResult result = reducer.applyCommit(state, SemanticTestData.commitLegacy(
          "i", 1, state.screenRevision, state.screenRevision + 1, 1, 1,
          null, null, null,
          new TerminalCursor(0, 0, true, TerminalCursor.Shape.BLOCK, (i & 1) == 0),
          null, null));
      assertTrue(result instanceof ProjectionResult.Applied);
      state = ((ProjectionResult.Applied) result).state();
      assertSame(originalMain, state.mainSurface);
      assertSame(originalAlternate, state.alternateSurface);
    }
  }

  private static LineBody body(String text) {
    return new LineBody(1, false, new CellValue[] {
        new CellValue(text, (byte) 1, null, null)
    });
  }
}
