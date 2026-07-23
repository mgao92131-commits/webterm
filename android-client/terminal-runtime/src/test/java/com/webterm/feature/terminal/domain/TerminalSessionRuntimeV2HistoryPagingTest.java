package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.PagedTerminalHistorySnapshot;
import com.webterm.terminal.model.SlotState;
import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** v2 冻结投影必须能在 Baseline 尾页之前继续按需加载历史。 */
public final class TerminalSessionRuntimeV2HistoryPagingTest {
  @Test
  public void frozenProjectionLoadsRangeBeforeBaselineTail() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());

    assertEquals(1, runtime.model().displayExtent().firstSeq);
    assertEquals(173, runtime.model().firstCachedHistorySeq());

    runtime.freezeStream();
    assertEquals(TerminalSessionRuntime.StreamState.FROZEN, runtime.streamState());
    assertTrue(runtime.requestHistoryRange(45, 128, 45));
    assertEquals(45, connection.fromSeq);
    assertEquals(128, connection.toSeq);

    connection.listener.onScreenMessage(historyRange(connection.requestId, 45, 128).toByteArray());

    assertEquals(45, runtime.model().firstCachedHistorySeq());
    assertNotNull(runtime.model().renderSnapshot().history.lineAt(44));
    assertEquals(45, runtime.model().renderSnapshot().history.lineAt(44).historySeq);
  }

  @Test
  public void frozenFocusDoesNotResumeAndResizeWaitsForLiveBaseline() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());
    connection.listener.onScreenMessage(TerminalScreenV2Proto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(2)
        .setLayoutLease(TerminalScreenV2Proto.LayoutLease.newBuilder()
            .setRequestId(connection.acquireRequestId)
            .setLeaseId("lease-1")
            .setGranted(true)
            .setInteractive(true))
        .build().toByteArray());

    runtime.freezeStream();
    assertEquals(1, connection.modeChanges);
    runtime.sendFocusInput(true);
    runtime.requestResize(120, 40);
    assertEquals("focus must not force LIVE", 1, connection.modeChanges);
    assertEquals(0, connection.focusInputs);
    assertEquals(0, connection.resizeRequests);

    runtime.resumeLiveStream();
    assertEquals(2, connection.modeChanges);
    assertEquals(TerminalSessionRuntime.StreamState.RESYNCING, runtime.streamState());
    assertEquals(0, connection.resizeRequests);

    connection.listener.onScreenMessage(baseline(3).toByteArray());
    assertEquals(TerminalSessionRuntime.StreamState.LIVE, runtime.streamState());
    assertEquals(1, connection.resizeRequests);
    assertEquals(120, connection.resizeCols);
    assertEquals(40, connection.resizeRows);
    runtime.sendFocusInput(true);
    assertEquals(1, connection.focusInputs);
  }

  @Test
  public void frozenUserInputWaitsForLiveBaseline() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());
    connection.listener.onScreenMessage(TerminalScreenV2Proto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(2)
        .setLayoutLease(TerminalScreenV2Proto.LayoutLease.newBuilder()
            .setRequestId(connection.acquireRequestId)
            .setLeaseId("lease-1")
            .setGranted(true)
            .setInteractive(true))
        .build().toByteArray());

    runtime.freezeStream();
    runtime.sendTextInput("queued");
    assertEquals(0, connection.textInputs.size());
    assertEquals(TerminalSessionRuntime.StreamState.RESYNCING, runtime.streamState());

    connection.listener.onScreenMessage(baseline(3).toByteArray());
    assertEquals(1, connection.textInputs.size());
    assertEquals("queued", connection.textInputs.get(0));
  }

  @Test
  public void staleRangeRequestsFreshBaselineAndRetryableUsesServerBackoff() {
    FakeScheduler scheduler = new FakeScheduler();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, scheduler);
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());
    scheduler.clear();

    assertTrue(runtime.requestHistoryRange(1, 128, 1));
    connection.listener.onScreenMessage(historyRange(
        connection.requestId, 1, 128,
        TerminalScreenV2Proto.HistoryRangeStatus.HISTORY_RANGE_STATUS_RETRYABLE,
        1, 300, 750, false).toByteArray());
    assertEquals(750L, scheduler.lastDelay());
    int requestsBeforeRetry = connection.rangeRequests;
    scheduler.runLast();
    assertEquals(requestsBeforeRetry + 1, connection.rangeRequests);

    connection.listener.onScreenMessage(historyRange(
        connection.requestId, 1, 128,
        TerminalScreenV2Proto.HistoryRangeStatus.HISTORY_RANGE_STATUS_STALE_PROJECTION,
        1, 300, 0, false).toByteArray());
    assertEquals(TerminalSessionRuntime.StreamState.RESYNCING, runtime.streamState());
    assertEquals(TerminalScreenV2Proto.ScreenStreamMode.SCREEN_STREAM_MODE_LIVE,
        connection.lastMode);
  }

  @Test
  public void okRangeMarksRequestedPartOutsideAvailableExtentUnavailable() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());

    assertTrue(runtime.requestHistoryRange(1, 128, 1));
    connection.listener.onScreenMessage(historyRange(
        connection.requestId, 50, 128,
        TerminalScreenV2Proto.HistoryRangeStatus.HISTORY_RANGE_STATUS_OK,
        50, 300, 0, true).toByteArray());

    PagedTerminalHistorySnapshot history =
        (PagedTerminalHistorySnapshot) runtime.model().renderSnapshot().history;
    assertEquals(SlotState.UNAVAILABLE, history.slotStateAt(0));
    assertNull(history.firstRequestablePage(1, 49));
    assertEquals(50, history.lineBySeq(50).historySeq);
  }

  private static TerminalScreenV2Proto.ScreenEnvelope baseline(long generation) {
    TerminalScreenV2Proto.Baseline.Builder baseline =
        TerminalScreenV2Proto.Baseline.newBuilder()
            .setSessionId("s1")
            .setInstanceId("i1")
            .setLayoutEpoch(1)
            .setScreenRevision(1)
            .setStreamGeneration(generation)
            .setGeometry(TerminalScreenV2Proto.Geometry.newBuilder().setRows(1).setCols(1))
            .setActiveBuffer(TerminalScreenV2Proto.BufferKind.BUFFER_KIND_MAIN)
            .setHistoryExtent(extent(1, 300))
            .setHistoryTail(TerminalScreenV2Proto.HistoryTail.newBuilder()
                .setExtent(extent(1, 300)))
            .setScreenLayout(TerminalScreenV2Proto.ScreenLayout.newBuilder().addLineIds(1000))
            .addScreenLines(line(1000, 0));
    for (long seq = 173; seq <= 300; seq++) {
      baseline.getHistoryTailBuilder().addLines(line(seq, seq));
    }
    return TerminalScreenV2Proto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(2)
        .setBaseline(baseline)
        .build();
  }

  private static TerminalScreenV2Proto.ScreenEnvelope historyRange(
      String requestId, long fromSeq, long toSeq) {
    return historyRange(
        requestId, fromSeq, toSeq,
        TerminalScreenV2Proto.HistoryRangeStatus.HISTORY_RANGE_STATUS_OK,
        1, 300, 0, true);
  }

  private static TerminalScreenV2Proto.ScreenEnvelope historyRange(
      String requestId, long lineFromSeq, long lineToSeq,
      TerminalScreenV2Proto.HistoryRangeStatus status,
      long extentFirst, long extentLast, int retryAfterMs, boolean includeLines) {
    TerminalScreenV2Proto.HistoryRangeResponse.Builder response =
        TerminalScreenV2Proto.HistoryRangeResponse.newBuilder()
            .setRequestId(requestId)
            .setInstanceId("i1")
            .setLayoutEpoch(1)
            .setStatus(status)
            .setRetryAfterMs(retryAfterMs)
            .setAvailableExtent(extent(extentFirst, extentLast));
    if (includeLines) {
      for (long seq = lineFromSeq; seq <= lineToSeq; seq++) response.addLines(line(seq, seq));
    }
    return TerminalScreenV2Proto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(2)
        .setHistoryRangeResponse(response)
        .build();
  }

  private static TerminalScreenV2Proto.HistoryExtent extent(long first, long last) {
    return TerminalScreenV2Proto.HistoryExtent.newBuilder()
        .setFirstSeq(first).setLastSeq(last).build();
  }

  private static TerminalScreenV2Proto.LineData line(long id, long historySeq) {
    return TerminalScreenV2Proto.LineData.newBuilder()
        .setLineId(id)
        .setLineVersion(1)
        .setHistorySeq(historySeq)
        .addRuns(TerminalScreenV2Proto.CellRun.newBuilder()
            .setCol(0)
            .addCells(TerminalScreenV2Proto.Cell.newBuilder().setText("x").setWidth(1)))
        .build();
  }

  private static final class FakeV2Connection
      implements TerminalSessionRuntime.ScreenConnection {
    Listener listener;
    String requestId;
    long fromSeq;
    long toSeq;
    int modeChanges;
    int focusInputs;
    int resizeRequests;
    int resizeCols;
    int resizeRows;
    String acquireRequestId = "";
    int rangeRequests;
    final List<String> textInputs = new ArrayList<>();
    TerminalScreenV2Proto.ScreenStreamMode lastMode;

    @Override public void setListener(@NonNull Listener listener) { this.listener = listener; }
    @Override public boolean beginSync(long generation,
        @NonNull TerminalScreenV2Proto.ScreenStreamMode mode, @Nullable String instanceId,
        long layoutEpoch, boolean hasFrozenProjection) { return true; }
    @Override public boolean setStreamMode(long generation,
        @NonNull TerminalScreenV2Proto.ScreenStreamMode mode) {
      modeChanges++;
      lastMode = mode;
      return true;
    }
    @Override public boolean requestHistoryRange(@NonNull String requestId,
        @NonNull String instanceId, long layoutEpoch, long fromSeq, long toSeq) {
      this.requestId = requestId;
      this.fromSeq = fromSeq;
      this.toSeq = toSeq;
      rangeRequests++;
      return true;
    }
    @Override public void setLayoutLeaseId(@NonNull String leaseId) {}
    @Override public void sendTextInput(@NonNull String text) { textInputs.add(text); }
    @Override public void sendPasteInput(@NonNull String text) {}
    @Override public void sendKeyInput(@NonNull String key, boolean shift, boolean alt,
        boolean ctrl, boolean meta, boolean pressed) {}
    @Override public void sendMouseInput(int row, int col, @NonNull String button,
        int wheelDelta, boolean shift, boolean alt, boolean ctrl, boolean meta, boolean pressed) {}
    @Override public void sendFocusInput(boolean focused) { focusInputs++; }
    @Override public boolean requestResize(int cols, int rows) {
      resizeRequests++;
      resizeCols = cols;
      resizeRows = rows;
      return true;
    }
    @Override public void acquireLayout(boolean interactive) {}
    @Override public void acquireLayout(@NonNull String requestId, boolean interactive) {
      acquireRequestId = requestId;
    }
    @Override public void releaseLayout() {}
    @Override public void sendClipboardResponse(@NonNull String requestId, boolean allowed,
        boolean timeout, @Nullable byte[] data) {}
    @Override public void close() {}
  }

  private static final class FakeScheduler implements TerminalSessionRuntime.TimeoutScheduler {
    final List<Runnable> tasks = new ArrayList<>();
    final List<Long> delays = new ArrayList<>();

    @Override public void schedule(@NonNull Runnable task, long delayMs) {
      tasks.add(task);
      delays.add(delayMs);
    }

    void clear() {
      tasks.clear();
      delays.clear();
    }

    long lastDelay() {
      return delays.get(delays.size() - 1);
    }

    void runLast() {
      int index = tasks.size() - 1;
      Runnable task = tasks.remove(index);
      delays.remove(index);
      task.run();
    }
  }
}
