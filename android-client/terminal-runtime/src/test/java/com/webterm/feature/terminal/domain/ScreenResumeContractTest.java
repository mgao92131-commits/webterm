package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ResumeToken;
import com.webterm.terminal.protocol.generated.TerminalScreenProto;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 屏幕增量恢复契约测试（计划 §12 Task 1）。
 *
 * <p>同时固化 §15 混合版本兼容行为，以及 Task 5/6 的同步门和原子恢复契约。</p>
 */
public final class ScreenResumeContractTest {

  /**
   * 兼容回归（§15）：旧 Go 可能因 bell 等输出 bump revision 而发送无任何变化字段的
   * 空 patch。必须按 no-op 容忍应用：推进 revision，不触发 resync / reconnect。
   */
  @Test
  public void emptyPatchIsToleratedAsNoOp() {
    QueuingExecutor modelExecutor = new QueuingExecutor();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("s1", new RemoteTerminalModel(),
        modelExecutor, Runnable::run, (task, delayMs) -> {});
    FakeScreenConnection connection = new FakeScreenConnection();
    runtime.attachConnection(connection);

    connection.listener.onScreenMessage(snapshot(1).toByteArray());
    modelExecutor.runAll();
    assertEquals(1, runtime.model().screenRevision);

    // 空 patch：只有 revision 推进（base=1 → screen=2），无任何实际变化字段。
    connection.listener.onScreenMessage(emptyPatch(1, 2).toByteArray());
    modelExecutor.runAll();

    assertEquals("empty patch still advances revision", 2, runtime.model().screenRevision);
    assertEquals("empty patch must not trigger resync", 0, connection.resyncRequests);
    assertEquals("empty patch must not trigger reconnect", 0, connection.reconnectRequests);
  }

  @Test
  public void cumulativeResumePatchAppliedAtomicallyWithRevisionJump() {
    RuntimeFixture fixture = runtimeWithSnapshot(snapshot(100));
    TerminalSessionRuntime runtime = fixture.runtime;
    FakeScreenConnection connection = fixture.connection;

    connection.listener.onScreenMessage(envelope(patch(100, 150)
        .addLineUpdates(terminalLine(1, 2, "z", 0))
        .setTitle("resumed")).toByteArray());

    assertEquals(150, runtime.model().screenRevision);
    assertEquals("z", runtime.model().renderSnapshot().screen[0].at(0).text);
    assertEquals("resumed", runtime.model().title());
    assertEquals(0, connection.resyncRequests);
  }

  @Test
  public void historyWatermarkAppliedAtomicallyIndependentOfHistoryTrimOrder() {
    RuntimeFixture fixture = runtimeWithSnapshot(snapshotWithHistory(100, 100, 101));
    TerminalSessionRuntime runtime = fixture.runtime;
    FakeScreenConnection connection = fixture.connection;

    TerminalScreenProto.ScreenPatch.Builder builder = patch(100, 150)
        .setHistoryTrimBeforeSeq(102)
        .addLineUpdates(historyLine(102, 1, "h102").toBuilder().setHistorySeq(102).build())
        .addHistoryAppendSeqs(102);
    connection.listener.onScreenMessage(envelope(builder).toByteArray());

    assertEquals(150, runtime.model().screenRevision);
    assertEquals(102, runtime.model().firstAvailableHistorySeq());
    assertEquals(1, runtime.model().historySize());
    assertEquals(102, runtime.model().firstCachedHistorySeq());

    // 晚到的旧 HistoryTrim 不得让水位倒退或复活已删除行。
    connection.listener.onScreenMessage(TerminalScreenProto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(1)
        .setHistoryTrim(TerminalScreenProto.HistoryTrim.newBuilder()
            .setLayoutEpoch(1).setFirstAvailableHistorySeq(101))
        .build().toByteArray());
    assertEquals(102, runtime.model().firstAvailableHistorySeq());
    assertEquals(102, runtime.model().firstCachedHistorySeq());
  }

