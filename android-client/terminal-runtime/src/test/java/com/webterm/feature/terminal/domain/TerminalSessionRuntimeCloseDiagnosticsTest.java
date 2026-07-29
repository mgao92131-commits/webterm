package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.HistoryPush;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.ScreenLineContent;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * P1-1 / P1-2：close 后最终快照时机、幂等 unregister、lifetime archived 累计。
 */
public final class TerminalSessionRuntimeCloseDiagnosticsTest {

  @Before
  public void setUp() {
    TerminalPipelineDiagnosticsRegistry.clearForTest();
  }

  @After
  public void tearDown() {
    TerminalPipelineDiagnosticsRegistry.clearForTest();
  }

  @Test
  public void closeTwiceProducesSingleRecentClosedEntry() {
    TerminalSessionRuntime runtime =
        new TerminalSessionRuntime("close-twice", new RemoteTerminalModel(), Runnable::run);
    runtime.close();
    runtime.close();

    assertEquals(TerminalSessionRuntime.State.CLOSED, runtime.state());
    assertEquals(1, TerminalPipelineDiagnosticsRegistry.snapshotRecentClosed().size());
    assertEquals(0, TerminalPipelineDiagnosticsRegistry.snapshotActive().size());
    TerminalPipelineDiagnosticsRegistry.SessionDiagnosticsSnapshot closed =
        TerminalPipelineDiagnosticsRegistry.snapshotRecentClosed().get(0);
    assertEquals("CLOSED", closed.state);
    assertEquals("CLOSED", closed.finalState);
    assertTrue(closed.closeRequestedAtEpochMs > 0L);
    assertTrue(closed.closedAtEpochMs >= closed.closeRequestedAtEpochMs);
  }

