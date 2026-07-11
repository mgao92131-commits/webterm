package com.webterm.terminal.model;

import java.util.Collections;
import java.util.List;

/**
 * 历史窗口。firstAvailable 是 Go 权威历史起点；included 是本次携带的范围。
 */
public final class HistoryWindow {
  public final long firstAvailableLineId;
  public final long firstIncludedLineId;
  public final long lastIncludedLineId;
  public final boolean hasMoreBefore;
  public final List<TerminalLine> lines;

  public HistoryWindow(long firstAvailableLineId, long firstIncludedLineId,
                       long lastIncludedLineId, boolean hasMoreBefore,
                       List<TerminalLine> lines) {
    this.firstAvailableLineId = firstAvailableLineId;
    this.firstIncludedLineId = firstIncludedLineId;
    this.lastIncludedLineId = lastIncludedLineId;
    this.hasMoreBefore = hasMoreBefore;
    this.lines = lines != null ? Collections.unmodifiableList(lines) : Collections.emptyList();
  }

  public static HistoryWindow empty() {
    return new HistoryWindow(0, 0, 0, false, Collections.emptyList());
  }
}