  @Test
  public void historyTrimClearsCachedScrollbackAndAdvancesWatermark() {
    RuntimeFixture fixture = runtimeWithSnapshot(snapshotWithHistory(100, 100, 101));
    TerminalSessionRuntime runtime = fixture.runtime;
    FakeScreenConnection connection = fixture.connection;
    assertEquals(2, runtime.model().historySize());

    connection.listener.onScreenMessage(TerminalScreenProto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(1)
        .setHistoryTrim(TerminalScreenProto.HistoryTrim.newBuilder()
            .setLayoutEpoch(1).setFirstAvailableHistorySeq(102))
        .build().toByteArray());

    assertEquals(102, runtime.model().firstAvailableHistorySeq());
    assertEquals(0, runtime.model().historySize());
    assertTrue(runtime.model().renderSnapshot().history.isEmpty());
  }

  @Test
  public void connectedOnlyAfterResumeAckOrAtomicResumeFrame() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("s1", new RemoteTerminalModel(),
        Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeScreenConnection connection = new FakeScreenConnection();
    runtime.attachConnection(connection);

    connection.listener.onConnected();
    assertEquals(TerminalSessionRuntime.State.SYNCING, runtime.state());
    assertFalse(connection.resumeToken.hasProjection);
    assertEquals("SYNCING must not acquire an interactive lease", 0, connection.acquireRequests);

    connection.listener.onScreenMessage(snapshot(1).toByteArray());
    assertEquals(TerminalSessionRuntime.State.CONNECTED, runtime.state());
    assertEquals(1, connection.acquireRequests);
    assertTrue(runtime.model().projectionHealth().complete);
  }

  @Test
  public void duplicateConnectedCallbackCannotSendSecondHello() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("s1", new RemoteTerminalModel(),
        Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeScreenConnection connection = new FakeScreenConnection();
    runtime.attachConnection(connection);

    connection.listener.onConnected();
    connection.listener.onConnected();

    assertEquals("one logical channel generation must send one screen Hello",
        1, connection.syncRequests);
  }

  @Test
  public void failedHelloSendImmediatelyRequestsFreshChannel() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("s1", new RemoteTerminalModel(),
        Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeScreenConnection connection = new FakeScreenConnection();
    connection.syncSucceeds = false;
    runtime.attachConnection(connection);

    connection.listener.onConnected();

    assertEquals(TerminalSessionRuntime.State.RECONNECTING, runtime.state());
    assertEquals(1, connection.syncRequests);
    assertEquals(1, connection.reconnectRequests);
  }

  @Test
  public void hotPageReattachRefreshesUnhealthyConnectionImmediately() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("s1", new RemoteTerminalModel(),
        Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeScreenConnection connection = new FakeScreenConnection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    assertEquals(TerminalSessionRuntime.State.SYNCING, runtime.state());

    runtime.detachPage();
    runtime.attachPage();

    assertEquals(TerminalSessionRuntime.State.RECONNECTING, runtime.state());
    assertEquals("reattach must not wait for the stale sync timeout", 1,
        connection.reconnectRequests);
  }

