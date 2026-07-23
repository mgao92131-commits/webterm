package com.webterm.terminal.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;
import org.junit.Test;

public final class ScreenMessageV2MapperTest {
  @Test
  public void baselineResolvesMessageLocalStyleIntoImmutableCell() {
    TerminalScreenV2Proto.TerminalStyle style =
        TerminalScreenV2Proto.TerminalStyle.newBuilder()
            .setId(4)
            .setFg(TerminalScreenV2Proto.Color.newBuilder()
                .setKind(TerminalScreenV2Proto.ColorKind.COLOR_KIND_RGB)
                .setRgb(0x123456))
            .build();
    TerminalScreenV2Proto.LineData line =
        TerminalScreenV2Proto.LineData.newBuilder()
            .setLineId(9).setLineVersion(1)
            .addRuns(TerminalScreenV2Proto.CellRun.newBuilder().setCol(0)
                .addCells(TerminalScreenV2Proto.Cell.newBuilder()
                    .setText("A").setWidth(1).setStyleId(4)))
            .build();
    TerminalScreenV2Proto.Baseline wire =
        TerminalScreenV2Proto.Baseline.newBuilder()
            .setSessionId("s").setInstanceId("i")
            .setLayoutEpoch(1).setScreenRevision(1).setStreamGeneration(1)
            .setGeometry(TerminalScreenV2Proto.Geometry.newBuilder().setRows(1).setCols(1))
            .setActiveBuffer(TerminalScreenV2Proto.BufferKind.BUFFER_KIND_MAIN)
            .setHistoryExtent(TerminalScreenV2Proto.HistoryExtent.newBuilder()
                .setFirstSeq(1).setLastSeq(0))
            .setHistoryTail(TerminalScreenV2Proto.HistoryTail.newBuilder()
                .setExtent(TerminalScreenV2Proto.HistoryExtent.newBuilder()
                    .setFirstSeq(1).setLastSeq(0)))
            .setScreenLayout(TerminalScreenV2Proto.ScreenLayout.newBuilder().addLineIds(9))
            .addScreenLines(line)
            .setCursor(TerminalScreenV2Proto.Cursor.newBuilder())
            .setModes(TerminalScreenV2Proto.Modes.newBuilder())
            .setPalette(TerminalScreenV2Proto.TerminalPalette.newBuilder())
            .setDictionary(TerminalScreenV2Proto.Dictionary.newBuilder().addStyles(style))
            .build();

    ScreenBaseline baseline = ScreenMessageV2Mapper.mapBaseline(wire);
    assertEquals("A", baseline.screen.get(0).cells[0].text);
    assertNotNull(baseline.screen.get(0).cells[0].style);
    assertEquals(0x123456, baseline.screen.get(0).cells[0].style.fg.rgb);
  }
}
