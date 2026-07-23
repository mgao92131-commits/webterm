package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import com.webterm.terminal.model.HistoryBudget;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalColor;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalStyle;
import com.webterm.terminal.model.TerminalViewportState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowCanvas;

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
 * RemoteTerminalRenderer.render() 性能基线（计划 §5.2）。
 *
 * <p>需要 android.graphics.Canvas，纯 JVM 无法实例化，因此用 Robolectric 在宿主 JVM 上
 * 提供阴影实现。注意：Robolectric 的 Canvas 是软件阴影（draw 调用走完整 Java 路径但不
 * 光栅化），且 ShadowBitmap 会为每次 draw 保留调试记录——alloc_bytes_per_op 主要是
 * 阴影记录开销，不代表真机分配；绝对耗时只可与同环境后续采样对比，不代表真机 GPU 管线时间。</p>
 *
 * <p>只打印结构化报告（[PerfBaseline] 前缀），不设耗时阈值断言。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class RemoteTerminalRendererRenderBaselineTest {

  private static final int WARMUP = 30;
  private static final int ITERATIONS = 100;
  /** Robolectric ShadowBitmap 会把每帧 draw 事件记录在位图描述里，必须分块重建避免无界累积。 */
  private static final int FRAMES_PER_BITMAP = 20;

  private static final float CELL_WIDTH = 10f;
  private static final float LINE_HEIGHT = 20f;
  private static final float BASELINE_OFFSET = 16f;

  /** 固定数据规模：{cols, rows, history lines}。 */
  private static final int[][] SIZES = {
      {80, 24, 1000},
      {120, 40, 5000},
      {200, 50, 10000},
  };

  private static final String[] EMOJI = {"😀", "🚀", "🎉", "👍", "🔥", "🐛", "📦", "🌟"};
  private static final String[] COMBINING = {
      "é", "à", "ñ", "ö", "û"};

  private enum Content {
    ASCII, STYLED_ASCII, STYLED_RUNS, CJK, EMOJI, MIXED
  }

  @Test
  public void styledAsciiRuns_areDrawnAsRuns() {
    int cols = 200;
    int rows = 50;
    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer();
    // Robolectric's legacy Paint shadow measures every ASCII glyph as exactly 1px.
    renderer.setFontMetrics(1f, LINE_HEIGHT, BASELINE_OFFSET);
    RemoteTerminalModel model = newModel(cols, rows, 100, Content.STYLED_RUNS);
    Canvas canvas = new Canvas(Bitmap.createBitmap(cols,
        (int) (rows * LINE_HEIGHT + renderer.getTopInset()), Bitmap.Config.ARGB_8888));

    renderer.render(canvas, model.renderSnapshot(), new TerminalViewportState(), true);

    ShadowCanvas shadow = Shadow.extract(canvas);
    assertTrue("styled ASCII should not draw one text event per cell",
        shadow.getTextHistoryCount() < rows * cols / 4);
    boolean sawMultiCellRun = false;
    for (int i = 0; i < shadow.getTextHistoryCount(); i++) {
      if (shadow.getDrawnTextEvent(i).text.length() >= 3) {
        sawMultiCellRun = true;
        break;
      }
    }
    assertTrue("expected at least one batched styled text run", sawMultiCellRun);
  }

  @Test
  public void render_baseline() {
    printEnv();
    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer();
    renderer.setFontMetrics(CELL_WIDTH, LINE_HEIGHT, BASELINE_OFFSET);

    for (int[] size : SIZES) {
      int cols = size[0], rows = size[1], history = size[2];
      for (Content content : Content.values()) {
        // Robolectric's legacy Paint measures ASCII as 1px. ASCII run scenarios therefore use
        // that natural width to model the production monospace path; otherwise the renderer
        // correctly detects a synthetic scaling requirement and falls back to per-cell drawing.
        float scenarioCellWidth = content == Content.ASCII || content == Content.STYLED_RUNS
            ? 1f : CELL_WIDTH;
        renderer.setFontMetrics(scenarioCellWidth, LINE_HEIGHT, BASELINE_OFFSET);
        int widthPx = (int) Math.ceil(cols * scenarioCellWidth);
        int heightPx = (int) Math.ceil(rows * LINE_HEIGHT + renderer.getTopInset());
        RemoteTerminalModel model = newModel(cols, rows, history, content);
        RemoteTerminalModel.RenderSnapshot snapshot = model.renderSnapshot();
        assertNotNull(snapshot.screen);
        assertEquals(rows, snapshot.screen.length);
        assertEquals(history, snapshot.history.size());

        for (boolean scrolled : new boolean[]{false, true}) {
          TerminalViewportState viewport = new TerminalViewportState();
          if (scrolled) {
            int maxScroll = (int) (history * LINE_HEIGHT);
            viewport.scrollBy(maxScroll, maxScroll);
          }
          int chunkCount = (ITERATIONS + FRAMES_PER_BITMAP - 1) / FRAMES_PER_BITMAP;
          Canvas[] canvases = new Canvas[chunkCount];
          for (int c = 0; c < chunkCount; c++) {
            Bitmap bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
            canvases[c] = new Canvas(bitmap);
          }
          Canvas warmupCanvas = new Canvas(
              Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888));
          for (int i = 0; i < WARMUP; i++) {
            renderer.render(warmupCanvas, snapshot, viewport, true);
          }
          long allocBefore = allocatedBytes();
          long[] times = new long[ITERATIONS];
          for (int i = 0; i < ITERATIONS; i++) {
            Canvas canvas = canvases[i / FRAMES_PER_BITMAP];
            long t0 = System.nanoTime();
            renderer.render(canvas, snapshot, viewport, true);
            times[i] = System.nanoTime() - t0;
          }
          long allocPerOp = perOp(allocatedBytes() - allocBefore, ITERATIONS);

          // 正确性：渲染完成后 snapshot 与视口状态未被修改。
          assertEquals(history, snapshot.history.size());
          assertEquals(scrolled ? (int) (history * LINE_HEIGHT) : 0,
              viewport.scrollOffsetPixels);

          report(cols, rows, history, content, scrolled ? "scrolled" : "follow-tail", times,
              allocPerOp);
        }
      }
    }
  }

  // ------------------------------------------------------------------ fixtures

  private static RemoteTerminalModel newModel(int cols, int rows, int historyLines,
                                              Content content) {
    RemoteTerminalModel model = new RemoteTerminalModel(
        new HistoryBudget(historyLines, historyLines, 0, 0));
    // 历史行共享同一个 cells 数组：scrolled 模式只绘制顶部可见历史行，
    // 每行内容相同不影响 draw 调用次数与路径。
    TerminalCell[] historyCells = buildCells(cols, content, seed(cols, content) + 7);
    List<TerminalLine> history = new ArrayList<>(historyLines);
    int historyStart = Math.max(1, historyLines - 127);
    for (int i = historyStart; i <= historyLines; i++) {
      history.add(new TerminalLine(i, 1, i, false, historyCells));
    }
    List<TerminalLine> screen = new ArrayList<>(rows);
    for (int r = 0; r < rows; r++) {
      screen.add(new TerminalLine(100_000 + r, false,
          buildCells(cols, content, seed(cols, content) + r)));
    }
    HistoryExtent extent = historyLines == 0
        ? HistoryExtent.INITIAL_EMPTY : new HistoryExtent(1, historyLines);
    model.applyBaseline(new ScreenBaseline("s1", "i1", 1, 1, 1, rows, cols,
        TerminalBufferKind.MAIN, extent, history, screen,
        new TerminalCursor(rows - 1, 0, true, TerminalCursor.Shape.BLOCK, false),
        TerminalModes.defaults(), TerminalPalette.defaults(), "", ""));
    return model;
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
        case STYLED_RUNS:
          cells[col++] = asciiCell(rnd, 1 + (col / 12) % 4);
          break;
        case CJK:
          col = wide(cells, col, cols, cjkChar(rnd));
          break;
        case EMOJI:
          col = wide(cells, col, cols, EMOJI[block++ % EMOJI.length]);
          break;
        case MIXED:
          int p = block++ % 8;
          if (p < 4) {
            cells[col++] = asciiCell(rnd, 0);
          } else if (p == 4) {
            cells[col++] = asciiCell(rnd, 1 + rnd.nextInt(4));
          } else if (p == 5) {
            cells[col++] = new TerminalCell(COMBINING[(block / 8) % COMBINING.length],
                (byte) 1, null, null);
          } else {
            String text = (block / 8) % 2 == 0 ? cjkChar(rnd) : EMOJI[(block / 8) % EMOJI.length];
            col = wide(cells, col, cols, text);
          }
          break;
      }
    }
    while (col < cols) {
      cells[col++] = asciiCell(rnd, 0);
    }
    return cells;
  }

  private static int wide(TerminalCell[] cells, int col, int cols, String text) {
    if (col + 2 <= cols) {
      cells[col] = new TerminalCell(text, (byte) 2, null, null);
      cells[col + 1] = TerminalCell.SPACER;
      return col + 2;
    }
    cells[col] = new TerminalCell(" ", (byte) 1, null, null);
    return col + 1;
  }

  private static TerminalCell asciiCell(Random rnd, int styleId) {
    char c = (char) (' ' + rnd.nextInt('~' - ' ' + 1));
    return new TerminalCell(
        String.valueOf(c), (byte) 1, styleId == 0 ? null : styles().get(styleId), null);
  }

  private static String cjkChar(Random rnd) {
    return String.valueOf((char) (0x4E00 + rnd.nextInt(0x9FFF - 0x4E00)));
  }

  private static boolean usesStyles(Content content) {
    return content == Content.STYLED_ASCII || content == Content.STYLED_RUNS
        || content == Content.MIXED;
  }

  private static Map<Integer, TerminalStyle> styles() {
    Map<Integer, TerminalStyle> styles = new HashMap<>();
    styles.put(1, new TerminalStyle(1, TerminalColor.indexed(1), TerminalColor.DEFAULT_BG, null,
        1 << 0));
    styles.put(2, new TerminalStyle(2, TerminalColor.indexed(2), TerminalColor.DEFAULT_BG, null,
        1 << 3));
    styles.put(3, new TerminalStyle(3, TerminalColor.DEFAULT_FG, TerminalColor.indexed(4), null,
        1 << 2));
    styles.put(4, new TerminalStyle(4, TerminalColor.rgb(0xFFAA00), TerminalColor.DEFAULT_BG, null,
        1 << 12));
    return styles;
  }

  private static long seed(int cols, Content content) {
    return 0x5EEDL + cols * 131L + content.ordinal() * 17L;
  }

  // ------------------------------------------------------------------ metrics
  // 见 PerformanceBaselineTest：MXBean 编译期不可见，反射访问，不可用则 alloc=-1。

  private static final Object THREAD_MX_BEAN;
  private static final Method IS_ALLOC_SUPPORTED;
  private static final Method GET_THREAD_ALLOCATED_BYTES;

  static {
    Object tmb = null;
    Method allocSupported = null;
    Method allocBytes = null;
    try {
      Class<?> managementFactory = Class.forName("java.lang.management.ManagementFactory");
      tmb = managementFactory.getMethod("getThreadMXBean").invoke(null);
      Class<?> sunThreadMXBean = Class.forName("com.sun.management.ThreadMXBean");
      allocSupported = sunThreadMXBean.getMethod("isThreadAllocatedMemorySupported");
      allocBytes = sunThreadMXBean.getMethod("getThreadAllocatedBytes", long.class);
    } catch (Throwable t) {
      System.out.println("[PerfBaseline] JVM MXBean unavailable, allocation metrics disabled: "
          + t);
      tmb = null;
    }
    THREAD_MX_BEAN = tmb;
    IS_ALLOC_SUPPORTED = allocSupported;
    GET_THREAD_ALLOCATED_BYTES = allocBytes;
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

  private static void report(int cols, int rows, int history, Content content, String viewport,
                             long[] times, long allocPerOp) {
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
        "[PerfBaseline] scenario=render size=%dx%d history=%d content=%s viewport=%s iters=%d"
            + " p50_us=%.2f p95_us=%.2f p99_us=%.2f max_us=%.2f mean_us=%.2f"
            + " alloc_bytes_per_op=%d%n",
        cols, rows, history, content.name().toLowerCase(Locale.US), viewport, ITERATIONS,
        p50, p95, p99, max, mean, allocPerOp);
  }

  private static double us(long[] sorted, int percentile) {
    return sorted[(int) ((sorted.length - 1L) * percentile / 100.0)] / 1000.0;
  }

  private static void printEnv() {
    System.out.printf(Locale.US,
        "[PerfBaseline] env phase=render-robolectric java=%s vm=%s os=%s arch=%s cpus=%d%n",
        System.getProperty("java.version"),
        System.getProperty("java.vm.name"),
        System.getProperty("os.name"),
        System.getProperty("os.arch"),
        Runtime.getRuntime().availableProcessors());
  }
}
