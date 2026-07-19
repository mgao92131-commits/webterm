package com.webterm.terminal.protocol;

import com.webterm.terminal.protocol.generated.TerminalScreenProto;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ScreenMessageValidatorTest {
  private static TerminalScreenProto.ScreenPatch.Builder patch() {
    return TerminalScreenProto.ScreenPatch.newBuilder().setInstanceId("i")
        .setLayoutEpoch(1).setBaseRevision(1).setScreenRevision(2);
  }
  @Test public void acceptsStableIdsWithGapsInHistory() {
    assertTrue(ScreenMessageValidator.validatePatch(patch().addHistoryAppendIds(4)
        .addHistoryAppendIds(9).build(), 5).ok);
  }
  @Test public void rejectsNonIncreasingHistoryIds() {
    assertFalse(ScreenMessageValidator.validatePatch(patch().addHistoryAppendIds(9)
        .addHistoryAppendIds(4).build(), 5).ok);
  }
  @Test public void rejectsLayoutLengthMismatch() {
    assertFalse(ScreenMessageValidator.validatePatch(patch().setLayout(
        TerminalScreenProto.ScreenLayout.newBuilder().addLineIds(1)).build(), 5).ok);
  }
  @Test public void rejectsExplicitSpacer() {
    TerminalScreenProto.LineData line = TerminalScreenProto.LineData.newBuilder().setLineId(1)
        .addRuns(TerminalScreenProto.CellRun.newBuilder().setCol(0)
            .addCells(TerminalScreenProto.Cell.newBuilder().setWidth(0))).build();
    assertFalse(ScreenMessageValidator.validatePatch(patch().addLineUpdates(line).build(), 5).ok);
  }
}
