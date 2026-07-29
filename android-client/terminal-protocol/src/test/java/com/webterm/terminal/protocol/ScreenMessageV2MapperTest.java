package com.webterm.terminal.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.google.protobuf.ByteString;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.TerminalCommit;
import com.webterm.terminal.model.HistoryBodyEntry;
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
            .setPhysicalColumns(1)
            .setUtf8Text(ByteString.copyFromUtf8("A"))
            .setGlyphMeta(ByteString.copyFrom(new byte[] {2}))
            .addStyleSpans(TerminalScreenV2Proto.StyleSpan.newBuilder()
                .setStartCol(0).setEndCol(1).setStyleId(4))
            .build();
    TerminalScreenV2Proto.Baseline wire =
        TerminalScreenV2Proto.Baseline.newBuilder()
            .setSessionId("s").setInstanceId("i")
            .setLayoutEpoch(1).setScreenRevision(1)
            .setDictionaryGeneration(1).setHistoryGeneration(1)
            .setGeometry(TerminalScreenV2Proto.Geometry.newBuilder().setRows(1).setCols(1))
            .setActiveBuffer(TerminalScreenV2Proto.BufferKind.BUFFER_KIND_MAIN)
            .setHistoryExtent(TerminalScreenV2Proto.HistoryExtent.newBuilder()
                .setFirstSeq(1).setLastSeq(0))
            .setScreenLayout(TerminalScreenV2Proto.ScreenLayout.newBuilder().addLineIds(9))
            .addScreenLines(line)
            .setCursor(TerminalScreenV2Proto.Cursor.newBuilder())
            .setModes(TerminalScreenV2Proto.Modes.newBuilder())
            .setPalette(TerminalScreenV2Proto.TerminalPalette.newBuilder())
            .setDictionary(TerminalScreenV2Proto.Dictionary.newBuilder().addStyles(style))
            .build();

    ScreenBaseline baseline = ScreenMessageV2Mapper.mapBaseline(wire);
    assertEquals("A", baseline.screen.get(0).body().at(0).text());
    assertNotNull(baseline.screen.get(0).body().at(0).style());
    assertEquals(
        0x123456, baseline.screen.get(0).body().at(0).style().fg().rgb);
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
            .addPushes(TerminalScreenV2Proto.HistoryPush.newBuilder()
                .setHistorySeq(7).setLineId(7).setLineVersion(1)))
        .build();

    TerminalCommit commit = ScreenMessageV2Mapper.mapTerminalCommit(
        wire, 2, 1, WireDictionary.EMPTY);
    assertEquals(1, commit.screen.scroll.deltaRows);
    assertEquals(12, commit.screen.writes.get(0).line.key().lineId());
    assertEquals(7, commit.history.pushes.get(0).historySeq);
    assertEquals(7, commit.history.pushes.get(0).key.lineId());
  }

  @Test
  public void terminalCommitResolvesCanonicalDictionaryBeforeModel() {
    TerminalScreenV2Proto.LineData styled = line(12, 0).toBuilder()
        .setUtf8Text(ByteString.copyFromUtf8("X"))
        .setGlyphMeta(ByteString.copyFrom(new byte[] {2}))
        .addStyleSpans(TerminalScreenV2Proto.StyleSpan.newBuilder()
            .setStartCol(0).setEndCol(1).setStyleId(99))
        .build();
    TerminalScreenV2Proto.TerminalCommit wire = commitBuilder()
        .setScreen(TerminalScreenV2Proto.ScreenMutation.newBuilder()
            .addWrites(TerminalScreenV2Proto.ScreenRowWrite.newBuilder()
                .setRow(0).setLine(styled)))
        .build();
    TerminalScreenV2Proto.Dictionary additions =
        TerminalScreenV2Proto.Dictionary.newBuilder()
            .addStyles(TerminalScreenV2Proto.TerminalStyle.newBuilder().setId(99))
            .build();
    TerminalCommit commit = ScreenMessageV2Mapper.mapTerminalCommit(
        wire, 1, 1, ScreenMessageV2Mapper.mapDictionary(additions));
    assertNotNull(commit.screen.writes.get(0).line.body().at(0).style());
  }

  @Test
  public void historyLineUsesItsOwnPhysicalColumnsAfterResize() {
    TerminalScreenV2Proto.LineData history = TerminalScreenV2Proto.LineData.newBuilder()
        .setLineId(20).setLineVersion(1).setHistorySeq(7)
        .setPhysicalColumns(200)
        .setUtf8Text(ByteString.copyFromUtf8("x"))
        .setGlyphMeta(ByteString.copyFrom(new byte[] {2}))
        .build();
    HistoryBodyEntry mapped = ScreenMessageV2Mapper.mapHistoryLine(
        history, TerminalScreenV2Proto.Dictionary.getDefaultInstance());
    assertEquals(200, mapped.body().physicalColumns);
  }

  private static TerminalScreenV2Proto.TerminalCommit.Builder commitBuilder() {
    return TerminalScreenV2Proto.TerminalCommit.newBuilder()
        .setInstanceId("i").setLayoutEpoch(1)
        .setDictionaryGeneration(1).setHistoryGeneration(1)
        .setBaseRevision(1).setRevision(2);
  }

  private static TerminalScreenV2Proto.LineData line(long id, long historySeq) {
    return TerminalScreenV2Proto.LineData.newBuilder()
        .setLineId(id).setLineVersion(1).setHistorySeq(historySeq)
        .setPhysicalColumns(1).build();
  }
}
