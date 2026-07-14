package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

public final class ProjectionHealthTest {

  @Test
  public void completeSnapshotPublishesAtomicResumeToken() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertFalse(model.projectionHealth().complete);
    assertFalse(model.resumeToken().hasProjection);

    model.applySnapshot(snapshot());

    ProjectionHealth health = model.projectionHealth();
    ResumeToken token = model.resumeToken();
    assertTrue(health.complete);
    assertTrue(token.hasProjection);
    assertEquals("i1", token.instanceId);
    assertEquals(7, token.layoutEpoch);
    assertEquals(11, token.screenRevision);
    assertEquals(RemoteTerminalModel.SCHEMA_GENERATION, token.schemaGeneration);

    model.resetForReconnect();
    assertFalse(model.projectionHealth().complete);
    assertFalse(model.resumeToken().hasProjection);
  }

  private static ScreenSnapshot snapshot() {
    TerminalLine[] rows = new TerminalLine[5];
    for (int row = 0; row < rows.length; row++) {
      TerminalCell[] cells = new TerminalCell[10];
      java.util.Arrays.fill(cells, TerminalCell.EMPTY);
      rows[row] = new TerminalLine(row, false, cells);
    }
    return new ScreenSnapshot(
        "s1", "i1", 7, 11, 5, 10, ScreenSnapshot.BufferKind.MAIN,
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(),
        new HistoryWindow(1, 1, 0, false, Collections.emptyList()),
        java.util.Arrays.asList(rows), Collections.emptyMap(), Collections.emptyMap(), "", "");
  }
}
