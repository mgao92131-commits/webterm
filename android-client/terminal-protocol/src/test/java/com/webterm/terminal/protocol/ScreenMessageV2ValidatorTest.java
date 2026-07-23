package com.webterm.terminal.protocol;

import static org.junit.Assert.assertEquals;

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
}
