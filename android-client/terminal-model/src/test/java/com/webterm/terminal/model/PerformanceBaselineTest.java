package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 可重复的 Android 端性能基线（计划 docs/go-android-terminal-performance-optimization-plan.md §5.2）。
 *
 * <p>覆盖：tail append（每 patch 追加一行历史）、单行活动屏幕修改、publishRenderSnapshot
 * 全量历史复制、持续输出时的滚动/follow-tail 视口行为，以及 styled ASCII / CJK / emoji /
 * combining mark / 宽字符混合内容。数据生成全部固定 seed，保证可重复。</p>
 *
 * <p>本测试只打印结构化基线报告（[PerfBaseline] 前缀），不对耗时设阈值断言，
 * 只对模型正确性做必要断言。耗时阈值交给后续优化阶段对比基线时人工判断。</p>
 *
 * <p>指标测量方法：
 * <ul>
 *   <li>耗时：System.nanoTime 单次采样，P50/P95/P99/max/mean（微秒）。</li>
 *   <li>分配量：com.sun.management.ThreadMXBean.getThreadAllocatedBytes 前后差值 / 迭代次数，
 *       patch 对象在循环外预构建，不计入被测分配。</li>
 *   <li>GC：GarbageCollectorMXBean collectionCount/collectionTime 前后差值。</li>
 *   <li>publishRenderSnapshot 是 private，用反射直接调用以隔离 history 全量复制成本。</li>
 * </ul></p>
 */
public final class PerformanceBaselineTest {

  private static final int WARMUP = 200;
  private static final int ITERATIONS = 1000;
  private static final int PUBLISH_ITERATIONS = 500;

  /** 固定数据规模：{cols, rows, history lines}。 */
  private static final int[][] SIZES = {
      {80, 24, 1000},
      {120, 40, 5000},
      {200, 50, 10000},
  };

  private static final String[] EMOJI = {"😀", "🚀", "🎉", "👍", "🔥", "🐛", "📦", "🌟"};
  private static final String[] COMBINING = {"é", "à", "ñ", "ö", "û"};

  private enum Content {
    ASCII, STYLED_ASCII, CJK, EMOJI, COMBINING, MIXED
  }

  // ---------------------------------------------------------------- tail append

  /**
   * 负载 1：每 patch 追加一行历史（tail append，持续输出）。soft==hard==目标行数，
   * 稳态下每次 append 驱逐一行最旧历史，历史规模保持固定，publish 每次都走全量复制。
   */
  @Test
  public void tailAppend_baseline() throws Exception {
    printEnv("tail-append");
    for (int[] size : SIZES) {
      int cols = size[0], rows = size[1], history = size[2];
      for (Content content : new Content[]{Content.ASCII, Content.MIXED}) {
        RemoteTerminalModel model = newModel(cols, rows, history, content);
        Map<Integer, TerminalStyle> newStyles = content == Content.MIXED ? styles() : Collections.emptyMap();

        // 循环外预构建全部 patch：withId 共享 cells 数组，模拟 Go 侧逐帧生成的新行。
        List<ScreenPatch> patches = new ArrayList<>(WARMUP + ITERATIONS);
        TerminalLine appended = contentLine(0, cols, content, seed(cols, content));
        for (int i = 0; i < WARMUP + ITERATIONS; i++) {
          long baseRevision = 1 + i;
          patches.add(new ScreenPatch("i1", 1, baseRevision, baseRevision + 1,
              Collections.singletonList(appended.withId(history + 1L + i)),
              Collections.emptyList(), null, null, null, newStyles, Collections.emptyMap(),
              null, null, Collections.emptyList()));
        }

        for (int i = 0; i < WARMUP; i++) {
          model.applyPatch(patches.get(i));
        }

        long allocBefore = allocatedBytes();
        long[] gcBefore = gcSnapshot();
        long[] times = new long[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
          ScreenPatch patch = patches.get(WARMUP + i);
          long t0 = System.nanoTime();
          model.applyPatch(patch);
          times[i] = System.nanoTime() - t0;
        }
        long allocPerOp = perOp(allocatedBytes() - allocBefore, ITERATIONS);
        long[] gcDelta = gcDelta(gcBefore, gcSnapshot());

        // 正确性：稳态历史行数固定、revision 连续推进、snapshot 历史完整。
        assertEquals("steady-state history size", history, model.historySize());
        assertEquals(1L + WARMUP + ITERATIONS, model.screenRevision);
        assertEquals(history, model.renderSnapshot().history.size());

        report("apply-patch/tail-append", cols, rows, history, content, ITERATIONS, times,
            allocPerOp, gcDelta);
      }
    }
  }

