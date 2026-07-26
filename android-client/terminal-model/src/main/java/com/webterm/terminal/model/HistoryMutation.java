package com.webterm.terminal.model;

import java.util.Collections;
import java.util.List;

public final class HistoryMutation {
  public final HistoryExtent finalExtent;
  public final List<LineData> appendedLines;
  public final List<HistoryPromotion> promotions;

  public HistoryMutation(HistoryExtent finalExtent, List<TerminalLine> appendedLines) {
    this(finalExtent, encode(appendedLines), Collections.emptyList());
  }

  private HistoryMutation(HistoryExtent finalExtent, List<LineData> appendedLines,
                          List<HistoryPromotion> promotions) {
    this.finalExtent = finalExtent;
    this.appendedLines = appendedLines == null ? Collections.emptyList() : appendedLines;
    this.promotions = promotions == null ? Collections.emptyList() : promotions;
  }

  public static HistoryMutation fromLineData(
      HistoryExtent finalExtent, List<LineData> appendedLines,
      List<HistoryPromotion> promotions) {
    return new HistoryMutation(finalExtent, appendedLines, promotions);
  }

  private static List<LineData> encode(List<TerminalLine> lines) {
    if (lines == null || lines.isEmpty()) return Collections.emptyList();
    java.util.ArrayList<LineData> encoded = new java.util.ArrayList<>(lines.size());
    for (TerminalLine line : lines) encoded.add(LineData.fromTerminalLine(line));
    return Collections.unmodifiableList(encoded);
  }
}
