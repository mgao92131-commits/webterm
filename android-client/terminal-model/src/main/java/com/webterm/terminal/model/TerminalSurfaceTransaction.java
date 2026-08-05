package com.webterm.terminal.model;

import java.util.HashSet;
import java.util.Set;

/** Surface 的单一原子事务入口。commit 前原 root 不发生任何修改。 */
public final class TerminalSurfaceTransaction {
  private final TerminalSurfaceState source;
  private ActiveRowLayout activeRows;
  private HistoryCatalog.Editor historyCatalog;
  private BodyCache.Editor bodyCache;
  private boolean activeRowsChanged;
  private boolean committed;

  TerminalSurfaceTransaction(TerminalSurfaceState source) {
    this.source = source;
    activeRows = source.activeRows;
  }

  public ActiveRowLayout activeRows() { return activeRows; }
  public HistoryCatalog.Editor historyCatalog() {
    ensureOpen();
    if (historyCatalog == null) historyCatalog = source.historyCatalog.edit();
    return historyCatalog;
  }
  public BodyCache.Editor bodyCache() {
    ensureOpen();
    if (bodyCache == null) bodyCache = source.bodyCache.edit();
    return bodyCache;
  }

  public TerminalSurfaceTransaction activeRows(ActiveRowLayout next) {
    ensureOpen();
    if (next == null) throw new IllegalArgumentException("active rows missing");
    activeRows = next;
    activeRowsChanged = next != source.activeRows;
    return this;
  }

  public TerminalSurfaceState commit() {
    ensureOpen();
    if (!activeRowsChanged && historyCatalog == null && bodyCache == null) {
      committed = true;
      return source;
    }
    HistoryCatalog nextCatalog = historyCatalog == null
        ? source.historyCatalog : historyCatalog.commit();
    BodyCache nextCache;
    if (bodyCache == null) {
      nextCache = source.bodyCache;
    } else {
      Set<LineKey> removedActive = new HashSet<>(source.activeRows.keys());
      removedActive.removeAll(activeRows.keys());
      removedActive.addAll(bodyCache.removedKeys());
      bodyCache.removeUnreferenced(activeRows.keys(), nextCatalog, removedActive);
      nextCache = bodyCache.commit();
    }
    for (LineKey key : activeRows.keys()) {
      if (!nextCache.contains(key)) {
        throw new IllegalStateException("active row body missing");
      }
      if (nextCatalog.historySeq(key) != null) {
        throw new IllegalStateException("LineKey belongs to screen and history");
      }
    }
    committed = true;
    return new TerminalSurfaceState(activeRows, nextCatalog, nextCache);
  }

  private void ensureOpen() {
    if (committed) throw new IllegalStateException("surface transaction already committed");
  }
}