  // ------------------------------------------------------- single screen row modify

  /**
   * 负载 2：每 patch 只修改一行活动屏幕（局部重绘）。history 不变，
   * publish 复用上一帧 historyLines，成本来自 screen.clone() 与单行替换。
   * 内容维度覆盖 styled ASCII / CJK / emoji / combining / 宽字符混合。
   */
  @Test
  public void screenRowModify_baseline() throws Exception {
    printEnv("screen-row-modify");
    for (int[] size : SIZES) {
      int cols = size[0], rows = size[1], history = size[2];
      for (Content content : Content.values()) {
        RemoteTerminalModel model = newModel(cols, rows, history, Content.ASCII);
        int row = rows / 2;
        Map<Integer, TerminalStyle> newStyles = usesStyles(content) ? styles() : Collections.emptyMap();
        TerminalLine lineA = contentLine(row, cols, content, seed(cols, content));
        TerminalLine lineB = contentLine(row, cols, content, seed(cols, content) + 1);

        List<ScreenPatch> patches = new ArrayList<>(WARMUP + ITERATIONS);
        for (int i = 0; i < WARMUP + ITERATIONS; i++) {
          long baseRevision = 1 + i;
          patches.add(new ScreenPatch("i1", 1, baseRevision, baseRevision + 1,
              Collections.emptyList(),
              Collections.singletonList((i & 1) == 0 ? lineA : lineB),
              null, null, null, newStyles, Collections.emptyMap(),
              null, null, Collections.emptyList()));
        }

        for (int i = 0; i < WARMUP; i++) {
          model.applyPatch(patches.get(i));
        }

        long allocBefore = allocatedBytes();
        long[] gcBefore = gcSnapshot();
        long[] times = new long[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
          ScreenPatch patch = patches.get(WARMUP + i);
          long t0 = System.nanoTime();
          model.applyPatch(patch);
          times[i] = System.nanoTime() - t0;
        }
        long allocPerOp = perOp(allocatedBytes() - allocBefore, ITERATIONS);
        long[] gcDelta = gcDelta(gcBefore, gcSnapshot());

        // 正确性：目标行内容已更新、revision 推进、历史未被触碰。
        assertEquals(1L + WARMUP + ITERATIONS, model.screenRevision);
        assertEquals(history, model.historySize());
        TerminalLine[] screen = model.renderSnapshot().screen;
        assertNotNull(screen);
        assertEquals(lineB.at(0).text, screen[row].at(0).text);

        report("apply-patch/screen-row", cols, rows, history, content, ITERATIONS, times,
            allocPerOp, gcDelta);
      }
    }
  }

  // -------------------------------------------------------- publishRenderSnapshot

