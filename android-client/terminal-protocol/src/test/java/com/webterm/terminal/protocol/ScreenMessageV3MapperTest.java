package com.webterm.terminal.protocol;

import static org.junit.Assert.assertEquals;

import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.protocol.generated.TerminalScreenV3Proto;
import org.junit.Test;

public final class ScreenMessageV3MapperTest {
  @Test
  public void mapsMinimalBaseline() {
    TerminalScreenV3Proto.Baseline wire = TerminalScreenV3Proto.Baseline.newBuilder()
        .setSessionId("s1")
        .setInstanceId("i1")
        .setLayoutEpoch(1)
        .setScreenRevision(1)
        .setHistoryGeneration(1)
        .setGeometry(TerminalScreenV3Proto.Geometry.newBuilder().setRows(1).setCols(1))
        .setActiveBuffer(TerminalScreenV3Proto.BufferKind.BUFFER_KIND_MAIN)
        .setHistoryExtent(TerminalScreenV3Proto.HistoryExtent.newBuilder().setFirstSeq(1).setLastSeq(0))
        .addScreenRows(TerminalScreenV3Proto.LineKey.newBuilder().setLineId(1).setBodyVersion(1))
        .addScreenBodies(TerminalScreenV3Proto.LineBodyRecord.newBuilder()
            .setKey(TerminalScreenV3Proto.LineKey.newBuilder().setLineId(1).setBodyVersion(1))
            .setPhysicalColumns(1)
            .setUtf8Text(com.google.protobuf.ByteString.copyFromUtf8("x"))
            .setGlyphMeta(com.google.protobuf.ByteString.copyFrom(new byte[] {2}))
            .addStyleSpans(TerminalScreenV3Proto.StyleSpan.newBuilder().setStartCol(0).setEndCol(1)))
        .setCursor(TerminalScreenV3Proto.Cursor.newBuilder())
        .setModes(TerminalScreenV3Proto.Modes.newBuilder())
        .setPalette(TerminalScreenV3Proto.TerminalPalette.newBuilder())
        .setDictionary(TerminalScreenV3Proto.Dictionary.newBuilder())
        .build();
    ScreenBaseline baseline = ScreenMessageV3Mapper.mapBaseline(wire);
    assertEquals(new LineKey(1, 1), baseline.screenRows.get(0));
    assertEquals(1, baseline.rows);
  }
}
