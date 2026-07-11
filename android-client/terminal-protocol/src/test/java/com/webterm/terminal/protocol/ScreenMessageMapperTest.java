package com.webterm.terminal.protocol;

import com.webterm.terminal.model.ScreenPatch;
import com.webterm.terminal.model.ScreenSnapshot;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.protocol.generated.TerminalScreenProto;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ScreenMessageMapperTest {
  @Test
  public void patchPreservesInstanceRowSparseColumnAndWideSpacer() {
    TerminalScreenProto.Cell wide = TerminalScreenProto.Cell.newBuilder().setText("界").setWidth(2).build();
    TerminalScreenProto.Cell ascii = TerminalScreenProto.Cell.newBuilder().setText("x").setWidth(1).build();
    TerminalScreenProto.CellRun run = TerminalScreenProto.CellRun.newBuilder()
        .setCol(3).addCells(wide).addCells(ascii).build();
    TerminalScreenProto.TerminalLine line = TerminalScreenProto.TerminalLine.newBuilder()
        .setRow(7).addRuns(run).build();
    TerminalScreenProto.ScreenPatch pb = TerminalScreenProto.ScreenPatch.newBuilder()
        .setInstanceId("instance-1").setLayoutEpoch(2).setBaseRevision(3).setScreenRevision(4)
        .addScreenRows(line).build();

    ScreenPatch patch = ScreenMessageMapper.mapPatch(pb);
    assertEquals("instance-1", patch.instanceId);
    TerminalLine mapped = patch.screenRows.get(0);
    assertEquals(7, mapped.id);
    assertTrue(mapped.at(0).isDefault());
    assertEquals("界", mapped.at(3).text);
    assertTrue(mapped.at(4).isSpacer());
    assertEquals("x", mapped.at(5).text);
  }

  @Test
  public void snapshotRowsAreExpandedToDeclaredGeometry() {
    TerminalScreenProto.ScreenSnapshot pb = TerminalScreenProto.ScreenSnapshot.newBuilder()
        .setSessionId("s").setInstanceId("i").setLayoutEpoch(1).setScreenRevision(1)
        .setGeometry(TerminalScreenProto.Size.newBuilder().setRows(5).setCols(10))
        .addScreen(TerminalScreenProto.TerminalLine.newBuilder().setRow(4)).build();
    ScreenSnapshot snapshot = ScreenMessageMapper.mapSnapshot(pb);
    assertEquals(1, snapshot.screen.size());
    assertEquals(10, snapshot.screen.get(0).length());
  }
}
