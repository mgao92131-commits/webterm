package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.protobuf.ByteString;
import com.webterm.terminal.model.PagedTerminalHistorySnapshot;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** screen.v2 始终 LIVE：分页历史与实时投影是两条独立数据链。 */
public final class TerminalSessionRuntimeV2HistoryPagingTest {
  @Test
  public void renderConsumerRegistrationAndDestructiveConsumeAreTokenChecked() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("owner", model, Runnable::run);
    FakeV2Connection connection = connect(runtime);
    connection.listener.onScreenMessage(baseline(1, 2).toByteArray());
    Object owner = new Object();
    Object stranger = new Object();

    runtime.registerRenderConsumer(owner);
    try {
      runtime.consumeRenderUpdate(stranger);
      org.junit.Assert.fail("unregistered consumer must be rejected");
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage().contains("not active"));
    }
    assertNotNull(runtime.consumeRenderUpdate(owner));
    runtime.unregisterRenderConsumer(owner);
  }

  @Test
  public void explicitVisibleGapRequestCarriesHistoryGeneration() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "history", new RemoteTerminalModel(), Runnable::run, Runnable::run,
        (task, delayMs) -> {});
    FakeV2Connection connection = connect(runtime);
    connection.listener.onScreenMessage(baseline(1, 2).toByteArray());

    assertTrue(runtime.requestHistoryRange(1, 64, 1));
    assertEquals(1, connection.rangeRequests);
    assertEquals(1, connection.historyGeneration);
    assertEquals(1, connection.fromSeq);
    assertEquals(64, connection.toSeq);
  }

  @Test
  public void staleHistoryRangeDoesNotLoseProjectionContinuity() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "stale-range", new RemoteTerminalModel(), Runnable::run, Runnable::run,
        (task, delayMs) -> {});
    FakeV2Connection connection = connect(runtime);
    connection.listener.onScreenMessage(baseline(1, 2).toByteArray());
    assertTrue(runtime.requestHistoryRange(1, 64, 1));

    connection.listener.onScreenMessage(historyRange(
        connection.requestId,
        TerminalScreenV2Proto.HistoryRangeStatus.HISTORY_RANGE_STATUS_STALE_PROJECTION,
        false).toByteArray());

    assertEquals(TerminalSessionRuntime.State.LIVE, runtime.state());
    assertEquals(TerminalSessionRuntime.ProjectionContinuityState.CONTINUOUS,
        runtime.projectionContinuityState());
    assertEquals(0, connection.reconnectRequests);
  }

  @Test
  public void retryableHistoryRangeDoesNotResyncProjection() {
    List<Runnable> scheduled = new ArrayList<>();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "retry-range", new RemoteTerminalModel(), Runnable::run, Runnable::run,
        (task, delayMs) -> scheduled.add(task));
    FakeV2Connection connection = connect(runtime);
    connection.listener.onScreenMessage(baseline(1, 2).toByteArray());
    assertTrue(runtime.requestHistoryRange(1, 64, 1));

    TerminalScreenV2Proto.ScreenEnvelope retryable =
        historyRange(connection.requestId,
            TerminalScreenV2Proto.HistoryRangeStatus.HISTORY_RANGE_STATUS_RETRYABLE,
            false);
    retryable = retryable.toBuilder().setHistoryRangeResponse(
        retryable.getHistoryRangeResponse().toBuilder().setRetryAfterMs(375)).build();
    connection.listener.onScreenMessage(retryable.toByteArray());

    assertEquals(TerminalSessionRuntime.State.LIVE, runtime.state());
    assertEquals(TerminalSessionRuntime.ProjectionContinuityState.CONTINUOUS,
        runtime.projectionContinuityState());
    assertEquals(0, connection.reconnectRequests);
    assertFalse(scheduled.isEmpty());
  }

  @Test
  public void rapidFivePageRequestsQueueFifthUntilAnInFlightResponseCompletes() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "queued-pages", new RemoteTerminalModel(), Runnable::run, Runnable::run,
        (task, delayMs) -> {});
    FakeV2Connection connection = connect(runtime);
    connection.listener.onScreenMessage(baseline(1, 2).toByteArray());

    assertTrue(runtime.requestHistoryRange(1, 10, 1));
    assertTrue(runtime.requestHistoryRange(20, 30, 20));
    assertTrue(runtime.requestHistoryRange(40, 50, 40));
    assertTrue(runtime.requestHistoryRange(60, 70, 60));
    assertTrue(runtime.requestHistoryRange(80, 90, 80));

    // 第五页不能挤掉第一个已发请求；它应等待槽位，而不是让 r0 的响应失效。
    assertEquals(4, connection.rangeRequests);
    String firstRequestId = connection.rangeRequestIds.get(0);
    connection.listener.onScreenMessage(historyRange(
        firstRequestId,
        TerminalScreenV2Proto.HistoryRangeStatus.HISTORY_RANGE_STATUS_RETRYABLE,
        false).toByteArray());

    assertEquals(5, connection.rangeRequests);
    assertEquals(80, connection.fromSeq);
    assertEquals(90, connection.toSeq);
  }

  @Test
  public void responseFromPreviousHistoryGenerationIsDroppedWithoutResync() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "late-range", new RemoteTerminalModel(), Runnable::run, Runnable::run,
        (task, delayMs) -> {});
    FakeV2Connection connection = connect(runtime);
    connection.listener.onScreenMessage(baseline(1, 2, 1).toByteArray());
    assertTrue(runtime.requestHistoryRange(1, 64, 1));
    String oldRequestId = connection.requestId;

    connection.listener.onScreenMessage(baseline(2, 2, 2).toByteArray());
    connection.listener.onScreenMessage(historyRange(
        oldRequestId,
        TerminalScreenV2Proto.HistoryRangeStatus.HISTORY_RANGE_STATUS_OK,
        true, 1).toByteArray());

    PagedTerminalHistorySnapshot history =
        (PagedTerminalHistorySnapshot) runtime.model().renderSnapshot().history;
    assertNull(history.lineBySeq(1));
    assertEquals(2, runtime.model().historyGeneration());
    assertEquals(0, connection.reconnectRequests);
    assertEquals(TerminalSessionRuntime.ProjectionContinuityState.CONTINUOUS,
        runtime.projectionContinuityState());
  }

  @Test
  public void historyRangeLoadsOnlyRequestedPageWithoutAdvancingRevision() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "range", new RemoteTerminalModel(), Runnable::run, Runnable::run,
        (task, delayMs) -> {});
    FakeV2Connection connection = connect(runtime);
    connection.listener.onScreenMessage(baseline(1, 2).toByteArray());
    long revision = runtime.model().screenRevision;
    assertTrue(runtime.requestHistoryRange(1, 64, 1));

    connection.listener.onScreenMessage(historyRange(
        connection.requestId,
        TerminalScreenV2Proto.HistoryRangeStatus.HISTORY_RANGE_STATUS_OK,
        true).toByteArray());

    assertEquals(revision, runtime.model().screenRevision);
    PagedTerminalHistorySnapshot history =
        (PagedTerminalHistorySnapshot) runtime.model().renderSnapshot().history;
    assertNotNull(history.lineBySeq(1));
    assertNull(history.lineBySeq(65));
  }

  @Test
  public void historyRangeCompletesIntoMainWhileAlternateIsActive() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "range-buffer", new RemoteTerminalModel(), Runnable::run, Runnable::run,
        (task, delayMs) -> {});
    FakeV2Connection connection = connect(runtime);
    connection.listener.onScreenMessage(baseline(1, 2).toByteArray());
    assertTrue(runtime.requestHistoryRange(1, 64, 1));
    String requestId = connection.requestId;

    connection.listener.onScreenMessage(
        bufferSwitchCommit(1, 2,
            TerminalScreenV2Proto.BufferKind.BUFFER_KIND_ALTERNATE).toByteArray());
    long alternateFirstLineId = runtime.model().renderSnapshot().screen[0].id;
    assertEquals(com.webterm.terminal.model.TerminalBufferKind.ALTERNATE,
        runtime.model().activeBuffer);

    connection.listener.onScreenMessage(historyRange(
        requestId, TerminalScreenV2Proto.HistoryRangeStatus.HISTORY_RANGE_STATUS_OK,
        true).toByteArray());

    assertEquals(alternateFirstLineId, runtime.model().renderSnapshot().screen[0].id);
    assertEquals(0, runtime.model().historyIndex().loadedLineIds().size());
    assertNull(((PagedTerminalHistorySnapshot)
        runtime.model().renderSnapshot().history).lineBySeq(1));
    assertEquals(0, connection.reconnectRequests);

    connection.listener.onScreenMessage(
        bufferSwitchCommit(2, 3,
            TerminalScreenV2Proto.BufferKind.BUFFER_KIND_MAIN).toByteArray());
    assertNotNull(((PagedTerminalHistorySnapshot)
        runtime.model().renderSnapshot().history).lineBySeq(1));
    assertEquals(10_001,
        ((PagedTerminalHistorySnapshot) runtime.model().renderSnapshot().history)
            .lineBySeq(1).id);
    assertEquals(0, connection.reconnectRequests);
  }

  @Test
  public void synchronousHistoryResponseCannotBeatPendingReservation() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "range-sync", new RemoteTerminalModel(), Runnable::run, Runnable::run,
        (task, delayMs) -> {});
    FakeV2Connection connection = connect(runtime);
    connection.listener.onScreenMessage(baseline(1, 2).toByteArray());
    connection.respondSynchronously = true;

    assertTrue(runtime.requestHistoryRange(1, 64, 1));

    assertEquals(1, connection.rangeRequests);
    assertNotNull(((PagedTerminalHistorySnapshot)
        runtime.model().renderSnapshot().history).lineBySeq(1));
    assertEquals(0, connection.reconnectRequests);
  }

  @Test
  public void requestWireUsesSingleProjectionIdentityEvenIfModelChangesDuringSend() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "range-identity", new RemoteTerminalModel(), Runnable::run, Runnable::run,
        (task, delayMs) -> {});
    FakeV2Connection connection = connect(runtime);
    connection.listener.onScreenMessage(baseline(1, 2, 1).toByteArray());
    connection.onRangeRequest = () ->
        runtime.model().applyBaseline(
            com.webterm.terminal.protocol.ScreenMessageV2Mapper.mapBaseline(
                baseline(2, 2, 2).getBaseline()));

    assertTrue(runtime.requestHistoryRange(1, 64, 1));

    assertEquals(1, connection.historyGeneration);
    assertEquals(2, runtime.model().historyGeneration());
    connection.listener.onScreenMessage(historyRange(
        connection.requestId,
        TerminalScreenV2Proto.HistoryRangeStatus.HISTORY_RANGE_STATUS_OK,
        true, connection.historyGeneration).toByteArray());
    assertNull(((PagedTerminalHistorySnapshot)
        runtime.model().renderSnapshot().history).lineBySeq(1));
    assertEquals(0, connection.reconnectRequests);
  }

  @Test
  public void revisionJumpCommitIsAppliedWhileViewportIsIrrelevant() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "jump", new RemoteTerminalModel(), Runnable::run, Runnable::run,
        (task, delayMs) -> {});
    FakeV2Connection connection = connect(runtime);
    connection.listener.onScreenMessage(baseline(1, 2).toByteArray());

    connection.listener.onScreenMessage(commit(1, 9, "z").toByteArray());

    assertEquals(9, runtime.model().screenRevision);
    assertEquals("z", runtime.model().renderSnapshot().screen[0].at(0).text);
    assertEquals(TerminalSessionRuntime.ProjectionContinuityState.CONTINUOUS,
        runtime.projectionContinuityState());
  }

  @Test
  public void reconnectHelloUsesLastSuccessfullyCommittedResumeToken() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "resume", new RemoteTerminalModel(), Runnable::run, Runnable::run,
        (task, delayMs) -> {});
    FakeV2Connection first = connect(runtime);
    first.listener.onScreenMessage(baseline(1, 2).toByteArray());
    first.listener.onScreenMessage(commit(1, 5, "r").toByteArray());

    first.listener.onDisconnected("test");
    FakeV2Connection second = new FakeV2Connection();
    runtime.attachConnection(second);
    second.listener.onConnected();

    assertNotNull(second.resume);
    assertEquals(5, second.resume.getScreenRevision());
    assertEquals(2, second.resume.getActiveRowsCount());
    assertEquals(1, second.resume.getDictionaryGeneration());
    assertEquals(1, second.resume.getHistoryGeneration());
  }

  @Test
  public void inputIsRejectedWithoutLiveContinuityInsteadOfQueued() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "input", new RemoteTerminalModel(), Runnable::run, Runnable::run,
        (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    runtime.sendTextInput("old");
    assertTrue(connection.textInputs.isEmpty());

    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1, 2).toByteArray());
    // 没有 layout lease，仍不得缓存后补发。
    runtime.sendTextInput("still-old");
    assertTrue(connection.textInputs.isEmpty());
  }

  @Test
  public void clipboardReadReliableEffectCompletesResponseRoundTrip() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "clipboard", new RemoteTerminalModel(), Runnable::run, Runnable::run,
        (task, delayMs) -> {});
    FakeV2Connection connection = connect(runtime);
    connection.listener.onScreenMessage(baseline(1, 2).toByteArray());
    runtime.setEffectSink((source, effect, hasPageListener) -> {
      if (effect.type() == TerminalScreenEffect.Type.CLIPBOARD_READ) {
        source.sendClipboardResponse(
            effect.asClipboardRead().requestId, true, false, "value".getBytes(StandardCharsets.UTF_8));
      }
    });
    TerminalScreenV2Proto.ScreenEnvelope clipboard =
        TerminalScreenV2Proto.ScreenEnvelope.newBuilder().setProtocolVersion(2)
            .setEffect(TerminalScreenV2Proto.TerminalEffect.newBuilder()
                .setInstanceId("i1").setScreenRevision(1)
                .setClipboardRead(TerminalScreenV2Proto.ClipboardReadRequest.newBuilder()
                    .setRequestId("clip-1").setClipboard("c")))
            .build();

    connection.listener.onScreenMessage(clipboard.toByteArray());

    assertEquals(1, connection.clipboardResponses);
    assertEquals("clip-1", connection.clipboardRequestId);
    assertEquals("value", new String(connection.clipboardData, StandardCharsets.UTF_8));
    assertEquals(TerminalSessionRuntime.ProjectionContinuityState.CONTINUOUS,
        runtime.projectionContinuityState());
    assertEquals(0, connection.reconnectRequests);
  }

  private static FakeV2Connection connect(TerminalSessionRuntime runtime) {
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    return connection;
  }

  private static TerminalScreenV2Proto.ScreenEnvelope baseline(long revision, int rows) {
    return baseline(revision, rows, 1);
  }

  private static TerminalScreenV2Proto.ScreenEnvelope baseline(
      long revision, int rows, long historyGeneration) {
    TerminalScreenV2Proto.Baseline.Builder baseline =
        TerminalScreenV2Proto.Baseline.newBuilder()
            .setSessionId("s1")
            .setInstanceId("i1")
            .setLayoutEpoch(1)
            .setScreenRevision(revision)
            .setGeometry(TerminalScreenV2Proto.Geometry.newBuilder().setRows(rows).setCols(1))
            .setActiveBuffer(TerminalScreenV2Proto.BufferKind.BUFFER_KIND_MAIN)
            .setHistoryExtent(extent(1, 128))
            .setHistoryTail(TerminalScreenV2Proto.HistoryTail.newBuilder()
                .setExtent(extent(1, 128)))
            .setDictionaryGeneration(1)
            .setHistoryGeneration(historyGeneration)
            .setHistoryPolicy(TerminalScreenV2Proto.BaselineHistoryPolicy
                .BASELINE_HISTORY_POLICY_RESET);
    for (int row = 0; row < rows; row++) {
      long lineId = 1000 + row;
      baseline.getScreenLayoutBuilder().addLineIds(lineId);
      baseline.addScreenLines(line(lineId, 1, 0, "x"));
    }
    return envelope(baseline.build());
  }

  private static TerminalScreenV2Proto.ScreenEnvelope commit(
      long baseRevision, long revision, String text) {
    TerminalScreenV2Proto.TerminalCommit commit =
        TerminalScreenV2Proto.TerminalCommit.newBuilder()
            .setInstanceId("i1")
            .setLayoutEpoch(1)
            .setBaseRevision(baseRevision)
            .setRevision(revision)
            .setDictionaryGeneration(1)
            .setHistoryGeneration(1)
            .setScreen(TerminalScreenV2Proto.ScreenMutation.newBuilder()
                .addWrites(TerminalScreenV2Proto.ScreenRowWrite.newBuilder()
                    .setRow(0).setLine(line(1000, 2, 0, text))))
            .build();
    return envelope(commit);
  }

  private static TerminalScreenV2Proto.ScreenEnvelope bufferSwitchCommit(
      long baseRevision, long revision, TerminalScreenV2Proto.BufferKind buffer) {
    TerminalScreenV2Proto.TerminalCommit.Builder commit =
        TerminalScreenV2Proto.TerminalCommit.newBuilder()
            .setInstanceId("i1")
            .setLayoutEpoch(1)
            .setBaseRevision(baseRevision)
            .setRevision(revision)
            .setDictionaryGeneration(1)
            .setHistoryGeneration(1)
            .setActiveBuffer(buffer)
            .setHistory(TerminalScreenV2Proto.HistoryMutation.newBuilder()
                .setFinalExtent(buffer == TerminalScreenV2Proto.BufferKind.BUFFER_KIND_MAIN
                    ? extent(1, 128) : extent(1, 0)));
    for (int row = 0; row < 2; row++) {
      long lineId = buffer == TerminalScreenV2Proto.BufferKind.BUFFER_KIND_MAIN
          ? 1000 + row : 2000 + row;
      commit.getScreenBuilder().addWrites(
          TerminalScreenV2Proto.ScreenRowWrite.newBuilder()
              .setRow(row).setLine(line(
                  lineId, 1, 0,
                  buffer == TerminalScreenV2Proto.BufferKind.BUFFER_KIND_MAIN ? "x" : "b")));
    }
    return envelope(commit.build());
  }

  private static TerminalScreenV2Proto.ScreenEnvelope historyRange(
      String requestId, TerminalScreenV2Proto.HistoryRangeStatus status,
      boolean includeLines) {
    return historyRange(requestId, status, includeLines, 1);
  }

  private static TerminalScreenV2Proto.ScreenEnvelope historyRange(
      String requestId, TerminalScreenV2Proto.HistoryRangeStatus status,
      boolean includeLines, long historyGeneration) {
    TerminalScreenV2Proto.HistoryRangeResponse.Builder response =
        TerminalScreenV2Proto.HistoryRangeResponse.newBuilder()
            .setRequestId(requestId)
            .setInstanceId("i1")
            .setLayoutEpoch(1)
            .setHistoryGeneration(historyGeneration)
            .setStatus(status)
            .setAvailableExtent(extent(1, 128));
    if (includeLines) {
      for (long seq = 1; seq <= 64; seq++) {
        response.addLines(line(10_000 + seq, 1, seq, "h"));
      }
    }
    return envelope(response.build());
  }

  private static TerminalScreenV2Proto.ScreenEnvelope envelope(
      TerminalScreenV2Proto.Baseline baseline) {
    return TerminalScreenV2Proto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(2).setBaseline(baseline).build();
  }

  private static TerminalScreenV2Proto.ScreenEnvelope envelope(
      TerminalScreenV2Proto.TerminalCommit commit) {
    return TerminalScreenV2Proto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(2).setTerminalCommit(commit).build();
  }

  private static TerminalScreenV2Proto.ScreenEnvelope envelope(
      TerminalScreenV2Proto.HistoryRangeResponse response) {
    return TerminalScreenV2Proto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(2).setHistoryRangeResponse(response).build();
  }

  private static TerminalScreenV2Proto.HistoryExtent extent(long first, long last) {
    return TerminalScreenV2Proto.HistoryExtent.newBuilder()
        .setFirstSeq(first).setLastSeq(last).build();
  }

  private static TerminalScreenV2Proto.LineData line(
      long id, long version, long historySeq, String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    return TerminalScreenV2Proto.LineData.newBuilder()
        .setLineId(id)
        .setLineVersion(version)
        .setHistorySeq(historySeq)
        .setUtf8Text(ByteString.copyFrom(bytes))
        .setGlyphMeta(ByteString.copyFrom(new byte[] {(byte) (bytes.length << 1)}))
        .build();
  }

  private static final class FakeV2Connection
      implements TerminalSessionRuntime.ScreenConnection {
    Listener listener;
    TerminalScreenV2Proto.ResumeToken resume;
    String requestId = "";
    long historyGeneration;
    long fromSeq;
    long toSeq;
    int rangeRequests;
    final List<String> rangeRequestIds = new ArrayList<>();
    int reconnectRequests;
    int clipboardResponses;
    String clipboardRequestId = "";
    byte[] clipboardData = new byte[0];
    boolean respondSynchronously;
    Runnable onRangeRequest;
    final List<String> textInputs = new ArrayList<>();

    @Override public void setListener(@NonNull Listener listener) {
      this.listener = listener;
    }

    @Override public boolean beginSync(@Nullable TerminalScreenV2Proto.ResumeToken resume) {
      this.resume = resume;
      return true;
    }

    @Override public boolean requestHistoryRange(
        @NonNull String requestId, @NonNull String instanceId,
        long layoutEpoch, long historyGeneration, long fromSeq, long toSeq) {
      this.requestId = requestId;
      this.historyGeneration = historyGeneration;
      this.fromSeq = fromSeq;
      this.toSeq = toSeq;
      rangeRequests++;
      rangeRequestIds.add(requestId);
      if (onRangeRequest != null) onRangeRequest.run();
      if (respondSynchronously) {
        listener.onScreenMessage(historyRange(
            requestId,
            TerminalScreenV2Proto.HistoryRangeStatus.HISTORY_RANGE_STATUS_OK,
            true, historyGeneration).toByteArray());
      }
      return true;
    }

    @Override public void requestReconnect(@NonNull String reason) {
      reconnectRequests++;
    }

    @Override public void setLayoutLeaseId(@NonNull String leaseId) {}
    @Override public void sendTextInput(@NonNull String text) { textInputs.add(text); }
    @Override public void sendPasteInput(@NonNull String text) {}
    @Override public void sendKeyInput(@NonNull String key, boolean shift, boolean alt,
        boolean ctrl, boolean meta, boolean pressed) {}
    @Override public void sendMouseInput(int row, int col, @NonNull String button,
        int wheelDelta, boolean shift, boolean alt, boolean ctrl, boolean meta,
        boolean pressed) {}
    @Override public void sendFocusInput(boolean focused) {}
    @Override public boolean requestResize(int cols, int rows) { return true; }
    @Override public void acquireLayout(boolean interactive) {}
    @Override public void releaseLayout() {}
    @Override public void sendClipboardResponse(
        @NonNull String requestId, boolean allowed, boolean timeout, @Nullable byte[] data) {
      clipboardResponses++;
      clipboardRequestId = requestId;
      clipboardData = data == null ? new byte[0] : data.clone();
    }
    @Override public void close() {}
  }
}
