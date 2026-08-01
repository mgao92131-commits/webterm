package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HistoryCatalogTest {
  @Test
  public void bindNewIndexesLineId() throws Exception {
    HistoryCatalog.Editor editor = new HistoryCatalog().edit();
    editor.setExtent(new HistoryExtent(1, 3));
    editor.bindNew(1, new LineKey(100, 1));
    editor.bindNew(2, new LineKey(101, 1));
    HistoryCatalog catalog = editor.commit();

    assertEquals(Long.valueOf(1), catalog.historySeqByLineId(100));
    assertEquals(Long.valueOf(2), catalog.historySeqByLineId(101));
    assertNull(catalog.historySeqByLineId(999));
  }

  @Test
  public void bindAuthoritativeUpsertsSameLineId() throws Exception {
    HistoryCatalog.Editor editor = new HistoryCatalog().edit();
    editor.setExtent(new HistoryExtent(1, 2));
    editor.bindNew(1, new LineKey(100, 1));
    editor.bindAuthoritative(1, new LineKey(100, 2));
    HistoryCatalog catalog = editor.commit();

    assertEquals(Long.valueOf(1), catalog.historySeqByLineId(100));
    assertEquals(new LineKey(100, 2), catalog.key(1));
    assertNull(catalog.historySeq(new LineKey(100, 1)));
  }

  @Test
  public void removeClearsLineIdOnlyWhenStillMappedToSeq() throws Exception {
    HistoryCatalog.Editor editor = new HistoryCatalog().edit();
    editor.setExtent(new HistoryExtent(1, 2));
    editor.bindNew(1, new LineKey(100, 1));
    editor.bindAuthoritative(2, new LineKey(100, 2));
    editor.remove(1);
    HistoryCatalog catalog = editor.commit();

    // seq 2 仍持有 lineId 100，remove(1) 不得误删。
    assertEquals(Long.valueOf(2), catalog.historySeqByLineId(100));
  }

  @Test
  public void setExtentRemovesLineIdBindingsOutsideRange() throws Exception {
    HistoryCatalog.Editor editor = new HistoryCatalog().edit();
    editor.setExtent(new HistoryExtent(1, 3));
    editor.bindNew(1, new LineKey(100, 1));
    editor.bindNew(2, new LineKey(101, 1));
    editor.bindNew(3, new LineKey(102, 1));
    editor.setExtent(new HistoryExtent(2, 3));
    HistoryCatalog catalog = editor.commit();

    assertNull(catalog.historySeqByLineId(100));
    assertEquals(Long.valueOf(2), catalog.historySeqByLineId(101));
    assertEquals(Long.valueOf(3), catalog.historySeqByLineId(102));
  }

  @Test
  public void rowOfLineIdUsesCatalogIndex() throws Exception {
    RemoteTerminalModel model = modelWithHistory();
    UnifiedContentAxis axis = model.renderSnapshot().contentAxis;
    assertEquals(Long.valueOf(0), axis.rowOfLineId(900));
    assertEquals(Long.valueOf(1), axis.rowOfLineId(901));
  }

  @Test
  public void rowOfLineKeyUsesCatalogIndex() throws Exception {
    RemoteTerminalModel model = modelWithHistory();
    UnifiedContentAxis axis = model.renderSnapshot().contentAxis;
    assertEquals(Long.valueOf(0), axis.rowOfLineKey(new LineKey(900, 1)));
    assertEquals(Long.valueOf(1), axis.rowOfLineKey(new LineKey(901, 1)));
  }

  private static RemoteTerminalModel modelWithHistory() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    java.util.List<HistoryPush> history = java.util.List.of(
        new HistoryPush(1, new LineKey(900, 1)),
        new HistoryPush(2, new LineKey(901, 1)));
    java.util.List<ScreenLineContent> screen = new java.util.ArrayList<>();
    for (int row = 0; row < 2; row++) {
      screen.add(new ScreenLineContent(
          new LineKey(100 + row, 1),
          new LineBody(1, false, new CellValue[] {
              new CellValue("r" + row, (byte) 1, null, null)
          })));
    }
    assertTrue(model.applyBaseline(SemanticTestData.baselineLegacy(
        "s1", "i1", 1, 1, 1, 1, 2, 1,
        TerminalBufferKind.MAIN,
        new HistoryExtent(1, 2),
        history,
        screen,
        TerminalCursor.hidden(),
        TerminalModes.defaults(),
        TerminalPalette.defaults())));
    return model;
  }
}
