package com.webterm.terminal.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 历史分页响应。lines 按 lineId 升序。
 */
public final class HistoryPage {
  public final String requestId;
  public final long layoutEpoch;
  public final long asOfRevision;
  public final long firstAvailableLineId;
  public final boolean hasMoreBefore;
  public final List<TerminalLine> lines;
  public final Map<Integer, TerminalStyle> styles;
  public final Map<Integer, Hyperlink> links;

  public HistoryPage(String requestId, long layoutEpoch, long asOfRevision,
                     long firstAvailableLineId, boolean hasMoreBefore,
                     List<TerminalLine> lines) {
    this(requestId, layoutEpoch, asOfRevision, firstAvailableLineId, hasMoreBefore,
        lines, Collections.emptyMap(), Collections.emptyMap());
  }

  public HistoryPage(String requestId, long layoutEpoch, long asOfRevision,
                     long firstAvailableLineId, boolean hasMoreBefore,
                     List<TerminalLine> lines,
                     Map<Integer, TerminalStyle> styles,
                     Map<Integer, Hyperlink> links) {
    this.requestId = requestId;
    this.layoutEpoch = layoutEpoch;
    this.asOfRevision = asOfRevision;
    this.firstAvailableLineId = firstAvailableLineId;
    this.hasMoreBefore = hasMoreBefore;
    this.lines = lines != null ? Collections.unmodifiableList(lines) : Collections.emptyList();
    this.styles = styles != null ? Collections.unmodifiableMap(styles) : Collections.emptyMap();
    this.links = links != null ? Collections.unmodifiableMap(links) : Collections.emptyMap();
  }
}
