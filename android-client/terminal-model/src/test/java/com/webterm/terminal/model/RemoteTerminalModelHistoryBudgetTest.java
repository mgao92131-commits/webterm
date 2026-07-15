package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 覆盖 §8.1/§8.3：双上限语义、驱逐方向（tail append 从最旧端、prepend 从较新端
 * 且保护新页与可见锚点）、连续分页不停滞不循环，以及 estimateHistoryLineBytes
 * 相对实测保留量的校准区间。
 */
public final class RemoteTerminalModelHistoryBudgetTest {

  private static final int PAGE_SIZE = 250;

  // ---- §8.3：tail append 超预算仍从最旧端驱逐 ----

  @Test
  public void tailAppend_overLineBudget_evictsOldestAndKeepsNewest() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel(new HistoryBudget(10, 20, 0, 0));
    model.applySnapshot(snapshot(5, 10, 1, 15, false));

    List<TerminalLine> appended = new ArrayList<>();
    for (long id = 16; id <= 25; id++) {
      appended.add(textLine(id, 10, "abcdefghij", 0));
    }
    model.applyPatch(new ScreenPatch(
        "i1", 1, 1, 2, appended, Collections.emptyList(),
        null, null, null, Collections.emptyMap(), Collections.emptyMap(),
        null, null, Collections.emptyList()));

