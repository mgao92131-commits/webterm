package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.TerminalCommit;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.PagedTerminalHistorySnapshot;
import com.webterm.terminal.model.SlotState;
import com.webterm.terminal.model.TerminalRenderMetrics;
import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;
import com.webterm.terminal.protocol.ScreenMessageV2Mapper;
import com.webterm.core.contract.diagnostics.Diagnostics;
import com.webterm.core.contract.diagnostics.DiagnosticSink;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** v2 冻结投影必须能在 Baseline 尾页之前继续按需加载历史。 */
public final class TerminalSessionRuntimeV2HistoryPagingTest {
  @Test
  public void renderConsumerRegistrationAndDestructiveConsumeAreTokenChecked() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(ScreenMessageV2Mapper.mapBaseline(baseline(1).getBaseline())));
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("owner", model, Runnable::run);
    Object owner = new Object();
    Object stranger = new Object();

    runtime.registerRenderConsumer(owner);
    runtime.registerRenderConsumer(owner);
    runtime.unregisterRenderConsumer(stranger);
    try {
      model.consumeRenderUpdate();
      org.junit.Assert.fail("runtime-bound model must reject direct destructive consume");
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage().contains("TerminalSessionRuntime"));
    }
    try {
      runtime.registerRenderConsumer(stranger);
      org.junit.Assert.fail("second active consumer must be rejected");
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage().contains("terminal session owner"));
    }
    try {
      runtime.consumeRenderUpdate(stranger);
      org.junit.Assert.fail("unregistered consumer must be rejected");
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage().contains("not active"));
    }
    assertNotNull(runtime.consumeRenderUpdate(owner));

    runtime.unregisterRenderConsumer(owner);
    runtime.registerRenderConsumer(stranger);
    runtime.unregisterRenderConsumer(stranger);
  }

  @Test
  public void requestModelRenderDoesNotWaitForModelMonitorAndMergesPublication() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(ScreenMessageV2Mapper.mapBaseline(baseline(1).getBaseline())));
    model.consumeRenderUpdate();
    assertTrue(model.applyTerminalCommit(new TerminalCommit(
        "i1", 1, 1, 1, 2, null, null,
        new TerminalCursor(0, 0, true, TerminalCursor.Shape.BLOCK, false), null, null)));
    QueuedExecutor modelExecutor = new QueuedExecutor();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("render-lock", model, modelExecutor);
    Object owner = new Object();
    runtime.registerRenderConsumer(owner);
    CountDownLatch monitorHeld = new CountDownLatch(1);
    CountDownLatch releaseMonitor = new CountDownLatch(1);
    Thread holder = holdModelMonitor(model, monitorHeld, releaseMonitor);
    ExecutorService ui = Executors.newSingleThreadExecutor();
    try {
      Future<?> request = ui.submit(runtime::requestModelRender);
      request.get(500, TimeUnit.MILLISECONDS);
      assertEquals(1, modelExecutor.tasks.size());

      releaseMonitor.countDown();
      holder.join(1_000);
      modelExecutor.runAll();
      RenderUpdate update = runtime.consumeRenderUpdate(owner);
      assertNotNull(update);
      assertEquals(2, update.snapshot.screenRevision);
      assertEquals("", update.snapshot.title);
      assertTrue(update.dirty.fullInvalidate);
      assertFalse(update.state.titleChanged);
    } finally {
      releaseMonitor.countDown();
      ui.shutdownNow();
      runtime.unregisterRenderConsumer(owner);
    }
  }

  @Test
  public void historyRequestUsesPublishedProjectionWithoutWaitingForModelMonitor()
      throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "history-lock", model, Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());
    CountDownLatch monitorHeld = new CountDownLatch(1);
    CountDownLatch releaseMonitor = new CountDownLatch(1);
    Thread holder = holdModelMonitor(model, monitorHeld, releaseMonitor);
    ExecutorService ui = Executors.newSingleThreadExecutor();
    try {
      Future<Boolean> request = ui.submit(() -> runtime.requestHistoryRange(100, 127, 100));
      assertTrue(request.get(500, TimeUnit.MILLISECONDS));
      assertEquals(100, connection.fromSeq);
      assertEquals(127, connection.toSeq);
    } finally {
      releaseMonitor.countDown();
      holder.join(1_000);
      ui.shutdownNow();
    }
  }

  @Test
  public void outOfRequestHistoryRangeStartsResyncWithoutPartialCommit() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "invalid-range", new RemoteTerminalModel(), Runnable::run, Runnable::run,
        (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());
    assertTrue(runtime.requestHistoryRange(100, 127, 100));

    connection.listener.onScreenMessage(historyRangeWithSeqs(
        connection.requestId, 1, 300, 100, 101, 128).toByteArray());

    assertEquals(TerminalSessionRuntime.StreamState.RESYNCING, runtime.streamState());
    PagedTerminalHistorySnapshot history =
        (PagedTerminalHistorySnapshot) runtime.model().renderSnapshot().history;
    assertNull(history.lineBySeq(100));
    assertNull(history.lineBySeq(101));
    assertEquals(128, history.loadedLineCount());
  }

  private static Thread holdModelMonitor(RemoteTerminalModel model,
                                         CountDownLatch held,
                                         CountDownLatch release) throws Exception {
    Thread holder = new Thread(() -> {
      synchronized (model) {
        held.countDown();
        try {
          release.await();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    });
    holder.start();
    assertTrue(held.await(1, TimeUnit.SECONDS));
    return holder;
  }

  @Test
  public void frozenRuntimeDropsLiveDeltasBeforeProtobufParseButAcceptsTailStatus() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());
    runtime.setFreezeReason(TerminalSessionRuntime.FREEZE_PAGE_HIDDEN, true);
    assertEquals(TerminalSessionRuntime.StreamState.FROZEN, runtime.streamState());

    TerminalRenderMetrics.Snapshot before = TerminalRenderMetrics.snapshot();
    connection.listener.onScreenMessage(screenPatch(2).toByteArray());
    connection.listener.onScreenMessage(historyDelta(2).toByteArray());
    connection.listener.onScreenMessage(tailStatus(2, 5, 1, 340).toByteArray());
    TerminalRenderMetrics.Snapshot after = TerminalRenderMetrics.snapshot();

    assertEquals(1L, after.protobufParseCount - before.protobufParseCount);
    assertEquals(2L, after.backgroundPatchDroppedCount - before.backgroundPatchDroppedCount);
    assertEquals(1, runtime.model().screenRevision);
    assertEquals(5, runtime.model().remoteScreenRevision());
    assertEquals(340, runtime.model().remoteAvailableExtent().lastSeq);
  }

  @Test
  public void clearingHistoryFreezeCannotClearLifecycleFreeze() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());

    runtime.setFreezeReason(TerminalSessionRuntime.FREEZE_PAGE_HIDDEN, true);
    runtime.freezeStream();
    runtime.resumeLiveStream();

    assertEquals(TerminalSessionRuntime.FREEZE_PAGE_HIDDEN, runtime.freezeReasons());
    assertEquals(TerminalSessionRuntime.StreamState.FROZEN, runtime.streamState());
    assertEquals(1, connection.modeChanges);
  }

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
  public void liveRecoveryBaselineConvergesBackToPageHiddenFreezeBeforeFlushingWork() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());
    grantLayoutLease(connection);

    runtime.setFreezeReason(TerminalSessionRuntime.FREEZE_PAGE_HIDDEN, true);
    assertTrue(runtime.requestHistoryRange(1, 128, 1));
    connection.listener.onScreenMessage(historyRange(
        connection.requestId, 1, 128,
        TerminalScreenV2Proto.HistoryRangeStatus.HISTORY_RANGE_STATUS_STALE_PROJECTION,
        1, 300, 0, false).toByteArray());
    assertEquals(TerminalSessionRuntime.StreamState.RESYNCING, runtime.streamState());
    assertEquals(TerminalScreenV2Proto.ScreenStreamMode.SCREEN_STREAM_MODE_LIVE,
        connection.lastMode);

    runtime.sendTextInput("queued");
    runtime.requestResize(120, 40);
    connection.listener.onScreenMessage(baseline(3).toByteArray());

    assertEquals(TerminalSessionRuntime.StreamState.FROZEN, runtime.streamState());
    assertEquals(TerminalScreenV2Proto.ScreenStreamMode.SCREEN_STREAM_MODE_FROZEN,
        connection.lastMode);
    assertEquals(0, connection.textInputs.size());
    assertEquals(0, connection.resizeRequests);

    runtime.setFreezeReason(TerminalSessionRuntime.FREEZE_PAGE_HIDDEN, false);
    assertEquals(TerminalSessionRuntime.StreamState.RESYNCING, runtime.streamState());
    connection.listener.onScreenMessage(baseline(5).toByteArray());

    assertEquals(TerminalSessionRuntime.StreamState.LIVE, runtime.streamState());
    assertEquals(1, connection.textInputs.size());
    assertEquals("queued", connection.textInputs.get(0));
    assertEquals(1, connection.resizeRequests);
    connection.listener.onScreenMessage(screenPatch(5).toByteArray());
    assertEquals(2, runtime.model().screenRevision);
    assertEquals(0, connection.reconnectRequests);
  }

  @Test
  public void appBackgroundRecoveryBaselineRemainsFrozenAndDropsFollowingPatch() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());

    runtime.setFreezeReason(TerminalSessionRuntime.FREEZE_APP_BACKGROUND, true);
    assertTrue(runtime.requestHistoryRange(1, 128, 1));
    connection.listener.onScreenMessage(historyRange(
        connection.requestId, 1, 128,
        TerminalScreenV2Proto.HistoryRangeStatus.HISTORY_RANGE_STATUS_STALE_PROJECTION,
        1, 300, 0, false).toByteArray());
    connection.listener.onScreenMessage(baseline(3).toByteArray());

    assertEquals(TerminalSessionRuntime.StreamState.FROZEN, runtime.streamState());
    connection.listener.onScreenMessage(screenPatch(4).toByteArray());
    assertEquals(1, runtime.model().screenRevision);

    runtime.setFreezeReason(TerminalSessionRuntime.FREEZE_APP_BACKGROUND, false);
    connection.listener.onScreenMessage(baseline(5).toByteArray());
    connection.listener.onScreenMessage(screenPatch(5).toByteArray());
    assertEquals(TerminalSessionRuntime.StreamState.LIVE, runtime.streamState());
    assertEquals(2, runtime.model().screenRevision);
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

  @Test
  public void tailStatusAdvancesRemoteRevisionWithoutChangingDisplayedProjection() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());
    runtime.freezeStream();

    connection.listener.onScreenMessage(tailStatus(2, 5, 1, 340).toByteArray());

    assertEquals(1, runtime.model().screenRevision);
    assertEquals(5, runtime.model().remoteScreenRevision());
    assertEquals(300, runtime.model().displayExtent().lastSeq);
    assertEquals(340, runtime.model().remoteAvailableExtent().lastSeq);
    assertTrue(runtime.model().hasRemoteTailChanges());

    connection.listener.onScreenMessage(tailStatus(2, 4, 1, 400).toByteArray());
    assertEquals(5, runtime.model().remoteScreenRevision());
    assertEquals(340, runtime.model().remoteAvailableExtent().lastSeq);
  }

  @Test
  public void frozenReconnectHelloCompletesOnTailStatusWithoutFlushingLiveWork() {
    FakeScheduler scheduler = new FakeScheduler();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, scheduler);
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());
    scheduler.clear();

    connection.listener.onDisconnected("test reconnect");
    runtime.setFreezeReason(TerminalSessionRuntime.FREEZE_APP_BACKGROUND, true);
    connection.listener.onConnected();
    assertEquals(TerminalSessionRuntime.State.SYNCING, runtime.state());
    assertEquals(TerminalScreenV2Proto.ScreenStreamMode.SCREEN_STREAM_MODE_FROZEN,
        connection.lastBeginSyncMode);
    assertEquals(2L, connection.lastBeginSyncGeneration);

    runtime.sendTextInput("queued");
    runtime.requestResize(120, 40);
    connection.listener.onScreenMessage(tailStatus(1, 1, 1, 300).toByteArray());
    assertEquals(TerminalSessionRuntime.State.SYNCING, runtime.state());
    connection.listener.onScreenMessage(tailStatus(2, 1, 1, 300).toByteArray());

    assertEquals(TerminalSessionRuntime.State.CONNECTED, runtime.state());
    assertEquals(TerminalSessionRuntime.StreamState.FROZEN, runtime.streamState());
    assertEquals(0, connection.modeChanges);
    assertEquals(0, connection.textInputs.size());
    assertEquals(0, connection.resizeRequests);
    assertEquals(0, connection.reconnectRequests);
    scheduler.runLast();
    assertEquals(0, connection.reconnectRequests);
    assertEquals(TerminalSessionRuntime.State.CONNECTED, runtime.state());
  }

  @Test
  public void clearingLastFreezeReasonDuringFrozenHelloRequestsOneLiveBaselineAfterTailStatus() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());

    connection.listener.onDisconnected("test reconnect");
    runtime.setFreezeReason(TerminalSessionRuntime.FREEZE_APP_BACKGROUND, true);
    connection.listener.onConnected();
    assertEquals(2L, connection.lastBeginSyncGeneration);
    runtime.setFreezeReason(TerminalSessionRuntime.FREEZE_APP_BACKGROUND, false);
    assertEquals(TerminalSessionRuntime.State.SYNCING, runtime.state());
    assertEquals(0, connection.modeChanges);

    connection.listener.onScreenMessage(tailStatus(2, 1, 1, 300).toByteArray());

    assertEquals(TerminalSessionRuntime.State.CONNECTED, runtime.state());
    assertEquals(TerminalSessionRuntime.StreamState.RESYNCING, runtime.streamState());
    assertEquals(1, connection.modeChanges);
    assertEquals(3L, connection.lastModeGeneration);
    assertEquals(TerminalScreenV2Proto.ScreenStreamMode.SCREEN_STREAM_MODE_LIVE,
        connection.lastMode);

    connection.listener.onScreenMessage(baseline(3).toByteArray());
    assertEquals(TerminalSessionRuntime.State.CONNECTED, runtime.state());
    assertEquals(TerminalSessionRuntime.StreamState.LIVE, runtime.streamState());
    assertEquals(1, connection.modeChanges);
    assertEquals(0, connection.reconnectRequests);
  }

  @Test
  public void remainingFreezeReasonKeepsFrozenHelloFrozenAfterTailStatus() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());

    connection.listener.onDisconnected("test reconnect");
    runtime.setFreezeReason(TerminalSessionRuntime.FREEZE_PAGE_HIDDEN, true);
    runtime.setFreezeReason(TerminalSessionRuntime.FREEZE_APP_BACKGROUND, true);
    connection.listener.onConnected();
    runtime.setFreezeReason(TerminalSessionRuntime.FREEZE_PAGE_HIDDEN, false);
    connection.listener.onScreenMessage(tailStatus(2, 1, 1, 300).toByteArray());

    assertEquals(TerminalSessionRuntime.State.CONNECTED, runtime.state());
    assertEquals(TerminalSessionRuntime.StreamState.FROZEN, runtime.streamState());
    assertEquals(TerminalSessionRuntime.FREEZE_APP_BACKGROUND, runtime.freezeReasons());
    assertEquals(0, connection.modeChanges);
  }

  @Test
  public void duplicateFrozenTailStatusIsIdempotent() {
    FakeScheduler scheduler = new FakeScheduler();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, scheduler);
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());
    scheduler.clear();

    connection.listener.onDisconnected("test reconnect");
    runtime.setFreezeReason(TerminalSessionRuntime.FREEZE_APP_BACKGROUND, true);
    connection.listener.onConnected();
    TerminalScreenV2Proto.ScreenEnvelope status = tailStatus(2, 1, 1, 300);
    connection.listener.onScreenMessage(status.toByteArray());
    connection.listener.onScreenMessage(status.toByteArray());

    assertEquals(TerminalSessionRuntime.State.CONNECTED, runtime.state());
    assertEquals(TerminalSessionRuntime.StreamState.FROZEN, runtime.streamState());
    assertEquals(0, connection.modeChanges);
    assertEquals(0, connection.reconnectRequests);
    scheduler.runLast();
    assertEquals(0, connection.reconnectRequests);
  }

  @Test
  public void staleTailStatusCannotCompleteFrozenHello() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());

    connection.listener.onDisconnected("test reconnect");
    runtime.setFreezeReason(TerminalSessionRuntime.FREEZE_APP_BACKGROUND, true);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(tailStatus(1, 1, 1, 300).toByteArray());

    assertEquals(TerminalSessionRuntime.State.SYNCING, runtime.state());
    assertEquals(TerminalSessionRuntime.StreamState.FROZEN, runtime.streamState());
    assertEquals(0, connection.modeChanges);
    assertEquals(0, connection.reconnectRequests);
  }

  @Test
  public void frozenDisconnectClearedBeforeReconnectBindsLiveToNextGeneration() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());
    runtime.freezeStream();
    assertEquals(2L, connection.lastModeGeneration);

    connection.listener.onDisconnected("test reconnect");
    runtime.resumeLiveStream();
    connection.listener.onConnected();

    assertEquals(TerminalScreenV2Proto.ScreenStreamMode.SCREEN_STREAM_MODE_LIVE,
        connection.lastBeginSyncMode);
    assertEquals(3L, connection.lastBeginSyncGeneration);
    connection.listener.onScreenMessage(tailStatus(2, 1, 1, 300).toByteArray());
    connection.listener.onScreenMessage(baseline(2).toByteArray());
    assertEquals(TerminalSessionRuntime.State.SYNCING, runtime.state());
    connection.listener.onScreenMessage(baseline(3).toByteArray());
    assertEquals(TerminalSessionRuntime.State.CONNECTED, runtime.state());
    assertEquals(TerminalSessionRuntime.StreamState.LIVE, runtime.streamState());
  }

  @Test
  public void frozenReconnectWithUnchangedModeKeepsBoundGeneration() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());
    runtime.freezeStream();

    connection.listener.onDisconnected("test reconnect");
    connection.listener.onConnected();

    assertEquals(TerminalScreenV2Proto.ScreenStreamMode.SCREEN_STREAM_MODE_FROZEN,
        connection.lastBeginSyncMode);
    assertEquals(2L, connection.lastBeginSyncGeneration);
  }

  @Test
  public void onlineModeSwitchAdvancesEachGenerationExactlyOnce() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    assertEquals(1L, connection.lastBeginSyncGeneration);
    connection.listener.onScreenMessage(baseline(1).toByteArray());

    runtime.freezeStream();
    assertEquals(2L, connection.lastModeGeneration);
    runtime.resumeLiveStream();
    assertEquals(3L, connection.lastModeGeneration);
    assertEquals(2, connection.modeChanges);
  }

  @Test
  public void sameVersionDifferentScreenContentRequestsResyncWithoutCommittingPatch() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());

    connection.listener.onScreenMessage(screenPatchWithLine(
        1, 1, "changed-without-version").toByteArray());

    assertEquals(1, runtime.model().screenRevision);
    assertEquals("x", runtime.model().renderSnapshot().screen[0].at(0).text);
    assertEquals(TerminalSessionRuntime.StreamState.RESYNCING, runtime.streamState());
    assertEquals(1, connection.modeChanges);
    assertEquals(TerminalScreenV2Proto.ScreenStreamMode.SCREEN_STREAM_MODE_LIVE,
        connection.lastMode);
    assertEquals(0, connection.reconnectRequests);
  }

  @Test
  public void revisionOnlyCommitDoesNotWakeRendererButMetadataCommitStillDoes() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, (task, delayMs) -> {});
    CountingListener listener = new CountingListener();
    Object renderOwner = new Object();
    runtime.registerRenderConsumer(renderOwner);
    runtime.addListener(listener);
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());
    assertEquals(1, listener.renderNeeded);
    assertNotNull(runtime.consumeRenderUpdate(renderOwner));

    connection.listener.onScreenMessage(screenPatchWithLine(1, 1, "x").toByteArray());
    assertEquals(2, runtime.model().screenRevision);
    assertEquals(2, listener.renderNeeded);
    assertNotNull(runtime.consumeRenderUpdate(renderOwner));
    assertEquals(0, connection.reconnectRequests);

    connection.listener.onScreenMessage(metadataPatch(1, 2, 3, "updated").toByteArray());
    assertEquals(3, listener.renderNeeded);
    assertNotNull(runtime.consumeRenderUpdate(renderOwner));
    assertTrue(runtime.model().cursor().visible);
    runtime.unregisterRenderConsumer(renderOwner);
  }

  @Test
  public void historyLineUsesIndependentStorageDomain() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());
    long loadedBefore = ((PagedTerminalHistorySnapshot)
        runtime.model().renderSnapshot().history).loadedLineCount();

    connection.listener.onScreenMessage(historyDeltaWithLine(1, 301, 1000).toByteArray());

    assertEquals(TerminalSessionRuntime.StreamState.LIVE, runtime.streamState());
    assertTrue(((PagedTerminalHistorySnapshot)
        runtime.model().renderSnapshot().history).loadedLineCount() > loadedBefore);
    assertEquals(0, connection.reconnectRequests);
  }

  @org.junit.Ignore("跨消息迁移状态机已由原子 TerminalCommit 删除")
  @Test
  public void invalidCrossMessageHistoryMigrationStartsResyncWithoutPartialCommit() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());
    long loadedBefore = ((PagedTerminalHistorySnapshot)
        runtime.model().renderSnapshot().history).loadedLineCount();

    connection.listener.onScreenMessage(screenPatchReplacingOnlyLine(1, 2000, "next").toByteArray());
    connection.listener.onScreenMessage(historyDeltaWithText(1, 301, 1000, "B").toByteArray());

    assertEquals(2, runtime.model().screenRevision);
    assertEquals(TerminalSessionRuntime.StreamState.RESYNCING, runtime.streamState());
    assertEquals(1, connection.modeChanges);
    assertEquals(2, connection.lastModeGeneration);
    assertEquals(loadedBefore, ((PagedTerminalHistorySnapshot)
        runtime.model().renderSnapshot().history).loadedLineCount());
    assertEquals(0, connection.reconnectRequests);
  }

  @Test
  public void validAtomicHistoryAndScreenCommitKeepsLiveStream() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());

    connection.listener.onScreenMessage(TerminalScreenV2Proto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(2)
        .setTerminalCommit(TerminalScreenV2Proto.TerminalCommit.newBuilder()
            .setInstanceId("i1").setLayoutEpoch(1).setStreamGeneration(1)
            .setBaseRevision(1).setRevision(2)
            .setScreen(TerminalScreenV2Proto.ScreenMutation.newBuilder()
                .addWrites(TerminalScreenV2Proto.ScreenRowWrite.newBuilder()
                    .setRow(0).setLine(line(2000, 0, "next"))))
            .setHistory(TerminalScreenV2Proto.HistoryMutation.newBuilder()
                .setFinalExtent(extent(1, 301))
                .addAppendedLines(line(1000, 301, "x"))))
        .build().toByteArray());

    assertEquals(TerminalSessionRuntime.StreamState.LIVE, runtime.streamState());
    assertEquals(0, connection.modeChanges);
    assertEquals(0, connection.reconnectRequests);
    assertEquals(1000, ((PagedTerminalHistorySnapshot)
        runtime.model().renderSnapshot().history).lineBySeq(301).id);
  }

  @Test
  public void failedFrozenModeSendImmediatelyRebuildsAndReconnectHelloStaysFrozen() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());
    connection.modeSwitchSucceeds = false;

    runtime.freezeStream();
    assertEquals(1, connection.reconnectRequests);
    assertEquals(TerminalSessionRuntime.State.RECONNECTING, runtime.state());

    connection.listener.onConnected();

    assertEquals(TerminalScreenV2Proto.ScreenStreamMode.SCREEN_STREAM_MODE_FROZEN,
        connection.lastBeginSyncMode);
    assertTrue(connection.lastBeginSyncHasFrozenProjection);
  }

  @Test
  public void repeatedResumeWhileResyncingSendsOnlyOneLiveModeRequest() {
    QueuedExecutor executor = new QueuedExecutor();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), executor, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    executor.runAll();
    connection.listener.onScreenMessage(baseline(1).toByteArray());
    executor.runAll();
    runtime.freezeStream();
    executor.runAll();

    runtime.resumeLiveStream();
    runtime.resumeLiveStream();
    runtime.resumeLiveStream();
    executor.runAll();

    assertEquals(2, connection.modeChanges);
    assertEquals(3L, connection.lastModeGeneration);
    assertEquals(TerminalSessionRuntime.StreamState.RESYNCING, runtime.streamState());
    connection.listener.onScreenMessage(baseline(3).toByteArray());
    executor.runAll();
    assertEquals(TerminalSessionRuntime.StreamState.LIVE, runtime.streamState());
  }

  @Test
  public void staleProjectionFramesAreDroppedWithoutNewResync() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());
    long revision = runtime.model().screenRevision;

    connection.listener.onScreenMessage(screenPatch(0).toByteArray());
    connection.listener.onScreenMessage(historyDelta(0).toByteArray());
    connection.listener.onScreenMessage(tailStatus(0, 9, 1, 400).toByteArray());

    assertEquals(revision, runtime.model().screenRevision);
    assertEquals(0, connection.modeChanges);
    assertEquals(TerminalSessionRuntime.StreamState.LIVE, runtime.streamState());
  }

  @Test
  public void staleBaselineDuringRecoveryIsDroppedAndMatchingBaselineCompletesRecovery() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());
    runtime.freezeStream();
    runtime.resumeLiveStream();
    assertEquals(TerminalSessionRuntime.StreamState.RESYNCING, runtime.streamState());

    connection.listener.onScreenMessage(baseline(2).toByteArray());
    assertEquals(TerminalSessionRuntime.StreamState.RESYNCING, runtime.streamState());
    assertEquals(2, connection.modeChanges);
    connection.listener.onScreenMessage(baseline(3).toByteArray());
    assertEquals(TerminalSessionRuntime.StreamState.LIVE, runtime.streamState());
    assertEquals(2, connection.modeChanges);
  }

  @Test
  public void futureGenerationRequestsChannelRebuild() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(baseline(1).toByteArray());

    connection.listener.onScreenMessage(screenPatch(2).toByteArray());

    assertEquals(1, connection.reconnectRequests);
    assertEquals(TerminalSessionRuntime.State.RECONNECTING, runtime.state());
    assertEquals(1, runtime.model().screenRevision);
  }

  @Test
  public void tailStatusIsClassifiedBeforeFullParse() {
    assertEquals(ScreenMailbox.MessageKind.TAIL_STATUS,
        TerminalSessionRuntime.classifyScreenMessage(
            tailStatus(1, 2, 1, 301).toByteArray()));
  }

  @Test
  public void smallControlFrameBurstDoesNotOverflowProjectionMailbox() {
    QueuedExecutor executor = new QueuedExecutor();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "s1", new RemoteTerminalModel(), executor, Runnable::run, (task, delayMs) -> {});
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    executor.runAll();
    connection.listener.onScreenMessage(baseline(1).toByteArray());
    executor.runAll();

    byte[] pong = TerminalScreenV2Proto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(2)
        .setPong(TerminalScreenV2Proto.Pong.newBuilder().setScreenRevision(1))
        .build().toByteArray();
    for (int i = 0; i < 128; i++) connection.listener.onScreenMessage(pong);
    executor.runAll();

    assertEquals(TerminalSessionRuntime.State.CONNECTED, runtime.state());
    assertEquals(0, connection.modeChanges);
    assertEquals(0, connection.reconnectRequests);
  }

  @Test
  public void generationDiagnosticsUseStableReasonsWithoutTerminalPayload() {
    List<Map<String, ?>> events = new ArrayList<>();
    Diagnostics.install((level, area, event, fields) -> {
      if (event.startsWith("screen_v2_generation_")) events.add(fields);
    });
    try {
      TerminalSessionRuntime runtime = new TerminalSessionRuntime(
          "s1", new RemoteTerminalModel(), Runnable::run, Runnable::run,
          (task, delayMs) -> {});
      FakeV2Connection connection = new FakeV2Connection();
      runtime.attachConnection(connection);
      connection.listener.onConnected();
      connection.listener.onScreenMessage(baseline(1).toByteArray());
      connection.listener.onScreenMessage(screenPatch(0).toByteArray());
      connection.listener.onScreenMessage(screenPatch(2).toByteArray());

      assertEquals(2, events.size());
      assertEquals("STALE_GENERATION", events.get(0).get("failureReason"));
      assertEquals("TERMINAL_COMMIT", events.get(0).get("payloadCase"));
      assertEquals("FUTURE_GENERATION", events.get(1).get("failureReason"));
      assertFalse(events.toString().contains("terminal-secret"));
    } finally {
      Diagnostics.install(DiagnosticSink.NO_OP);
    }
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

  private static void grantLayoutLease(FakeV2Connection connection) {
    connection.listener.onScreenMessage(TerminalScreenV2Proto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(2)
        .setLayoutLease(TerminalScreenV2Proto.LayoutLease.newBuilder()
            .setRequestId(connection.acquireRequestId)
            .setLeaseId("lease-1")
            .setGranted(true)
            .setInteractive(true))
        .build().toByteArray());
  }

  private static TerminalScreenV2Proto.ScreenEnvelope historyRange(
      String requestId, long fromSeq, long toSeq) {
    return historyRange(
        requestId, fromSeq, toSeq,
        TerminalScreenV2Proto.HistoryRangeStatus.HISTORY_RANGE_STATUS_OK,
        1, 300, 0, true);
  }

  private static TerminalScreenV2Proto.ScreenEnvelope screenPatch(long generation) {
    return metadataPatch(generation, 1, 2, "ignored");
  }

  private static TerminalScreenV2Proto.ScreenEnvelope screenPatchWithLine(
      long generation, long lineVersion, String text) {
    return TerminalScreenV2Proto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(2)
        .setTerminalCommit(TerminalScreenV2Proto.TerminalCommit.newBuilder()
            .setInstanceId("i1")
            .setLayoutEpoch(1)
            .setStreamGeneration(generation)
            .setBaseRevision(1)
            .setRevision(2)
            .setScreen(TerminalScreenV2Proto.ScreenMutation.newBuilder()
              .addWrites(TerminalScreenV2Proto.ScreenRowWrite.newBuilder().setRow(0).setLine(
                TerminalScreenV2Proto.LineData.newBuilder()
                .setLineId(1000)
                .setLineVersion(lineVersion)
                .setHistorySeq(0)
                .addRuns(TerminalScreenV2Proto.CellRun.newBuilder()
                    .setCol(0)
                    .addCells(TerminalScreenV2Proto.Cell.newBuilder()
                        .setText(text)
                        .setWidth(1)))))))
        .build();
  }

  private static TerminalScreenV2Proto.ScreenEnvelope screenPatchReplacingOnlyLine(
      long generation, long replacementId, String text) {
    return TerminalScreenV2Proto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(2)
        .setTerminalCommit(TerminalScreenV2Proto.TerminalCommit.newBuilder()
            .setInstanceId("i1")
            .setLayoutEpoch(1)
            .setStreamGeneration(generation)
            .setBaseRevision(1)
            .setRevision(2)
            .setScreen(TerminalScreenV2Proto.ScreenMutation.newBuilder()
                .addWrites(TerminalScreenV2Proto.ScreenRowWrite.newBuilder()
                    .setRow(0).setLine(line(replacementId, 0, text)))))
        .build();
  }

  private static TerminalScreenV2Proto.ScreenEnvelope metadataPatch(
      long generation, long baseRevision, long revision, String title) {
    return TerminalScreenV2Proto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(2)
        .setTerminalCommit(TerminalScreenV2Proto.TerminalCommit.newBuilder()
            .setInstanceId("i1")
            .setLayoutEpoch(1)
            .setStreamGeneration(generation)
            .setBaseRevision(baseRevision)
            .setRevision(revision)
            .setCursor(TerminalScreenV2Proto.Cursor.newBuilder().setVisible(true)))
        .build();
  }

  private static TerminalScreenV2Proto.ScreenEnvelope historyDelta(long generation) {
    return TerminalScreenV2Proto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(2)
        .setTerminalCommit(TerminalScreenV2Proto.TerminalCommit.newBuilder()
            .setInstanceId("i1")
            .setLayoutEpoch(1)
            .setStreamGeneration(generation).setBaseRevision(1).setRevision(2)
            .setHistory(TerminalScreenV2Proto.HistoryMutation.newBuilder()
                .setFinalExtent(extent(1, 300))))
        .build();
  }

  private static TerminalScreenV2Proto.ScreenEnvelope historyDeltaWithLine(
      long generation, long historySeq, long lineId) {
    return historyDeltaWithText(generation, historySeq, lineId, "x");
  }

  private static TerminalScreenV2Proto.ScreenEnvelope historyDeltaWithText(
      long generation, long historySeq, long lineId, String text) {
    return TerminalScreenV2Proto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(2)
        .setTerminalCommit(TerminalScreenV2Proto.TerminalCommit.newBuilder()
            .setInstanceId("i1")
            .setLayoutEpoch(1)
            .setStreamGeneration(generation).setBaseRevision(1).setRevision(2)
            .setHistory(TerminalScreenV2Proto.HistoryMutation.newBuilder()
                .setFinalExtent(extent(1, historySeq))
                .addAppendedLines(line(lineId, historySeq, text))))
        .build();
  }

  private static TerminalScreenV2Proto.ScreenEnvelope tailStatus(
      long generation, long latestScreenRevision, long extentFirst, long extentLast) {
    return TerminalScreenV2Proto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(2)
        .setTailStatus(TerminalScreenV2Proto.TailStatus.newBuilder()
            .setInstanceId("i1")
            .setLayoutEpoch(1)
            .setStreamGeneration(generation)
            .setLatestScreenRevision(latestScreenRevision)
            .setLatestHistoryExtent(extent(extentFirst, extentLast)))
        .build();
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

  private static TerminalScreenV2Proto.ScreenEnvelope historyRangeWithSeqs(
      String requestId, long extentFirst, long extentLast, long... historySeqs) {
    TerminalScreenV2Proto.HistoryRangeResponse.Builder response =
        TerminalScreenV2Proto.HistoryRangeResponse.newBuilder()
            .setRequestId(requestId)
            .setInstanceId("i1")
            .setLayoutEpoch(1)
            .setStatus(TerminalScreenV2Proto.HistoryRangeStatus.HISTORY_RANGE_STATUS_OK)
            .setAvailableExtent(extent(extentFirst, extentLast));
    for (long seq : historySeqs) response.addLines(line(10_000 + seq, seq));
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
    return line(id, historySeq, "x");
  }

  private static TerminalScreenV2Proto.LineData line(long id, long historySeq, String text) {
    return TerminalScreenV2Proto.LineData.newBuilder()
        .setLineId(id)
        .setLineVersion(1)
        .setHistorySeq(historySeq)
        .addRuns(TerminalScreenV2Proto.CellRun.newBuilder()
            .setCol(0)
            .addCells(TerminalScreenV2Proto.Cell.newBuilder().setText(text).setWidth(1)))
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
    boolean modeSwitchSucceeds = true;
    TerminalScreenV2Proto.ScreenStreamMode lastBeginSyncMode;
    long lastBeginSyncGeneration;
    boolean lastBeginSyncHasFrozenProjection;
    final List<String> textInputs = new ArrayList<>();
    TerminalScreenV2Proto.ScreenStreamMode lastMode;
    long lastModeGeneration;
    int reconnectRequests;

    @Override public void setListener(@NonNull Listener listener) { this.listener = listener; }
    @Override public boolean beginSync(long generation,
        @NonNull TerminalScreenV2Proto.ScreenStreamMode mode, @Nullable String instanceId,
        long layoutEpoch, boolean hasFrozenProjection) {
      lastBeginSyncMode = mode;
      lastBeginSyncGeneration = generation;
      lastBeginSyncHasFrozenProjection = hasFrozenProjection;
      return true;
    }
    @Override public boolean setStreamMode(long generation,
        @NonNull TerminalScreenV2Proto.ScreenStreamMode mode) {
      modeChanges++;
      lastMode = mode;
      lastModeGeneration = generation;
      return modeSwitchSucceeds;
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
    @Override public void requestReconnect(@NonNull String reason) { reconnectRequests++; }
  }

  private static final class CountingListener implements TerminalSessionRuntime.Listener {
    int renderNeeded;

    @Override public void onRenderNeeded() { renderNeeded++; }
    @Override public void onEffect(@NonNull TerminalScreenEffect effect) {}
    @Override public void onConnectionStateChange(@NonNull TerminalSessionRuntime.State state) {}
  }

  private static final class QueuedExecutor implements java.util.concurrent.Executor {
    final List<Runnable> tasks = new ArrayList<>();

    @Override public void execute(@NonNull Runnable command) {
      tasks.add(command);
    }

    void runAll() {
      while (!tasks.isEmpty()) tasks.remove(0).run();
    }
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
