package com.webterm.terminal.renderer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RenderNode;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.LineBodyRecord;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderLine;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 第二轮 2A 的显式缓存/覆盖层 benchmark；普通 connected test 不执行。 */
@RunWith(AndroidJUnit4.class)
public final class RendererRound2CacheBenchmarkTest {
  private static final int WARMUP_SAMPLES = 10;
  private static final int MEASURED_SAMPLES = 30;
  private static final int BACKGROUND = 0xFF000000;

  @Test
  public void preparedCacheAndRenderNodeHitBaseline() {
    Bundle args = InstrumentationRegistry.getArguments();
    Assume.assumeTrue("webtermPerf=true is required",
        "true".equalsIgnoreCase(args.getString("webtermPerf")));

    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer(
        TerminalFontRegistry.get(context));
    float textSizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, 14f, context.getResources().getDisplayMetrics());
    renderer.updateFont(textSizePx, Typeface.MONOSPACE);
    TerminalPalette palette = TerminalPalette.defaults();

    for (RendererPerformanceFixtures.Scenario scenario
        : RendererPerformanceFixtures.Scenario.values()) {
      RenderLine[] lines = RendererPerformanceFixtures.lines(scenario);
      RemoteTerminalModel.RenderSnapshot snapshot = snapshot(lines);
      for (int i = 0; i < WARMUP_SAMPLES; i++) recordCold(renderer, snapshot, lines, palette);

      long[] cold = new long[MEASURED_SAMPLES];
      for (int i = 0; i < MEASURED_SAMPLES; i++) {
        long started = System.nanoTime();
        recordCold(renderer, snapshot, lines, palette);
        cold[i] = Math.max(0L, System.nanoTime() - started);
      }

      TerminalPreparedLineCache prepared = new TerminalPreparedLineCache();
      long[] warm = new long[MEASURED_SAMPLES];
      for (int i = 0; i < WARMUP_SAMPLES; i++) {
        recordFrame(renderer, snapshot, lines, palette, prepared);
      }
      for (int i = 0; i < MEASURED_SAMPLES; i++) {
        long started = System.nanoTime();
        recordFrame(renderer, snapshot, lines, palette, prepared);
        warm[i] = Math.max(0L, System.nanoTime() - started);
      }

      Arrays.sort(cold);
      Arrays.sort(warm);
      System.out.println("{\"scenario\":\"" + scenario.name().toLowerCase()
          + "\",\"samples\":" + MEASURED_SAMPLES
          + ",\"cold_record_p50_ns\":" + percentile(cold, 0.50)
          + ",\"cold_record_p95_ns\":" + percentile(cold, 0.95)
          + ",\"warm_hit_p50_ns\":" + percentile(warm, 0.50)
          + ",\"warm_hit_p95_ns\":" + percentile(warm, 0.95)
          + ",\"prepared_hits\":" + prepared.hitCountForTest()
          + ",\"prepared_misses\":" + prepared.missCountForTest()
          + ",\"prepared_bytes\":" + prepared.estimatedBytesForTest()
          + "}");
    }
  }

  private static void recordCold(
      RemoteTerminalRenderer renderer,
      RemoteTerminalModel.RenderSnapshot snapshot,
      RenderLine[] lines,
      TerminalPalette palette) {
    TerminalPreparedLineCache prepared = new TerminalPreparedLineCache();
    recordFrame(renderer, snapshot, lines, palette, prepared);
  }

  private static void recordFrame(
      RemoteTerminalRenderer renderer,
      RemoteTerminalModel.RenderSnapshot snapshot,
      RenderLine[] lines,
      TerminalPalette palette,
      TerminalPreparedLineCache prepared) {
    prepared.beginFrame(snapshot, renderer, BACKGROUND, 1, 1, 1);
    int width = Math.max(1, renderer.contentWidthPx(RendererPerformanceFixtures.COLUMNS));
    int height = Math.max(1, renderer.lineHeightPx());
    for (int row = 0; row < lines.length; row++) {
      RenderNode node = new RenderNode("round2-prepared-" + row);
      node.setPosition(0, 0, width, height);
      Canvas recordingCanvas = node.beginRecording(width, height);
      try {
        PreparedTerminalLine preparedLine = prepared.getOrPrepare(
            lines[row], renderer, RendererPerformanceFixtures.COLUMNS, palette, BACKGROUND);
        renderer.drawPreparedLineContent(recordingCanvas, preparedLine, 0f, BACKGROUND);
      } finally {
        node.endRecording();
      }
    }
  }

  private static RemoteTerminalModel.RenderSnapshot snapshot(RenderLine[] lines) {
    RemoteTerminalModel model = new RemoteTerminalModel();
    ArrayList<LineKey> keys = new ArrayList<>(lines.length);
    ArrayList<LineBodyRecord> bodies = new ArrayList<>(lines.length);
    for (RenderLine line : lines) {
      keys.add(line.key());
      bodies.add(new LineBodyRecord(line.key(), line.body()));
    }
    ScreenBaseline baseline = new ScreenBaseline(
        "round2", "round2-instance", 1, 1, 1,
        RendererPerformanceFixtures.ROWS, RendererPerformanceFixtures.COLUMNS,
        TerminalBufferKind.MAIN, HistoryExtent.INITIAL_EMPTY, List.of(), keys, bodies,
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults());
    if (!model.applyBaseline(baseline)) throw new AssertionError("benchmark baseline rejected");
    return model.renderSnapshot();
  }

  private static long percentile(long[] sorted, double fraction) {
    int index = Math.min(sorted.length - 1,
        Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1));
    return sorted[index];
  }
}
