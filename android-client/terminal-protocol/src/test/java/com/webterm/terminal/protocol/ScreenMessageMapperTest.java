package com.webterm.terminal.protocol;

import com.google.protobuf.ByteString;
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
        .setLineId(42).setLineVersion(7).setHistorySeq(42).addRuns(TerminalScreenProto.CellRun.newBuilder()
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

  @Test public void utf8CompactRestoresWideCellsSpacersAndTerminalColumnStyles() {
    TerminalScreenProto.LineData line = compactLine(11, "A中B", new byte[] {1, (byte) 0x81, 1})
        .toBuilder().addStyleSpans(TerminalScreenProto.StyleSpan.newBuilder()
            .setStartCol(1).setEndCol(3).setStyleId(7).setLinkId(9)).build();
    TerminalLine mapped = ScreenMessageMapper.mapPatch(compactPatch(line), 6).lineUpdates.get(0);
    assertEquals("A", mapped.at(0).text);
    assertEquals("中", mapped.at(1).text); assertEquals(2, mapped.at(1).width);
    assertEquals(7, mapped.at(1).styleId); assertEquals(9, mapped.at(1).linkId);
    assertTrue(mapped.at(2).isSpacer());
    assertEquals("B", mapped.at(3).text);
    assertSame(TerminalCell.EMPTY, mapped.at(4));
  }

  @Test public void utf8CompactPreservesSupplementaryAndMultiCodePointCells() {
    String[] text = {"😀", "é", "❤️", "👍🏻", "👨‍👩‍👧‍👦", "🇯🇵"};
    byte[] meta = {(byte) 0x81, 2, (byte) 0x82, (byte) 0x82, (byte) 0x87, (byte) 0x82};
    TerminalLine mapped = ScreenMessageMapper.mapPatch(compactPatch(compactLine(12,
        String.join("", text), meta)), 10).lineUpdates.get(0);
    int col = 0;
    for (int i = 0; i < text.length; i++) {
      int width = (meta[i] & 0x80) != 0 ? 2 : 1;
      assertEquals(text[i], mapped.at(col).text);
      assertEquals(width, mapped.at(col).width);
      if (width == 2) assertTrue(mapped.at(col + 1).isSpacer());
      col += width;
    }
  }

  @Test public void utf8CompactDecodeBaseline() throws Exception {
    StringBuilder text = new StringBuilder();
    byte[] meta = new byte[100];
    TerminalScreenProto.CellRun.Builder run = TerminalScreenProto.CellRun.newBuilder().setCol(0);
    for (int i = 0; i < 100; i++) {
      if ((i & 1) == 0) {
        text.append("中"); meta[i] = (byte) 0x81;
        run.addCells(TerminalScreenProto.Cell.newBuilder().setText("中").setWidth(2));
      } else {
        text.append("A"); meta[i] = 1;
        run.addCells(TerminalScreenProto.Cell.newBuilder().setText("A").setWidth(1));
      }
    }
    byte[] compact = compactPatch(compactLine(20, text.toString(), meta)).toByteArray();
    TerminalScreenProto.LineData runLine = TerminalScreenProto.LineData.newBuilder()
        .setLineId(20).setLineVersion(1).addRuns(run).build();
    byte[] cellRuns = compactPatch(runLine).toByteArray();
    long start = System.nanoTime();
    for (int i = 0; i < 500; i++) {
      ScreenMessageMapper.mapPatch(TerminalScreenProto.ScreenPatch.parseFrom(compact), 150);
    }
    long compactNanos = System.nanoTime() - start;
    start = System.nanoTime();
    for (int i = 0; i < 500; i++) {
      ScreenMessageMapper.mapPatch(TerminalScreenProto.ScreenPatch.parseFrom(cellRuns), 150);
    }
    long cellRunNanos = System.nanoTime() - start;
    System.out.println("[UTF8CompactDecode] scenario=mixed-100 compact_bytes=" + compact.length
        + " cellrun_bytes=" + cellRuns.length + " compact_ns_per_op=" + (compactNanos / 500)
        + " cellrun_ns_per_op=" + (cellRunNanos / 500));
  }

  private static TerminalScreenProto.ScreenPatch compactPatch(TerminalScreenProto.LineData line) {
    return TerminalScreenProto.ScreenPatch.newBuilder().setInstanceId("i").setLayoutEpoch(1)
        .setBaseRevision(1).setScreenRevision(2).addLineUpdates(line).build();
  }

  private static TerminalScreenProto.LineData compactLine(long id, String text, byte[] meta) {
    return TerminalScreenProto.LineData.newBuilder().setLineId(id).setLineVersion(1)
        .setText(text).setCellMeta(ByteString.copyFrom(meta)).build();
  }
}
