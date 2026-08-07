package com.webterm.terminal.protocol;

import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import com.webterm.terminal.model.HistoryBudget;
import com.webterm.terminal.model.ProjectionResult;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.ScreenProjectionReducer;
import com.webterm.terminal.protocol.generated.TerminalScreenV3Proto;

import org.junit.Test;

import java.util.Arrays;

/** Characterization benchmark for Baseline catalog parse, map, and reducer scaling. */
public final class BaselineHistoryCatalogBenchmarkTest {
  @Test
  public void reportsZeroOneThousandAndTenThousandHistoryCatalogCosts() throws Exception {
    for (int historyLines : new int[] {0, 1000, 10000}) {
      byte[] payload = envelope(historyLines).toByteArray();
      measure(payload); // JIT/class-loading warmup.
      long[] parseSamples = new long[5];
      long[] mapSamples = new long[5];
      long[] applySamples = new long[5];
      for (int sample = 0; sample < 5; sample++) {
        long[] timings = measure(payload);
        parseSamples[sample] = timings[0];
        mapSamples[sample] = timings[1];
        applySamples[sample] = timings[2];
      }

      System.out.printf(
          "baseline_catalog history=%d bytes=%d parse_ns=%d validate_map_ns=%d apply_ns=%d%n",
          historyLines, payload.length, median(parseSamples), median(mapSamples),
          median(applySamples));
    }
  }

  private static long[] measure(byte[] payload) throws Exception {
    long parseStarted = System.nanoTime();
    TerminalScreenV3Proto.ScreenEnvelope parsed =
        TerminalScreenV3Proto.ScreenEnvelope.parseFrom(payload);
    long parseNanos = System.nanoTime() - parseStarted;

    long mapStarted = System.nanoTime();
    ScreenMessageV3Validator.validateBaseline(parsed.getBaseline());
    ScreenBaseline mapped = ScreenMessageV3Mapper.mapBaseline(parsed.getBaseline());
    long mapNanos = System.nanoTime() - mapStarted;

    long applyStarted = System.nanoTime();
    ProjectionResult result =
        new ScreenProjectionReducer(HistoryBudget.defaults()).applyBaseline(mapped);
    long applyNanos = System.nanoTime() - applyStarted;
    assertTrue(result instanceof ProjectionResult.Applied);
    return new long[] {parseNanos, mapNanos, applyNanos};
  }

  private static long median(long[] samples) {
    Arrays.sort(samples);
    return samples[samples.length / 2];
  }

  private static TerminalScreenV3Proto.ScreenEnvelope envelope(int historyLines) {
    final int rows = 24;
    final int cols = 80;
    TerminalScreenV3Proto.Baseline.Builder baseline =
        TerminalScreenV3Proto.Baseline.newBuilder()
            .setSessionId("benchmark")
            .setInstanceId("instance")
            .setLayoutEpoch(1)
            .setScreenRevision(1)
            .setHistoryGeneration(1)
            .setGeometry(TerminalScreenV3Proto.Geometry.newBuilder().setRows(rows).setCols(cols))
            .setActiveBuffer(TerminalScreenV3Proto.BufferKind.BUFFER_KIND_MAIN)
            .setHistoryExtent(TerminalScreenV3Proto.HistoryExtent.newBuilder()
                .setFirstSeq(1).setLastSeq(historyLines))
            .setCursor(TerminalScreenV3Proto.Cursor.newBuilder())
            .setModes(TerminalScreenV3Proto.Modes.newBuilder())
            .setPalette(TerminalScreenV3Proto.TerminalPalette.newBuilder())
            .setDictionary(TerminalScreenV3Proto.Dictionary.newBuilder());

    for (int history = 1; history <= historyLines; history++) {
      baseline.addHistoryBindings(TerminalScreenV3Proto.HistoryBinding.newBuilder()
          .setHistorySeq(history)
          .setKey(TerminalScreenV3Proto.LineKey.newBuilder()
              .setLineId(history).setBodyVersion(1)));
    }
    for (int row = 0; row < rows; row++) {
      long lineId = historyLines + row + 1L;
      TerminalScreenV3Proto.LineKey key = TerminalScreenV3Proto.LineKey.newBuilder()
          .setLineId(lineId).setBodyVersion(1).build();
      baseline.addScreenRows(key);
      baseline.addScreenBodies(TerminalScreenV3Proto.LineBodyRecord.newBuilder()
          .setKey(key)
          .setPhysicalColumns(cols)
          .setUtf8Text(ByteString.EMPTY)
          .setGlyphMeta(ByteString.EMPTY));
    }
    return TerminalScreenV3Proto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(3)
        .setBaseline(baseline)
        .build();
  }
}
