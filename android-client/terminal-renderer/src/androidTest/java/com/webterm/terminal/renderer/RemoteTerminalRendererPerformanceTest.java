package com.webterm.terminal.renderer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RenderNode;
import android.graphics.Typeface;
import android.util.TypedValue;

import androidx.test.platform.app.InstrumentationRegistry;

import com.webterm.terminal.model.RenderLine;
import com.webterm.terminal.model.TerminalPalette;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import java.util.Arrays;

/**
 * 显式开启的 renderer 冷录制性能套件。普通 connectedDebugAndroidTest 不会执行它。
 */
@RunWith(AndroidJUnit4.class)
public final class RemoteTerminalRendererPerformanceTest {
  private static final int WARMUP_SAMPLES = 10;
  private static final int MEASURED_SAMPLES = 30;
  // 与 TerminalPalette.defaults() 的 DEFAULT_BG 一致，保证 CellValue.EMPTY 真正属于
  // default blank fast path，而不是被测试背景人为变成带背景的 styled blank。
  private static final int BACKGROUND = 0xFF000000;

  @Test
  public void coldRenderNodeScenariosProduceRepeatableBaseline() {
    Assume.assumeTrue("webtermPerf=true is required", performanceEnabled());
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    RendererFrameWorkStats workStats = new RendererFrameWorkStats();
    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer(
        TerminalFontSet.fromContext(context), workStats);
    float textSizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        14f,
        context.getResources().getDisplayMetrics());
    renderer.updateFont(textSizePx, Typeface.MONOSPACE);
    TerminalPalette palette = TerminalPalette.defaults();