  /**
   * 隔离测量 publishRenderSnapshot：historyChanged=true 时执行
   * new ArrayList<>(historyCache.values()) 全量复制，成本随历史规模增长；
   * historyChanged=false 时复用上一帧列表。
   */
  @Test
  public void publishRenderSnapshot_baseline() throws Exception {
    printEnv("publish-render-snapshot");
    Method publish = RemoteTerminalModel.class.getDeclaredMethod(
        "publishRenderSnapshot", boolean.class, boolean.class, boolean.class);
    publish.setAccessible(true);

    for (int[] size : SIZES) {
      int cols = size[0], rows = size[1], history = size[2];
      RemoteTerminalModel model = newModel(cols, rows, history, Content.ASCII);
      model.consumeRenderUpdate();

      for (boolean historyChanged : new boolean[]{true, false}) {
        for (int i = 0; i < WARMUP; i++) {
          publish.invoke(model, historyChanged, false, false);
        }
        long allocBefore = allocatedBytes();
        long[] gcBefore = gcSnapshot();
        long[] times = new long[PUBLISH_ITERATIONS];
        for (int i = 0; i < PUBLISH_ITERATIONS; i++) {
          long t0 = System.nanoTime();
          publish.invoke(model, historyChanged, false, false);
          times[i] = System.nanoTime() - t0;
        }
        long allocPerOp = perOp(allocatedBytes() - allocBefore, PUBLISH_ITERATIONS);
        long[] gcDelta = gcDelta(gcBefore, gcSnapshot());

        RemoteTerminalModel.RenderSnapshot snapshot = model.renderSnapshot();
        assertEquals(history, snapshot.history.size());
        assertNotNull(snapshot.screen);
        assertEquals(rows, snapshot.screen.length);
        if (!historyChanged) {
          // 复用语义：history 未变化时相邻两帧必须共享同一个 snapshot 实例。
          publish.invoke(model, false, false, false);
          assertSame(snapshot.history, model.renderSnapshot().history);
        }

        report(historyChanged ? "publish/history-copy" : "publish/history-reuse",
            cols, rows, history, Content.ASCII, PUBLISH_ITERATIONS, times, allocPerOp, gcDelta);
      }
    }
  }

  // ------------------------------------------------------ scroll / follow-tail

  /**
   * 负载 3：持续输出时的滚动/follow-tail 行为。TerminalViewportState 是纯 UI 状态，
   * 模型 applyPatch 不读写它；本场景验证滚上去之后持续 append 不破坏钉住语义，
   * 并记录「applyPatch + scrollBy」单帧成本（预期与 tail-append 基本相同）。
   */
  @Test
  public void scrollFollowTail_baseline() throws Exception {
    printEnv("scroll-follow-tail");
    for (int[] size : SIZES) {
      int cols = size[0], rows = size[1], history = size[2];
      RemoteTerminalModel model = newModel(cols, rows, history, Content.ASCII);
      TerminalViewportState viewport = new TerminalViewportState();
      int lineHeightPx = 20;
      int maxScroll = history * lineHeightPx;

      // 用户向上滚到历史中段：followTail 必须解除。
      viewport.scrollBy(maxScroll / 2, maxScroll);
      assertFalse("scrolled up must release follow-tail", viewport.followTail);
      int pinnedOffset = viewport.scrollOffsetPixels;

      List<ScreenPatch> patches = new ArrayList<>(WARMUP + ITERATIONS);
      TerminalLine appended = contentLine(0, cols, Content.ASCII, seed(cols, Content.ASCII));
      for (int i = 0; i < WARMUP + ITERATIONS; i++) {
        long baseRevision = 1 + i;
        patches.add(new ScreenPatch("i1", 1, baseRevision, baseRevision + 1,
            Collections.singletonList(appended.withId(history + 1L + i)),
            Collections.emptyList(), null, null, null, Collections.emptyMap(),
            Collections.emptyMap(), null, null, Collections.emptyList()));
      }
      for (int i = 0; i < WARMUP; i++) {
        model.applyPatch(patches.get(i));
      }

      long allocBefore = allocatedBytes();
      long[] gcBefore = gcSnapshot();
      long[] times = new long[ITERATIONS];
      for (int i = 0; i < ITERATIONS; i++) {
        long t0 = System.nanoTime();
        model.applyPatch(patches.get(WARMUP + i));
        // 视口保持钉住：模型追加历史时 UI 侧不需要任何偏移补偿。
        viewport.scrollBy(0, maxScroll);
        times[i] = System.nanoTime() - t0;
      }
      long allocPerOp = perOp(allocatedBytes() - allocBefore, ITERATIONS);
      long[] gcDelta = gcDelta(gcBefore, gcSnapshot());

      assertEquals(history, model.historySize());
      assertFalse("appends must not steal the viewport while scrolled", viewport.followTail);
      assertEquals(pinnedOffset, viewport.scrollOffsetPixels);
      // 用户滚回底部立即恢复 follow-tail。
      viewport.scrollBy(-maxScroll, maxScroll);
      assertEquals(0, viewport.scrollOffsetPixels);
      assertTrue("reaching bottom resumes tail-following", viewport.followTail);

      report("apply-patch/scrolled-append", cols, rows, history, Content.ASCII, ITERATIONS,
          times, allocPerOp, gcDelta);
    }
  }

