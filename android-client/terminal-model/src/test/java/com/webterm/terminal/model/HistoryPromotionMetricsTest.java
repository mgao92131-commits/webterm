package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
    assertEquals(0L, HistoryPromotionMetrics.snapshot().get("promotionVersionMismatchCount"));
    assertEquals(0L, HistoryPromotionMetrics.snapshot().get("promotionBodyAbsentCount"));
  }

  @Test
  public void versionMismatchIncrementsCountAndDoesNotMarkResidentUnderWrongKey()
      throws Exception {
    RemoteTerminalModel model = modelWithScreenLine(new LineKey(10, 2), "a");
    LineBody bodyV2 = model.bodyCache().body(new LineKey(10, 2));
    assertNotNull(bodyV2);

    assertTrue(model.applyTerminalCommit(scrollPushCommit(
        SemanticTestData.screen(12, 1, "c"),
        new HistoryPush(101, new LineKey(10, 1)))));

    assertEquals(1L, HistoryPromotionMetrics.snapshot().get("promotionVersionMismatchCount"));
    assertEquals(0L, HistoryPromotionMetrics.snapshot().get("promotionExactReuseCount"));
    // 禁止 lineId-only 复用：catalog 绑定 push key，但不得把 (10,2) 升为 resident。
    assertEquals(new LineKey(10, 1), model.historyCatalog().key(101));
    assertNull(model.bodyCache().historyResidency().key(101));
    assertNull(model.bodyCache().body(new LineKey(10, 1)));
    assertNotNull(bodyV2);
    assertEquals(
        HistoryBodyMissReason.PROMOTION_VERSION_MISMATCH,
        HistoryPromotionMetrics.reasonFor(101));
  }

  @Test
  public void bodyAbsentIncrementsCountForUnknownLineId() throws Exception {
    RemoteTerminalModel model = modelWithScreenLine(new LineKey(10, 1), "a");

    assertTrue(model.applyTerminalCommit(scrollPushCommit(
        SemanticTestData.screen(12, 1, "c"),
        new HistoryPush(101, new LineKey(999, 1)))));

    assertEquals(1L, HistoryPromotionMetrics.snapshot().get("promotionBodyAbsentCount"));
    assertEquals(0L, HistoryPromotionMetrics.snapshot().get("promotionExactReuseCount"));
    assertNull(model.bodyCache().historyResidency().key(101));
    assertEquals(
        HistoryBodyMissReason.PROMOTION_BODY_ABSENT,
        HistoryPromotionMetrics.reasonFor(101));
  }

  @Test
  public void recordHistoryRequestMissReasonAppearsInSnapshot() {
    HistoryPromotionMetrics.recordVersionMismatch(42, 2, 1);
    HistoryPromotionMetrics.recordHistoryRequestMissReason(
        HistoryPromotionMetrics.reasonFor(42));
    HistoryPromotionMetrics.recordHistoryRequestMissReason(null);

    @SuppressWarnings("unchecked")
    Map<String, Long> byReason =
        (Map<String, Long>) HistoryPromotionMetrics.snapshot().get("historyRequestByMissReason");
    assertEquals(Long.valueOf(1L), byReason.get("PROMOTION_VERSION_MISMATCH"));
    assertEquals(Long.valueOf(1L), byReason.get("UNKNOWN"));
    assertEquals(Long.valueOf(0L), byReason.get("PROMOTION_BODY_ABSENT"));
  }

  private static RemoteTerminalModel modelWithScreenLine(LineKey screenKey, String text) {
    List<HistoryPush> bindings = new ArrayList<>();
    for (long seq = 1; seq <= 100; seq++) {
      bindings.add(new HistoryPush(seq, new LineKey(100 + seq, 1)));
    }
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(new ScreenBaseline(
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
        "i1", 1, 1, 2, 1, 1, null,
        new ScreenMutation(new ScreenScroll(0, 2, 1),
            Collections.singletonList(new ScreenRowWrite(1, exposed))),
        new HistoryMutation(new HistoryExtent(1, 101),
            Collections.singletonList(push)),
        null, null, null);
  }
}
