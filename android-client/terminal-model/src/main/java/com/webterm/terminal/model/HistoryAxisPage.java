package com.webterm.terminal.model;

import java.util.Collections;
import java.util.List;

/**
 * 历史轴上的一页投影（对齐 {@link HistoryResidencyIndex#PAGE_SIZE}）。
 * 页内保存 HistorySeq / LineKey / RenderLine，不固化全局 startRow。
 */
final class HistoryAxisPage {
  final long pageNumber;
  final List<UnifiedContentAxis.Item> items;
  final int loadedCount;

  HistoryAxisPage(long pageNumber, List<UnifiedContentAxis.Item> items, int loadedCount) {
    this.pageNumber = pageNumber;
    this.items = items == null ? List.of() : Collections.unmodifiableList(items);
    this.loadedCount = loadedCount;
  }

  boolean isEmpty() {
    return loadedCount <= 0;
  }
}
