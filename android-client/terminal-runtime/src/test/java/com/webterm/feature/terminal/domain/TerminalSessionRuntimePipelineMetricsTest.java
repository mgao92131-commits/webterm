package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.protobuf.ByteString;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.HistoryBodyEntry;
import com.webterm.terminal.model.HistoryMutation;
import com.webterm.terminal.model.HistoryPush;
import com.webterm.terminal.model.HistoryRangeResult;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.ScreenLineContent;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalCommit;
import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executor;

import org.junit.Test;

/**
 * 屏幕管线水位：Baseline/Commit 推进 decoded/model/published/consumed/handled/rendered；
 * 历史-only 与 requestFullRender 推进 publicationVersion；失败不推进 handled/rendered。
 * 成功路径经 {@link TerminalSessionRuntime#onRenderFrameSucceeded} 原子推进 handled+rendered。
 */
public final class TerminalSessionRuntimePipelineMetricsTest {

  @Test
  public void revisionGapFirstRequestsInBandResyncWithoutChannelRebuild() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    TerminalSessionRuntime runtime =
        new TerminalSessionRuntime("revision-gap-resync", model, Runnable::run);
    FakeV2Connection connection = connect(runtime);
    connection.listener.onScreenMessage(baselineEnvelope(100));

    connection.listener.onScreenMessage(commitEnvelope(99, 101));

