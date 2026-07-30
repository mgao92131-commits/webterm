package com.webterm.core.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Handler;

import com.webterm.transport.api.MuxTransport;
import com.webterm.transport.api.TransportFactory;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class DeviceConnectionInboundBatchTest {
  @Test
  public void oneHundredBinaryFramesUseFewDrainTasks() {
    CountingHandler state = new CountingHandler();
    FakeMuxTransport transport = new FakeMuxTransport();
    DeviceConnection connection = new DeviceConnection(
        state.handler, "http://example.com", "", "device1",
        new FixedFactory(transport));
    connection.openScreenChannel("s1", new NoOpListener());
    state.drainAll(); // connectPhysical → transport.start(listener)
    transport.simulateOpen();
    state.drainAll();

    int postsBefore = state.postCount;
    for (int i = 0; i < 100; i++) {
      transport.simulateBinaryBuffer(ByteBuffer.wrap(new byte[] {(byte) i}));
    }
    int postsForInbound = state.postCount - postsBefore;
    assertTrue("expected batched drain posts, got " + postsForInbound,
        postsForInbound > 0 && postsForInbound < 100);

    state.drainAll();
    MuxInboundMailbox.Snapshot snap = connection.inboundMailboxSnapshot();
    assertEquals(0, snap.currentEvents);
    assertTrue(snap.drainRuns >= 1);
    assertTrue(snap.drainEventCount >= 100);
  }

  @Test
  public void binaryTextBinaryOrderIsPreserved() {
    CountingHandler state = new CountingHandler();
    FakeMuxTransport transport = new FakeMuxTransport();
    DeviceConnection connection = new DeviceConnection(
        state.handler, "http://example.com", "", "device1",
        new FixedFactory(transport));
    RecordingListener listener = new RecordingListener();
    String channelId = connection.openScreenChannel("s1", listener);
    state.drainAll();
    transport.simulateOpen();
    state.drainAll();
    transport.simulateText(wsConnected(channelId));
    state.drainAll();

    byte[] frameA = WebTermProtocol.encodeTunnelFrame(channelId, new byte[] {1}, true);
    byte[] frameC = WebTermProtocol.encodeTunnelFrame(channelId, new byte[] {3}, true);
    transport.simulateBinary(frameA);
    // 控制面 Text 插在两个 Binary 之间；Binary 仍必须按入站顺序到达。
    transport.simulateText("{\"type\":\"pong\"}");
    transport.simulateBinary(frameC);
    state.drainAll();

    assertEquals(List.of("binary:1", "binary:3"), listener.events);
    assertTrue(connection.isConnected());
  }

  @Test
  public void closeIsProcessedAfterEarlierBinaryFrames() {
    CountingHandler state = new CountingHandler();
    FakeMuxTransport transport = new FakeMuxTransport();
    DeviceConnection connection = new DeviceConnection(
        state.handler, "http://example.com", "", "device1",
        new FixedFactory(transport));
    RecordingListener listener = new RecordingListener();
    String channelId = connection.openScreenChannel("s1", listener);
    state.drainAll();
    transport.simulateOpen();
    state.drainAll();
    transport.simulateText(wsConnected(channelId));
    state.drainAll();

    transport.simulateBinary(
        WebTermProtocol.encodeTunnelFrame(channelId, new byte[] {9}, true));
    transport.simulateClose(1000, "bye");
    state.drainAll();

    assertEquals(List.of("binary:9", "failure"), listener.events);
    assertFalse(connection.isConnected());
  }

  @Test
  public void transportReplacementInvalidatesQueuedEvents() {
    CountingHandler state = new CountingHandler();
    FakeMuxTransport first = new FakeMuxTransport();
    FakeMuxTransport second = new FakeMuxTransport();
    DeviceConnection connection = new DeviceConnection(
        state.handler, "http://example.com", "", "device1",
        new RotatingFactory(first, second));
    RecordingListener listener = new RecordingListener();
    String channelId = connection.openScreenChannel("s1", listener);
    state.drainAll();
    first.simulateOpen();
    state.drainAll();
    first.simulateText(wsConnected(channelId));
    state.drainAll();
    listener.events.clear();

    connection.forceReconnect("test");
    state.drainAll();
    // 旧 transport 迟到的帧仍进入旧 Listener 闭包，drain 时 generation 校验丢弃。
    first.simulateBinary(
        WebTermProtocol.encodeTunnelFrame(channelId, new byte[] {7}, true));
    state.drainAll();

    assertFalse(listener.events.contains("binary:7"));
    assertEquals(0, connection.inboundMailboxSnapshot().currentEvents);
  }

  @Test
  public void stopClearsQueuedBuffers() {
    CountingHandler state = new CountingHandler();
    FakeMuxTransport transport = new FakeMuxTransport();
    DeviceConnection connection = new DeviceConnection(
        state.handler, "http://example.com", "", "device1",
        new FixedFactory(transport));
    connection.openScreenChannel("s1", new NoOpListener());
    state.drainAll();
    transport.simulateOpen();
    state.drainAll();

    for (int i = 0; i < 10; i++) {
      transport.simulateBinaryBuffer(ByteBuffer.wrap(new byte[] {(byte) i}));
    }
    connection.stop();
    state.drainAll();
    assertEquals(0, connection.inboundMailboxSnapshot().currentEvents);
  }

  @Test
  public void rejectedRescheduleClearsMailbox() {
    CountingHandler state = new CountingHandler();
    FakeMuxTransport transport = new FakeMuxTransport();
    DeviceConnection connection = new DeviceConnection(
        state.handler, "http://example.com", "", "device1",
        new FixedFactory(transport));
    connection.openScreenChannel("s1", new NoOpListener());
    state.drainAll();
    transport.simulateOpen();
    state.drainAll();

    state.remainingAccepts = 1;
    int excess = MuxInboundMailbox.MAX_DRAIN_EVENTS + 8;
    for (int i = 0; i < excess; i++) {
      transport.simulateBinaryBuffer(ByteBuffer.wrap(new byte[] {(byte) i}));
    }
    state.drainAll();

    assertEquals(0, connection.inboundMailboxSnapshot().currentEvents);
    assertEquals(0L, connection.inboundMailboxSnapshot().currentBytes);
    assertFalse(connection.isInboundDrainScheduled());
  }

  @Test
  public void offerAfterRejectedDrainCanScheduleAgain() {
    CountingHandler state = new CountingHandler();
    FakeMuxTransport transport = new FakeMuxTransport();
    DeviceConnection connection = new DeviceConnection(
        state.handler, "http://example.com", "", "device1",
        new FixedFactory(transport));
    connection.openScreenChannel("s1", new NoOpListener());
    state.drainAll();
    transport.simulateOpen();
    state.drainAll();

    state.remainingAccepts = 1;
    for (int i = 0; i < MuxInboundMailbox.MAX_DRAIN_EVENTS + 4; i++) {
      transport.simulateBinaryBuffer(ByteBuffer.wrap(new byte[] {(byte) i}));
    }
    state.drainAll();
    assertFalse(connection.isInboundDrainScheduled());
    assertEquals(0, connection.inboundMailboxSnapshot().currentEvents);

    state.remainingAccepts = Integer.MAX_VALUE;
    transport.simulateBinaryBuffer(ByteBuffer.wrap(new byte[] {42}));
    assertTrue(connection.isInboundDrainScheduled());
    state.drainAll();
    assertEquals(0, connection.inboundMailboxSnapshot().currentEvents);
    assertFalse(connection.isInboundDrainScheduled());
  }

  @Test
  public void rejectedInitialPostClearsQueuedBuffers() {
    CountingHandler state = new CountingHandler();
    FakeMuxTransport transport = new FakeMuxTransport();
    DeviceConnection connection = new DeviceConnection(
        state.handler, "http://example.com", "", "device1",
        new FixedFactory(transport));
    connection.openScreenChannel("s1", new NoOpListener());
    state.drainAll();
    transport.simulateOpen();
    state.drainAll();

    state.remainingAccepts = 0;
    transport.simulateBinaryBuffer(ByteBuffer.wrap(new byte[] {7, 8, 9}));
    assertEquals(0, connection.inboundMailboxSnapshot().currentEvents);
    assertEquals(0L, connection.inboundMailboxSnapshot().currentBytes);
    assertFalse(connection.isInboundDrainScheduled());
  }

  private static String wsConnected(String channelId) {
    return "{\"type\":\"ws-connected\",\"tunnelConnectionId\":\"" + channelId + "\"}";
  }

  private static final class CountingHandler {
    final Handler handler = mock(Handler.class);
    final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    int postCount;
    int remainingAccepts = Integer.MAX_VALUE;

    CountingHandler() {
      when(handler.post(any(Runnable.class))).thenAnswer(invocation -> {
        if (remainingAccepts <= 0) {
          return false;
        }
        remainingAccepts--;
        queue.addLast(invocation.getArgument(0));
        postCount++;
        return true;
      });
      // 延迟任务不进入即时 drain，避免 connect timeout 在单测中形成重连环。
      when(handler.postDelayed(any(Runnable.class), anyLong())).thenReturn(true);
    }

    void drainAll() {
      int guard = 0;
      while (!queue.isEmpty()) {
        if (++guard > 10_000) {
          throw new IllegalStateException("inbound drain did not converge");
        }
        queue.removeFirst().run();
      }
    }
  }

  private static final class NoOpListener implements DeviceConnection.ChannelListener {
    @Override public void onConnected(String channelId) {}
    @Override public void onData(String channelId, byte[] payload, boolean binary) {}
    @Override public void onFailure(String channelId, ChannelFailure failure) {}
  }

  private static final class RecordingListener implements DeviceConnection.ChannelListener {
    final List<String> events = new ArrayList<>();

    @Override public void onConnected(String channelId) {}

    @Override public void onData(String channelId, byte[] payload, boolean binary) {
      if (binary) {
        events.add("binary:" + (payload.length == 0 ? 0 : payload[0] & 0xff));
      } else {
        events.add("data");
      }
    }

    @Override public void onFailure(String channelId, ChannelFailure failure) {
      events.add("failure");
    }
  }

  private static final class FixedFactory implements TransportFactory {
    private final FakeMuxTransport transport;
    FixedFactory(FakeMuxTransport transport) { this.transport = transport; }
    @Override public MuxTransport create(String url, String cookie, String protocol) {
      return transport;
    }
  }

  private static final class RotatingFactory implements TransportFactory {
    private final FakeMuxTransport[] transports;
    private int next;
    RotatingFactory(FakeMuxTransport... transports) { this.transports = transports; }
    @Override public MuxTransport create(String url, String cookie, String protocol) {
      return transports[Math.min(next++, transports.length - 1)];
    }
  }

  private static final class FakeMuxTransport implements MuxTransport {
    private Listener listener;
    private boolean open;

    @Override public void start(Listener listener) { this.listener = listener; }
    @Override public void close() { open = false; }
    @Override public boolean isConnected() { return open; }
    @Override public boolean sendText(String text) { return open; }
    @Override public boolean sendBinary(byte[] payload) { return open; }

    void simulateOpen() {
      open = true;
      if (listener != null) listener.onOpen();
    }

    void simulateClose(int code, String reason) {
      open = false;
      if (listener != null) listener.onClosed(code, reason);
    }

    void simulateText(String text) {
      if (listener != null) listener.onText(text);
    }

    void simulateBinary(byte[] data) {
      if (listener != null) listener.onBinary(data);
    }

    void simulateBinaryBuffer(ByteBuffer data) {
      if (listener != null) listener.onBinaryBuffer(data);
    }
  }
}
