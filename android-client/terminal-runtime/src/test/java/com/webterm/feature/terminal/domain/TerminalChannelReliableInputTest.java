package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;

import com.webterm.core.session.DeviceConnection;
import com.webterm.core.session.DeviceConnectionRegistry;
import com.webterm.terminal.protocol.generated.TerminalScreenProto;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

public final class TerminalChannelReliableInputTest {
  @Test
  public void queueFailureIsReportedButTransportAdapterDoesNotOwnReconnectPolicy()
      throws Exception {
    Handler handler = mock(Handler.class);
    DeviceConnectionRegistry registry = mock(DeviceConnectionRegistry.class);
    DeviceConnection connection = mock(DeviceConnection.class);
    when(connection.tryEnqueueTunnelFrame(anyString(), any(byte[].class), anyBoolean(), any()))
        .thenAnswer(invocation -> {
          DeviceConnection.TunnelSendCallback callback = invocation.getArgument(3);
          callback.onResult(DeviceConnection.TunnelSendResult.LOCAL_QUEUE_FULL);
          return false;
        });
    AtomicInteger uncertain = new AtomicInteger();

    TerminalChannel channel = new TerminalChannel(
        handler, registry, "https://relay.example", "", "s1", "cfg-1", false, "d1");
    setField(channel, "deviceConnection", connection);
    setField(channel, "channelId", "screen-1");
    channel.setListener(new TerminalSessionRuntime.ScreenConnection.Listener() {
      @Override public void onScreenMessage(byte[] payload) {}
      @Override public void onConnected() {}
      @Override public void onDisconnected(String reason) {}
      @Override public void onInputDeliveryUncertain(String message) { uncertain.incrementAndGet(); }
      @Override public void onClosed() {}
    });
    channel.reliableInputTracker().observeTerminalInstance("terminal-instance-1");
    channel.setLayoutLeaseId("lease-1");

    channel.sendTextInput("x");

    assertEquals(1, uncertain.get());
    verify(connection, never()).forceReconnect(anyString());
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}
