package com.webterm.terminal.model;

import java.util.Collections;
import java.util.Set;

/**
 * 模型变更摘要。用于 renderer 局部 invalidate。
 */
public final class ModelChange {
  public final boolean fullInvalidate;
  public final Set<Integer> changedScreenRows;
  public final boolean historyChanged;
  public final boolean cursorChanged;
  public final boolean modesChanged;
  public final boolean titleChanged;
  public final int appendedHistoryLines;
  /** True when a snapshot changes the terminal instance or grid geometry. */
  public final boolean geometryChanged;

  public ModelChange(boolean fullInvalidate, Set<Integer> changedScreenRows,
                     boolean historyChanged, boolean cursorChanged,
                     boolean modesChanged, boolean titleChanged) {
    this(fullInvalidate, changedScreenRows, historyChanged, cursorChanged, modesChanged, titleChanged, 0);
  }

  public ModelChange(boolean fullInvalidate, Set<Integer> changedScreenRows,
                     boolean historyChanged, boolean cursorChanged,
                     boolean modesChanged, boolean titleChanged, int appendedHistoryLines) {
    this(fullInvalidate, changedScreenRows, historyChanged, cursorChanged,
        modesChanged, titleChanged, appendedHistoryLines, false);
  }

  public ModelChange(boolean fullInvalidate, Set<Integer> changedScreenRows,
                     boolean historyChanged, boolean cursorChanged,
                     boolean modesChanged, boolean titleChanged, int appendedHistoryLines,
                     boolean geometryChanged) {
    this.fullInvalidate = fullInvalidate;
    this.changedScreenRows = changedScreenRows != null
        ? Collections.unmodifiableSet(changedScreenRows)
        : Collections.emptySet();
    this.historyChanged = historyChanged;
    this.cursorChanged = cursorChanged;
    this.modesChanged = modesChanged;
    this.titleChanged = titleChanged;
    this.appendedHistoryLines = Math.max(0, appendedHistoryLines);
    this.geometryChanged = geometryChanged;
  }

  public static ModelChange full() {
    return full(true);
  }

  public static ModelChange full(boolean geometryChanged) {
    return new ModelChange(true, null, true, true, true, true, 0, geometryChanged);
  }

  public static ModelChange none() {
    return new ModelChange(false, null, false, false, false, false);
  }
}
