package com.webterm.terminal.renderer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RenderNode;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.webterm.terminal.model.RenderLine;
import com.webterm.terminal.model.TerminalPalette;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

/**
 * 可同时注入 Phase 5 基线和 Round 1 HEAD 的最小冷录制对照 harness。
 *
 * <p>故意只依赖 Phase 5 已存在的 renderer 入口，不读取 Round 1 专用工作量统计，
 * 因此同一份测试源码可以复制到两个提交分别编译和运行。</p>
 */
@RunWith(AndroidJUnit4.class)
public final class RendererRound1CommonBenchmarkTest {
  private static final int WARMUP_SAMPLES = 10;
  private static final int MEASURED_SAMPLES = 30;
  private static final int BACKGROUND = 0xFF000000;

  @Test
  public void coldRecordScenariosUseCommonHarness() {
    Bundle arguments = InstrumentationRegistry.getArguments();
    Assume.assumeTrue(
        "webtermPerf=true is required",
        "true".equalsIgnoreCase(arguments.getString("webtermPerf")));

    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer(
        TerminalFontSet.fromContext(context));
    float textSizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        14f,
        context.getResources().getDisplayMetrics());
    renderer.updateFont(textSizePx, Typeface.MONOSPACE);
    TerminalPalette palette = TerminalPalette.defaults();
    String target = arguments.getString("webtermPerfLabel");
    if (target == null) target = "unknown";

    for (RendererPerformanceFixtures.Scenario scenario
        : RendererPerformanceFixtures.Scenario.values()) {
      RenderLine[] lines = RendererPerformanceFixtures.lines(scenario);
      for (int i = 0; i < WARMUP_SAMPLES; i++) {
        recordOnce(renderer, lines, palette);
      }

      long[] durations = new long[MEASURED_SAMPLES];
      for (int i = 0; i < MEASURED_SAMPLES; i++) {
        long startedNanos = System.nanoTime();
        recordOnce(renderer, lines, palette);
        durations[i] = Math.max(0L, System.nanoTime() - startedNanos);
      }
      Arrays.sort(durations);
      System.out.println("{\"target\":\"" + target
          + "\",\"scenario\":\"" + scenario.name().toLowerCase()
          + "\",\"samples\":" + MEASURED_SAMPLES
          + ",\"render_p50_ns\":" + percentile(durations, 0.50)
          + ",\"render_p90_ns\":" + percentile(durations, 0.90)
          + ",\"render_p95_ns\":" + percentile(durations, 0.95)
          + ",\"render_max_ns\":" + durations[durations.length - 1]
          + "}");
    }
  }

  private static void recordOnce(
      RemoteTerminalRenderer renderer,
      RenderLine[] lines,
      TerminalPalette palette) {
    int width = Math.max(1, renderer.contentWidthPx(RendererPerformanceFixtures.COLUMNS));
    int height = Math.max(1, renderer.lineHeightPx());
    for (int row = 0; row < lines.length; row++) {
      RenderNode node = new RenderNode("round1-common-perf-" + row);
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
}
