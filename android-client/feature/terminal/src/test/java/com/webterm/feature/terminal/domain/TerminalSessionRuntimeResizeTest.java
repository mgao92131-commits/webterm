package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.protocol.generated.TerminalScreenProto;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 覆盖 resize 的投递保证：租约未授予时的请求必须缓存并在授予后补发，
 * 断线期间的请求必须等重连拿到新租约后补发，不能静默丢失。
 */
public final class TerminalSessionRuntimeResizeTest {

  private TerminalSessionRuntime runtime;
  private FakeScreenConnection connection;

  @Before
  public void setUp() {
    // 同步 executor：handleScreenMessage 直接在当前线程执行，断言无需等待。
    runtime = new TerminalSessionRuntime("s1", new RemoteTerminalModel(),
        Runnable::run, Runnable::run);
    connection = new FakeScreenConnection();
    runtime.attachConnection(connection);
  }

  @Test
  public void requestResizeBeforeLeaseGranted_isFlushedOnGrant() {
    runtime.requestResize(120, 40);
    assertTrue("租约授予前不应发送 resize", connection.resizes.isEmpty());

    grantLease("lease-1");

    assertEquals("lease-1", connection.leaseId);
    assertEquals(1, connection.resizes.size());
    assertEquals(120, connection.resizes.get(0)[0]);
    assertEquals(40, connection.resizes.get(0)[1]);
    assertTrue(runtime.hasLayoutLease());
  }

  @Test
  public void requestResizeDuringDisconnect_isFlushedAfterRegrant() {
    grantLease("lease-1");
    runtime.requestResize(120, 40);
    assertEquals(1, connection.resizes.size());

    connection.listener.onDisconnected("relay lost");
    assertFalse("断线后租约应失效", runtime.hasLayoutLease());

    runtime.requestResize(130, 50);
    assertEquals("断线期间的 resize 不应丢进死通道", 1, connection.resizes.size());

    grantLease("lease-2");
    assertEquals("lease-2", connection.leaseId);
    assertEquals("拿到新租约后应补发最新尺寸", 2, connection.resizes.size());
    assertEquals(130, connection.resizes.get(1)[0]);
    assertEquals(50, connection.resizes.get(1)[1]);
  }

  @Test
  public void requestResizeWithNonPositiveSize_isIgnored() {
    runtime.requestResize(0, 0);
    grantLease("lease-1");
    assertTrue(connection.resizes.isEmpty());
  }

  @Test
  public void revisionGap_requestsOneResyncUntilAuthoritativeSnapshotArrives() {
    connection.listener.onScreenMessage(snapshot(1).toByteArray());

    // Both patches are based on revision 0 while the model is at revision 1.
    // The first requests recovery; the second must not amplify it.
    TerminalScreenProto.ScreenEnvelope stalePatch = TerminalScreenProto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(1)
        .setPatch(TerminalScreenProto.ScreenPatch.newBuilder()
            .setInstanceId("i1")
            .setLayoutEpoch(1)
            .setBaseRevision(0)
            .setScreenRevision(2)
            .build())
        .build();
    connection.listener.onScreenMessage(stalePatch.toByteArray());
    connection.listener.onScreenMessage(stalePatch.toByteArray());
    assertEquals(1, connection.resyncRequests);

    connection.listener.onScreenMessage(snapshot(2).toByteArray());
    connection.listener.onScreenMessage(stalePatch.toByteArray());
    assertEquals("new snapshot releases the recovery fence", 2, connection.resyncRequests);
  }

  @Test
  public void historyPage_isAppliedOnlyForTheOutstandingRequest() {
    connection.listener.onScreenMessage(snapshot(1).toByteArray());
    runtime.requestHistoryPage(100, 250);

    connection.listener.onScreenMessage(historyPage("stale", 42).toByteArray());
    assertEquals(0, runtime.model().firstAvailableHistoryId());

    connection.listener.onScreenMessage(historyPage(connection.historyRequestId, 42).toByteArray());
    assertEquals(42, runtime.model().firstAvailableHistoryId());
  }

  @Test
  public void excessiveQueuedFrames_areCollapsedAndRecoveredByOneSnapshot() {
    QueuingExecutor modelExecutor = new QueuingExecutor();
    runtime = new TerminalSessionRuntime("s1", new RemoteTerminalModel(), modelExecutor, Runnable::run);
    connection = new FakeScreenConnection();
    runtime.attachConnection(connection);

    // The mailbox accepts a bounded burst. The next frame must discard stale
    // work and request one authoritative resync instead of growing the
    // executor queue without bound.
    for (int revision = 1; revision <= 65; revision++) {
      connection.listener.onScreenMessage(snapshot(revision).toByteArray());
    }

    assertEquals(1, connection.resyncRequests);
    assertEquals("one mailbox drain runnable serves the whole burst", 1, modelExecutor.tasks.size());

    modelExecutor.runAll();
    assertEquals(65, runtime.model().screenRevision);
  }

  private static TerminalScreenProto.ScreenEnvelope snapshot(long revision) {
    return TerminalScreenProto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(1)
        .setSnapshot(TerminalScreenProto.ScreenSnapshot.newBuilder()
            .setSessionId("s1")
            .setInstanceId("i1")
            .setLayoutEpoch(1)
            .setScreenRevision(revision)
            .setGeometry(TerminalScreenProto.Size.newBuilder().setRows(5).setCols(10).build())
            .setHistory(TerminalScreenProto.HistoryWindow.getDefaultInstance())
            .build())
        .build();
  }

  private static TerminalScreenProto.ScreenEnvelope historyPage(@NonNull String requestId,
                                                                 long firstAvailableLineId) {
    return TerminalScreenProto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(1)
        .setHistoryPage(TerminalScreenProto.HistoryPage.newBuilder()
            .setRequestId(requestId)
            .setLayoutEpoch(1)
            .setAsOfRevision(1)
            .setFirstAvailableLineId(firstAvailableLineId)
            .setHasMoreBefore(false)
            .build())
        .build();
  }

  private void grantLease(@NonNull String leaseId) {
    TerminalScreenProto.ScreenEnvelope envelope = TerminalScreenProto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(1)
        .setLayoutLease(TerminalScreenProto.LayoutLease.newBuilder()
            .setLeaseId(leaseId)
            .setGranted(true)
            .build())
        .build();
    connection.listener.onScreenMessage(envelope.toByteArray());
  }

  private static final class FakeScreenConnection implements TerminalSessionRuntime.ScreenConnection {
    final List<int[]> resizes = new ArrayList<>();
    int resyncRequests;
    String historyRequestId = "";
    String leaseId = "";
    Listener listener;

    @Override
    public void setListener(@NonNull Listener listener) {
      this.listener = listener;
    }

    @Override
    public void setLayoutLeaseId(@NonNull String leaseId) {
      this.leaseId = leaseId;
    }

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
    public void requestResize(int cols, int rows) {
      resizes.add(new int[]{cols, rows});
    }

    @Override
    public void requestHistoryPage(@NonNull String requestId, long beforeLineId, int limit) {
      historyRequestId = requestId;
    }

    @Override
    public void requestResync(long layoutEpoch, long screenRevision, @NonNull String reason) {
      resyncRequests++;
    }

    @Override
    public void acquireLayout(boolean interactive) {}

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
