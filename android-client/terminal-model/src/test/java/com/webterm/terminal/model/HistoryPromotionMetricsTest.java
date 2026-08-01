package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public final class HistoryPromotionMetricsTest {
  @Before
  public void resetMetrics() {
    HistoryPromotionMetrics.resetForTest();
  }

  @Test
  public void exactReuseIncrementsPromotionExactReuseCount() throws Exception {
    RemoteTerminalModel model = modelWithScreenLine(new LineKey(10, 1), "a");
    LineBody before = model.bodyCache().body(new LineKey(10, 1));
    assertNotNull(before);

    assertTrue(model.applyTerminalCommit(scrollPushCommit(
        SemanticTestData.screen(12, 1, "c"),
        new HistoryPush(101, new LineKey(10, 1)))));

    assertSame(before, model.bodyCache().body(new LineKey(10, 1)));
    assertEquals(new LineKey(10, 1), model.bodyCache().historyResidency().key(101));
    assertEquals(1L, HistoryPromotionMetrics.snapshot().get("promotionExactReuseCount"));
    assertEquals(0L, HistoryPromotionMetrics.snapshot().get("promotionBodyInvariantFailureCount"));
  }

  @Test
  public void missingBodyKeyFailsCommitAsInvariantFailure() throws Exception {
    RemoteTerminalModel model = modelWithScreenLine(new LineKey(10, 1), "a");
    try {
      model.applyTerminalCommit(scrollPushCommit(
          SemanticTestData.screen(12, 1, "c"),
          new HistoryPush(101, new LineKey(999, 1))));
      org.junit.Assert.fail("expected commit failure");
    } catch (CommitValidationException expected) {
      assertEquals(1L,
          HistoryPromotionMetrics.snapshot().get("promotionBodyInvariantFailureCount"));
    }
  }

  @Test
  public void recordHistoryRequestMissReasonAppearsInSnapshot() {
    HistoryPromotionMetrics.recordBodyInvariantFailure(42);
    HistoryPromotionMetrics.recordHistoryRequestMissReason(
        HistoryPromotionMetrics.reasonFor(42));
    HistoryPromotionMetrics.recordHistoryRequestMissReason(null);

    @SuppressWarnings("unchecked")
    Map<String, Long> byReason =
        (Map<String, Long>) HistoryPromotionMetrics.snapshot().get("historyRequestByMissReason");
    assertEquals(Long.valueOf(1L), byReason.get("PROMOTION_BODY_INVARIANT_FAILURE"));
    assertEquals(Long.valueOf(1L), byReason.get("UNKNOWN"));
  }

  private static RemoteTerminalModel modelWithScreenLine(LineKey screenKey, String text) {
    List<HistoryPush> bindings = new ArrayList<>();
    for (long seq = 1; seq <= 100; seq++) {
      bindings.add(new HistoryPush(seq, new LineKey(100 + seq, 1)));
    }
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(SemanticTestData.baselineLegacy(
        "s1", "i1", 1, 1, 1, 1,
        2, 1, TerminalBufferKind.MAIN,
        new HistoryExtent(1, 100), bindings,
        java.util.Arrays.asList(
            new ScreenLineContent(screenKey, SemanticTestData.body(text)),
            SemanticTestData.screen(11, 1, "b")),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults())));
    return model;
  }

  private static TerminalCommit scrollPushCommit(
      ScreenLineContent exposed, HistoryPush push) {
    return new TerminalCommit(
        "i1", 1, 1, 2, 1, null,
        SemanticTestData.upserts(exposed),
        new ScreenMutation(new ScreenScroll(0, 2, 1),
            Collections.singletonList(ScreenRowWrite.fromLine(1, exposed))),
        new HistoryMutation(new HistoryExtent(1, 101),
            Collections.singletonList(push)),
        null, null, null);
  }
}
