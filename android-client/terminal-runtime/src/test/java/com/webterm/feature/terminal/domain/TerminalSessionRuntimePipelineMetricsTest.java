package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.protobuf.ByteString;
import com.webterm.terminal.model.DictionaryEntries;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.HistoryBodyEntry;
import com.webterm.terminal.model.HistoryLineRef;
import com.webterm.terminal.model.HistoryMutation;
import com.webterm.terminal.model.HistoryPush;
import com.webterm.terminal.model.HistoryRangeResult;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.ScreenLineContent;
import com.webterm.terminal.model.TerminalBufferKind;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;
import com.webterm.terminal.model.TerminalCommit;
import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

/**
 * 屏幕管线水位：Baseline/Commit 推进 decoded/model/published/consumed/handled/rendered；
 * 历史-only 与 requestFullRender 推进 publicationVersion；失败不推进 handled/rendered。
 * 成功路径经 {@link TerminalSessionRuntime#onRenderFrameSucceeded} 原子推进 handled+rendered。
 */
public final class TerminalSessionRuntimePipelineMetricsTest {

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
  public void lateOldRangeAfterWsRebindDoesNotReconnectAndNextRangeLoadsCurrentBody()
      throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(domainBaseline()));
    assertTrue(model.applyTerminalCommit(new TerminalCommit(
        "i1", 1, 1, 2, 1, 1, DictionaryEntries.EMPTY, null, null,
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
    assertEquals(new HistoryLineRef(2001, 1), model.historyIndex().ref(100));
    int historyIndex = model.renderSnapshot().history.findSeqIndex(100);
    assertEquals("new", model.renderSnapshot().history.lineAt(historyIndex).at(0).text);
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

  private static TerminalLine domainLine(long id, long historySeq, String text) {
    return new TerminalLine(id, 1, historySeq, false,
        new TerminalCell[] {new TerminalCell(text, (byte) 1, null, null)});
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
    @Override public void requestReconnect(@NonNull String reason) { reconnectCount++; }
    @Override public void close() {}
  }
}
