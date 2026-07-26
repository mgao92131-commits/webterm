package com.webterm.terminal.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;
import org.junit.Test;

public final class ScreenMessageV2BuilderTest {
  @Test
  public void helloCarriesResumeToken() throws Exception {
    TerminalScreenV2Proto.ResumeToken resume = TerminalScreenV2Proto.ResumeToken.newBuilder()
        .setInstanceId("instance-1").setLayoutEpoch(3).setScreenRevision(7)
        .setDictionaryGeneration(2).setHistoryGeneration(4)
        .setActiveBuffer(TerminalScreenV2Proto.BufferKind.BUFFER_KIND_MAIN)
        .addActiveRows(TerminalScreenV2Proto.ResumeScreenLine.newBuilder()
            .setLineId(11).setLineVersion(5))
        .build();
    TerminalScreenV2Proto.ScreenEnvelope envelope =
        TerminalScreenV2Proto.ScreenEnvelope.parseFrom(ScreenMessageV2Builder.hello(
            80, 24, resume, 64));

    assertEquals(2, envelope.getProtocolVersion());
    assertEquals(7, envelope.getHello().getResume().getScreenRevision());
    assertEquals("instance-1", envelope.getHello().getResume().getInstanceId());
    assertEquals(3, envelope.getHello().getResume().getLayoutEpoch());
    assertEquals(11, envelope.getHello().getResume().getActiveRows(0).getLineId());
  }

  @Test
  public void historyRangeUsesClosedAbsoluteSequenceInterval() throws Exception {
    TerminalScreenV2Proto.HistoryRangeRequest request =
        TerminalScreenV2Proto.ScreenEnvelope.parseFrom(
            ScreenMessageV2Builder.historyRange("r-1", "instance-1", 4, 9, 129, 256))
            .getHistoryRangeRequest();

    assertEquals(9, request.getHistoryGeneration());
    assertEquals(129, request.getFromSeq());
    assertEquals(256, request.getToSeq());
  }
}
