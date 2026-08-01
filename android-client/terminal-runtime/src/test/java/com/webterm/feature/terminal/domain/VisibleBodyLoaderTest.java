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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public final class VisibleBodyLoaderTest {
  @Test
  public void firstMissingBatchCollectsUnloadedKeysAndRespectsSingleFlight()
      throws Exception {
    VisibleBodyLoader loader = new VisibleBodyLoader();
    HistoryCatalog catalog = catalogWithBindings(
        new LineKey(10, 1), new LineKey(11, 1), new LineKey(12, 1));
    BodyCache cache = new BodyCache(HistoryBudget.defaults())
        .edit()
        .setHistoryExtent(new HistoryExtent(1, 3))
        .setAvailableExtent(new HistoryExtent(1, 3))
        .commit();
    SemanticHistoryRenderView history = new SemanticHistoryRenderView(catalog, cache);

    loader.setDemand(new VisibleBodyLoader.Demand(1, 3, 1));
    VisibleBodyLoader.Batch batch = loader.firstMissingBatch(
        "i1", 1, 1, 1, 3, new HistoryExtent(1, 3), history, cache, catalog);
    assertNotNull(batch);
    assertEquals(3, batch.keys.size());
    assertEquals(new LineKey(10, 1), batch.keys.get(0));
    assertEquals(new LineKey(11, 1), batch.keys.get(1));
    assertEquals(new LineKey(12, 1), batch.keys.get(2));

    AtomicBoolean cancelled = new AtomicBoolean();
    assertTrue(loader.begin(batch, () -> cancelled.set(true)));
    assertFalse(loader.begin(
        new VisibleBodyLoader.Batch("i1", 1, 1, batch.keys, 1),
        () -> {}));
    assertNotNull(loader.activeRequest());
    assertFalse(cancelled.get());
  }

  @Test
  public void closeCancelsInFlightRequest() {
    VisibleBodyLoader loader = new VisibleBodyLoader();
    AtomicBoolean cancelled = new AtomicBoolean();
    VisibleBodyLoader.Batch batch =
        new VisibleBodyLoader.Batch("i1", 1, 1, List.of(new LineKey(1, 1)), 1);
    assertTrue(loader.begin(batch, () -> cancelled.set(true)));
    loader.close();
    assertTrue(loader.closed());
    assertNull(loader.activeRequest());
    assertTrue(cancelled.get());
  }

  private static HistoryCatalog catalogWithBindings(LineKey... keys)
      throws Exception {
    HistoryCatalog.Editor editor = new HistoryCatalog().edit();
    editor.setExtent(new HistoryExtent(1, keys.length));
    for (int i = 0; i < keys.length; i++) {
      editor.bindNew(i + 1L, keys[i]);
    }
    return editor.commit();
  }
}
