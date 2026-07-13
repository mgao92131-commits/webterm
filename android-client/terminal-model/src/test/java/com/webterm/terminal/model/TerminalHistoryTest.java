package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class TerminalHistoryTest {

  private static final ToLongEstimator EST = new ToLongEstimator();

  @Test
  public void emptyHistory() {
    TerminalHistory h = history();
    assertEquals(0, h.size());
    assertTrue(h.isEmpty());
    assertEquals(-1L, h.firstLineId());
    assertEquals(-1L, h.lastLineId());
    assertEquals(0L, h.estimatedBytes());
    assertTrue(h.snapshot().isEmpty());
  }

  @Test
  public void append_single() {
    TerminalHistory h = history();
    h.append(line(1));
    assertEquals(1, h.size());
    assertEquals(1L, h.firstLineId());
    assertEquals(1L, h.lastLineId());
    assertEquals(lineBytes(1), h.estimatedBytes());
    assertEquals(1L, h.lineAt(0).id);
  }

  @Test
  public void append_manyAcrossChunkBoundary() {
    TerminalHistory h = history();
    int n = TerminalHistory.CHUNK_SIZE + 7;
    for (long id = 1; id <= n; id++) {
      h.append(line(id));
    }
    assertEquals(n, h.size());
    assertEquals(1L, h.firstLineId());
    assertEquals(n, h.lastLineId());
    assertEquals(n * lineBytes(1), h.estimatedBytes());
    assertEquals(1L, h.lineAt(0).id);
    assertEquals(n, h.lineAt(n - 1).id);
    assertEquals(TerminalHistory.CHUNK_SIZE, h.lineAt(TerminalHistory.CHUNK_SIZE - 1).id);
  }

  @Test
  public void appendAll_sameAsLoop() {
    TerminalHistory h1 = history();
    TerminalHistory h2 = history();
    List<TerminalLine> lines = new ArrayList<>();
    for (long id = 1; id <= 50; id++) lines.add(line(id));
    h1.appendAll(lines);
    for (TerminalLine line : lines) h2.append(line);
    assertEquals(h1.size(), h2.size());
    assertEquals(h1.firstLineId(), h2.firstLineId());
    assertEquals(h1.lastLineId(), h2.lastLineId());
    assertEquals(h1.estimatedBytes(), h2.estimatedBytes());
  }

  @Test
  public void prepend_addsToFront() {
    TerminalHistory h = history();
    h.append(line(10));
    h.append(line(11));
    List<TerminalLine> page = new ArrayList<>();
    for (long id = 5; id <= 9; id++) page.add(line(id));
    h.prepend(page);

    assertEquals(7, h.size());
    assertEquals(5L, h.firstLineId());
    assertEquals(11L, h.lastLineId());
    assertEquals(5L, h.lineAt(0).id);
    assertEquals(11L, h.lineAt(6).id);
  }

  @Test
  public void findLineIndex_existing() {
    TerminalHistory h = history();
    for (long id = 1; id <= 300; id++) h.append(line(id));
    assertEquals(0, h.findLineIndex(1));
    assertEquals(299, h.findLineIndex(300));
    assertEquals(TerminalHistory.CHUNK_SIZE - 1, h.findLineIndex(TerminalHistory.CHUNK_SIZE));
    assertEquals(TerminalHistory.CHUNK_SIZE, h.findLineIndex(TerminalHistory.CHUNK_SIZE + 1));
  }

  @Test
  public void findLineIndex_missing() {
    TerminalHistory h = history();
    for (long id = 1; id <= 100; id++) h.append(line(id * 2));
    assertEquals(-1, h.findLineIndex(3));
    assertEquals(-1, h.findLineIndex(0));
    assertEquals(-1, h.findLineIndex(201));
  }

  @Test
  public void snapshot_isImmutableView() {
    TerminalHistory h = history();
    h.append(line(1));
    h.append(line(2));
    h.append(line(3));
    TerminalHistorySnapshot snap = h.snapshot();
    assertEquals(3, snap.size());

    h.append(line(4));
    h.trimHeadUntil(2);
    h.put(lineWithText(3, "z"));

    // append/partial trim/replace must not mutate any chunk already published
    // through an older RenderSnapshot.
    assertEquals(3, snap.size());
    assertEquals(1L, snap.lineAt(0).id);
    assertEquals(2L, snap.lineAt(1).id);
    assertEquals(3L, snap.lineAt(2).id);
    assertEquals("a", snap.lineAt(2).at(0).text);
    assertEquals(0, snap.findLineIndex(1));
    assertEquals(2, snap.findLineIndex(3));
  }

  @Test
  public void appendAfterPartialHeadTrim_startsNewChunkWithoutOverflow() {
    TerminalHistory h = history();
    for (long id = 1; id <= TerminalHistory.CHUNK_SIZE; id++) h.append(line(id));

    h.trimHeadUntil(2);
    h.append(line(TerminalHistory.CHUNK_SIZE + 1L));

    assertEquals(TerminalHistory.CHUNK_SIZE, h.size());
    assertEquals(2L, h.firstLineId());
    assertEquals(TerminalHistory.CHUNK_SIZE + 1L, h.lastLineId());
    TerminalHistorySnapshot snap = h.snapshot();
    assertEquals(2L, snap.lineAt(0).id);
    assertEquals(TerminalHistory.CHUNK_SIZE + 1L,
        snap.lineAt(TerminalHistory.CHUNK_SIZE - 1).id);
  }

  @Test
  public void snapshotIndexing_handlesPartialChunks() {
    TerminalHistory h = history();
    int count = TerminalHistory.CHUNK_SIZE * 3 + 17;
    for (long id = 1; id <= count; id++) h.append(line(id));
    h.trimHeadUntil(11);
    TerminalHistorySnapshot snap = h.snapshot();

    for (int index = 0; index < snap.size(); index++) {
      long expectedId = index + 11L;
      assertEquals(expectedId, snap.lineAt(index).id);
      assertEquals(index, snap.findLineIndex(expectedId));
    }
  }

  @Test
  public void trimHeadUntil_partialChunk() {
    TerminalHistory h = history();
    for (long id = 1; id <= 10; id++) h.append(line(id));
    h.trimHeadUntil(5);
    assertEquals(6, h.size()); // 5..10
    assertEquals(5L, h.firstLineId());
    assertEquals(10L, h.lastLineId());
  }

  @Test
  public void trimHeadUntil_wholeChunks() {
    TerminalHistory h = history();
    int n = TerminalHistory.CHUNK_SIZE * 3;
    for (long id = 1; id <= n; id++) h.append(line(id));
    long firstKept = TerminalHistory.CHUNK_SIZE * 2 + 1L;
    h.trimHeadUntil(firstKept);
    assertEquals(n - (firstKept - 1), h.size());
    assertEquals(firstKept, h.firstLineId());
    assertEquals(n, h.lastLineId());
  }

  @Test
  public void trimHeadToBudget_keepsAtLeastOne() {
    TerminalHistory h = history();
    for (long id = 1; id <= 100; id++) h.append(line(id));
    h.trimHeadToBudget(5, Long.MAX_VALUE);
    assertEquals(5, h.size());
    assertEquals(96L, h.firstLineId());
    assertEquals(100L, h.lastLineId());

    // already under budget: no-op
    h.trimHeadToBudget(5, Long.MAX_VALUE);
    assertEquals(5, h.size());
  }

  @Test
  public void trimHeadToBudget_byBytes() {
    TerminalHistory h = history();
    for (long id = 1; id <= 100; id++) h.append(line(id));
    long perLine = lineBytes(1);
    h.trimHeadToBudget(Integer.MAX_VALUE, perLine * 10);
    assertTrue(h.size() <= 10);
    assertTrue(h.size() >= 1);
    assertTrue(h.estimatedBytes() <= perLine * 10);
    assertEquals(100L, h.lastLineId());
  }

  @Test
  public void trimTailToBudget_evictsNewerSideAndKeepsAnchor() {
    // 原历史 11..30（20 行），prepend 页 1..10（更旧）。anchor = 11（原历史最旧行）。
    TerminalHistory h = history();
    for (long id = 11; id <= 30; id++) h.append(line(id));
    List<TerminalLine> page = new ArrayList<>();
    for (long id = 1; id <= 10; id++) page.add(line(id));
    h.prepend(page);

    h.trimTailToBudget(10, Long.MAX_VALUE, 11L);

    assertEquals(10, h.size());
    assertTrue(h.asMap().containsKey(11L));
    assertFalse(h.asMap().containsKey(30L)); // 较新端被驱逐
  }

  @Test
  public void trimTailToBudget_anchorAtTailFallsBackToHeadEviction() {
    // anchor 是整体最新行：无法从尾部驱逐，转而从头部驱逐。
    TerminalHistory h = history();
    for (long id = 1; id <= 20; id++) h.append(line(id));
    long anchor = 20L;
    h.trimTailToBudget(5, Long.MAX_VALUE, anchor);
    assertEquals(5, h.size());
    assertTrue(h.asMap().containsKey(anchor));
    assertEquals(anchor, h.lastLineId());
  }

  @Test
  public void trimTailToBudget_anchorInsideChunk() {
    TerminalHistory h = history();
    int n = TerminalHistory.CHUNK_SIZE + 10;
    for (long id = 1; id <= n; id++) h.append(line(id));
    long anchor = TerminalHistory.CHUNK_SIZE - 5L;
    h.trimTailToBudget(5, Long.MAX_VALUE, anchor);
    assertTrue(h.asMap().containsKey(anchor));
    assertFalse(h.asMap().containsKey((long) n));
  }

  @Test
  public void estimatedBytes_monotonic() {
    TerminalHistory h = history();
    long sum = 0;
    for (long id = 1; id <= 50; id++) {
      h.append(line(id));
      sum += lineBytes(id);
      assertEquals(sum, h.estimatedBytes());
    }
    h.trimHeadToBudget(10, Long.MAX_VALUE);
    assertTrue(h.estimatedBytes() < sum);
    assertEquals(h.size() * lineBytes(1), h.estimatedBytes());
  }

  // ---- fixtures ----

  private static TerminalHistory history() {
    return new TerminalHistory(EST);
  }

  private static TerminalLine line(long id) {
    return lineWithText(id, "a");
  }

  private static TerminalLine lineWithText(long id, String text) {
    TerminalCell[] cells = new TerminalCell[10];
    for (int i = 0; i < cells.length; i++) {
      cells[i] = new TerminalCell(text, (byte) 1, 0, 0);
    }
    return new TerminalLine(id, false, cells);
  }

  private static long lineBytes(long id) {
    return 48 + 10 * 68; // matches updated RemoteTerminalModel baseline
  }

  private static final class ToLongEstimator implements java.util.function.ToLongFunction<TerminalLine> {
    @Override
    public long applyAsLong(TerminalLine line) {
      return lineBytes(line.id);
    }
  }
}