  // ------------------------------------------------------------------ fixtures

  private static RemoteTerminalModel newModel(int cols, int rows, int historyLines,
                                              Content historyContent) {
    // soft==hard==目标行数：snapshot 恰好 N 行不触发驱逐，之后每次 append 驱逐 1 行，
    // 稳态行数恒为 N。字节预算置 0 关闭，只保留行数上限。
    RemoteTerminalModel model = new RemoteTerminalModel(
        new HistoryBudget(historyLines, historyLines, 0, 0));

    // 历史行共享同一个 cells 数组（内容不可变）：setup 分配 O(1)/行，
    // 被测路径的分配与耗时不受影响——历史复制只搬运引用。
    TerminalCell[] historyCells = buildCells(cols, historyContent, seed(cols, historyContent) + 7);
    List<TerminalLine> history = new ArrayList<>(historyLines);
    for (int i = 0; i < historyLines; i++) {
      history.add(new TerminalLine(i + 1, false, historyCells));
    }
    HistoryWindow window = new HistoryWindow(1, 1, historyLines, false, history);

    TerminalCell[] screenCells = buildCells(cols, Content.ASCII, seed(cols, Content.ASCII) + 13);
    List<TerminalLine> screen = new ArrayList<>(rows);
    for (int r = 0; r < rows; r++) {
      screen.add(new TerminalLine(r, false, screenCells));
    }

    model.applySnapshot(new ScreenSnapshot("s1", "i1", 1, 1, rows, cols,
        ScreenSnapshot.BufferKind.MAIN,
        new TerminalCursor(rows - 1, 0, true, TerminalCursor.Shape.BLOCK, false),
        TerminalModes.defaults(), TerminalPalette.defaults(),
        window, screen, usesStyles(historyContent) ? styles() : Collections.emptyMap(),
        Collections.emptyMap(), "", ""));
    assertEquals(historyLines, model.historySize());
    return model;
  }

  /** 生成恰好 cols 个 cell 的一行；宽字符占两格（宽 cell + spacer）。 */
  private static TerminalLine contentLine(long id, int cols, Content content, long contentSeed) {
    return new TerminalLine(id, false, buildCells(cols, content, contentSeed));
  }

