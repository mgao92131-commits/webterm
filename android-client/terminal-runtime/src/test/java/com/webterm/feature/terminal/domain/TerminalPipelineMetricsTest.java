package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;

import java.util.Map;
import org.junit.Test;

public final class TerminalPipelineMetricsTest {
  @Test
  public void snapshotReportsWatermarkGapsAndRecordsNewViolationShape() {
    TerminalPipelineMetrics metrics = new TerminalPipelineMetrics();
    metrics.onPublicationCreated(3, 7);
    metrics.onRenderConsumed(4, 7);
    metrics.onRenderFrameSucceeded(4, 7, 0);

    Map<String, Object> first = metrics.snapshot();
    assertEquals(1L, first.get("pipelineWatermarkInvariantViolationCount"));
    assertEquals(1L, first.get("publishedConsumedGap"));
    assertEquals(0L, first.get("consumedHandledGap"));
    assertEquals(0L, first.get("consumedRenderedGap"));

    Map<String, Object> repeated = metrics.snapshot();
    assertEquals(1L, repeated.get("pipelineWatermarkInvariantViolationCount"));
  }
}
