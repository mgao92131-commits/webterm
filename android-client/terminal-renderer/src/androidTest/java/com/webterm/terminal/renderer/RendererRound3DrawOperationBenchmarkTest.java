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

/** 第三轮显式 TUI 操作量基线；普通 connected test 不执行。 */
@RunWith(AndroidJUnit4.class)
public final class RendererRound3DrawOperationBenchmarkTest {
  private static final int WARMUP_SAMPLES = 10;
  private static final int MEASURED_SAMPLES = 30;
  private static final int BACKGROUND = 0xFF000000;

  @Test
  public void tuiDrawOperationBaseline() {
    Bundle args = InstrumentationRegistry.getArguments();
    Assume.assumeTrue("webtermPerf=true is required",
        "true".equalsIgnoreCase(args.getString("webtermPerf")));

    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    RendererFrameWorkStats stats = new RendererFrameWorkStats();
    RemoteTerminalRenderer renderer = new RemoteTerminalRenderer(
        TerminalFontSet.fromContext(context), stats);
    float textSizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, 14f, context.getResources().getDisplayMetrics());
    renderer.updateFont(textSizePx, Typeface.MONOSPACE);
    TerminalPalette palette = TerminalPalette.defaults();

    for (RendererPerformanceFixtures.Round3Scenario scenario
        : RendererPerformanceFixtures.Round3Scenario.values()) {
      RenderLine[] lines = RendererPerformanceFixtures.round3Lines(scenario);
      RemoteTerminalModel.RenderSnapshot snapshot = snapshot(lines);
      TerminalPreparedLineCache prepared = new TerminalPreparedLineCache();
      for (int i = 0; i < WARMUP_SAMPLES; i++) {
        stats.reset();
        recordOnce(renderer, snapshot, lines, palette, prepared);
      }

      long[] durations = new long[MEASURED_SAMPLES];
      RendererFrameWorkStats.Snapshot last = null;
      for (int i = 0; i < MEASURED_SAMPLES; i++) {
        stats.reset();
        long started = System.nanoTime();
        recordOnce(renderer, snapshot, lines, palette, prepared);
        durations[i] = Math.max(0L, System.nanoTime() - started);
        last = renderer.workStatsForTest();
      }
      Arrays.sort(durations);
      if (last == null) throw new AssertionError("operation stats were not injected");
      System.out.println("{\"scenario\":\"" + scenario.name().toLowerCase()
          + "\",\"samples\":" + MEASURED_SAMPLES
          + ",\"prepared_hit_p50_ns\":" + percentile(durations, 0.50)
          + ",\"prepared_hit_p95_ns\":" + percentile(durations, 0.95)
          + ",\"prepared_span_visits\":" + last.preparedSpanVisitCount()
          + ",\"background_runs\":" + last.backgroundRunCount()
          + ",\"background_rect_draws\":" + last.backgroundRectDrawCount()
          + ",\"text_foreground_ops\":" + last.textForegroundOpCount()
          + ",\"special_glyphs\":" + last.specialGlyphCount()
          + ",\"special_glyph_runs\":" + last.specialGlyphRunCount()
          + ",\"special_glyph_family_dispatch\":"
          + last.specialGlyphFamilyDispatchCount()
          + ",\"special_glyph_cell_clips\":" + last.specialGlyphCellClipCount()
          + ",\"special_glyph_run_clips\":" + last.specialGlyphRunClipCount()
          + ",\"decoration_source_spans\":" + last.decorationSourceSpanCount()
          + ",\"decoration_runs\":" + last.decorationRunCount()
          + ",\"decoration_clips\":" + last.decorationClipCount()
          + ",\"curly_pattern_builds\":" + last.curlyPatternBuildCount()
          + ",\"curly_pattern_cache_hits\":" + last.curlyPatternCacheHitCount()
          + ",\"curly_pattern_segments\":" + last.curlyPatternSegmentCount()
          + ",\"dotted_primitives\":" + last.dottedPrimitiveCount()
          + ",\"dashed_primitives\":" + last.dashedPrimitiveCount()
          + ",\"decoration_path_draws\":" + last.decorationPathDrawCount()
          + ",\"static_foreground_ops\":" + last.staticForegroundOpCount()
          + ",\"slow_blink_ops\":" + last.slowBlinkForegroundOpCount()
          + ",\"fast_blink_ops\":" + last.fastBlinkForegroundOpCount()
          + "}");
    }
  }

  private static void recordOnce(
      RemoteTerminalRenderer renderer,
      RemoteTerminalModel.RenderSnapshot snapshot,
      RenderLine[] lines,
      TerminalPalette palette,
      TerminalPreparedLineCache prepared) {
    prepared.beginFrame(snapshot, renderer, BACKGROUND, 1, 1, 1);
    int width = Math.max(1, renderer.contentWidthPx(RendererPerformanceFixtures.COLUMNS));
    int height = Math.max(1, renderer.lineHeightPx());
    for (int row = 0; row < lines.length; row++) {
      RenderNode node = new RenderNode("round3-prepared-" + row);
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
        "round3", "round3-instance", 1, 1, 1,
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