    assertEquals("evict down to the soft line target", 10, model.historySize());
    assertEquals("oldest lines are evicted first", 16L, model.firstCachedHistoryId());
    assertEquals("newest tail lines stay", 25L, lastHistoryId(model));
  }

  // ---- §8.3：prepend 超预算从较新端驱逐，新页与可见锚点保留 ----

  @Test
  public void prepend_overLineBudget_evictsNewestSideAndKeepsPageAndAnchor() {
    RemoteTerminalModel model = new RemoteTerminalModel(new HistoryBudget(10, 20, 0, 0));
    // 20 行，恰好在 hard 上限；最旧行 201 是翻页前的可见锚点。
    model.applySnapshot(snapshot(5, 10, 201, 20, true));

    List<TerminalLine> page = new ArrayList<>();
    for (long id = 196; id <= 200; id++) {
      page.add(textLine(id, 10, "page012345", 0));
    }
    model.prependHistoryPage(new HistoryPage("r1", 1, 1, 1, true, page));

    assertEquals("evict down to the soft line target", 10, model.historySize());
    assertEquals("the whole prepended page survives", 196L, model.firstCachedHistoryId());
    assertTrue("the visible anchor survives", containsHistoryId(model, 201L));
    assertFalse("the newest, screen-side history is evicted instead",
        containsHistoryId(model, 220L));
    assertEquals(205L, lastHistoryId(model));
  }

  @Test
  public void prepend_overByteBudget_evictsNewestSideAndKeepsPageAndAnchor() {
    // 10 列文本行估算约 0.8KB；soft/hard 分别约容 6/10 行。
    RemoteTerminalModel model = new RemoteTerminalModel(
        new HistoryBudget(1_000_000, 1_000_000, 5_000, 8_000));
    model.applySnapshot(snapshot(5, 10, 201, 8, true));
    long anchor = model.firstCachedHistoryId();

    List<TerminalLine> page = new ArrayList<>();
    for (long id = 198; id <= 200; id++) {
      page.add(textLine(id, 10, "page012345", 7));
    }
    model.prependHistoryPage(new HistoryPage("r1", 1, 1, 1, true, page));

    assertTrue("hard byte budget respected after eviction", model.historyBytes() <= 8_000);
    assertEquals("the whole prepended page survives", 198L, model.firstCachedHistoryId());
    assertTrue("the visible anchor survives", containsHistoryId(model, anchor));
    assertFalse("the newest, screen-side history is evicted instead",
        containsHistoryId(model, 208L));
  }

  @Test
  public void prepend_toEmptyCache_overBudget_keepsScreenSideOfPage() {
    // 预算装不下整页：缓存为空时可见锚点是实时屏幕，保留新页中靠近屏幕的行。
    RemoteTerminalModel model = new RemoteTerminalModel(
        new HistoryBudget(1_000_000, 1_000_000, 5_000, 8_000));
    model.applySnapshot(snapshot(5, 10, 1, 0, true));

    List<TerminalLine> page = new ArrayList<>();
    for (long id = 1; id <= 20; id++) {
      page.add(textLine(id, 10, "page012345", 0));
    }
    model.prependHistoryPage(new HistoryPage("r1", 1, 1, 1, false, page));

    assertTrue(model.historySize() < 20);
    assertTrue("the page's oldest lines are dropped first",
        model.firstCachedHistoryId() > 1);
    assertEquals("the screen-side end of the page survives", 20L, lastHistoryId(model));
  }

  // ---- §8.3：连续分页在行数/字节上限附近不停滞、不循环 ----

  @Test
  public void continuousPaging_80colAscii_steadyProgressNearByteBudget() {
    runContinuousPaging(80, ContentKind.ASCII);
  }

  @Test
  public void continuousPaging_80colWideChars_steadyProgressNearByteBudget() {
    runContinuousPaging(80, ContentKind.WIDE);
  }

  @Test
  public void continuousPaging_200colStyled_steadyProgressNearByteBudget() {
    runContinuousPaging(200, ContentKind.STYLED_ASCII);
  }

  @Test
  public void continuousPaging_nearLineLimit_steadyProgress() {
    // 只限制行数（字节预算关闭）：每 2 页触发一次驱逐。
    RemoteTerminalModel model = new RemoteTerminalModel(new HistoryBudget(750, 1000, 0, 0));
    model.applySnapshot(snapshot(24, 80, 10_000, 900, true));

    long previousFirst = model.firstCachedHistoryId();
    Set<Long> requestedBeforeIds = new HashSet<>();
    for (int p = 0; p < 8; p++) {
      long beforeId = model.firstCachedHistoryId();
      assertTrue("beforeId must not repeat (no request loop)", requestedBeforeIds.add(beforeId));
      model.prependHistoryPage(historyPage(p, beforeId - PAGE_SIZE, beforeId, ContentKind.ASCII, 80));

      long first = model.firstCachedHistoryId();
      assertTrue("round " + p + ": beforeId must strictly advance toward older history",
          first < previousFirst);
      assertTrue("round " + p + ": the visible anchor must survive eviction",
          containsHistoryId(model, previousFirst));
      assertTrue("round " + p + ": hard line limit respected", model.historySize() <= 1000);
      previousFirst = first;
    }
  }

  private void runContinuousPaging(int cols, ContentKind kind) {
    // 单行估算（1 字符文本 cell：112 + 4×cols + cols×(64+2)），宽字符行近似同量级；
    // 预算按约 1.25/1.75 页设定，保证第二页起必须驱逐，同时整页受保护可装下。
    long estLineBytes = 112 + cols * 4L + cols * 70L;
    long pageBytes = estLineBytes * PAGE_SIZE;
    HistoryBudget budget = new HistoryBudget(1_000_000, 1_000_000,
        pageBytes * 5 / 4, pageBytes * 7 / 4);
    RemoteTerminalModel model = new RemoteTerminalModel(budget);
    model.applySnapshot(snapshot(24, cols, 10_000, 100, true));

    long previousFirst = model.firstCachedHistoryId();
    Set<Long> requestedBeforeIds = new HashSet<>();
    for (int p = 0; p < 10; p++) {
      long beforeId = model.firstCachedHistoryId();
      assertTrue("beforeId must not repeat (no request loop)", requestedBeforeIds.add(beforeId));
      model.prependHistoryPage(historyPage(p, beforeId - PAGE_SIZE, beforeId, kind, cols));

      long first = model.firstCachedHistoryId();
      assertTrue("round " + p + ": beforeId must strictly advance toward older history",
          first < previousFirst);
      assertTrue("round " + p + ": the visible anchor must survive eviction",
          containsHistoryId(model, previousFirst));
      assertTrue("round " + p + ": the just-prepended page must survive eviction",
          containsHistoryId(model, beforeId - 1));
      assertTrue("round " + p + ": hard byte budget respected",
          model.historyBytes() <= budget.hardBytes);
      previousFirst = first;
    }
  }

  // ---- §8.2：预算参数化构造 ----

  @Test
  public void defaultBudget_matchesDocumentedDefaults() {
    HistoryBudget defaults = HistoryBudget.defaults();
    assertEquals(7500, defaults.softLines);
    assertEquals(10000, defaults.hardLines);
    assertEquals(6L << 20, defaults.softBytes);
    assertEquals(8L << 20, defaults.hardBytes);

    // 默认构造与显式默认值行为一致：10001 行 1 字符行不超字节预算，按行数驱逐到 7500。
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applySnapshot(snapshot(5, 4, 1, 10_001, false));
    assertEquals(7500, model.historySize());
    assertEquals(2502L, model.firstCachedHistoryId());
  }

  @Test
  public void explicitBudget_isRespected() {
    RemoteTerminalModel byLines = new RemoteTerminalModel(new HistoryBudget(50, 100, 0, 0));
    byLines.applySnapshot(snapshot(5, 4, 1, 101, false));
    assertEquals(50, byLines.historySize());
    assertEquals(52L, byLines.firstCachedHistoryId());

    // 字节预算关闭时（0）不触发字节驱逐。
    assertEquals(50, byLines.historySize());
  }

  @Test
  public void historyBudget_clampsInvalidValues() {
    HistoryBudget budget = new HistoryBudget(0, -5, -1, -10);
    assertTrue(budget.softLines >= 1);
    assertTrue(budget.hardLines >= budget.softLines);
    assertTrue(budget.softBytes >= 0);
    assertTrue(budget.hardBytes >= budget.softBytes);
  }

  // ---- §8.2：estimateHistoryLineBytes 校准（JVM 代表样本实测） ----

  @Test
  public void byteEstimate_doesNotSignificantlyUnderestimate_80colAscii() {
    assertEstimateWithinMeasuredBand(80, ContentKind.ASCII, 3000);
  }

  @Test
  public void byteEstimate_doesNotSignificantlyUnderestimate_200colStyledWideMix() {
    assertEstimateWithinMeasuredBand(200, ContentKind.MIXED, 1500);
  }

  /**
   * 在宿主 JVM 上粗量 N 行历史（含 TreeMap 与发布快照拷贝）的实际保留增量，
   * 与模型内部估算总量比较：估算不得低于实测的 60%（不明显低估），
   * 也不得高于 250%（避免过度保守把有效缓存砍半）。结论同时写在
   * RemoteTerminalModel#estimateHistoryLineBytes 的注释中。
   */
  private void assertEstimateWithinMeasuredBand(int cols, ContentKind kind, int lineCount) {
    HistoryBudget unbounded = new HistoryBudget(1_000_000, 1_000_000, 0, 0);
    RemoteTerminalModel model = new RemoteTerminalModel(unbounded);
    model.applySnapshot(snapshot(24, cols, 1, 0, true));

    long before = usedMemoryBytes();
    List<TerminalLine> lines = new ArrayList<>(lineCount);
    for (long id = 1; id <= lineCount; id++) {
      lines.add(contentLine(id, cols, kind));
    }
    model.prependHistoryPage(new HistoryPage("m", 1, 1, 1, false, lines));
    long after = usedMemoryBytes();

    long measured = after - before;
    long estimated = model.historyBytes();
    double ratio = (double) estimated / (double) measured;
    System.out.println("[byteEstimate] cols=" + cols + " kind=" + kind
        + " lines=" + lineCount + " measured=" + measured + " estimated=" + estimated
        + " ratio=" + String.format(java.util.Locale.US, "%.2f", ratio));
    assertTrue("estimate must not significantly underestimate (ratio=" + ratio + ")",
        ratio >= 0.6);
    assertTrue("estimate must not be grossly conservative (ratio=" + ratio + ")",
        ratio <= 2.5);
  }

  private static long usedMemoryBytes() {
    Runtime rt = Runtime.getRuntime();
    for (int i = 0; i < 3; i++) {
      System.gc();
      System.runFinalization();
    }
    return rt.totalMemory() - rt.freeMemory();
  }

  private static boolean containsHistoryId(RemoteTerminalModel model, long lineId) {
    return model.renderSnapshot().history.findLineIndex(lineId) >= 0;
  }

  private static long lastHistoryId(RemoteTerminalModel model) {
    return model.renderSnapshot().history.lastLineId();
  }

  // ---- fixtures ----

  private enum ContentKind { ASCII, STYLED_ASCII, WIDE, MIXED }

  private static HistoryPage historyPage(int page, long firstId, long lastIdExclusive,
                                         ContentKind kind, int cols) {
    List<TerminalLine> lines = new ArrayList<>();
    for (long id = firstId; id < lastIdExclusive; id++) {
      lines.add(contentLine(id, cols, kind));
    }
    return new HistoryPage("r" + page, 1, 1, 1, true, lines);
  }

  private static TerminalLine contentLine(long id, int cols, ContentKind kind) {
    switch (kind) {
      case WIDE:
        return wideLine(id, cols, (int) (id % 4));
      case STYLED_ASCII:
        return textLine(id, cols, "The quick brown fox jumps over 0123456789", (int) (id % 8));
      case MIXED:
        return (id % 2 == 0)
            ? wideLine(id, cols, (int) (id % 4))
            : textLine(id, cols, "mixed 中文 content 0123456789", (int) (id % 8));
      case ASCII:
      default:
        return textLine(id, cols, "abcdefghij", 0);
    }
  }

  /** 每 cell 一个独立 String（不共享），贴近协议解码后的真实占用。 */
  private static TerminalLine textLine(long id, int cols, String fill, int styleId) {
    TerminalCell[] cells = new TerminalCell[cols];
    for (int i = 0; i < cols; i++) {
      String text = new String(new char[]{fill.charAt((i + (int) (id % fill.length())) % fill.length())});
      cells[i] = new TerminalCell(text, (byte) 1, styleId, 0);
    }
    return new TerminalLine(id, false, cells);
  }

  /** 宽字符 + SPACER 交替，贴近 CJK 行的真实布局。 */
  private static TerminalLine wideLine(long id, int cols, int styleId) {
    TerminalCell[] cells = new TerminalCell[cols];
    char c = (char) ('一' + (int) (id % 200));
    for (int i = 0; i < cols; i += 2) {
      cells[i] = new TerminalCell(new String(new char[]{(char) (c + i % 13)}), (byte) 2, styleId, 0);
      if (i + 1 < cols) {
        cells[i + 1] = TerminalCell.SPACER;
      }
    }
    return new TerminalLine(id, false, cells);
  }

  private static ScreenSnapshot snapshot(int rows, int cols, long firstHistoryId,
                                         int historyCount, boolean hasMoreBefore) {
    List<TerminalLine> screen = new ArrayList<>();
    for (int r = 0; r < rows; r++) {
      screen.add(TerminalLine.empty(r, cols));
    }
    List<TerminalLine> history = new ArrayList<>();
    for (int i = 0; i < historyCount; i++) {
      history.add(textLine(firstHistoryId + i, cols, "h", 0));
    }
    long lastId = historyCount > 0 ? firstHistoryId + historyCount - 1 : firstHistoryId;
    return new ScreenSnapshot(
        "s1", "i1", 1, 1, rows, cols, ScreenSnapshot.BufferKind.MAIN,
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(),
        new HistoryWindow(firstHistoryId, firstHistoryId, lastId, hasMoreBefore, history),
        screen, Collections.emptyMap(), Collections.emptyMap(), "", ""
    );
  }
}
