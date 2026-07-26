package com.webterm.terminal.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.TerminalCommit;
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

  @Test
  public void terminalCommitMapsAtomicScreenAndHistoryMutations() {
    TerminalScreenV2Proto.TerminalCommit wire = commitBuilder()
        .setScreen(TerminalScreenV2Proto.ScreenMutation.newBuilder()
            .setScroll(TerminalScreenV2Proto.ScreenScroll.newBuilder()
                .setTopRow(0).setBottomRowExclusive(2).setDeltaRows(1))
            .addWrites(TerminalScreenV2Proto.ScreenRowWrite.newBuilder()
                .setRow(1).setLine(line(12, 0))))
        .setHistory(TerminalScreenV2Proto.HistoryMutation.newBuilder()
            .setFinalExtent(TerminalScreenV2Proto.HistoryExtent.newBuilder()
                .setFirstSeq(1).setLastSeq(7))
            .addAppendedLines(line(7, 7)))
        .build();

    TerminalCommit commit = ScreenMessageV2Mapper.mapTerminalCommit(wire, 2, 1);
    assertEquals(1, commit.screen.scroll.deltaRows);
    assertEquals(12, commit.screen.writes.get(0).line.id);
    assertEquals(7, commit.history.appendedLines.get(0).historySeq);
  }

  @Test(expected = IllegalArgumentException.class)
  public void terminalCommitRejectsIncompleteDictionaryReferences() {
    TerminalScreenV2Proto.LineData styled = line(12, 0).toBuilder()
        .addRuns(TerminalScreenV2Proto.CellRun.newBuilder().setCol(0)
            .addCells(TerminalScreenV2Proto.Cell.newBuilder()
                .setText("X").setWidth(1).setStyleId(99)))
        .build();
    TerminalScreenV2Proto.TerminalCommit wire = commitBuilder()
        .setScreen(TerminalScreenV2Proto.ScreenMutation.newBuilder()
            .addWrites(TerminalScreenV2Proto.ScreenRowWrite.newBuilder()
                .setRow(0).setLine(styled)))
        .build();
    ScreenMessageV2Mapper.mapTerminalCommit(wire, 1, 1);
  }

  private static TerminalScreenV2Proto.TerminalCommit.Builder commitBuilder() {
    return TerminalScreenV2Proto.TerminalCommit.newBuilder()
        .setInstanceId("i").setLayoutEpoch(1).setStreamGeneration(1)
        .setBaseRevision(1).setRevision(2);
  }

  private static TerminalScreenV2Proto.LineData line(long id, long historySeq) {
    return TerminalScreenV2Proto.LineData.newBuilder()
        .setLineId(id).setLineVersion(1).setHistorySeq(historySeq).build();
  }
}