  private static TerminalCell[] buildCells(int cols, Content content, long contentSeed) {
    Random rnd = new Random(contentSeed);
    TerminalCell[] cells = new TerminalCell[cols];
    int col = 0;
    int block = 0;
    while (col < cols) {
      switch (content) {
        case ASCII:
          cells[col++] = asciiCell(rnd, 0);
          break;
        case STYLED_ASCII:
          cells[col++] = asciiCell(rnd, 1 + rnd.nextInt(4));
          break;
        case CJK:
          col = wide(cells, col, cols, cjkChar(rnd));
          break;
        case EMOJI:
          col = wide(cells, col, cols, EMOJI[block++ % EMOJI.length]);
          break;
        case COMBINING:
          cells[col++] = new TerminalCell(COMBINING[block++ % COMBINING.length], (byte) 1, 0, 0);
          break;
        case MIXED:
          // 8 格一块：4 ASCII + 1 styled + 1 combining + 1 宽字符（CJK/emoji 交替）。
          int p = block++ % 8;
          if (p < 4) {
            cells[col++] = asciiCell(rnd, 0);
          } else if (p == 4) {
            cells[col++] = asciiCell(rnd, 1 + rnd.nextInt(4));
          } else if (p == 5) {
            cells[col++] = new TerminalCell(COMBINING[(block / 8) % COMBINING.length],
                (byte) 1, 0, 0);
          } else {
            String text = (block / 8) % 2 == 0 ? cjkChar(rnd) : EMOJI[(block / 8) % EMOJI.length];
            col = wide(cells, col, cols, text);
          }
          break;
      }
    }
    // 宽字符跨尾时最后一个 slot 用窄 cell 补齐。
    while (col < cols) {
      cells[col++] = asciiCell(rnd, 0);
    }
    return cells;
  }

  private static int wide(TerminalCell[] cells, int col, int cols, String text) {
    if (col + 2 <= cols) {
      cells[col] = new TerminalCell(text, (byte) 2, 0, 0);
      cells[col + 1] = TerminalCell.SPACER;
      return col + 2;
    }
    cells[col] = new TerminalCell(" ", (byte) 1, 0, 0);
    return col + 1;
  }

  private static TerminalCell asciiCell(Random rnd, int styleId) {
    char c = (char) (' ' + rnd.nextInt('~' - ' ' + 1));
    return new TerminalCell(String.valueOf(c), (byte) 1, styleId, 0);
  }

  private static String cjkChar(Random rnd) {
    return String.valueOf((char) (0x4E00 + rnd.nextInt(0x9FFF - 0x4E00)));
  }

  private static boolean usesStyles(Content content) {
    return content == Content.STYLED_ASCII || content == Content.MIXED;
  }

  private static Map<Integer, TerminalStyle> styles() {
    Map<Integer, TerminalStyle> styles = new HashMap<>();
    styles.put(1, new TerminalStyle(1, TerminalColor.indexed(1), TerminalColor.DEFAULT_BG, null,
        1 << 0)); // bold red
    styles.put(2, new TerminalStyle(2, TerminalColor.indexed(2), TerminalColor.DEFAULT_BG, null,
        1 << 3)); // underline green
    styles.put(3, new TerminalStyle(3, TerminalColor.DEFAULT_FG, TerminalColor.indexed(4), null,
        1 << 2)); // italic on blue
    styles.put(4, new TerminalStyle(4, TerminalColor.rgb(0xFFAA00), TerminalColor.DEFAULT_BG, null,
        1 << 12)); // strike orange
    return styles;
  }

  private static long seed(int cols, Content content) {
    return 0x5EEDL + cols * 131L + content.ordinal() * 17L;
  }

  // ------------------------------------------------------------------ metrics
  // java.lang.management / com.sun.management 不在 Android API 面，unit test 编译期
  // 不可见（mockable android.jar 优先于宿主 JDK）；但测试实际运行在宿主 HotSpot JVM 上，
  // 因此全部通过反射访问，不可用时退化为只测耗时（alloc=-1）。

  private static final Object THREAD_MX_BEAN;
  private static final Method IS_ALLOC_SUPPORTED;
  private static final Method GET_THREAD_ALLOCATED_BYTES;
  private static final List<?> GC_BEANS;
  private static final Method GC_COLLECTION_COUNT;
  private static final Method GC_COLLECTION_TIME;