  @Test
  public void closeWithActiveHistoryRequestFinalizesLoaderClosed() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(domainBaseline()));
    model.consumeRenderUpdate();

    ArrayDeque<Runnable> modelQueue = new ArrayDeque<>();
    TerminalSessionRuntime runtime =
        new TerminalSessionRuntime("close-hist", model, modelQueue::add, Runnable::run);
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    drain(modelQueue);
    runtime.enterLiveForTest();
    drain(modelQueue);

    AtomicReference<HistoryRangeSource.Callback> pending = new AtomicReference<>();
    runtime.setHistoryRangeSource(new HistoryRangeSource() {
      @Override
      public RequestHandle fetch(
          @NonNull HistoryRangeLoader.Range range, @NonNull Callback callback) {
        pending.set(callback);
        return () -> {};
      }

      @Override
      public void close() {}
    });
    drain(modelQueue);

    runtime.onVisibleHistoryDemand(100, 180, 100, -1, 50);
    drain(modelQueue);
    assertTrue("history fetch should be in-flight", pending.get() != null);

    runtime.close();
    assertEquals(TerminalSessionRuntime.State.CLOSING, runtime.state());
    drain(modelQueue);

    assertEquals(TerminalSessionRuntime.State.CLOSED, runtime.state());
    assertEquals(1, TerminalPipelineDiagnosticsRegistry.snapshotRecentClosed().size());
    TerminalPipelineDiagnosticsRegistry.SessionDiagnosticsSnapshot closed =
        TerminalPipelineDiagnosticsRegistry.snapshotRecentClosed().get(0);
    assertEquals(Boolean.TRUE, closed.historyLoader.get("closed"));
    assertEquals(Boolean.FALSE, closed.historyLoader.get("hasActiveRequest"));
  }

  @Test
  public void closePreservesMailboxDepthAtRequestAndFinalZero() {
    ArrayDeque<Runnable> modelQueue = new ArrayDeque<>();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "close-mbox", new RemoteTerminalModel(), modelQueue::add, Runnable::run);
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    drain(modelQueue);

    // 入队但不 drain：mailbox 保持非空。
    connection.listener.onScreenMessage(new byte[] {1, 2, 3, 4});
    assertEquals(1, modelQueue.size()); // drainScreenMailbox 已排队

    runtime.close();
    assertEquals(TerminalSessionRuntime.State.CLOSING, runtime.state());
    drain(modelQueue);

    TerminalPipelineDiagnosticsRegistry.SessionDiagnosticsSnapshot closed =
        TerminalPipelineDiagnosticsRegistry.snapshotRecentClosed().get(0);
    assertEquals(1, closed.mailboxMessagesAtCloseRequest);
    assertEquals(4L, closed.mailboxBytesAtCloseRequest);
    assertEquals(0, closed.finalMailboxMessages);
    assertEquals(0L, closed.finalMailboxBytes);
    assertEquals(0, closed.mailboxMessages);
    assertEquals(0L, closed.mailboxBytes);
  }

  @Test
  public void closingFortyRuntimesKeepsLifetimeTotalsMonotonic() {
    long prevFrames = -1L;
    long prevRenders = -1L;
    for (int i = 0; i < 40; i++) {
      TerminalSessionRuntime runtime =
          new TerminalSessionRuntime("life-" + i, new RemoteTerminalModel(), Runnable::run);
      runtime.pipelineMetrics().onFrameReceived("BASELINE", 10);
      runtime.onRenderFrameRendered(1L, 1L, 100L);
      runtime.close();

      Map<String, Long> agg = TerminalPipelineDiagnosticsRegistry.aggregateScreenPipeline();
      long frames = agg.get("receivedFrameCount");
      long renders = agg.get("renderSuccessCount");
      assertTrue("receivedFrameCount must not decrease", frames >= prevFrames);
      assertTrue("renderSuccessCount must not decrease", renders >= prevRenders);
      prevFrames = frames;
      prevRenders = renders;
    }

    Map<String, Long> counts = TerminalPipelineDiagnosticsRegistry.lifetimeSessionCounts();
    assertEquals(0L, (long) counts.get("activeSessionCount"));
    assertEquals(32L, (long) counts.get("recentClosedSessionCount"));
    assertEquals(8L, (long) counts.get("archivedSessionCount"));
    assertEquals(40L, (long) counts.get("lifetimeSessionCount"));
    assertEquals(32, TerminalPipelineDiagnosticsRegistry.snapshotRecentClosed().size());

    Map<String, Long> finalAgg = TerminalPipelineDiagnosticsRegistry.aggregateScreenPipeline();
    assertEquals(40L, (long) finalAgg.get("receivedFrameCount"));
    assertEquals(40L, (long) finalAgg.get("renderSuccessCount"));
  }

  @Test
  public void requestModelRenderNoopsAfterClosing() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    ArrayDeque<Runnable> modelQueue = new ArrayDeque<>();
    TerminalSessionRuntime runtime =
        new TerminalSessionRuntime("render-close", model, modelQueue::add, Runnable::run);
    runtime.enterLiveForTest();
    drain(modelQueue);
    long publishedBefore = model.lastPublicationVersion();

    runtime.close();
    assertEquals(TerminalSessionRuntime.State.CLOSING, runtime.state());
    int queuedAfterClose = modelQueue.size();
    runtime.requestModelRender();
    // CLOSING 时不得再排队 full-render 任务。
    assertEquals(queuedAfterClose, modelQueue.size());
    drain(modelQueue);

    assertEquals(TerminalSessionRuntime.State.CLOSED, runtime.state());
    assertEquals(publishedBefore, model.lastPublicationVersion());
    runtime.requestModelRender();
    assertTrue(modelQueue.isEmpty());
    assertEquals(publishedBefore, model.lastPublicationVersion());
  }

  @Test
  public void requestModelRenderAlreadyQueuedIsDroppedWhenClosing() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    ArrayDeque<Runnable> modelQueue = new ArrayDeque<>();
    TerminalSessionRuntime runtime =
        new TerminalSessionRuntime("render-race", model, modelQueue::add, Runnable::run);
    runtime.enterLiveForTest();
    drain(modelQueue);
    long publishedBefore = model.lastPublicationVersion();

    runtime.requestModelRender();
    assertEquals(1, modelQueue.size());
    runtime.close();
    assertEquals(TerminalSessionRuntime.State.CLOSING, runtime.state());
    // 队列中：full-render 任务 + finishClose；full-render 执行时发现 CLOSING 应 no-op。
    drain(modelQueue);
    assertEquals(TerminalSessionRuntime.State.CLOSED, runtime.state());
    assertEquals(publishedBefore, model.lastPublicationVersion());
  }

  @Test
  public void inputAbandonedAtCloseClosesDeliveryEquation() {
    ArrayDeque<Runnable> modelQueue = new ArrayDeque<>();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "input-abandon", new RemoteTerminalModel(), modelQueue::add, Runnable::run);
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    drain(modelQueue);
    runtime.enterLiveForTest();
    drain(modelQueue);
    runtime.grantLayoutLeaseForTest("lease-abandon");

    runtime.sendTextInput("ab");
    runtime.sendTextInput("c");
    Map<String, Long> beforeClose = runtime.inputDeliverySnapshot();
    assertEquals(2L, (long) beforeClose.get("inputLocalQueueAcceptedCount"));
    assertEquals(2L, (long) beforeClose.get("inputPendingFinalResultCount"));
    assertEquals(0L, (long) beforeClose.get("inputAbandonedAtCloseCount"));

    // 清空 connection 后仍能记账（模拟 close 已清空 connection 字段的在途回调）。
    runtime.close();
    assertEquals(TerminalSessionRuntime.State.CLOSING, runtime.state());
    connection.listener.onInputSendResult("WEBSOCKET_ENQUEUED");
    Map<String, Long> mid = runtime.inputDeliverySnapshot();
    assertEquals(1L, (long) mid.get("inputWebSocketEnqueuedCount"));
    assertEquals(1L, (long) mid.get("inputPendingFinalResultCount"));

    drain(modelQueue);
    assertEquals(TerminalSessionRuntime.State.CLOSED, runtime.state());
    Map<String, Long> closed = runtime.inputDeliverySnapshot();
    assertEquals(0L, (long) closed.get("inputPendingFinalResultCount"));
    assertEquals(1L, (long) closed.get("inputAbandonedAtCloseCount"));
    assertEquals(
        (long) closed.get("inputLocalQueueAcceptedCount"),
        closed.get("inputWebSocketEnqueuedCount")
            + closed.get("inputChannelNotOpenCount")
            + closed.get("inputTransportRejectedCount")
            + closed.get("inputConnectionStoppedCount")
            + closed.get("inputAbandonedAtCloseCount"));

    TerminalPipelineDiagnosticsRegistry.SessionDiagnosticsSnapshot snap =
        TerminalPipelineDiagnosticsRegistry.snapshotRecentClosed().get(0);
    assertEquals(1L, (long) snap.inputDelivery.get("inputAbandonedAtCloseCount"));
    assertEquals(0L, (long) snap.inputDelivery.get("inputPendingFinalResultCount"));
  }

  @Test
  public void concurrentUnregisterDuringAggregateDoesNotDoubleCountLifetime() throws Exception {
    final int sessionCount = 48;
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(8);
    try {
      java.util.concurrent.CountDownLatch start =
          new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.CountDownLatch done =
          new java.util.concurrent.CountDownLatch(sessionCount);
      java.util.concurrent.atomic.AtomicLong maxLifetime =
          new java.util.concurrent.atomic.AtomicLong(0L);
      java.util.concurrent.atomic.AtomicLong maxFrames =
          new java.util.concurrent.atomic.AtomicLong(0L);

      for (int i = 0; i < sessionCount; i++) {
        final int index = i;
        pool.execute(() -> {
          try {
            start.await();
            TerminalSessionRuntime runtime = new TerminalSessionRuntime(
                "conc-" + index, new RemoteTerminalModel(), Runnable::run);
            runtime.pipelineMetrics().onFrameReceived("BASELINE", 1);
            runtime.close();
            Map<String, Long> counts =
                TerminalPipelineDiagnosticsRegistry.lifetimeSessionCounts();
            long lifetime = counts.get("lifetimeSessionCount");
            for (;;) {
              long cur = maxLifetime.get();
              if (lifetime <= cur || maxLifetime.compareAndSet(cur, lifetime)) break;
            }
            Map<String, Long> agg =
                TerminalPipelineDiagnosticsRegistry.aggregateScreenPipeline();
            long frames = agg.get("receivedFrameCount");
            for (;;) {
              long cur = maxFrames.get();
              if (frames <= cur || maxFrames.compareAndSet(cur, frames)) break;
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            done.countDown();
          }
        });
      }
      start.countDown();
      assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));

      Map<String, Long> counts = TerminalPipelineDiagnosticsRegistry.lifetimeSessionCounts();
      assertEquals(sessionCount, (long) counts.get("lifetimeSessionCount"));
      assertEquals(sessionCount, (long) maxLifetime.get());
      Map<String, Long> agg = TerminalPipelineDiagnosticsRegistry.aggregateScreenPipeline();
      assertEquals(sessionCount, (long) agg.get("receivedFrameCount"));
      assertEquals(sessionCount, (long) maxFrames.get());
    } finally {
      pool.shutdownNow();
    }
  }

  private static void drain(ArrayDeque<Runnable> queue) {
    while (!queue.isEmpty()) {
      queue.removeFirst().run();
    }
  }

  private static ScreenBaseline domainBaseline() {
    java.util.List<HistoryPush> bindings = new java.util.ArrayList<>();
    for (long seq = 1; seq <= 300; seq++) {
      bindings.add(new HistoryPush(seq, new LineKey(10_000 + seq, 1)));
    }
    return new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 1, 1, 1,
        TerminalBufferKind.MAIN, new HistoryExtent(1, 300), bindings,
        Collections.singletonList(new ScreenLineContent(
            new LineKey(1000, 1),
            new LineBody(1, false, new CellValue[] {
                new CellValue("a", (byte) 1, null, null)
            }))),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults());
  }

  private static final class FakeV2Connection implements TerminalSessionRuntime.ScreenConnection {
    TerminalSessionRuntime.ScreenConnection.Listener listener;

    @Override public void setListener(@NonNull Listener listener) { this.listener = listener; }
    @Override public boolean beginSync(@Nullable TerminalScreenV2Proto.ResumeToken resume,
                                        boolean forceBaseline) {
      return true;
    }
    @Override public void setLayoutLeaseId(@NonNull String leaseId) {}
    @Override public boolean sendTextInput(@NonNull String text) { return true; }
    @Override public boolean sendPasteInput(@NonNull String text) { return true; }
    @Override public boolean sendKeyInput(@NonNull String key, boolean shift, boolean alt,
                                       boolean ctrl, boolean meta, boolean pressed) {
      return true;
    }
    @Override public boolean sendMouseInput(int row, int col, @NonNull String button, int wheelDelta,
                                         boolean shift, boolean alt, boolean ctrl, boolean meta,
                                         boolean pressed) {
      return true;
    }
    @Override public void sendFocusInput(boolean focused) {}
    @Override public boolean requestResize(int cols, int rows) { return true; }
    @Override public void acquireLayout(boolean interactive) {}
    @Override public void releaseLayout() {}
    @Override public void sendClipboardResponse(@NonNull String requestId, boolean allowed,
                                                boolean timeout, @Nullable byte[] data) {}
    @Override public void close() {}
  }
}
