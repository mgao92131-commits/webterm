package com.webterm.terminal.model;

/** Surface 的单一原子事务入口。commit 前原 root 不发生任何修改。 */
public final class TerminalSurfaceTransaction {
  private ActiveRowLayout activeRows;
  private final HistoryCatalog.Editor historyCatalog;
  private final BodyCache.Editor bodyCache;
  private boolean committed;

  TerminalSurfaceTransaction(TerminalSurfaceState source) {
    activeRows = source.activeRows;
    historyCatalog = source.historyCatalog.edit();
    bodyCache = source.bodyCache.edit();
  }

  public ActiveRowLayout activeRows() { return activeRows; }
  public HistoryCatalog.Editor historyCatalog() { return historyCatalog; }
  public BodyCache.Editor bodyCache() { return bodyCache; }

  public TerminalSurfaceTransaction activeRows(ActiveRowLayout next) {
    ensureOpen();
    if (next == null) throw new IllegalArgumentException("active rows missing");
    activeRows = next;
    return this;
  }

  public TerminalSurfaceState commit() {
    ensureOpen();
    HistoryCatalog nextCatalog = historyCatalog.commit();
    bodyCache.retainOnlyActiveAndResident(activeRows.keys());
    BodyCache nextCache = bodyCache.commit();
    for (LineKey key : activeRows.keys()) {
      if (!nextCache.contains(key)) {
        throw new IllegalStateException("active row body missing");
      }
      if (nextCatalog.historySeq(key) != null) {
        throw new IllegalStateException("LineKey belongs to screen and history");
      }
    }
    for (HistoryResidencyIndex.ResidentEntry entry :
        nextCache.historyResidency().residentEntries()) {
      if (!entry.key().equals(nextCatalog.key(entry.historySeq()))) {
        throw new IllegalStateException("resident key disagrees with HistoryCatalog");
      }
    }
    committed = true;
    return new TerminalSurfaceState(activeRows, nextCatalog, nextCache);
  }

  private void ensureOpen() {
    if (committed) throw new IllegalStateException("surface transaction already committed");
  }
}
