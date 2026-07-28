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
            80, 24, resume));

    assertEquals(2, envelope.getProtocolVersion());
    assertEquals(7, envelope.getHello().getResume().getScreenRevision());
    assertEquals("instance-1", envelope.getHello().getResume().getInstanceId());
    assertEquals(3, envelope.getHello().getResume().getLayoutEpoch());
    assertEquals(11, envelope.getHello().getResume().getActiveRows(0).getLineId());
  }

  @Test
  public void helloCanRequestForceBaseline() throws Exception {
    TerminalScreenV2Proto.ScreenEnvelope envelope =
        TerminalScreenV2Proto.ScreenEnvelope.parseFrom(ScreenMessageV2Builder.hello(
            80, 24, null,
            TerminalScreenV2Proto.InitialSyncMode.INITIAL_SYNC_MODE_FORCE_BASELINE));
    assertEquals(TerminalScreenV2Proto.InitialSyncMode.INITIAL_SYNC_MODE_FORCE_BASELINE,
        envelope.getHello().getInitialSyncMode());
  }
}
