package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.webterm.terminal.model.BodyCache;
import com.webterm.terminal.model.HistoryBudget;
import com.webterm.terminal.model.HistoryCatalog;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.SemanticHistoryRenderView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public final class VisibleBodyLoaderTest {
  @Test
  public void acceptedDemandPlansOnceAndPumpConsumesPendingBatch() throws Exception {
    VisibleBodyLoader loader = new VisibleBodyLoader();
    Fixture fixture = fixture(10_000);
    VisibleBodyLoader.Demand demand = loader.acceptDemand(
        5_000, 5_019, 5_000, -1, 20, 0L,
        "i1", 1, 1, fixture.extent, fixture.history, fixture.cache, fixture.catalog);
    assertNotNull(demand);

    VisibleBodyLoader.Batch pending = loader.takePendingBatch(demand, "i1", 1, 1);
    assertNotNull(pending);
    assertEquals("keys=" + pending.keys.size() + " from=" + pending.plannedFromSeq
        + " to=" + pending.plannedToSeq, 128, pending.keys.size());
    assertNull(loader.takePendingBatch(demand, "i1", 1, 1));
  }

  @Test
  public void visibleGapPrefetchesToMinimumBatchTowardOlderHistory() throws Exception {
    VisibleBodyLoader loader = new VisibleBodyLoader();
    Fixture fixture = fixture(1000);
    VisibleBodyLoader.Demand demand = new VisibleBodyLoader.Demand(
        900, 919, 900, -1, 20, 1, 0L);
    VisibleBodyLoader.Batch batch = loader.planMissingBatch(
        demand, "i1", 1, 1, fixture.extent, fixture.history, fixture.cache, fixture.catalog);
    assertNotNull(batch);
    assertEquals("keys=" + batch.keys.size() + " from=" + batch.plannedFromSeq
        + " to=" + batch.plannedToSeq, 128, batch.keys.size());
    assertEquals(20, batch.visibleKeyCount);
    assertEquals(108, batch.prefetchKeyCount);
    assertEquals(new LineKey(900, 1), batch.keys.get(0));
    assertTrue(batch.plannedFromSeq < 900);
    assertEquals(919, batch.plannedToSeq);
  }

  @Test
  public void directionPlusOnePrefersNewerHistory() throws Exception {
    VisibleBodyLoader loader = new VisibleBodyLoader();
    Fixture fixture = fixture(1000);
    VisibleBodyLoader.Demand demand = new VisibleBodyLoader.Demand(
        100, 119, 100, 1, 20, 1, 0L);
    VisibleBodyLoader.Batch batch = loader.planMissingBatch(
        demand, "i1", 1, 1, fixture.extent, fixture.history, fixture.cache, fixture.catalog);
    assertNotNull(batch);
    assertEquals(128, batch.keys.size());
    assertEquals(new LineKey(100, 1), batch.keys.get(0));
    assertEquals(100, batch.plannedFromSeq);
    assertTrue(batch.plannedToSeq > 119);
  }

  @Test
  public void skipsAlreadyLoadedVisibleKeys() throws Exception {
    VisibleBodyLoader loader = new VisibleBodyLoader();
    Fixture fixture = fixture(40);
    LineKey loaded = new LineKey(1, 1);
    BodyCache cache = fixture.cache.edit()
        .putHistory(1, loaded, body())
        .commit();
    SemanticHistoryRenderView history = new SemanticHistoryRenderView(fixture.catalog, cache);
    VisibleBodyLoader.Demand demand = new VisibleBodyLoader.Demand(
        1, 10, 1, 0, 10, 1, 0L);
    VisibleBodyLoader.Batch batch = loader.planMissingBatch(
        demand, "i1", 1, 1, fixture.extent, history, cache, fixture.catalog);
    assertNotNull(batch);
    assertFalse(batch.keys.contains(loaded));
    assertEquals(9, batch.visibleKeyCount);
  }

  @Test
  public void activeBatchCoveringVisibleMissingReusesDemandEpoch() throws Exception {
    VisibleBodyLoader loader = new VisibleBodyLoader();
    Fixture fixture = fixture(300);
    VisibleBodyLoader.Demand accepted = loader.acceptDemand(
        100, 119, 100, -1, 20, 0L,
        "i1", 1, 1, fixture.extent, fixture.history, fixture.cache, fixture.catalog);
    assertNotNull(accepted);
    VisibleBodyLoader.Batch batch = loader.takePendingBatch(accepted, "i1", 1, 1);
    assertNotNull(batch);
    AtomicBoolean cancelled = new AtomicBoolean();
    assertTrue(loader.begin(batch, () -> cancelled.set(true)));

    VisibleBodyLoader.Demand again = loader.acceptDemand(
        105, 124, 105, -1, 20, 0L,
        "i1", 1, 1, fixture.extent, fixture.history, fixture.cache, fixture.catalog);
    assertNotNull(again);
    assertEquals(accepted.demandEpoch, again.demandEpoch);
    assertFalse(cancelled.get());
    assertNotNull(loader.activeRequest());
  }

  @Test
  public void farJumpCancelsActiveRequest() throws Exception {
    VisibleBodyLoader loader = new VisibleBodyLoader();
    Fixture fixture = fixture(1000);
    VisibleBodyLoader.Demand accepted = loader.acceptDemand(
        100, 119, 100, -1, 20, 0L,
        "i1", 1, 1, fixture.extent, fixture.history, fixture.cache, fixture.catalog);
    VisibleBodyLoader.Batch batch = loader.takePendingBatch(
        accepted, "i1", 1, 1);
    assertNotNull(batch);
    AtomicBoolean cancelled = new AtomicBoolean();
    assertTrue(loader.begin(batch, () -> cancelled.set(true)));
    VisibleBodyLoader.ActiveRequest active = loader.activeRequest();
    assertNotNull(active);

    VisibleBodyLoader.Demand jumped = loader.acceptDemand(
        800, 819, 800, -1, 20, 0L,
        "i1", 1, 1, fixture.extent, fixture.history, fixture.cache, fixture.catalog);
    assertNotNull(jumped);
    assertTrue(cancelled.get());
    assertNull(loader.activeRequest());
    assertFalse(active.beginApplying());
  }

  @Test
  public void fillsFromOppositeDirectionWhenPrimarySideExhausted() throws Exception {
    VisibleBodyLoader loader = new VisibleBodyLoader();
    // 可见区贴着历史头部，direction=-1 主方向无可扩展，应向更新侧补齐。
    Fixture fixture = fixture(200);
    VisibleBodyLoader.Demand demand = new VisibleBodyLoader.Demand(
        1, 20, 1, -1, 20, 1, 0L);
    VisibleBodyLoader.Batch batch = loader.planMissingBatch(
        demand, "i1", 1, 1, fixture.extent, fixture.history, fixture.cache, fixture.catalog);
    assertNotNull(batch);
    assertEquals(128, batch.keys.size());
    assertEquals(1, batch.plannedFromSeq);
    assertEquals(128, batch.plannedToSeq);
  }

  @Test
  public void closeCancelsInFlightRequest() {
    VisibleBodyLoader loader = new VisibleBodyLoader();
    AtomicBoolean cancelled = new AtomicBoolean();
    VisibleBodyLoader.Batch batch = new VisibleBodyLoader.Batch(
        "i1", 1, 1, List.of(new LineKey(1, 1)), 1, 1, 1, 1, 0);
    assertTrue(loader.begin(batch, () -> cancelled.set(true)));
    loader.close();
    assertTrue(loader.closed());
    assertNull(loader.activeRequest());
    assertTrue(cancelled.get());
  }

  private static Fixture fixture(int lines) throws Exception {
    List<LineKey> keys = new ArrayList<>(lines);
    for (int i = 1; i <= lines; i++) {
      keys.add(new LineKey(i, 1));
    }
    HistoryCatalog catalog = catalogWithBindings(keys.toArray(new LineKey[0]));
    HistoryExtent extent = new HistoryExtent(1, lines);
    BodyCache cache = new BodyCache(HistoryBudget.defaults())
        .edit()
        .setHistoryExtent(extent)
        .setAvailableExtent(extent)
        .commit();
    return new Fixture(catalog, cache, extent, new SemanticHistoryRenderView(catalog, cache));
  }

  private static HistoryCatalog catalogWithBindings(LineKey... keys) throws Exception {
    HistoryCatalog.Editor editor = new HistoryCatalog().edit();
    editor.setExtent(new HistoryExtent(1, keys.length));
    for (int i = 0; i < keys.length; i++) {
      editor.bindNew(i + 1L, keys[i]);
    }
    return editor.commit();
  }

  private static com.webterm.terminal.model.LineBody body() {
    return new com.webterm.terminal.model.LineBody(1, false,
        new com.webterm.terminal.model.CellValue[] {
            new com.webterm.terminal.model.CellValue("x", (byte) 1, null, null)
        });
  }

  private static final class Fixture {
    final HistoryCatalog catalog;
    final BodyCache cache;
    final HistoryExtent extent;
    final SemanticHistoryRenderView history;

    Fixture(
        HistoryCatalog catalog, BodyCache cache, HistoryExtent extent,
        SemanticHistoryRenderView history) {
      this.catalog = catalog;
      this.cache = cache;
      this.extent = extent;
      this.history = history;
    }
  }
}
