package com.webterm.terminal.protocol;

import com.webterm.terminal.model.ScreenPatch;
import com.webterm.terminal.model.ScreenSnapshot;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.protocol.generated.TerminalScreenProto;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ScreenMessageMapperTest {
  @Test public void patchMapsStableLineDataAndLayout() {
    TerminalScreenProto.LineData line = TerminalScreenProto.LineData.newBuilder()
        .setLineId(42).setLineVersion(7).addRuns(TerminalScreenProto.CellRun.newBuilder()
            .setCol(3).addCells(TerminalScreenProto.Cell.newBuilder().setText("界").setWidth(2))
            .addCells(TerminalScreenProto.Cell.newBuilder().setText("x").setWidth(1))).build();
    ScreenPatch patch = ScreenMessageMapper.mapPatch(TerminalScreenProto.ScreenPatch.newBuilder()
        .setInstanceId("i").setLayoutEpoch(1).setBaseRevision(1).setScreenRevision(2)
        .setLayout(TerminalScreenProto.ScreenLayout.newBuilder().addLineIds(42))
        .addLineUpdates(line).addHistoryAppendIds(42).build(), 10);
    assertArrayEquals(new long[] {42}, patch.layout);
    TerminalLine mapped = patch.lineUpdates.get(0);
    assertEquals(42, mapped.id); assertEquals(7, mapped.version);
    assertEquals("界", mapped.at(3).text); assertTrue(mapped.at(4).isSpacer());
    assertEquals("x", mapped.at(5).text); assertEquals(Long.valueOf(42), patch.historyAppendIds.get(0));
  }

  @Test public void snapshotResolvesLayoutByLineId() {
    TerminalScreenProto.LineData line = TerminalScreenProto.LineData.newBuilder().setLineId(8)
        .setLineVersion(1).build();
    ScreenSnapshot snapshot = ScreenMessageMapper.mapSnapshot(TerminalScreenProto.ScreenSnapshot.newBuilder()
        .setSessionId("s").setInstanceId("i").setLayoutEpoch(1).setScreenRevision(1)
        .setGeometry(TerminalScreenProto.Size.newBuilder().setRows(1).setCols(10))
        .setLayout(TerminalScreenProto.ScreenLayout.newBuilder().addLineIds(8))
        .addScreenLines(line).build());
    assertEquals(1, snapshot.screen.size()); assertEquals(8, snapshot.screen.get(0).id);
    assertSame(TerminalCell.EMPTY, snapshot.screen.get(0).at(0));
  }
}