    assertEquals(1, connection.resyncCount);
    assertEquals(0, connection.reconnectCount);
    assertEquals(TerminalSessionRuntime.State.SYNCING, runtime.state());
    assertEquals(
        TerminalSessionRuntime.ProjectionContinuityState.LOST,
        runtime.projectionContinuityState());
  }

  @Test
  public void authoritativeBaselineCompletesInBandRecoveryWithoutRebuild() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    TerminalSessionRuntime runtime =
        new TerminalSessionRuntime("revision-gap-baseline", model, Runnable::run);
    FakeV2Connection connection = connect(runtime);
    connection.listener.onScreenMessage(baselineEnvelope(100));
    connection.listener.onScreenMessage(commitEnvelope(99, 101));

    connection.listener.onScreenMessage(baselineEnvelope(120));

    assertEquals(1, connection.resyncCount);
    assertEquals(0, connection.reconnectCount);
    assertEquals(TerminalSessionRuntime.State.LIVE, runtime.state());
    assertEquals(
        TerminalSessionRuntime.ProjectionContinuityState.CONTINUOUS,
        runtime.projectionContinuityState());
    Map<String, Object> diagnostics = runtime.diagnosticsSnapshot().pipeline;
    assertEquals(1L, diagnostics.get("inBandResyncCount"));
    assertEquals(1L, diagnostics.get("recoveryCompletedCount"));
    assertEquals("BASELINE", diagnostics.get("recoverySnapshotKind"));
  }

  @Test
  public void suspendAbortsActiveRecovery() {
    TerminalSessionRuntime runtime =
        new TerminalSessionRuntime("suspend-recovery", new RemoteTerminalModel(), Runnable::run);
    FakeV2Connection connection = connect(runtime);
    connection.listener.onScreenMessage(baselineEnvelope(100));
    connection.listener.onScreenMessage(commitEnvelope(99, 101));
    assertEquals(true, runtime.diagnosticsSnapshot().pipeline.get("recoveryActive"));

    runtime.suspendConnection();

    Map<String, Object> diagnostics = runtime.diagnosticsSnapshot().pipeline;
    assertEquals(false, diagnostics.get("recoveryActive"));
    assertEquals("SESSION_SUSPENDED", diagnostics.get("recoveryFinalOutcome"));
    assertEquals(1L, diagnostics.get("recoveryAbortedCount"));
  }

  @Test
  public void newConnectionDoesNotInheritOldRecovery() {
    TerminalSessionRuntime runtime =
        new TerminalSessionRuntime("replace-recovery", new RemoteTerminalModel(), Runnable::run);
    FakeV2Connection first = connect(runtime);
    first.listener.onScreenMessage(baselineEnvelope(100));
    first.listener.onScreenMessage(commitEnvelope(99, 101));

    FakeV2Connection replacement = new FakeV2Connection();
    runtime.attachConnection(replacement);

    Map<String, Object> diagnostics = runtime.diagnosticsSnapshot().pipeline;
    assertEquals(false, diagnostics.get("recoveryActive"));
    assertEquals("CONNECTION_REPLACED", diagnostics.get("recoveryFinalOutcome"));
    assertEquals(1L, diagnostics.get("recoveryAbortedCount"));
  }

  @Test
  public void dictionaryMismatchRebuildsOnceAndNextHelloForcesBaseline() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    TerminalSessionRuntime runtime =
        new TerminalSessionRuntime("force-baseline", model, Runnable::run);
    FakeV2Connection connection = connect(runtime);
    connection.listener.onScreenMessage(baselineEnvelope(100));

    TerminalScreenV2Proto.ScreenEnvelope valid =
        TerminalScreenV2Proto.ScreenEnvelope.parseFrom(commitEnvelope(100, 101));
    TerminalScreenV2Proto.TerminalCommit invalid =
        valid.getTerminalCommit().toBuilder().setDictionaryGeneration(9).build();
    connection.listener.onScreenMessage(
        TerminalScreenV2Proto.ScreenEnvelope.newBuilder()
            .setProtocolVersion(2)
            .setTerminalCommit(invalid)
            .build().toByteArray());

    assertEquals("resyncCount=" + connection.resyncCount, 1, connection.reconnectCount);
    connection.listener.onConnected();
    assertTrue(connection.lastForceBaseline);
    assertTrue(connection.lastResumeToken == null);
    assertEquals(2, connection.beginSyncCount);
  }

  @Test
  public void projectionMailboxOverflowUsesInBandResyncInsteadOfReconnect() {
    ManualExecutor executor = new ManualExecutor();
    RemoteTerminalModel model = new RemoteTerminalModel();
    TerminalSessionRuntime runtime =
        new TerminalSessionRuntime("projection-overflow", model, executor);
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    executor.runAll();
    connection.listener.onScreenMessage(baselineEnvelope(100));
    executor.runAll();

    // Runtime 的生产预算是 256 帧；第 257 帧必须形成 generation fence，
    // 而不是依靠后续重复 revision gap 偶然进入同一恢复路径。
    for (int index = 0; index < 257; index++) {
      connection.listener.onScreenMessage(commitEnvelope(100, 101));
    }
    executor.runAll();

    assertEquals(1, connection.resyncCount);
    assertEquals(0, connection.reconnectCount);
    assertEquals(
        TerminalSessionRuntime.ProjectionContinuityState.LOST,
        runtime.projectionContinuityState());
  }

  @Test
  public void fatalMailboxOverflowRebuildsChannelDirectly() {
    ManualExecutor executor = new ManualExecutor();
    TerminalSessionRuntime runtime =
        new TerminalSessionRuntime("fatal-overflow", new RemoteTerminalModel(), executor);
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    executor.runAll();

    connection.listener.onScreenMessage(new byte[2 * 1024 * 1024 + 1]);
    executor.runAll();

    assertEquals(0, connection.resyncCount);
    assertEquals(1, connection.reconnectCount);
    assertEquals(TerminalSessionRuntime.State.RECONNECTING, runtime.state());
  }

  @Test
  public void baselineAndCommitsAdvancePipelineWatermarks() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("pipe-wm", model, Runnable::run);
    FakeV2Connection connection = connect(runtime);
    Object renderConsumer = new Object();
    runtime.registerRenderConsumer(renderConsumer);

    connection.listener.onScreenMessage(baselineEnvelope(100));
    Map<String, Object> afterBaseline = runtime.pipelineMetrics().snapshot();
    assertEquals(100L, afterBaseline.get("lastDecodedScreenRevision"));
    assertEquals(100L, afterBaseline.get("lastModelScreenRevision"));
    long publishedAfterBaseline = (Long) afterBaseline.get("lastPublishedVersion");
    assertTrue(publishedAfterBaseline > 0L);
    assertEquals(100L, afterBaseline.get("lastPublishedScreenRevision"));
    assertEquals(100L, (long) model.screenRevision);

    connection.listener.onScreenMessage(commitEnvelope(100, 101));
    connection.listener.onScreenMessage(commitEnvelope(101, 102));

    Map<String, Object> afterCommits = runtime.pipelineMetrics().snapshot();
    assertEquals(102L, afterCommits.get("lastDecodedScreenRevision"));
    assertEquals(102L, afterCommits.get("lastModelScreenRevision"));
    long publishedAfterCommits = (Long) afterCommits.get("lastPublishedVersion");
    assertTrue(publishedAfterCommits > publishedAfterBaseline);
    assertEquals(102L, afterCommits.get("lastPublishedScreenRevision"));
    assertEquals(102L, (long) model.screenRevision);

    RenderUpdate update = runtime.consumeRenderUpdate(renderConsumer);
    assertNotNull(update);
    assertEquals(102L, update.snapshot.screenRevision);
    runtime.onRenderFrameSucceeded(update.publicationVersion, update.snapshot.screenRevision, 1_000L);

    Map<String, Object> afterDraw = runtime.pipelineMetrics().snapshot();
    assertEquals(update.publicationVersion, afterDraw.get("lastConsumedVersion"));
    assertEquals(update.publicationVersion, afterDraw.get("lastRenderedVersion"));
    assertEquals(update.publicationVersion, afterDraw.get("lastHandledVersion"));
    assertEquals(0L, afterDraw.get("lastRenderFailedVersion"));
    assertEquals(102L, afterDraw.get("lastConsumedScreenRevision"));
    assertEquals(102L, afterDraw.get("lastRenderedScreenRevision"));
    assertEquals(102L, afterDraw.get("lastHandledScreenRevision"));
    assertTrue((Long) afterDraw.get("lastRenderedAtNanos") > 0L);
    assertEquals(1L, afterDraw.get("renderSuccessCount"));
    assertEquals(0L, afterDraw.get("renderFailureCount"));
    assertEquals(0L, afterDraw.get("stateOnlyHandledCount"));
  }

  @Test
  public void historyOnlyPublicationAdvancesVersionWithoutScreenRevision() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(domainBaseline()));
    model.consumeRenderUpdate();

    TerminalSessionRuntime runtime = new TerminalSessionRuntime("hist-wm", model, Runnable::run);
    connect(runtime);
    runtime.enterLiveForTest();
    Object renderConsumer = new Object();
    runtime.registerRenderConsumer(renderConsumer);

    long revBefore = model.screenRevision;
    long publishedBefore = model.lastPublicationVersion();

    runtime.setHistoryRangeSource(new HistoryRangeSource() {
      @Override public RequestHandle fetch(
          @NonNull HistoryRangeLoader.Range range, @NonNull Callback callback) {
        callback.onResult(decodedRange(range));
        return () -> {};
      }
      @Override public void close() {}
    });
    runtime.onVisibleHistoryDemand(100, 180, 100, -1, 50);

    assertEquals(revBefore, model.screenRevision);
    assertTrue(model.lastPublicationVersion() > publishedBefore);

    Map<String, Object> afterHistory = runtime.pipelineMetrics().snapshot();
    // 历史段不改 screenRevision，故不推进 lastModelScreenRevision。
    assertEquals(0L, afterHistory.get("lastModelScreenRevision"));
    assertEquals(model.lastPublicationVersion(), afterHistory.get("lastPublishedVersion"));
    assertEquals(revBefore, afterHistory.get("lastPublishedScreenRevision"));

    RenderUpdate update = runtime.consumeRenderUpdate(renderConsumer);
    assertNotNull(update);
    assertTrue(update.state.historyChanged);
    assertEquals(revBefore, update.snapshot.screenRevision);
    runtime.onRenderFrameSucceeded(update.publicationVersion, update.snapshot.screenRevision, 500L);

    Map<String, Object> afterDraw = runtime.pipelineMetrics().snapshot();
    assertEquals(update.publicationVersion, afterDraw.get("lastConsumedVersion"));
    assertEquals(update.publicationVersion, afterDraw.get("lastRenderedVersion"));
    assertEquals(update.publicationVersion, afterDraw.get("lastHandledVersion"));
    assertEquals(revBefore, afterDraw.get("lastRenderedScreenRevision"));
  }

  @Test
  public void requestFullRenderAdvancesLastPublishedVersion() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(domainBaseline()));
    model.consumeRenderUpdate();

    TerminalSessionRuntime runtime = new TerminalSessionRuntime("full-wm", model, Runnable::run);
    connect(runtime);
    runtime.enterLiveForTest();

    long publishedBefore = model.lastPublicationVersion();
    long revBefore = model.screenRevision;
    Map<String, Object> before = runtime.pipelineMetrics().snapshot();
    assertEquals(0L, before.get("lastPublishedVersion"));

    runtime.requestModelRender();

    assertTrue(model.lastPublicationVersion() > publishedBefore);
    assertEquals(revBefore, model.screenRevision);

    Map<String, Object> after = runtime.pipelineMetrics().snapshot();
    assertEquals(model.lastPublicationVersion(), after.get("lastPublishedVersion"));
    assertEquals(revBefore, after.get("lastPublishedScreenRevision"));
    // requestFullRender 不改 screenRevision，不应推进 lastModelScreenRevision。
    assertEquals(0L, after.get("lastModelScreenRevision"));
  }

  @Test
  public void historyRangeProtocolErrorCompletesRequestAndQuarantinesRangeWithoutReconnect() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(domainBaseline()));
    model.consumeRenderUpdate();
    TerminalSessionRuntime runtime =
        new TerminalSessionRuntime("range-conflict", model, Runnable::run);
    connect(runtime);
    runtime.enterLiveForTest();
    runtime.setHistoryRangeSource(new HistoryRangeSource() {
      @Override public RequestHandle fetch(
          @NonNull HistoryRangeLoader.Range range, @NonNull Callback callback) {
        callback.onResult(new Result(
            range.instanceId, range.layoutEpoch, range.generation,
            new HistoryExtent(1, 300),
            java.util.Arrays.asList(
                historyEntry(9000, 100, "a"),
                historyEntry(9000, 101, "b"))));
        return () -> {};
      }

      @Override public void close() {}
    });

    runtime.onVisibleHistoryDemand(100, 101, 100, 1, 50);

    Map<String, Object> loader = runtime.diagnosticsSnapshot().historyLoader;
    assertEquals(false, loader.get("hasActiveRequest"));
    assertEquals(true, loader.get("hasDemand"));
    assertEquals(true, loader.get("hasUnavailableRange"));
  }

  @Test
  public void temporarySessionNotReadyRetriesWithoutClearingDemandThenAppliesHistory() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(domainBaseline()));
    model.consumeRenderUpdate();
    ControlledScheduler scheduler = new ControlledScheduler();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "range-not-ready", model, Runnable::run, Runnable::run, scheduler);
    connect(runtime);
    runtime.enterLiveForTest();
    scheduler.clear();
    ControlledRangeSource source = new ControlledRangeSource();
    runtime.setHistoryRangeSource(source);

    runtime.onVisibleHistoryDemand(100, 100, 100, 0, 1);
    assertEquals(1, source.requests.size());
    source.requests.get(0).callback.onFailure(new HistoryRangeSource.Failure(
        HistoryRangeSource.FailureKind.SESSION_NOT_READY, 0, 1));

    Map<String, Object> afterFailure = runtime.diagnosticsSnapshot().historyLoader;
    assertEquals(true, afterFailure.get("hasDemand"));
    assertEquals(false, afterFailure.get("hasUnavailableRange"));
    assertEquals(1L, afterFailure.get("sessionNotReadyCount"));
    assertEquals(1L, afterFailure.get("retryScheduledCount"));

    scheduler.runUntil(() -> source.requests.size() >= 2);
    assertEquals(2, source.requests.size());
    source.requests.get(1).callback.onResult(decodedRange(source.requests.get(1).range));

    int historyIndex = model.renderSnapshot().history.findSeqIndex(100);
    assertNotNull(model.renderSnapshot().history.renderLineAt(historyIndex));
    assertEquals(false, runtime.diagnosticsSnapshot().historyLoader.get("hasActiveRequest"));
  }

  @Test
  public void sessionGoneDoesNotPoisonUnavailableRange() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(domainBaseline()));
    model.consumeRenderUpdate();
    TerminalSessionRuntime runtime =
        new TerminalSessionRuntime("range-gone", model, Runnable::run);
    connect(runtime);
    runtime.enterLiveForTest();
    ControlledRangeSource source = new ControlledRangeSource();
    runtime.setHistoryRangeSource(source);

    runtime.onVisibleHistoryDemand(100, 100, 100, 0, 1);
    source.requests.get(0).callback.onFailure(new HistoryRangeSource.Failure(
        HistoryRangeSource.FailureKind.SESSION_GONE, 0, 1));

    Map<String, Object> loader = runtime.diagnosticsSnapshot().historyLoader;
    assertEquals(false, loader.get("hasDemand"));
    assertEquals(false, loader.get("hasUnavailableRange"));
    assertEquals(1L, loader.get("sessionGoneCount"));
  }

  @Test
  public void repeatedSessionNotReadyEscalatesToChannelRebuild() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(domainBaseline()));
    model.consumeRenderUpdate();
    ControlledScheduler scheduler = new ControlledScheduler();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "range-retry-exhausted", model, Runnable::run, Runnable::run, scheduler);
    FakeV2Connection connection = connect(runtime);
    runtime.enterLiveForTest();
    scheduler.clear();
    ControlledRangeSource source = new ControlledRangeSource();
    runtime.setHistoryRangeSource(source);

    runtime.onVisibleHistoryDemand(100, 100, 100, 0, 1);
    for (int attempt = 0; attempt < 3; attempt++) {
      source.requests.get(attempt).callback.onFailure(new HistoryRangeSource.Failure(
          HistoryRangeSource.FailureKind.SESSION_NOT_READY, 0, 1));
      if (attempt < 2) {
        int expectedRequests = attempt + 2;
        scheduler.runUntil(() -> source.requests.size() >= expectedRequests);
      }
    }

    assertEquals(1, connection.reconnectCount);
    assertEquals(1L,
        runtime.diagnosticsSnapshot().historyLoader.get("retryExhaustedCount"));
  }

  @Test
  public void staleProjectionRequestsBaselineWithoutPoisoningRange() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(domainBaseline()));
    model.consumeRenderUpdate();
    TerminalSessionRuntime runtime =
        new TerminalSessionRuntime("range-stale", model, Runnable::run);
    FakeV2Connection connection = connect(runtime);
    runtime.enterLiveForTest();
    ControlledRangeSource source = new ControlledRangeSource();
    runtime.setHistoryRangeSource(source);

    runtime.onVisibleHistoryDemand(100, 100, 100, 0, 1);
    source.requests.get(0).callback.onFailure(new HistoryRangeSource.Failure(
        HistoryRangeSource.FailureKind.STALE_PROJECTION, 0, 1));

    Map<String, Object> loader = runtime.diagnosticsSnapshot().historyLoader;
    assertEquals(loader.toString(), true, loader.get("hasDemand"));
    assertEquals(false, loader.get("hasUnavailableRange"));
    assertEquals(1, connection.reconnectCount);
    assertEquals(1L, loader.get("staleProjectionResponseCount"));
  }

  @Test
  public void lateOldRangeAfterWsRebindDoesNotReconnectAndNextRangeLoadsCurrentBody()
      throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(domainBaseline()));
    assertTrue(model.applyTerminalCommit(new TerminalCommit(
        "i1", 1, 1, 2, 1, 1, null, null,
        new HistoryMutation(new HistoryExtent(1, 300),
            Collections.singletonList(new HistoryPush(100, 2001, 1))),
        null, null, null)));
    model.consumeRenderUpdate();

    TerminalSessionRuntime runtime =
        new TerminalSessionRuntime("range-rebind-race", model, Runnable::run);
    FakeV2Connection connection = connect(runtime);
    runtime.enterLiveForTest();
    java.util.concurrent.atomic.AtomicInteger requests =
        new java.util.concurrent.atomic.AtomicInteger();
    runtime.setHistoryRangeSource(new HistoryRangeSource() {
      @Override public RequestHandle fetch(
          @NonNull HistoryRangeLoader.Range range, @NonNull Callback callback) {
        int request = requests.incrementAndGet();
        callback.onResult(new Result(
            range.instanceId, range.layoutEpoch, range.generation,
            new HistoryExtent(1, 300),
            Collections.singletonList(request == 1
                ? historyEntry(1001, 100, "old")
                : historyEntry(2001, 100, "new"))));
        return () -> {};
      }

      @Override public void close() {}
    });

    runtime.onVisibleHistoryDemand(100, 100, 100, 0, 1);

    assertEquals(2, requests.get());
    assertEquals(0, connection.reconnectCount);
    assertEquals(new LineKey(2001, 1), model.historyCatalog().key(100));
    int historyIndex = model.renderSnapshot().history.findSeqIndex(100);
    assertEquals(
        "new",
        model.renderSnapshot().history.renderLineAt(historyIndex).at(0).text());
  }

  @Test
  public void rapidViewportUpdatesQueueOneActorDrainAndApplyLatestDemand() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(domainBaseline()));
    model.consumeRenderUpdate();
    ManualExecutor actor = new ManualExecutor();
    TerminalSessionRuntime.TimeoutScheduler scheduler = (task, delayMs) -> {};
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "demand-conflation", model, actor, Runnable::run, scheduler, scheduler);
    runtime.enterLiveForTest();
    actor.runAll();

    for (int i = 0; i < 1000; i++) {
      runtime.onVisibleHistoryDemand(100 + i % 100, 119 + i % 100,
          100 + i % 100, 1, 20);
    }

    assertEquals(1, actor.size());
    actor.runAll();
    Map<String, Object> loader = runtime.diagnosticsSnapshot().historyLoader;
    assertEquals(1000L, loader.get("demandReceivedCount"));
    assertEquals(999L, loader.get("demandConflatedCount"));
    assertEquals(1L, loader.get("demandAppliedCount"));
    assertEquals(false, loader.get("hasActiveRequest"));
  }

  @Test
  public void identicalDeliveredViewportDemandDoesNotAllocateAnotherEpoch() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(domainBaseline()));
    model.consumeRenderUpdate();
    TerminalSessionRuntime runtime =
        new TerminalSessionRuntime("demand-dedup", model, Runnable::run);
    runtime.enterLiveForTest();

    runtime.onVisibleHistoryDemand(100, 119, 100, 1, 20);
    runtime.onVisibleHistoryDemand(100, 119, 100, 1, 20);

    Map<String, Object> loader = runtime.diagnosticsSnapshot().historyLoader;
    assertEquals(2L, loader.get("demandReceivedCount"));
    assertEquals(1L, loader.get("demandDeduplicatedCount"));
    assertEquals(1L, loader.get("demandAppliedCount"));
    assertEquals(1L, loader.get("latestDemandEpoch"));
  }

  @Test
  public void distantDemandCancelsOldRequestAndLateCallbackCannotCompleteReplacement() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(domainBaseline()));
    model.consumeRenderUpdate();
    TerminalSessionRuntime.TimeoutScheduler scheduler = (task, delayMs) -> {};
    TerminalSessionRuntime runtime = new TerminalSessionRuntime(
        "range-preemption", model, Runnable::run, Runnable::run, scheduler, scheduler);
    runtime.enterLiveForTest();
    ControlledRangeSource source = new ControlledRangeSource();
    runtime.setHistoryRangeSource(source);

    runtime.onVisibleHistoryDemand(1, 20, 1, -1, 20);
    assertEquals(1, source.requests.size());
    ControlledRangeSource.Pending first = source.requests.get(0);

    runtime.onVisibleHistoryDemand(250, 270, 250, 1, 21);
    assertTrue(first.cancelled.get());
    assertEquals(2, source.requests.size());
    ControlledRangeSource.Pending second = source.requests.get(1);
    assertFalse(second.cancelled.get());

    first.callback.onResult(decodedRange(first.range));
    Map<String, Object> afterLate = runtime.diagnosticsSnapshot().historyLoader;
    assertEquals(second.range.fromSeq, afterLate.get("activeFromSeq"));
    assertEquals(1L, afterLate.get("requestCancelledCount"));
    assertEquals(1L, afterLate.get("requestObsoleteAtCompletionCount"));
    int oldHistoryIndex = model.renderSnapshot().history.findSeqIndex(first.range.fromSeq);
    assertNotNull(model.renderSnapshot().history.renderLineAt(oldHistoryIndex));

    long publicationBefore = model.lastPublicationVersion();
    second.callback.onResult(decodedRange(second.range));
    Map<String, Object> afterCurrent = runtime.diagnosticsSnapshot().historyLoader;
    assertEquals(false, afterCurrent.get("hasActiveRequest"));
    assertEquals(1L, afterCurrent.get("requestUsefulAtCompletionCount"));
    assertTrue(model.lastPublicationVersion() > publicationBefore);
  }

  @Test
  public void stateOnlyHandledDoesNotAdvanceRendered() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("state-wm", model, Runnable::run);
    Object renderConsumer = new Object();
    runtime.registerRenderConsumer(renderConsumer);

    runtime.onRenderPublicationHandled(/*publicationVersion*/ 7L, /*screenRevision*/ 3L, false);

    Map<String, Object> snap = runtime.pipelineMetrics().snapshot();
    assertEquals(7L, snap.get("lastHandledVersion"));
    assertEquals(3L, snap.get("lastHandledScreenRevision"));
    assertEquals(0L, snap.get("lastRenderedVersion"));
    assertEquals(0L, snap.get("lastRenderFailedVersion"));
    assertEquals(1L, snap.get("stateOnlyHandledCount"));
    assertEquals(0L, snap.get("renderSuccessCount"));
  }

  @Test
  public void renderSuccessMaintainsPublishedConsumedHandledRenderedInvariant() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("inv-wm", model, Runnable::run);
    FakeV2Connection connection = connect(runtime);
    Object renderConsumer = new Object();
    runtime.registerRenderConsumer(renderConsumer);

    connection.listener.onScreenMessage(baselineEnvelope(100));
    connection.listener.onScreenMessage(commitEnvelope(100, 101));

    RenderUpdate update = runtime.consumeRenderUpdate(renderConsumer);
    assertNotNull(update);
    runtime.onRenderFrameSucceeded(update.publicationVersion, update.snapshot.screenRevision, 1L);

    Map<String, Object> snap = runtime.pipelineMetrics().snapshot();
    long published = (Long) snap.get("lastPublishedVersion");
    long consumed = (Long) snap.get("lastConsumedVersion");
    long handled = (Long) snap.get("lastHandledVersion");
    long rendered = (Long) snap.get("lastRenderedVersion");
    assertTrue(published >= consumed);
    assertTrue(consumed >= handled);
    assertTrue(handled >= rendered);
    assertEquals(handled, rendered);
  }

  @Test
  public void concurrentSnapshotDuringSuccessNeverShowsRenderedAboveHandled() throws Exception {
    TerminalPipelineMetrics metrics = new TerminalPipelineMetrics();
    ExecutorService pool = Executors.newFixedThreadPool(4);
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean sawViolation = new AtomicBoolean(false);

    Runnable successLoop = () -> {
      try {
        started.await(2, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      for (int n = 0; n < 500; n++) {
        metrics.onRenderFrameSucceeded(n + 1, n + 1, 100L);
      }
    };
    Runnable snapshotLoop = () -> {
      try {
        started.await(2, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      for (int n = 0; n < 2000; n++) {
        Map<String, Object> snap = metrics.snapshot();
        long handled = (Long) snap.get("lastHandledVersion");
        long rendered = (Long) snap.get("lastRenderedVersion");
        if (rendered > handled) {
          sawViolation.set(true);
          return;
        }
      }
    };

    for (int i = 0; i < 3; i++) {
      pool.submit(successLoop);
    }
    pool.submit(snapshotLoop);

    started.countDown();
    pool.shutdown();
    assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    assertFalse(sawViolation.get());
  }

  @Test
  public void renderFailureAdvancesFailedNotHandledOrRendered() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("fail-wm", model, Runnable::run);

    runtime.onRenderFrameFailed(9L, 4L, 100L, new IllegalStateException("secret-detail"));

    Map<String, Object> snap = runtime.pipelineMetrics().snapshot();
    assertEquals(9L, snap.get("lastRenderFailedVersion"));
    assertEquals(4L, snap.get("lastRenderFailedScreenRevision"));
    assertEquals(0L, snap.get("lastRenderedVersion"));
    assertEquals(0L, snap.get("lastHandledVersion"));
    assertEquals(1L, snap.get("renderFailureCount"));
    assertEquals(0L, snap.get("renderSuccessCount"));
  }

  private static byte[] baselineEnvelope(long screenRevision) {
    TerminalScreenV2Proto.LineData line = line(1000, 0, "A");
    TerminalScreenV2Proto.Baseline wire = TerminalScreenV2Proto.Baseline.newBuilder()
        .setSessionId("s1").setInstanceId("i1")
        .setLayoutEpoch(1).setScreenRevision(screenRevision)
        .setDictionaryGeneration(1).setHistoryGeneration(1)
        .setGeometry(TerminalScreenV2Proto.Geometry.newBuilder().setRows(1).setCols(1))
        .setActiveBuffer(TerminalScreenV2Proto.BufferKind.BUFFER_KIND_MAIN)
        .setHistoryExtent(TerminalScreenV2Proto.HistoryExtent.newBuilder()
            .setFirstSeq(1).setLastSeq(0))
        .setScreenLayout(TerminalScreenV2Proto.ScreenLayout.newBuilder().addLineIds(1000))
        .addScreenLines(line)
        .setCursor(TerminalScreenV2Proto.Cursor.newBuilder())
        .setModes(TerminalScreenV2Proto.Modes.newBuilder())
        .setPalette(TerminalScreenV2Proto.TerminalPalette.newBuilder())
        .setDictionary(TerminalScreenV2Proto.Dictionary.newBuilder())
        .build();
    return TerminalScreenV2Proto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(2).setBaseline(wire).build().toByteArray();
  }

  private static byte[] commitEnvelope(long baseRevision, long revision) {
    TerminalScreenV2Proto.TerminalCommit wire = TerminalScreenV2Proto.TerminalCommit.newBuilder()
        .setInstanceId("i1").setLayoutEpoch(1)
        .setDictionaryGeneration(1).setHistoryGeneration(1)
        .setBaseRevision(baseRevision).setRevision(revision)
        .setScreen(TerminalScreenV2Proto.ScreenMutation.newBuilder()
            .addWrites(TerminalScreenV2Proto.ScreenRowWrite.newBuilder()
                .setRow(0).setLine(line(1000 + revision, 0, "C"))))
        .build();
    return TerminalScreenV2Proto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(2).setTerminalCommit(wire).build().toByteArray();
  }

  private static TerminalScreenV2Proto.LineData line(long id, long historySeq, String text) {
    return TerminalScreenV2Proto.LineData.newBuilder()
        .setLineId(id).setLineVersion(1).setHistorySeq(historySeq)
        .setPhysicalColumns(1)
        .setUtf8Text(ByteString.copyFromUtf8(text))
        .setGlyphMeta(ByteString.copyFrom(new byte[] {2}))
        .build();
  }

  private static ScreenBaseline domainBaseline() {
    List<HistoryPush> bindings = new ArrayList<>();
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

  private static HistoryRangeSource.Result decodedRange(
      @NonNull HistoryRangeLoader.Range range) {
    List<HistoryBodyEntry> lines = new ArrayList<>();
    for (long seq = range.fromSeq; seq <= range.toSeq; seq++) {
      lines.add(historyEntry(10_000 + seq, seq, "h"));
    }
    return new HistoryRangeSource.Result(
        range.instanceId, range.layoutEpoch, range.generation,
        new HistoryExtent(1, 300), lines);
  }

  private static HistoryBodyEntry historyEntry(long id, long historySeq, String text) {
    return new HistoryBodyEntry(
        historySeq,
        new LineKey(id, 1),
        new LineBody(1, false, new CellValue[] {
            new CellValue(text, (byte) 1, null, null)
        }));
  }

  private static FakeV2Connection connect(TerminalSessionRuntime runtime) {
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    return connection;
  }

  private static final class FakeV2Connection implements TerminalSessionRuntime.ScreenConnection {
    TerminalSessionRuntime.ScreenConnection.Listener listener;
    int reconnectCount;
    int resyncCount;
    int beginSyncCount;
    boolean lastForceBaseline;
    @Nullable TerminalScreenV2Proto.ResumeToken lastResumeToken;

    @Override public void setListener(@NonNull Listener listener) { this.listener = listener; }
    @Override public boolean beginSync(@Nullable TerminalScreenV2Proto.ResumeToken resume,
                                        boolean forceBaseline) {
      beginSyncCount++;
      lastForceBaseline = forceBaseline;
      lastResumeToken = resume;
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
    @Override public boolean requestResync(
        long layoutEpoch, long screenRevision, @NonNull String reason) {
      resyncCount++;
      return true;
    }
    @Override public void acquireLayout(boolean interactive) {}
    @Override public void releaseLayout() {}
    @Override public void sendClipboardResponse(@NonNull String requestId, boolean allowed,
                                                boolean timeout, @Nullable byte[] data) {}
    @Override public void requestReconnect(@NonNull String reason) { reconnectCount++; }
    @Override public void close() {}
  }

  private static final class ManualExecutor implements Executor {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    @Override public void execute(@NonNull Runnable command) {
      tasks.addLast(command);
    }

    int size() {
      return tasks.size();
    }

    void runAll() {
      while (!tasks.isEmpty()) tasks.removeFirst().run();
    }
  }

  private static final class ControlledScheduler
      implements TerminalSessionRuntime.TimeoutScheduler {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    @Override public void schedule(@NonNull Runnable task, long delayMs) {
      tasks.addLast(task);
    }

    void runNext() {
      assertFalse(tasks.isEmpty());
      tasks.removeFirst().run();
    }

    void runUntil(@NonNull java.util.function.BooleanSupplier condition) {
      while (!condition.getAsBoolean()) {
        runNext();
      }
    }

    void clear() {
      tasks.clear();
    }
  }

  private static final class ControlledRangeSource implements HistoryRangeSource {
    static final class Pending {
      final HistoryRangeLoader.Range range;
      final Callback callback;
      final AtomicBoolean cancelled = new AtomicBoolean();

      Pending(HistoryRangeLoader.Range range, Callback callback) {
        this.range = range;
        this.callback = callback;
      }
    }

    final List<Pending> requests = new ArrayList<>();

    @NonNull @Override public RequestHandle fetch(
        @NonNull HistoryRangeLoader.Range range, @NonNull Callback callback) {
      Pending pending = new Pending(range, callback);
      requests.add(pending);
      return () -> pending.cancelled.set(true);
    }

    @Override public void close() {}
  }
}
