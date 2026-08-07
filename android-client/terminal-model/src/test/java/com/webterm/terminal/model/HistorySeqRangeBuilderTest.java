package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

public final class HistorySeqRangeBuilderTest {
  @Test
  public void coalescesAscendingWithoutSingletonRanges() {
    HistorySeqRangeBuilder builder = new HistorySeqRangeBuilder();
    for (long seq : new long[] {10, 11, 12, 20, 21}) builder.add(seq);
    assertEquals(
        List.of(new HistorySeqRange(10, 12), new HistorySeqRange(20, 21)),
        builder.build());
  }

  @Test
  public void coalescesDescendingResponseOrder() {
    HistorySeqRangeBuilder builder = new HistorySeqRangeBuilder();
    for (long seq : new long[] {21, 20, 12, 11, 10}) builder.add(seq);
    assertEquals(
        List.of(new HistorySeqRange(10, 12), new HistorySeqRange(20, 21)),
        builder.build());
  }

  @Test
  public void sortsOnlyWhenResponseDirectionChanges() {
    HistorySeqRangeBuilder builder = new HistorySeqRangeBuilder();
    for (long seq : new long[] {12, 10, 11, 11, 21, 20}) builder.add(seq);
    assertEquals(
        List.of(new HistorySeqRange(10, 12), new HistorySeqRange(20, 21)),
        builder.build());
  }
}
