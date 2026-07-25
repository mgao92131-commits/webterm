package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;

public final class RemoteTerminalModelPatchScaleTest {
  @Test
  public void millionLineLogicalExtentDoesNotGrowScreenStore() throws Exception {
    final int rows = 40;
    final int iterations = 1_000;
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(
        RemoteTerminalModelScreenLineStoreTest.baseline(rows, 1, 1, 1_000_000L)));

    assertEquals(1, model.loadedHistoryLineCountForTest());
    long structuralVisitsAfterBaseline = model.structuralHistorySlotVisitCountForTest();
    assertEquals(1_000_000L, structuralVisitsAfterBaseline);

    long revision = 1;
    long nextLineId = 20_000;
    long startedNanos = System.nanoTime();
    for (int iteration = 0; iteration < iterations; iteration++) {
      RemoteTerminalModel.RenderSnapshot snapshot = model.renderSnapshot();
      long[] layout = new long[rows];
      for (int row = 0; row < rows - 1; row++) {
        layout[row] = snapshot.screen[row + 1].id;
      }
      layout[rows - 1] = nextLineId;
      model.applyScreenPatch(new ScreenPatchV2(
          "i1", 1, 1, revision, revision + 1, layout,
          Collections.singletonList(RemoteTerminalModelScreenLineStoreTest.line(
              nextLineId, 1, 0, "n")),
          null, null, null, null, null, null));
      revision++;
      nextLineId++;
      assertTrue(model.screenLineStoreSize() <= rows);
    }
    long elapsedNanos = System.nanoTime() - startedNanos;

    assertEquals(1_000_000, model.historySize());
    assertEquals(1, model.loadedHistoryLineCountForTest());
    assertEquals(rows, model.screenLineStoreSize());
    assertEquals("ScreenPatch must not inspect logical history slots",
        structuralVisitsAfterBaseline, model.structuralHistorySlotVisitCountForTest());
    System.out.println("PERF_BASELINE screen_patch_scale rows=" + rows
        + " logical_history=1000000 iterations=" + iterations
        + " elapsed_nanos=" + elapsedNanos);
  }
}
