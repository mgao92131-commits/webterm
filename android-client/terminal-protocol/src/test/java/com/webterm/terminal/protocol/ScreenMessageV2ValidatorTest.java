package com.webterm.terminal.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.CommitValidationException;
import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;

import org.junit.Test;

public final class ScreenMessageV2ValidatorTest {
  @Test(expected = CommitValidationException.class)
  public void terminalCommitRejectsPartialScrollRegion() throws Exception {
    ScreenMessageV2Validator.validateTerminalCommit(commitBuilder()
        .setScreen(TerminalScreenV2Proto.ScreenMutation.newBuilder()
            .setScroll(TerminalScreenV2Proto.ScreenScroll.newBuilder()
                .setTopRow(1).setBottomRowExclusive(3).setDeltaRows(1)))
        .build(), 3);
  }

  @Test
  public void historyExtentBoundaryMatchesDomainModel() throws Exception {
    assertAcceptedExtent(1, 0);
    assertAcceptedExtent(1, 1);
    assertAcceptedExtent(10, 9);
    assertAcceptedExtent(Long.MAX_VALUE - 128, Long.MAX_VALUE - 1);
    assertRejectedExtent(10, 8);
    assertRejectedExtent(1, Long.MAX_VALUE);
  }

  @Test
  public void terminalCommitAcceptsScrollWritesAndBoundedHistory() throws Exception {
    ScreenMessageV2Validator.validateTerminalCommit(commitBuilder()
        .setScreen(TerminalScreenV2Proto.ScreenMutation.newBuilder()
            .setScroll(TerminalScreenV2Proto.ScreenScroll.newBuilder()
                .setTopRow(0).setBottomRowExclusive(3).setDeltaRows(1))
            .addWrites(TerminalScreenV2Proto.ScreenRowWrite.newBuilder()
                .setRow(2).setLine(line(10, 0))))
        .setHistory(TerminalScreenV2Proto.HistoryMutation.newBuilder()
            .setFinalExtent(TerminalScreenV2Proto.HistoryExtent.newBuilder()
                .setFirstSeq(1).setLastSeq(2))
            .addAppendedLines(line(20, 2)))
        .build(), 3);
  }

  @Test(expected = CommitValidationException.class)
  public void terminalCommitRejectsDuplicateScreenRows() throws Exception {
    ScreenMessageV2Validator.validateTerminalCommit(commitBuilder()
        .setScreen(TerminalScreenV2Proto.ScreenMutation.newBuilder()
            .addWrites(TerminalScreenV2Proto.ScreenRowWrite.newBuilder()
                .setRow(0).setLine(line(1, 0)))
            .addWrites(TerminalScreenV2Proto.ScreenRowWrite.newBuilder()
                .setRow(0).setLine(line(2, 0))))
        .build(), 2);
  }

  @Test(expected = CommitValidationException.class)
  public void terminalCommitRejectsMoreThan128HistoryBodies() throws Exception {
    TerminalScreenV2Proto.HistoryMutation.Builder history =
        TerminalScreenV2Proto.HistoryMutation.newBuilder()
            .setFinalExtent(TerminalScreenV2Proto.HistoryExtent.newBuilder()
                .setFirstSeq(1).setLastSeq(129));
    for (int i = 1; i <= 129; i++) history.addAppendedLines(line(1000 + i, i));
    ScreenMessageV2Validator.validateTerminalCommit(
        commitBuilder().setHistory(history).build(), 2);
  }

  private static TerminalScreenV2Proto.TerminalCommit.Builder commitBuilder() {
    return TerminalScreenV2Proto.TerminalCommit.newBuilder()
        .setInstanceId("i1").setLayoutEpoch(1)
        .setDictionaryGeneration(1).setHistoryGeneration(1)
        .setBaseRevision(1).setRevision(2);
  }

  private static TerminalScreenV2Proto.LineData line(long id, long historySeq) {
    return TerminalScreenV2Proto.LineData.newBuilder()
        .setLineId(id).setLineVersion(1).setHistorySeq(historySeq).build();
  }

  private static void assertAcceptedExtent(long first, long last) throws Exception {
    ScreenMessageV2Validator.validateTerminalCommit(historyCommit(first, last), 1);
    new HistoryExtent(first, last);
  }

  private static void assertRejectedExtent(long first, long last) {
    try {
      ScreenMessageV2Validator.validateTerminalCommit(historyCommit(first, last), 1);
      fail("validator accepted invalid extent " + first + ".." + last);
    } catch (IllegalArgumentException
        | com.webterm.terminal.model.CommitValidationException expected) {
      // Expected.
    }
    try {
      new HistoryExtent(first, last);
      fail("domain model accepted invalid extent " + first + ".." + last);
    } catch (IllegalArgumentException expected) {
      // Expected.
    }
  }

  private static TerminalScreenV2Proto.TerminalCommit historyCommit(long first, long last) {
    return commitBuilder().setHistory(TerminalScreenV2Proto.HistoryMutation.newBuilder()
        .setFinalExtent(TerminalScreenV2Proto.HistoryExtent.newBuilder()
            .setFirstSeq(first).setLastSeq(last))).build();
  }
}
