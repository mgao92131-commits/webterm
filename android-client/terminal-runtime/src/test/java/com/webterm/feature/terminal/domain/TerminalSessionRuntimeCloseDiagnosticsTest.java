package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.DictionaryEntries;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.SegmentKey;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalLine;
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
    assertTrue(model.applyBaseline(domainBaseline(/*sealedThrough*/ 256)));
    model.consumeRenderUpdate();

    ArrayDeque<Runnable> modelQueue = new ArrayDeque<>();
    TerminalSessionRuntime runtime =
        new TerminalSessionRuntime("close-hist", model, modelQueue::add, Runnable::run);
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    drain(modelQueue);
    runtime.enterLiveForTest();
    drain(modelQueue);

    AtomicReference<HistorySegmentSource.Callback> pending = new AtomicReference<>();
    runtime.setHistorySegmentSource(new HistorySegmentSource() {
      @Override
      public RequestHandle fetch(@NonNull SegmentKey key, @NonNull Callback callback) {
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

  private static void drain(ArrayDeque<Runnable> queue) {
    while (!queue.isEmpty()) {
      queue.removeFirst().run();
    }
  }

  private static ScreenBaseline domainBaseline(long sealedThrough) {
    List<TerminalLine> tail = new ArrayList<>();
    for (long seq = 173; seq <= 300; seq++) {
      tail.add(domainLine(seq, seq, "h"));
    }
    return new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 1, false, DictionaryEntries.EMPTY, 1, 1,
        TerminalBufferKind.MAIN, new HistoryExtent(1, 300), tail,
        Collections.singletonList(domainLine(1000, 0, "a")),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(),
        sealedThrough);
  }

  private static TerminalLine domainLine(long id, long historySeq, String text) {
    return new TerminalLine(id, 1, historySeq, false,
        new TerminalCell[] {new TerminalCell(text, (byte) 1, null, null)});
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
