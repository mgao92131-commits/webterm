package com.webterm.terminal.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;

import org.junit.Test;

public final class ScreenMessageV2ValidatorTest {
  @Test(expected = IllegalArgumentException.class)
  public void emptyPatchIsRejected() {
    ScreenMessageV2Validator.validatePatch(TerminalScreenV2Proto.ScreenPatch.newBuilder()
        .setInstanceId("i1")
        .setLayoutEpoch(1)
        .setStreamGeneration(1)
        .setBaseScreenRevision(1)
        .setScreenRevision(2)
        .build());
  }

  @Test
  public void protoDefaultExtentIsNormalizedToCanonicalEmpty() {
    TerminalScreenV2Proto.HistoryDelta delta =
        TerminalScreenV2Proto.HistoryDelta.newBuilder()
            .setInstanceId("i1")
            .setLayoutEpoch(1)
            .setStreamGeneration(1)
            .build();
    ScreenMessageV2Validator.validateHistoryDelta(delta);
    assertEquals(HistoryExtent.INITIAL_EMPTY,
        ScreenMessageV2Mapper.mapHistoryDelta(delta, 1).availableExtent);
  }

  @Test
  public void historyExtentBoundaryMatchesDomainModel() {
    assertAcceptedExtent(1, 0);
    assertAcceptedExtent(1, 1);
    assertAcceptedExtent(10, 9);
    assertAcceptedExtent(Long.MAX_VALUE - 128, Long.MAX_VALUE - 1);
    assertRejectedExtent(10, 8);
    assertRejectedExtent(1, Long.MAX_VALUE);
  }

  @Test
  public void nonDataHistoryRangeStatusesRejectLinesButTrimmedAllowsIntersection() {
    assertHistoryRangeLineRejected(
        TerminalScreenV2Proto.HistoryRangeStatus.HISTORY_RANGE_STATUS_STALE_PROJECTION);
    assertHistoryRangeLineRejected(
        TerminalScreenV2Proto.HistoryRangeStatus.HISTORY_RANGE_STATUS_RETRYABLE);
    ScreenMessageV2Validator.validateHistoryRange(historyRange(
        TerminalScreenV2Proto.HistoryRangeStatus.HISTORY_RANGE_STATUS_TRIMMED, true));
  }

  private static void assertHistoryRangeLineRejected(
      TerminalScreenV2Proto.HistoryRangeStatus status) {
    try {
      ScreenMessageV2Validator.validateHistoryRange(historyRange(status, true));
      fail("validator accepted lines for " + status);
    } catch (IllegalArgumentException expected) {
      // Expected.
    }
  }

  private static TerminalScreenV2Proto.HistoryRangeResponse historyRange(
      TerminalScreenV2Proto.HistoryRangeStatus status, boolean withLine) {
    TerminalScreenV2Proto.HistoryRangeResponse.Builder response =
        TerminalScreenV2Proto.HistoryRangeResponse.newBuilder()
            .setRequestId("r1")
            .setInstanceId("i1")
            .setLayoutEpoch(1)
            .setStatus(status)
            .setAvailableExtent(TerminalScreenV2Proto.HistoryExtent.newBuilder()
                .setFirstSeq(1).setLastSeq(10));
    if (withLine) {
      response.addLines(TerminalScreenV2Proto.LineData.newBuilder()
          .setLineId(1).setLineVersion(1).setHistorySeq(1));
    }
    return response.build();
  }

  private static void assertAcceptedExtent(long first, long last) {
    ScreenMessageV2Validator.validateHistoryDelta(historyDelta(first, last));
    new HistoryExtent(first, last);
  }

  private static void assertRejectedExtent(long first, long last) {
    try {
      ScreenMessageV2Validator.validateHistoryDelta(historyDelta(first, last));
      fail("validator accepted invalid extent " + first + ".." + last);
    } catch (IllegalArgumentException expected) {
      // Expected.
    }
    try {
      new HistoryExtent(first, last);
      fail("domain model accepted invalid extent " + first + ".." + last);
    } catch (IllegalArgumentException expected) {
      // Expected.
    }
  }

  private static TerminalScreenV2Proto.HistoryDelta historyDelta(long first, long last) {
    return TerminalScreenV2Proto.HistoryDelta.newBuilder()
        .setInstanceId("i1")
        .setLayoutEpoch(1)
        .setStreamGeneration(1)
        .setAvailableExtent(TerminalScreenV2Proto.HistoryExtent.newBuilder()
            .setFirstSeq(first)
            .setLastSeq(last))
        .build();
  }
}
