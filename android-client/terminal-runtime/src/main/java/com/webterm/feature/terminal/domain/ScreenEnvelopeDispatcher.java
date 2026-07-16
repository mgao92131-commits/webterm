package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.protocol.generated.TerminalScreenProto;

/** ScreenEnvelope 单次解析后的定向分发入口。 */
public final class ScreenEnvelopeDispatcher {
  private ScreenEnvelopeDispatcher() {}

  /** 返回 true 表示 envelope 已由可靠输入账本完整消费。 */
  public static boolean dispatchReliableInput(
      @NonNull TerminalScreenProto.ScreenEnvelope envelope,
      @Nullable ReliableInputTracker tracker) {
    if (tracker == null) return false;
    switch (envelope.getPayloadCase()) {
      case INPUT_ACK:
        tracker.handleInputAck(envelope.getInputAck());
        return true;
      case INFO:
        tracker.observeTerminalInstance(envelope.getInfo().getInstanceId());
        return false;
      case SNAPSHOT:
        tracker.observeTerminalInstance(envelope.getSnapshot().getInstanceId());
        return false;
      case PATCH:
        tracker.observeTerminalInstance(envelope.getPatch().getInstanceId());
        return false;
      case RESUME_ACK:
        tracker.observeTerminalInstance(envelope.getResumeAck().getInstanceId());
        return false;
      default:
        return false;
    }
  }
}
