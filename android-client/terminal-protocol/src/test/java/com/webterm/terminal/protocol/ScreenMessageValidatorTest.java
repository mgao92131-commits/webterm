package com.webterm.terminal.protocol;

import com.google.protobuf.ByteString;
import com.webterm.terminal.protocol.generated.TerminalScreenProto;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ScreenMessageValidatorTest {
  private static TerminalScreenProto.ScreenPatch.Builder patch() {
    return TerminalScreenProto.ScreenPatch.newBuilder().setInstanceId("i")
        .setLayoutEpoch(1).setBaseRevision(1).setScreenRevision(2);
  }
  private static TerminalScreenProto.LineData historyLine(long id, long seq) {
    return TerminalScreenProto.LineData.newBuilder().setLineId(id).setLineVersion(1)
        .setHistorySeq(seq).build();
  }
  @Test public void acceptsStableIdsWithGapsInHistory() {
    assertTrue(ScreenMessageValidator.validatePatch(patch().addHistoryAppendSeqs(4)
        .addHistoryAppendSeqs(9).addLineUpdates(historyLine(100, 4))
        .addLineUpdates(historyLine(3, 9)).build(), 5, 5).ok);
  }
  @Test public void rejectsNonIncreasingHistorySeqs() {
    assertFalse(ScreenMessageValidator.validatePatch(patch().addHistoryAppendSeqs(9)
        .addHistoryAppendSeqs(4).build(), 5, 5).ok);
  }
  @Test public void rejectsHistoryPageWithoutHistorySequence() {
    TerminalScreenProto.HistoryPage page = TerminalScreenProto.HistoryPage.newBuilder()
        .setRequestId("page").setLayoutEpoch(1).setAsOfRevision(1)
        .addLines(TerminalScreenProto.LineData.newBuilder().setLineId(7).setLineVersion(1))
        .build();
    assertFalse(ScreenMessageValidator.validateHistoryPage(page).ok);
  }
  @Test public void rejectsLayoutLengthMismatch() {
    assertFalse(ScreenMessageValidator.validatePatch(patch().setLayout(
        TerminalScreenProto.ScreenLayout.newBuilder().addLineIds(1)).build(), 5, 5).ok);
  }
  @Test public void rejectsExplicitSpacer() {
    TerminalScreenProto.LineData line = TerminalScreenProto.LineData.newBuilder().setLineId(1)
        .addRuns(TerminalScreenProto.CellRun.newBuilder().setCol(0)
            .addCells(TerminalScreenProto.Cell.newBuilder().setWidth(0))).build();
    assertFalse(ScreenMessageValidator.validatePatch(patch().addLineUpdates(line).build(), 5, 5).ok);
  }
  @Test public void acceptsUtf8CompactWideAndMultiCodePointCells() {
    TerminalScreenProto.LineData line = compactLine("A❤️中", new byte[] {1, (byte) 0x82, (byte) 0x81});
    assertTrue(ScreenMessageValidator.validatePatch(patch().addLineUpdates(line).build(), 5, 5).ok);
  }
  @Test public void rejectsInvalidUtf8CompactMetadata() {
    assertFalse(ScreenMessageValidator.validatePatch(patch().addLineUpdates(
        compactLine("A", new byte[] {0})).build(), 5, 5).ok);
    assertFalse(ScreenMessageValidator.validatePatch(patch().addLineUpdates(
        compactLine("A", new byte[] {1, 1})).build(), 5, 5).ok);
    assertFalse(ScreenMessageValidator.validatePatch(patch().addLineUpdates(
        compactLine("中A", new byte[] {1})).build(), 5, 5).ok);
    assertFalse(ScreenMessageValidator.validatePatch(patch().addLineUpdates(
        compactLine("中", new byte[] {(byte) 0x81})).build(), 5, 1).ok);
  }
  @Test public void compactTextUsesLineLimitNotPerCell64ByteLimit() {
    String over64Bytes = repeat("界", 22); // 66 UTF-8 bytes, 22 code points.
    assertTrue(ScreenMessageValidator.validatePatch(patch().addLineUpdates(
        compactLine(over64Bytes, new byte[] {22})).build(), 5, 1).ok);

    String tooLarge = repeat("界", 127 * 87);
    byte[] metadata = new byte[87];
    java.util.Arrays.fill(metadata, (byte) 127);
    assertFalse(ScreenMessageValidator.validatePatch(patch().addLineUpdates(
        compactLine(tooLarge, metadata)).build(), 5, 100).ok);
  }
  @Test public void rejectsCompactRunsAndUnconsumedData() {
    TerminalScreenProto.LineData mixed = compactLine("A", new byte[] {1}).toBuilder()
        .addRuns(TerminalScreenProto.CellRun.newBuilder().setCol(0)
            .addCells(TerminalScreenProto.Cell.newBuilder().setText("A").setWidth(1))).build();
    assertFalse(ScreenMessageValidator.validatePatch(patch().addLineUpdates(mixed).build(), 5, 5).ok);
    TerminalScreenProto.LineData noMeta = TerminalScreenProto.LineData.newBuilder()
        .setLineId(1).setLineVersion(1).setText("A").build();
    assertFalse(ScreenMessageValidator.validatePatch(patch().addLineUpdates(noMeta).build(), 5, 5).ok);
  }

  private static TerminalScreenProto.LineData compactLine(String text, byte[] cellMeta) {
    return TerminalScreenProto.LineData.newBuilder().setLineId(1).setLineVersion(1)
        .setText(text).setCellMeta(ByteString.copyFrom(cellMeta)).build();
  }
  private static String repeat(String value, int count) {
    StringBuilder result = new StringBuilder(value.length() * count);
    for (int i = 0; i < count; i++) result.append(value);
    return result.toString();
  }
}
