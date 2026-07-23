package com.webterm.terminal.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;
import org.junit.Test;

public final class ScreenMessageV2BuilderTest {
  @Test
  public void frozenHelloCarriesProjectionIdentityAndGeneration() throws Exception {
    TerminalScreenV2Proto.ScreenEnvelope envelope =
        TerminalScreenV2Proto.ScreenEnvelope.parseFrom(ScreenMessageV2Builder.hello(
            80, 24, "client-1", 7,
            TerminalScreenV2Proto.ScreenStreamMode.SCREEN_STREAM_MODE_FROZEN,
            "instance-1", 3, true));

    assertEquals(2, envelope.getProtocolVersion());
    assertEquals(7, envelope.getHello().getStreamGeneration());
    assertEquals("instance-1", envelope.getHello().getInstanceId());
    assertEquals(3, envelope.getHello().getLayoutEpoch());
    assertTrue(envelope.getHello().getHasFrozenProjection());
  }

  @Test
  public void historyRangeUsesClosedAbsoluteSequenceInterval() throws Exception {
    TerminalScreenV2Proto.HistoryRangeRequest request =
        TerminalScreenV2Proto.ScreenEnvelope.parseFrom(
            ScreenMessageV2Builder.historyRange("r-1", "instance-1", 4, 129, 256))
            .getHistoryRangeRequest();

    assertEquals(129, request.getFromSeq());
    assertEquals(256, request.getToSeq());
  }
}
