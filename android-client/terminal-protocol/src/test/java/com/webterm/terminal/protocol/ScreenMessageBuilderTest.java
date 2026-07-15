package com.webterm.terminal.protocol;

import static org.junit.Assert.assertEquals;

import com.webterm.terminal.protocol.generated.TerminalScreenProto;

import org.junit.Test;

public final class ScreenMessageBuilderTest {

  @Test
  public void terminalInputCarriesReliabilityAnchor() throws Exception {
    byte[] payload = ScreenMessageBuilder.textInput("lease-1", "android-1", 42L, "echo ok");
    TerminalScreenProto.ScreenEnvelope envelope = TerminalScreenProto.ScreenEnvelope.parseFrom(payload);

    assertEquals("lease-1", envelope.getInput().getLeaseId());
    assertEquals("android-1", envelope.getInput().getClientInstanceId());
    assertEquals(42L, envelope.getInput().getInputSeq());
    assertEquals("echo ok", envelope.getInput().getText().getData());
  }
}
