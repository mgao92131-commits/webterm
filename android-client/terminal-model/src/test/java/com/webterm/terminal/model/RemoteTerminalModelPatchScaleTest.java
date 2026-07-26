package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class RemoteTerminalModelPatchScaleTest {
  @Test
  public void patchFastPathsOnlyRebuildAndScanForLayoutChanges() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(
        RemoteTerminalModelScreenLineStoreTest.baseline(3, 1, 1, 100)));
    model.consumeRenderUpdate();

    assertFalse(model.applyScreenPatch(patch(1, 2, null, Collections.emptyList(),
        null, null)));
    assertEquals(0, model.patchScreenCloneCountForTest());
    assertEquals(0, model.patchScreenStoreRebuildCountForTest());
    assertEquals(0, model.patchMigrationScanCountForTest());

    assertTrue(model.applyScreenPatch(patch(2, 3, null, Collections.emptyList(),
        new TerminalCursor(0, 0, true, TerminalCursor.Shape.BLOCK, false), null)));
    assertTrue(model.applyScreenPatch(patch(3, 4, null, Collections.emptyList(),
        null, "title")));
    assertEquals(0, model.patchScreenCloneCountForTest());
    assertEquals(0, model.patchScreenStoreRebuildCountForTest());
    assertEquals(0, model.patchMigrationScanCountForTest());

    assertTrue(model.applyScreenPatch(patch(4, 5, null,
        Collections.singletonList(RemoteTerminalModelScreenLineStoreTest.line(
            10_001, 2, 0, "changed")), null, null)));
    assertEquals(0, model.patchScreenCloneCountForTest());
    assertEquals(0, model.patchScreenStoreRebuildCountForTest());
    assertEquals(0, model.patchMigrationScanCountForTest());

    assertTrue(model.applyScreenPatch(patch(5, 6,
        new long[] {10_002, 10_003, 20_000},
        Collections.singletonList(RemoteTerminalModelScreenLineStoreTest.line(
            20_000, 1, 0, "new")), null, null)));
    assertEquals(0, model.patchScreenCloneCountForTest());
    assertEquals(1, model.patchScreenStoreRebuildCountForTest());
    assertEquals(1, model.patchMigrationScanCountForTest());
  }

  @Test
  public void millionLineLogicalExtentDoesNotGrowScreenStore() throws Exception {
    for (int rows : new int[] {40, 100, 200}) {
      assertMillionLineLogicalExtentDoesNotGrowScreenStore(rows);
    }
  }

  private static void assertMillionLineLogicalExtentDoesNotGrowScreenStore(int rows)
      throws Exception {
    final int iterations = 1_000;
    AtomicLong baselineHistoryVisits = new AtomicLong();
    RemoteTerminalModel model = new RemoteTerminalModel(
        HistoryBudget.defaults(), ignored -> baselineHistoryVisits.incrementAndGet());
    assertTrue(model.applyBaseline(
        RemoteTerminalModelScreenLineStoreTest.baseline(rows, 1, 1, 1_000_000L)));

    assertEquals(1, model.loadedHistoryLineCountForTest());
    long structuralVisitsAfterBaseline = baselineHistoryVisits.get();
    assertTrue(structuralVisitsAfterBaseline <= model.loadedHistoryLineCountForTest());

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
    assertEquals(structuralVisitsAfterBaseline, baselineHistoryVisits.get());
    System.out.println("PERF_BASELINE screen_patch_scale rows=" + rows
        + " logical_history=1000000 iterations=" + iterations
        + " elapsed_nanos=" + elapsedNanos);
  }

  private static ScreenPatchV2 patch(
      long baseRevision, long revision, long[] layout, java.util.List<TerminalLine> updates,
      TerminalCursor cursor, String title) {
    return new ScreenPatchV2("i1", 1, 1, baseRevision, revision, layout, updates,
        cursor, null, null, null, title, null);
  }
}
