package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class SemanticHistoryRenderViewTest {
  @Test
  public void resolvesCatalogThroughSingleBodyCacheWithoutCopyingPositions() throws Exception {
    HistoryExtent extent = new HistoryExtent(100, 102);
    LineKey key = new LineKey(77, 3);
    LineBody body = new LineBody(1, false, new CellValue[] {
        new CellValue("x", (byte) 1, null, null)
    });
    HistoryCatalog catalog = new HistoryCatalog().edit()
        .setExtent(extent)
        .bindNew(101, key)
        .commit();
    BodyCache cache = new BodyCache(HistoryBudget.defaults()).edit()
        .setHistoryExtent(extent)
        .setAvailableExtent(extent)
        .putHistory(101, key, body)
        .commit();

    SemanticHistoryRenderView view = new SemanticHistoryRenderView(catalog, cache);

    assertEquals(3, view.size());
    assertEquals(SlotState.UNLOADED, view.slotStateAt(0));
    assertEquals(SlotState.LOADED, view.slotStateAt(1));
    assertEquals(101, view.lineAt(1).historySeq);
    assertEquals("x", view.lineAt(1).at(0).text);
    assertNull(view.lineAt(0));
  }
}
