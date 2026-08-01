package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class BaselineBodyReuseTest {
  @Test
  public void compatibleBaselineReusesHistoryAxisTopology() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baselineWithHistory(300, 1)));
    for (int seq = 1; seq <= 10; seq++) {
      LineKey key = new LineKey(10_000L + seq, 1);
      assertTrue(model.applyHistoryBody(
          range(seq, key, "h"), request(seq, seq)) instanceof HistoryBodyResult.Applied);
    }
    UnifiedContentAxis.Item before = model.renderSnapshot().contentAxis.itemAtRow(0);

    assertTrue(model.applyBaseline(baselineWithHistory(300, 2)));

    UnifiedContentAxis.Item after = model.renderSnapshot().contentAxis.itemAtRow(0);
    assertSame(before, after);
    assertEquals(
        UnifiedContentAxis.Kind.MISSING_HISTORY_RANGE,
        model.renderSnapshot().contentAxis.itemAtRow(100).kind);
  }

  @Test
  public void reusesExactLineKeysAcrossCompatibleBaseline() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baselineWithHistory(500, 1)));
    for (int seq = 1; seq <= 500; seq++) {
      LineKey key = new LineKey(10_000L + seq, 1);
      assertTrue(model.applyHistoryBody(
          range(seq, key, "h"),
          request(seq, seq)) instanceof HistoryBodyResult.Applied);
    }
    assertEquals(501, model.bodyCache().bodyCount());

    assertTrue(model.applyBaseline(baselineWithHistory(450, 2)));
    assertNotNull(model.bodyCache().body(new LineKey(10_001, 1)));
    assertNotNull(model.bodyCache().body(new LineKey(10_450, 1)));
    assertNull(model.bodyCache().body(new LineKey(10_451, 1)));
    assertEquals(
        SlotState.LOADED,
        model.renderSnapshot().history.slotStateAt(0));
  }

  @Test
  public void layoutEpochChangeStillReusesBodies() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baselineWithHistory(10, 1)));
    LineKey key = new LineKey(10_001, 1);
    assertTrue(model.applyHistoryBody(range(1, key, "keep"), request(1, 1))
        instanceof HistoryBodyResult.Applied);

    ScreenBaseline next = SemanticTestData.baselineLegacy(
        "s1", "i1", 9, 2, 1, 1, 1, 1,
        TerminalBufferKind.MAIN,
        new HistoryExtent(1, 10),
        historyBindings(10),
        Collections.singletonList(new ScreenLineContent(
            new LineKey(9000, 1), body("s"))),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults());
    assertTrue(model.applyBaseline(next));
    assertEquals("keep", model.bodyCache().body(key).at(0).text());
  }

  @Test
  public void generationChangeDropsReuse() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baselineWithHistory(10, 1)));
    LineKey key = new LineKey(10_001, 1);
    assertTrue(model.applyHistoryBody(range(1, key, "old"), request(1, 1))
        instanceof HistoryBodyResult.Applied);

    ScreenBaseline next = SemanticTestData.baselineLegacy(
        "s1", "i1", 1, 2, 1, 2, 1, 1,
        TerminalBufferKind.MAIN,
        new HistoryExtent(1, 10),
        historyBindings(10),
        Collections.singletonList(new ScreenLineContent(
            new LineKey(9000, 1), body("s"))),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults());
    assertTrue(model.applyBaseline(next));
    assertNull(model.bodyCache().body(key));
  }

  @Test
  public void instanceChangeDropsReuse() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baselineWithHistory(5, 1)));
    LineKey key = new LineKey(10_001, 1);
    assertTrue(model.applyHistoryBody(range(1, key, "old"), request(1, 1))
        instanceof HistoryBodyResult.Applied);

    ScreenBaseline next = SemanticTestData.baselineLegacy(
        "s1", "i2", 1, 2, 1, 1, 1, 1,
        TerminalBufferKind.MAIN,
        new HistoryExtent(1, 5),
        historyBindings(5),
        Collections.singletonList(new ScreenLineContent(
            new LineKey(9000, 1), body("s"))),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults());
    assertTrue(model.applyBaseline(next));
    assertNull(model.bodyCache().body(key));
  }

  @Test
  public void conflictingScreenBodyIsRejected() throws Exception {
    ProjectionState previous = seededState(body("a"));
    ScreenBaseline baseline = SemanticTestData.baselineLegacy(
        "s1", "i1", 1, 2, 1, 1, 1, 1,
        TerminalBufferKind.MAIN,
        HistoryExtent.INITIAL_EMPTY,
        Collections.emptyList(),
        Collections.singletonList(new ScreenLineContent(
            new LineKey(9000, 1), body("b"))),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults());
    ProjectionState next = ((ProjectionResult.Applied)
        new ScreenProjectionReducer(HistoryBudget.defaults()).applyBaseline(baseline)).state();

    BaselineBodyReuse.Outcome outcome =
        BaselineBodyReuse.reuse(previous, next, baseline, EvictionPins.NONE);
    assertTrue(outcome instanceof BaselineBodyReuse.Outcome.Conflict);
  }

  @Test
  public void sameLineKeyMovesToNewHistorySeq() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    LineKey key = new LineKey(42, 7);
    assertTrue(model.applyBaseline(SemanticTestData.baselineLegacy(
        "s1", "i1", 1, 1, 1, 1, 1, 1,
        TerminalBufferKind.MAIN,
        new HistoryExtent(100, 100),
        Collections.singletonList(new HistoryPush(100, key)),
        Collections.singletonList(new ScreenLineContent(
            new LineKey(9000, 1), body("s"))),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults())));
    assertTrue(model.applyHistoryBody(
        range(100, key, "immortal"), request(100, 100))
        instanceof HistoryBodyResult.Applied);

    assertTrue(model.applyBaseline(SemanticTestData.baselineLegacy(
        "s1", "i1", 1, 2, 1, 1, 1, 1,
        TerminalBufferKind.MAIN,
        new HistoryExtent(200, 200),
        Collections.singletonList(new HistoryPush(200, key)),
        Collections.singletonList(new ScreenLineContent(
            new LineKey(9000, 1), body("s"))),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults())));
    assertEquals("immortal", model.bodyCache().body(key).at(0).text());
    assertEquals(key, model.historyCatalog().key(200));
    assertEquals(key, model.bodyCache().historyResidency().key(200));
  }

  private static ProjectionState seededState(LineBody screenBody) throws Exception {
    TerminalSurfaceState surface = new TerminalSurfaceState(HistoryBudget.defaults());
    TerminalSurfaceTransaction tx = surface.beginTransaction();
    LineKey key = new LineKey(9000, 1);
    tx.bodyCache().putBody(key, screenBody);
    tx.activeRows(new ActiveRowLayout(new LineKey[] {key}));
    surface = tx.commit();
    return new ProjectionState(
        new ProjectionIdentity("i1", 1, 1),
        1, 1, 1, TerminalBufferKind.MAIN,
        surface, new TerminalSurfaceState(HistoryBudget.defaults()),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults());
  }

  private static ScreenBaseline baselineWithHistory(int historyLines, long revision) {
    return SemanticTestData.baselineLegacy(
        "s1", "i1", 1, revision, 1, 1, 1, 1,
        TerminalBufferKind.MAIN,
        new HistoryExtent(1, historyLines),
        historyBindings(historyLines),
        Collections.singletonList(new ScreenLineContent(
            new LineKey(9000, 1), body("s"))),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults());
  }

  private static List<HistoryPush> historyBindings(int count) {
    List<HistoryPush> bindings = new ArrayList<>(count);
    for (int seq = 1; seq <= count; seq++) {
      bindings.add(new HistoryPush(seq, new LineKey(10_000L + seq, 1)));
    }
    return bindings;
  }

  private static HistoryRangeResult range(long seq, LineKey key, String text) {
    return new HistoryRangeResult(
        "r", "i1", 1, 1, HistoryRangeResult.Status.OK,
        new HistoryExtent(seq, seq),
        Collections.singletonList(new HistoryBodyEntry(seq, key, body(text))),
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
