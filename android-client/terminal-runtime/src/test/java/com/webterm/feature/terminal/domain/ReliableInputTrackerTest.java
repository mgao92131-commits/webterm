package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;

import android.os.Handler;

import com.webterm.terminal.protocol.ScreenMessageBuilder;
import com.webterm.terminal.protocol.generated.TerminalScreenProto;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.mock;

public final class ReliableInputTrackerTest {
  @Test
  public void trackerReportsFactsWithoutOwningReconnectActions() {
    Handler scheduler = mock(Handler.class);
    List<ReliableInputTracker.Event> events = new ArrayList<>();
    ReliableInputTracker tracker = new ReliableInputTracker(
        scheduler,
        (payload, callback) -> {
          callback.onResult(ReliableInputTracker.SendResult.QUEUE_FULL);
          return false;
        },
        events::add);
    tracker.observeTerminalInstance("instance-1");

    tracker.send((clientId, seq) -> ScreenMessageBuilder.textInput(
        "lease-1", clientId, seq, "x"));

    assertEquals(ReliableInputTracker.EventType.INPUT_QUEUE_FULL, events.get(0).type);
    assertEquals(1, tracker.pendingCount());
  }

  @Test
  public void instanceChangeAndOldClientAckCannotConfirmCurrentInput() {
    Handler scheduler = mock(Handler.class);
    ReliableInputTracker tracker = new ReliableInputTracker(
        scheduler,
        (payload, callback) -> {
          callback.onResult(ReliableInputTracker.SendResult.WEBSOCKET_ENQUEUED);
          return true;
        },
        event -> {});
    tracker.observeTerminalInstance("instance-1");
    tracker.send((clientId, seq) -> ScreenMessageBuilder.textInput(
        "lease-1", clientId, seq, "x"));

    tracker.handleInputAck(TerminalScreenProto.InputAck.newBuilder()
        .setClientInstanceId("old-client")
        .setInputSeq(1L)
        .setTerminalInstanceId("instance-1")
        .setStatus(TerminalScreenProto.InputAckStatus.INPUT_ACK_STATUS_WRITTEN)
        .build());
    assertEquals(1, tracker.pendingCount());

    tracker.observeTerminalInstance("instance-2");
    assertEquals(0, tracker.pendingCount());
  }
}