    for (RendererPerformanceFixtures.Scenario scenario
        : RendererPerformanceFixtures.Scenario.values()) {
      RenderLine[] lines = RendererPerformanceFixtures.lines(scenario);
      for (int i = 0; i < WARMUP_SAMPLES; i++) {
        recordOnce(renderer, lines, palette);
      }

      long[] durations = new long[MEASURED_SAMPLES];
      long[] compileDurations = new long[MEASURED_SAMPLES];
      long[] drawDurations = new long[MEASURED_SAMPLES];
      RendererFrameWorkStats.Snapshot aggregate = zeroStats();
      for (int i = 0; i < MEASURED_SAMPLES; i++) {
        renderer.resetWorkStatsForTest();
        long started = System.nanoTime();
        recordOnce(renderer, lines, palette);
        durations[i] = Math.max(0L, System.nanoTime() - started);
        RendererFrameWorkStats.Snapshot sample = renderer.workStatsForTest();
        compileDurations[i] = sample.compileNanos();
        drawDurations[i] = sample.compiledLineDrawNanos();
        aggregate = add(aggregate, sample);
      }
      Arrays.sort(durations);
      Arrays.sort(compileDurations);
      Arrays.sort(drawDurations);
      RendererFrameWorkStats.Snapshot average = divide(aggregate, MEASURED_SAMPLES);
      System.out.println("{\"scenario\":\"" + scenario.name().toLowerCase()
          + "\",\"samples\":" + MEASURED_SAMPLES
          + ",\"render_p50_ns\":" + percentile(durations, 0.50)
          + ",\"render_p90_ns\":" + percentile(durations, 0.90)
          + ",\"render_p95_ns\":" + percentile(durations, 0.95)
          + ",\"render_max_ns\":" + durations[durations.length - 1]
          + ",\"compile_p50_ns\":" + percentile(compileDurations, 0.50)
          + ",\"compile_p95_ns\":" + percentile(compileDurations, 0.95)
          + ",\"compile_avg_ns\":" + average.compileNanos()
          + ",\"draw_p50_ns\":" + percentile(drawDurations, 0.50)
          + ",\"draw_p95_ns\":" + percentile(drawDurations, 0.95)
          + ",\"draw_avg_ns\":" + average.compiledLineDrawNanos()
          + ",\"input_cells\":" + average.inputCellCount()
          + ",\"default_cells\":" + average.defaultCellCount()
          + ",\"emitted_spans\":" + average.emittedSpanCount()
          + ",\"emitted_clusters\":" + average.emittedClusterCount()
          + ",\"font_resolve\":" + average.fontResolveCount()
          + ",\"emoji_classify\":" + average.emojiClassificationCount()
          + ",\"batch_advance\":" + average.batchAdvanceCallCount()
          + ",\"legacy_run_advance\":" + average.legacyRunAdvanceCallCount()
          + ",\"cluster_fallback\":" + average.clusterFallbackCount()
          + "}");
    }
  }

  private static boolean performanceEnabled() {
    String value = InstrumentationRegistry.getArguments().getString("webtermPerf");
    return "true".equalsIgnoreCase(value);
  }

  private static void recordOnce(
      RemoteTerminalRenderer renderer,
      RenderLine[] lines,
      TerminalPalette palette) {
    int width = Math.max(1, renderer.contentWidthPx(RendererPerformanceFixtures.COLUMNS));
    int height = Math.max(1, renderer.lineHeightPx());
    for (int row = 0; row < lines.length; row++) {
      RenderNode node = new RenderNode("renderer-perf-" + row);
      node.setPosition(0, 0, width, height);
      Canvas recordingCanvas = node.beginRecording(width, height);
      try {
        CompiledTerminalLine compiled = renderer.compileLine(
            lines[row], RendererPerformanceFixtures.COLUMNS, palette, BACKGROUND);
        renderer.drawCompiledLineContent(recordingCanvas, compiled, 0f, BACKGROUND);
      } finally {
        node.endRecording();
      }
    }
  }

  private static long percentile(long[] sorted, double fraction) {
    int index = Math.min(sorted.length - 1,
        Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1));
    return sorted[index];
  }

  private static RendererFrameWorkStats.Snapshot zeroStats() {
    return new RendererFrameWorkStats().snapshot();
  }

  private static RendererFrameWorkStats.Snapshot add(
      RendererFrameWorkStats.Snapshot left,
      RendererFrameWorkStats.Snapshot right) {
    return new RendererFrameWorkStats.Snapshot(
        left.compiledLineCount() + right.compiledLineCount(),
        left.compileNanos() + right.compileNanos(),
        Math.max(left.compileMaxNanos(), right.compileMaxNanos()),
        left.compiledLineDrawCount() + right.compiledLineDrawCount(),
        left.compiledLineDrawNanos() + right.compiledLineDrawNanos(),
        left.inputCellCount() + right.inputCellCount(),
        left.defaultCellCount() + right.defaultCellCount(),
        left.emittedSpanCount() + right.emittedSpanCount(),
        left.emittedTextSpanCount() + right.emittedTextSpanCount(),
        left.emittedClusterCount() + right.emittedClusterCount(),
        left.fontResolveCount() + right.fontResolveCount(),
        left.emojiClassificationCount() + right.emojiClassificationCount(),
        left.batchAdvanceCallCount() + right.batchAdvanceCallCount(),
        left.legacyRunAdvanceCallCount() + right.legacyRunAdvanceCallCount(),
        left.clusterFallbackCount() + right.clusterFallbackCount());
  }

  private static RendererFrameWorkStats.Snapshot divide(
      RendererFrameWorkStats.Snapshot value, long divisor) {
    return new RendererFrameWorkStats.Snapshot(
        value.compiledLineCount() / divisor,
        value.compileNanos() / divisor,
        value.compileMaxNanos(),
        value.compiledLineDrawCount() / divisor,
        value.compiledLineDrawNanos() / divisor,
        value.inputCellCount() / divisor,
        value.defaultCellCount() / divisor,
        value.emittedSpanCount() / divisor,
        value.emittedTextSpanCount() / divisor,
        value.emittedClusterCount() / divisor,
        value.fontResolveCount() / divisor,
        value.emojiClassificationCount() / divisor,
        value.batchAdvanceCallCount() / divisor,
        value.legacyRunAdvanceCallCount() / divisor,
        value.clusterFallbackCount() / divisor);
  }
}
