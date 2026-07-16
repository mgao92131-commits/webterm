package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Handler;

import com.webterm.core.session.DeviceConnection;
import com.webterm.core.session.DeviceConnectionRegistry;
import com.webterm.terminal.protocol.generated.TerminalScreenProto;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class TerminalChannelReliableInputTest {
  @Test
  public void firstQueueFailureDoesNotMarkGenerationAndCanBeSentAgain() throws Exception {
    Handler handler = mock(Handler.class);
    DeviceConnectionRegistry registry = mock(DeviceConnectionRegistry.class);
    DeviceConnection connection = mock(DeviceConnection.class);
    AtomicInteger attempts = new AtomicInteger();
    when(connection.tryEnqueueTunnelFrame(anyString(), any(byte[].class), anyBoolean(), any()))
        .thenAnswer(invocation -> {
          DeviceConnection.TunnelSendCallback callback = invocation.getArgument(3);
          int attempt = attempts.incrementAndGet();
          callback.onResult(attempt == 1
              ? DeviceConnection.TunnelSendResult.LOCAL_QUEUE_FULL
              : DeviceConnection.TunnelSendResult.WEBSOCKET_ENQUEUED);
          return attempt != 1;
        });

    TerminalChannel channel = new TerminalChannel(
        handler, registry, "https://relay.example", "", "s1", "d1");
    setField(channel, "deviceConnection", connection);
    setField(channel, "channelId", "screen-1");
    channel.setListener(mock(TerminalSessionRuntime.ScreenConnection.Listener.class));
    channel.handleInboundEnvelope(TerminalScreenProto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(1)
        .setSnapshot(TerminalScreenProto.ScreenSnapshot.newBuilder()
            .setInstanceId("terminal-instance-1"))
        .build());
    channel.setLayoutLeaseId("lease-1");

    channel.sendTextInput("x");
    Object pending = onlyPendingInput(channel);
    assertEquals("failed local enqueue must remain unsent", 0L,
        longField(pending, "lastSentGeneration"));
    assertEquals("UNSENT", field(pending, "state").toString());

    // A later readiness signal in the same confirmed terminal instance retries
    // the UNSENT item. Only the successful WebSocket enqueue commits generation.
    channel.setLayoutLeaseId("lease-1");
    pending = onlyPendingInput(channel);
    assertEquals(2, attempts.get());
    assertEquals(1L, longField(pending, "lastSentGeneration"));
    assertEquals("WEBSOCKET_ENQUEUED", field(pending, "state").toString());
    assertFalse(((String) field(pending, "terminalInstanceId")).isEmpty());
  }

  private static Object onlyPendingInput(TerminalChannel channel) throws Exception {
    Field field = TerminalChannel.class.getDeclaredField("unackedInputs");
    field.setAccessible(true);
    LinkedHashMap<?, ?> pending = (LinkedHashMap<?, ?>) field.get(channel);
    assertEquals(1, pending.size());
    return pending.values().iterator().next();
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Object field(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static long longField(Object target, String name) throws Exception {
    return (long) field(target, name);
  }
}
