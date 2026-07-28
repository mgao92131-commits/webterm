package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.core.contract.diagnostics.DiagnosticLevel;
import com.webterm.core.contract.diagnostics.DiagnosticSink;
import com.webterm.core.contract.diagnostics.Diagnostics;
import com.webterm.terminal.model.DictionaryEntries;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.SegmentKey;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Segment 加载路径诊断：loader 状态转换与 fetch 生命周期。 */
public class TerminalSessionRuntimeV2HistoryPagingTest {
  private RecordingSink sink;

  @Before
  public void installSink() {
    sink = new RecordingSink();
    Diagnostics.install(sink);
  }

  @After
  public void resetSink() {
    Diagnostics.install(null);
  }

  @Test
  public void demandWithoutSealedCatalogDoesNotFetch() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("owner", model, Runnable::run);
    FakeV2Connection connection = connect(runtime);

    AtomicReference<SegmentKey> requested = new AtomicReference<>();
    runtime.setHistorySegmentSource(new HistorySegmentSource() {
      @Override public RequestHandle fetch(@NonNull SegmentKey key, @NonNull Callback callback) {
        requested.set(key);
        return () -> {};
      }
      @Override public void close() {}
    });

    runtime.onVisibleHistoryDemand(1, 64, 1, -1, 20);
    assertNull(requested.get());
    assertNotNull(connection);
  }

  @Test
  public void pumpIdleEmitsSealedZeroWhenCatalogUnsealed() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline(/*sealedThrough*/ 0)));
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("diag-sealed", model, Runnable::run);
    connect(runtime);
    runtime.enterLiveForTest();
    runtime.setHistorySegmentSource(idleSource());

    runtime.onVisibleHistoryDemand(1, 64, 1, -1, 20);

    assertTrue(sink.hasEvent("history_segment", "demand_updated"));
    assertTrue(sink.hasEventWithReason("history_segment", "history_loader_blocked", "sealed_zero"));
  }

  @Test
  public void pumpIdleEmitsNoSourceWhenSourceMissing() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline(/*sealedThrough*/ 256)));
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("diag-nosrc", model, Runnable::run);
    connect(runtime);
    runtime.enterLiveForTest();

    runtime.onVisibleHistoryDemand(1, 64, 1, -1, 20);

    assertTrue(sink.hasEventWithReason("history_segment", "history_loader_blocked", "no_source"));
  }

  @Test
  public void fetchBeginEmittedWhenTargetExists() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline(/*sealedThrough*/ 256)));
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("diag-fetch", model, Runnable::run);
    connect(runtime);
    runtime.enterLiveForTest();

    AtomicReference<SegmentKey> requested = new AtomicReference<>();
    runtime.setHistorySegmentSource(new HistorySegmentSource() {
      @Override public RequestHandle fetch(@NonNull SegmentKey key, @NonNull Callback callback) {
        requested.set(key);
        return () -> {};
      }
      @Override public void close() {}
    });

    runtime.onVisibleHistoryDemand(1, 64, 1, -1, 20);

    assertNotNull(requested.get());
    assertTrue(sink.hasEvent("history_segment", "history_fetch_started"));
  }

  @Test
  public void identicalDemandDoesNotEmitSecondDemandUpdated() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline(/*sealedThrough*/ 256)));
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("diag-dedup", model, Runnable::run);
    connect(runtime);
    runtime.enterLiveForTest();
    runtime.setHistorySegmentSource(idleSource());

    runtime.onVisibleHistoryDemand(1, 64, 1, -1, 20);
    assertEquals(1, sink.countEvents("history_segment", "demand_updated"));

    runtime.onVisibleHistoryDemand(1, 64, 1, -1, 20);
    assertEquals(1, sink.countEvents("history_segment", "demand_updated"));
  }

  @Test
  public void segmentApplyDispatchesRenderNeeded() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline(/*sealedThrough*/ 256)));
    model.consumeRenderUpdate();

    AtomicInteger renderNeededCount = new AtomicInteger();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("seg-render", model, Runnable::run);
    Object renderConsumer = new Object();
    runtime.registerRenderConsumer(renderConsumer);
    runtime.addListener(new TerminalSessionRuntime.Listener() {
      @Override public void onRenderNeeded() {
        renderNeededCount.incrementAndGet();
      }
      @Override public void onConnectionStateChange(@NonNull TerminalSessionRuntime.State state) {}
      @Override public void onEffect(@NonNull TerminalScreenEffect effect) {}
    });
    connect(runtime);
    runtime.enterLiveForTest();

    runtime.setHistorySegmentSource(new HistorySegmentSource() {
      @Override public RequestHandle fetch(@NonNull SegmentKey key, @NonNull Callback callback) {
        callback.onResult(decodedSegment(key));
        return () -> {};
      }
      @Override public void close() {}
    });

    runtime.onVisibleHistoryDemand(100, 180, 100, -1, 50);

    assertTrue("segment apply must wake UI render consumer", renderNeededCount.get() > 0);
    RenderUpdate update = runtime.consumeRenderUpdate(renderConsumer);
    assertNotNull(update);
    assertTrue(update.state.historyChanged);
  }

  @Test
  public void notSealedFailureSchedulesRetryInsteadOfSkip() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(baseline(/*sealedThrough*/ 256)));

    AtomicInteger fetchCount = new AtomicInteger();
    AtomicReference<Runnable> retryTask = new AtomicReference<>();
    AtomicLong retryDelayMs = new AtomicLong(-1);
    TerminalSessionRuntime.TimeoutScheduler retryScheduler = (task, delayMs) -> {
      retryTask.set(task);
      retryDelayMs.set(delayMs);
    };
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "not-sealed", model, Runnable::run, Runnable::run, retryScheduler);

    connect(runtime);
    runtime.enterLiveForTest();
    runtime.setHistorySegmentSource(new HistorySegmentSource() {
      @Override public RequestHandle fetch(@NonNull SegmentKey key, @NonNull Callback callback) {
        fetchCount.incrementAndGet();
        callback.onFailure(new Failure(FailureKind.NOT_SEALED, 250, key.generation));
        return () -> {};
      }
      @Override public void close() {}
    });

    runtime.onVisibleHistoryDemand(1, 64, 1, -1, 20);

    assertEquals(1, fetchCount.get());
    assertFalse(sink.hasEventWithFailureKind("history_segment", "segment_skipped", "NOT_SEALED"));
    assertNotNull(retryTask.get());
    assertEquals(250, retryDelayMs.get());

    retryTask.get().run();
    assertEquals(2, fetchCount.get());
  }

  private static HistorySegmentSource.DecodedHistorySegment decodedSegment(@NonNull SegmentKey key) {
    List<TerminalLine> lines = new ArrayList<>(SegmentKey.SIZE);
    for (long seq = key.firstSeq(); seq <= key.lastSeq(); seq++) {
      lines.add(line(10_000 + seq, seq, "h"));
    }
    return new HistorySegmentSource.DecodedHistorySegment(
        key, key.firstSeq(), key.lastSeq(), lines);
  }

  private static HistorySegmentSource idleSource() {
    return new HistorySegmentSource() {
      @Override public RequestHandle fetch(@NonNull SegmentKey key, @NonNull Callback callback) {
        return () -> {};
      }
      @Override public void close() {}
    };
  }

  private static ScreenBaseline baseline(long sealedThrough) {
    List<TerminalLine> tail = new ArrayList<>();
    for (long seq = 173; seq <= 300; seq++) {
      tail.add(line(seq, seq, "h"));
    }
    return new ScreenBaseline(
        "s1", "i1", 1, 1, 1, 1, false, DictionaryEntries.EMPTY, 1, 1,
        TerminalBufferKind.MAIN, new HistoryExtent(1, 300), tail,
        Collections.singletonList(line(1000, 0, "a")),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(),
        sealedThrough);
  }

  private static TerminalLine line(long id, long historySeq, String text) {
    return new TerminalLine(id, 1, historySeq, false,
        new TerminalCell[] {new TerminalCell(text, (byte) 1, null, null)});
  }

  private static FakeV2Connection connect(TerminalSessionRuntime runtime) {
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    return connection;
  }

  private static final class FakeV2Connection implements TerminalSessionRuntime.ScreenConnection {
    TerminalSessionRuntime.ScreenConnection.Listener listener;
    @Override public void setListener(@NonNull Listener listener) { this.listener = listener; }
    @Override public boolean beginSync(@Nullable TerminalScreenV2Proto.ResumeToken resume,
                                        boolean forceBaseline) {
      return true;
    }
    @Override public void setLayoutLeaseId(@NonNull String leaseId) {}
    @Override public void sendTextInput(@NonNull String text) {}
    @Override public void sendPasteInput(@NonNull String text) {}
    @Override public void sendKeyInput(@NonNull String key, boolean shift, boolean alt,
                                       boolean ctrl, boolean meta, boolean pressed) {}
    @Override public void sendMouseInput(int row, int col, @NonNull String button, int wheelDelta,
                                         boolean shift, boolean alt, boolean ctrl, boolean meta,
                                         boolean pressed) {}
    @Override public void sendFocusInput(boolean focused) {}
    @Override public boolean requestResize(int cols, int rows) { return true; }
    @Override public void acquireLayout(boolean interactive) {}
    @Override public void releaseLayout() {}
    @Override public void sendClipboardResponse(@NonNull String requestId, boolean allowed,
                                                boolean timeout, @Nullable byte[] data) {}
    @Override public void close() {}
  }

  private static final class RecordingSink implements DiagnosticSink {
    private final List<Recorded> events = new CopyOnWriteArrayList<>();

    @Override
    public void record(DiagnosticLevel level, String area, String event, Map<String, ?> fields) {
      events.add(new Recorded(level, area, event, fields));
    }

    boolean hasEvent(String area, String event) {
      for (Recorded recorded : events) {
        if (area.equals(recorded.area) && event.equals(recorded.event)) return true;
      }
      return false;
    }

    int countEvents(String area, String event) {
      int count = 0;
      for (Recorded recorded : events) {
        if (area.equals(recorded.area) && event.equals(recorded.event)) count++;
      }
      return count;
    }

    boolean hasEventWithReason(String area, String event, String reason) {
      for (Recorded recorded : events) {
        if (!area.equals(recorded.area) || !event.equals(recorded.event)) continue;
        Object value = recorded.fields.get("reason");
        if (reason.equals(String.valueOf(value))) return true;
      }
      return false;
    }

    boolean hasEventWithFailureKind(String area, String event, String failureKind) {
      for (Recorded recorded : events) {
        if (!area.equals(recorded.area) || !event.equals(recorded.event)) continue;
        Object value = recorded.fields.get("failureKind");
        if (failureKind.equals(String.valueOf(value))) return true;
      }
      return false;
    }
  }

  private static final class Recorded {
    final DiagnosticLevel level;
    final String area;
    final String event;
    final Map<String, ?> fields;

    Recorded(DiagnosticLevel level, String area, String event, Map<String, ?> fields) {
      this.level = level;
      this.area = area;
      this.event = event;
      this.fields = fields;
    }
  }
}