  static {
    Object tmb = null;
    Method allocSupported = null;
    Method allocBytes = null;
    List<?> gcBeans = null;
    Method gcCount = null;
    Method gcTime = null;
    try {
      Class<?> managementFactory = Class.forName("java.lang.management.ManagementFactory");
      tmb = managementFactory.getMethod("getThreadMXBean").invoke(null);
      Class<?> sunThreadMXBean = Class.forName("com.sun.management.ThreadMXBean");
      allocSupported = sunThreadMXBean.getMethod("isThreadAllocatedMemorySupported");
      allocBytes = sunThreadMXBean.getMethod("getThreadAllocatedBytes", long.class);
      @SuppressWarnings("unchecked")
      List<Object> beans =
          (List<Object>) managementFactory.getMethod("getGarbageCollectorMXBeans").invoke(null);
      gcBeans = beans;
      Class<?> gcBean = Class.forName("java.lang.management.GarbageCollectorMXBean");
      gcCount = gcBean.getMethod("getCollectionCount");
      gcTime = gcBean.getMethod("getCollectionTime");
    } catch (Throwable t) {
      System.out.println("[PerfBaseline] JVM MXBean unavailable, allocation/GC metrics disabled: "
          + t);
      tmb = null;
      gcBeans = null;
    }
    THREAD_MX_BEAN = tmb;
    IS_ALLOC_SUPPORTED = allocSupported;
    GET_THREAD_ALLOCATED_BYTES = allocBytes;
    GC_BEANS = gcBeans;
    GC_COLLECTION_COUNT = gcCount;
    GC_COLLECTION_TIME = gcTime;
  }

  private static long allocatedBytes() {
    try {
      if (THREAD_MX_BEAN != null
          && (Boolean) IS_ALLOC_SUPPORTED.invoke(THREAD_MX_BEAN)) {
        return (Long) GET_THREAD_ALLOCATED_BYTES.invoke(
            THREAD_MX_BEAN, Thread.currentThread().getId());
      }
    } catch (Throwable ignored) {
    }
    return -1L;
  }

  private static long perOp(long allocDelta, int ops) {
    return allocDelta < 0 ? -1 : allocDelta / ops;
  }

  private static long[] gcSnapshot() {
    long count = 0;
    long time = 0;
    if (GC_BEANS != null) {
      try {
        for (Object bean : GC_BEANS) {
          count += (Long) GC_COLLECTION_COUNT.invoke(bean);
          time += (Long) GC_COLLECTION_TIME.invoke(bean);
        }
      } catch (Throwable ignored) {
      }
    }
    return new long[]{count, time};
  }

  private static long[] gcDelta(long[] before, long[] after) {
    return new long[]{after[0] - before[0], after[1] - before[1]};
  }

  private static void report(String scenario, int cols, int rows, int history, Content content,
                             int iters, long[] times, long allocPerOp, long[] gcDelta) {
    long[] sorted = times.clone();
    Arrays.sort(sorted);
    double p50 = us(sorted, 50);
    double p95 = us(sorted, 95);
    double p99 = us(sorted, 99);
    double max = sorted[sorted.length - 1] / 1000.0;
    long sum = 0;
    for (long t : sorted) sum += t;
    double mean = sum / (double) sorted.length / 1000.0;
    System.out.printf(Locale.US,
        "[PerfBaseline] scenario=%s size=%dx%d history=%d content=%s iters=%d"
            + " p50_us=%.2f p95_us=%.2f p99_us=%.2f max_us=%.2f mean_us=%.2f"
            + " alloc_bytes_per_op=%d gc_count_delta=%d gc_time_ms_delta=%d%n",
        scenario, cols, rows, history, content.name().toLowerCase(Locale.US), iters,
        p50, p95, p99, max, mean, allocPerOp, gcDelta[0], gcDelta[1]);
  }

  private static double us(long[] sorted, int percentile) {
    return sorted[(int) ((sorted.length - 1L) * percentile / 100.0)] / 1000.0;
  }

  private static void printEnv(String phase) {
    System.out.printf(Locale.US,
        "[PerfBaseline] env phase=%s java=%s vm=%s os=%s arch=%s cpus=%d%n",
        phase,
        System.getProperty("java.version"),
        System.getProperty("java.vm.name"),
        System.getProperty("os.name"),
        System.getProperty("os.arch"),
        Runtime.getRuntime().availableProcessors());
  }
}