  @Test
  public void hotPageReattachKeepsHealthyConnection() {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("s1", new RemoteTerminalModel(),
        Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeScreenConnection connection = new FakeScreenConnection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(snapshot(1).toByteArray());
    assertEquals(TerminalSessionRuntime.State.CONNECTED, runtime.state());

    runtime.detachPage();
    runtime.attachPage();

    assertEquals(TerminalSessionRuntime.State.CONNECTED, runtime.state());
    assertEquals("healthy HOT reuse must remain seamless", 0, connection.reconnectRequests);
  }

  @Test
  public void staleQueuedSynchronizationCannotSendHelloAfterReconnect() {
    QueuingExecutor modelExecutor = new QueuingExecutor();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("s1", new RemoteTerminalModel(),
        modelExecutor, Runnable::run, (task, delayMs) -> {});
    FakeScreenConnection connection = new FakeScreenConnection();
    runtime.attachConnection(connection);

    // 首次 connected 只把同步任务排入 model executor；任务尚未真正发送 Hello。
    connection.listener.onConnected();
    // 在旧同步任务执行前物理 mux 断开并恢复。旧任务必须被连接代际作废，
    // 只能由新一代 connected 对应的同步任务发送一次 Hello。
    connection.listener.onDisconnected("mux reconnect");
    connection.listener.onConnected();
    modelExecutor.runAll();

    assertEquals("only the current connection epoch may send screen Hello",
        1, connection.syncRequests);
    assertEquals(TerminalSessionRuntime.State.SYNCING, runtime.state());
  }

  @Test
  public void staleQueuedScreenFrameCannotCompleteNewConnection() {
    QueuingExecutor modelExecutor = new QueuingExecutor();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("s1", new RemoteTerminalModel(),
        modelExecutor, Runnable::run, (task, delayMs) -> {});
    FakeScreenConnection connection = new FakeScreenConnection();
    runtime.attachConnection(connection);

    connection.listener.onConnected();
    connection.listener.onScreenMessage(snapshot(1).toByteArray());
    connection.listener.onDisconnected("mux reconnect");
    connection.listener.onConnected();
    modelExecutor.runAll();

    assertEquals("old snapshot must not complete the new connection", 0,
        runtime.model().screenRevision);
    assertEquals(TerminalSessionRuntime.State.SYNCING, runtime.state());

    connection.listener.onScreenMessage(snapshot(2).toByteArray());
    modelExecutor.runAll();
    assertEquals(2, runtime.model().screenRevision);
    assertEquals(TerminalSessionRuntime.State.CONNECTED, runtime.state());
  }

  @Test
  public void validationFailureRejectsWholeFrameAndSendsSingleResyncRequest() {
    RuntimeFixture fixture = runtimeWithSnapshot(snapshot(1));
    TerminalSessionRuntime runtime = fixture.runtime;
    FakeScreenConnection connection = fixture.connection;

    // style 7 未在已有字典或 new_styles 中定义；title 也不得部分落地。
    byte[] invalid = envelope(patch(1, 2)
        .addLineUpdates(terminalLine(1, 2, "x", 7))
        .setTitle("must-not-commit")).toByteArray();
    connection.listener.onScreenMessage(invalid);
    connection.listener.onScreenMessage(invalid);

    assertEquals(1, runtime.model().screenRevision);
    assertEquals("", runtime.model().title());
    assertEquals(0, runtime.model().styles().size());
    assertEquals(1, connection.resyncRequests);

    // 围栏期间合法 Patch 也被丢弃；只有权威 Snapshot 能解除。
    connection.listener.onScreenMessage(envelope(patch(1, 2).setTitle("ignored")).toByteArray());
    assertEquals(1, runtime.model().screenRevision);
    connection.listener.onScreenMessage(snapshot(2).toByteArray());
    assertEquals(2, runtime.model().screenRevision);
    assertEquals(1, connection.resyncRequests);
  }

  @Test
  public void detachedEffectStillDeliveredToPersistentSink() {
    RuntimeFixture fixture = runtimeWithSnapshot(snapshot(1));
    int[] deliveries = {0};
    fixture.runtime.setEffectSink((runtime, effect, hasPageListener) -> {
      assertFalse(hasPageListener);
      assertEquals(TerminalScreenEffect.Type.NOTIFICATION, effect.type());
      deliveries[0]++;
    });

    fixture.connection.listener.onScreenMessage(TerminalScreenProto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(1)
        .setEffect(TerminalScreenProto.TerminalEffect.newBuilder()
            .setInstanceId("i1")
            .setScreenRevision(1)
            .setNotification(TerminalScreenProto.DesktopNotification.newBuilder()
                .setTitle("done").setBody("body")))
        .build().toByteArray());

    assertEquals(1, deliveries[0]);
  }

  private static RuntimeFixture runtimeWithSnapshot(
      TerminalScreenProto.ScreenEnvelope snapshot) {
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("s1", new RemoteTerminalModel(),
        Runnable::run, Runnable::run, (task, delayMs) -> {});
    FakeScreenConnection connection = new FakeScreenConnection();
    runtime.attachConnection(connection);
    connection.listener.onScreenMessage(snapshot.toByteArray());
    return new RuntimeFixture(runtime, connection);
  }

  private static TerminalScreenProto.ScreenEnvelope snapshot(long revision) {
    return TerminalScreenProto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(1)
        .setSnapshot(TestScreenFrames.snapshotBuilder(revision).build())
        .build();
  }

  private static TerminalScreenProto.ScreenEnvelope snapshotWithHistory(
      long revision, long firstSeq, long lastSeq) {
    TerminalScreenProto.ScreenSnapshot.Builder snapshot = TestScreenFrames.snapshotBuilder(revision)
        .setFirstAvailableHistorySeq(firstSeq);
    for (long id = firstSeq; id <= lastSeq; id++) {
      snapshot.addHistoryTailSeqs(id);
      snapshot.addHistoryTailLines(historyLine(id, 1, "h" + id).toBuilder()
          .setHistorySeq(id).build());
    }
    return TerminalScreenProto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(1)
        .setSnapshot(snapshot)
        .build();
  }

  private static TerminalScreenProto.ScreenPatch.Builder patch(long baseRevision,
                                                                long screenRevision) {
    return TerminalScreenProto.ScreenPatch.newBuilder()
        .setInstanceId("i1")
        .setLayoutEpoch(1)
        .setBaseRevision(baseRevision)
        .setScreenRevision(screenRevision);
  }

  private static TerminalScreenProto.ScreenEnvelope envelope(
      TerminalScreenProto.ScreenPatch.Builder patch) {
    return TerminalScreenProto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(1)
        .setPatch(patch)
        .build();
  }

  private static TerminalScreenProto.LineData terminalLine(long id, long version, String text, int styleId) {
    return TestScreenFrames.line(id, version, text, styleId);
  }

  private static TerminalScreenProto.LineData historyLine(long id, long version, String text) {
    return TestScreenFrames.line(id, version, text);
  }

  private static final class RuntimeFixture {
    final TerminalSessionRuntime runtime;
    final FakeScreenConnection connection;

    RuntimeFixture(TerminalSessionRuntime runtime, FakeScreenConnection connection) {
      this.runtime = runtime;
      this.connection = connection;
    }
  }

  private static TerminalScreenProto.ScreenEnvelope emptyPatch(long baseRevision,
                                                               long screenRevision) {
    return TerminalScreenProto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(1)
        .setPatch(TerminalScreenProto.ScreenPatch.newBuilder()
            .setInstanceId("i1")
            .setLayoutEpoch(1)
            .setBaseRevision(baseRevision)
            .setScreenRevision(screenRevision)
            .build())
        .build();
  }

  private static final class FakeScreenConnection implements TerminalSessionRuntime.ScreenConnection {
    int resyncRequests;
    int reconnectRequests;
    int acquireRequests;
    int syncRequests;
    boolean syncSucceeds = true;
    ResumeToken resumeToken = ResumeToken.cold(0);
    Listener listener;

    @Override
    public void setListener(@NonNull Listener listener) {
      this.listener = listener;
    }

    @Override
    public boolean beginSync(@NonNull ResumeToken resumeToken) {
      syncRequests++;
      this.resumeToken = resumeToken;
      return syncSucceeds;
    }

    @Override
    public void setLayoutLeaseId(@NonNull String leaseId) {}

    @Override
    public void sendTextInput(@NonNull String text) {}

    @Override
    public void sendPasteInput(@NonNull String text) {}

    @Override
    public void sendKeyInput(@NonNull String key, boolean shift, boolean alt, boolean ctrl,
                             boolean meta, boolean pressed) {}

    @Override
    public void sendMouseInput(int row, int col, @NonNull String button, int wheelDelta,
                               boolean shift, boolean alt, boolean ctrl, boolean meta,
                               boolean pressed) {}

    @Override
    public void sendFocusInput(boolean focused) {}

    @Override
    public boolean requestResize(int cols, int rows) { return true; }

    @Override
    public boolean requestHistoryPage(@NonNull String requestId, long beforeHistorySeq, int limit) {
      return true;
    }

    @Override
    public void requestResync(long layoutEpoch, long screenRevision, @NonNull String reason) {
      resyncRequests++;
    }

    @Override
    public void requestReconnect(@NonNull String reason) {
      reconnectRequests++;
    }

    @Override
    public void acquireLayout(boolean interactive) { acquireRequests++; }

    @Override
    public void releaseLayout() {}

    @Override
    public void sendClipboardResponse(@NonNull String requestId, boolean allowed, boolean timeout,
                                      @Nullable byte[] data) {}

    @Override
    public void close() {}
  }

  private static final class QueuingExecutor implements Executor {
    final List<Runnable> tasks = new ArrayList<>();

    @Override
    public void execute(@NonNull Runnable command) {
      tasks.add(command);
    }

    void runAll() {
      while (!tasks.isEmpty()) {
        tasks.remove(0).run();
      }
    }
  }
}
