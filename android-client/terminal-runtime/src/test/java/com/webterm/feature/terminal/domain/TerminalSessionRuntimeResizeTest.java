package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ResumeToken;
import com.webterm.terminal.protocol.generated.TerminalScreenProto;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 覆盖 resize 的投递保证：租约未授予时的请求必须缓存并在授予后补发，
 * 断线期间的请求必须等重连拿到新租约后补发，不能静默丢失。
 * 同时覆盖 §6 的 resync 可恢复状态机：重试退避、generation 过期、
 * 围栏解除、mailbox 二次溢出与超限后的 channel 重建。
 */
public final class TerminalSessionRuntimeResizeTest {

  private TerminalSessionRuntime runtime;
  private FakeScreenConnection connection;
  private FakeTimeoutScheduler scheduler;
  private FakeTimeoutScheduler leaseScheduler;

  @Before
  public void setUp() {
    // 同步 executor：handleScreenMessage 直接在当前线程执行，断言无需等待。
    // timeout 用可注入的假调度器，测试手动推进，不做真实 sleep。
    scheduler = new FakeTimeoutScheduler();
    leaseScheduler = new FakeTimeoutScheduler();
    runtime = new TerminalSessionRuntime("s1", new RemoteTerminalModel(),
        Runnable::run, Runnable::run, scheduler, leaseScheduler);
    connection = new FakeScreenConnection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    // 本测试类手动注入 snapshot，关注的是 snapshot 之后的 resize/resync 时序；
    // 初始同步超时由 ScreenResumeContractTest 单独覆盖，避免它占用这里的队首任务。
    scheduler.tasks.clear();
    scheduler.delays.clear();
  }

  @Test
  public void requestResizeBeforeLeaseGranted_isFlushedOnGrant() {
    connection.listener.onScreenMessage(snapshot(1).toByteArray());
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
    connection.listener.onScreenMessage(snapshot(1).toByteArray());
    grantLease("lease-1");
    runtime.requestResize(120, 40);
    assertEquals(1, connection.resizes.size());

    connection.listener.onDisconnected("relay lost");
    assertFalse("断线后租约应失效", runtime.hasLayoutLease());

    runtime.requestResize(130, 50);
    assertEquals("断线期间的 resize 不应丢进死通道", 1, connection.resizes.size());

    connection.listener.onConnected();
    connection.listener.onScreenMessage(snapshot(2).toByteArray());
    grantLease("lease-2");
    assertEquals("lease-2", connection.leaseId);
    assertEquals("拿到新租约后应补发最新尺寸", 2, connection.resizes.size());
    assertEquals(130, connection.resizes.get(1)[0]);
    assertEquals(50, connection.resizes.get(1)[1]);
  }

  @Test
  public void deniedLayoutLease_retriesAndRecoversWithoutReconnect() {
    connection.listener.onScreenMessage(snapshot(1).toByteArray());
    assertEquals(1, connection.layoutRequestIds.size());
    String firstRequest = connection.layoutRequestIds.get(0);

    respondLease(firstRequest, "", false, 0L);
    assertFalse(runtime.hasLayoutLease());

    // 初始请求 timeout 已经排队但会因 request id 不再 pending 而失效；随后重试。
    leaseScheduler.runNext();
    leaseScheduler.runNext();
    assertEquals(2, connection.layoutRequestIds.size());
    String retryRequest = connection.layoutRequestIds.get(1);

    respondLease(retryRequest, "lease-recovered", true,
        System.currentTimeMillis() + 300_000L);
    assertTrue(runtime.hasLayoutLease());
    assertEquals("lease-recovered", connection.leaseId);
    assertEquals("Lease 恢复不应重建 screen channel", 0, connection.reconnectRequests);
  }

  @Test
  public void staleLayoutLeaseResponse_cannotOverrideCurrentRequest() {
    connection.listener.onScreenMessage(snapshot(1).toByteArray());
    String firstRequest = connection.layoutRequestIds.get(0);
    respondLease(firstRequest, "", false, 0L);
    leaseScheduler.runNext();
    leaseScheduler.runNext();
    String secondRequest = connection.layoutRequestIds.get(1);

    respondLease(firstRequest, "lease-stale", true,
        System.currentTimeMillis() + 300_000L);
    assertFalse("迟到的旧响应不能授予租约", runtime.hasLayoutLease());

    respondLease(secondRequest, "lease-current", true,
        System.currentTimeMillis() + 300_000L);
    assertTrue(runtime.hasLayoutLease());
    assertEquals("lease-current", connection.leaseId);
  }

  @Test
  public void detachCancelsPendingLayoutLeaseRetry() {
    connection.listener.onScreenMessage(snapshot(1).toByteArray());
    respondLease(connection.layoutRequestIds.get(0), "", false, 0L);
    runtime.detachPage();

    leaseScheduler.runNext();
    leaseScheduler.runNext();
    assertEquals("页面离开后不得继续申请交互租约", 1, connection.layoutRequestIds.size());
    assertFalse(runtime.hasLayoutLease());
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
    TerminalScreenProto.ScreenEnvelope stalePatch = patch(0, 2);
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
    assertTrue(runtime.requestHistoryPage(100, 250));

    connection.listener.onScreenMessage(historyPage("stale", 42).toByteArray());
    assertEquals(0, runtime.model().firstAvailableHistoryId());

    connection.listener.onScreenMessage(historyPage(connection.historyRequestId, 42).toByteArray());
    assertEquals(42, runtime.model().firstAvailableHistoryId());
  }

  @Test
  public void rejectedHistoryPageClearsOnlyThePagingRequestWithoutResync() {
    connection.listener.onScreenMessage(snapshot(1).toByteArray());
    assertTrue(runtime.requestHistoryPage(100, 250));
    String rejectedRequestId = connection.historyRequestId;

    connection.listener.onScreenMessage(invalidHistoryPage(rejectedRequestId).toByteArray());

    assertEquals("history page validation must not resync the screen", 0, connection.resyncRequests);
    assertTrue("a rejected page ends its pending request so the user can retry",
        runtime.requestHistoryPage(100, 250));
  }

  @Test
  public void requestHistoryPageWithoutConnection_failsAndLeavesNoPendingRequest() {
    TerminalSessionRuntime disconnected = new TerminalSessionRuntime("s2",
        new RemoteTerminalModel(), Runnable::run, Runnable::run, scheduler);
    assertFalse("无连接时必须返回失败", disconnected.requestHistoryPage(100, 250));

    // 失败的请求不得留下 pending id：之后到达的 HISTORY_PAGE 必须被丢弃，
    // 而不是被错误地 prepend 进新的视口。
    FakeScreenConnection lateConnection = new FakeScreenConnection();
    disconnected.attachConnection(lateConnection);
    lateConnection.listener.onScreenMessage(snapshot(1).toByteArray());
    lateConnection.listener.onScreenMessage(historyPage("h-1", 42).toByteArray());
    assertEquals(0, disconnected.model().firstAvailableHistoryId());
  }

  @Test
  public void rejectedHistoryRequest_failsAndLeavesNoPendingRequest() {
    connection.listener.onScreenMessage(snapshot(1).toByteArray());
    connection.historyRequestAccepted = false;

    assertFalse("底层发送失败必须传递给调用方", runtime.requestHistoryPage(100, 250));

    // 发送失败的请求不得登记为 pending；同一 request id 的迟到/伪造响应
    // 也不能被误当成有效分页结果应用。
    connection.listener.onScreenMessage(historyPage(connection.historyRequestId, 42).toByteArray());
    assertEquals(0, runtime.model().firstAvailableHistoryId());
  }

  @Test
  public void authenticationRequired_notifiesOwnerWithoutClosingRuntime() {
    List<String> reasons = new ArrayList<>();
    runtime.setAuthenticationListener(reasons::add);
    connection.listener.onScreenMessage(snapshot(1).toByteArray());
    assertTrue(runtime.requestHistoryPage(100, 250));
    String expiredRequestId = connection.historyRequestId;

    connection.listener.onAuthenticationRequired("cookie expired");

    assertEquals(TerminalSessionRuntime.State.RECONNECTING, runtime.state());
    assertEquals(Arrays.asList("cookie expired"), reasons);
    connection.listener.onScreenMessage(historyPage(expiredRequestId, 42).toByteArray());
    assertEquals("认证失效必须清除旧 channel 的 history pending", 0,
        runtime.model().firstAvailableHistoryId());
  }

  @Test
  public void pendingHistoryRequest_isClearedBySnapshotAndReconnect() {
    connection.listener.onScreenMessage(snapshot(1).toByteArray());
    assertTrue(runtime.requestHistoryPage(100, 250));

    // 权威 snapshot 替换本地投影：旧请求 id 的迟到响应必须被丢弃。
    connection.listener.onScreenMessage(snapshot(2).toByteArray());
    connection.listener.onScreenMessage(historyPage(connection.historyRequestId, 42).toByteArray());
    assertEquals(0, runtime.model().firstAvailableHistoryId());

    // 断线同样清理 pending history，重连后不会被迟到响应污染。
    assertTrue(runtime.requestHistoryPage(100, 250));
    connection.listener.onDisconnected("relay lost");
    connection.listener.onScreenMessage(historyPage(connection.historyRequestId, 42).toByteArray());
    assertEquals(0, runtime.model().firstAvailableHistoryId());
  }

  @Test
  public void excessiveQueuedFrames_areCollapsedAndRecoveredByOneSnapshot() {
    QueuingExecutor modelExecutor = new QueuingExecutor();
    runtime = new TerminalSessionRuntime("s1", new RemoteTerminalModel(),
        modelExecutor, Runnable::run, scheduler);
    connection = new FakeScreenConnection();
    runtime.attachConnection(connection);

    // The mailbox accepts a bounded burst. The next frame must discard stale
    // work and escalate to the model executor, which requests one authoritative
    // resync instead of growing the executor queue without bound.
    for (int revision = 1; revision <= 65; revision++) {
      connection.listener.onScreenMessage(snapshot(revision).toByteArray());
    }

    assertEquals("resync decisions belong to the model executor", 0, connection.resyncRequests);
    assertEquals("the drain owns the overflow fence ordering",
        1, modelExecutor.tasks.size());

    modelExecutor.runAll();
    assertEquals("the newest authoritative snapshot survives the overflow", 65,
        runtime.model().screenRevision);
    assertEquals("overflow still enters one fenced recovery", 1, connection.resyncRequests);

    connection.listener.onScreenMessage(snapshot(66).toByteArray());
    modelExecutor.runAll();
    assertEquals("a fresh authoritative snapshot releases the fence",
        66, runtime.model().screenRevision);
  }

  @Test
  public void mailboxDrainYieldsToQueuedControlWorkAfterOneSlice() {
    QueuingExecutor modelExecutor = new QueuingExecutor();
    runtime = new TerminalSessionRuntime("s1", new RemoteTerminalModel(),
        modelExecutor, Runnable::run, scheduler);
    connection = new FakeScreenConnection();
    runtime.attachConnection(connection);

    connection.listener.onScreenMessage(snapshot(1).toByteArray());
    for (int revision = 2; revision <= 17; revision++) {
      connection.listener.onScreenMessage(patch(revision - 1, revision).toByteArray());
    }
    final boolean[] controlRan = {false};
    modelExecutor.execute(() -> controlRan[0] = true);

    modelExecutor.runNext();
    assertEquals("one drain slice processes at most eight frames", 8, runtime.model().screenRevision);
    assertFalse("the control task is still queued behind the first drain slice", controlRan[0]);

    modelExecutor.runNext();
    assertTrue("the continuation must yield so control work can run", controlRan[0]);
    modelExecutor.runAll();
    assertEquals("all remaining patches converge after yielded continuations", 17,
        runtime.model().screenRevision);
  }

  @Test
  public void oversizedFrameIsRejectedBeforeMailboxRetention() {
    QueuingExecutor modelExecutor = new QueuingExecutor();
    runtime = new TerminalSessionRuntime("s1", new RemoteTerminalModel(),
        modelExecutor, Runnable::run, scheduler);
    connection = new FakeScreenConnection();
    runtime.attachConnection(connection);

    connection.listener.onScreenMessage(new byte[2 * 1024 * 1024 + 1]);
    assertEquals(1, modelExecutor.tasks.size());
    modelExecutor.runAll();
    assertEquals(0, runtime.model().screenRevision);
    assertEquals(1, connection.resyncRequests);
  }

  @Test
  public void byteBudgetOverflowDropsBrokenChainUntilFreshSnapshot() {
    QueuingExecutor modelExecutor = new QueuingExecutor();
    runtime = new TerminalSessionRuntime("s1", new RemoteTerminalModel(),
        modelExecutor, Runnable::run, scheduler);
    connection = new FakeScreenConnection();
    runtime.attachConnection(connection);
    String largeTitle = new String(new char[1536 * 1024]).replace('\0', 'x');
    for (int revision = 1; revision <= 3; revision++) {
      TerminalScreenProto.ScreenEnvelope large = snapshot(revision).toBuilder()
          .setSnapshot(snapshot(revision).getSnapshot().toBuilder().setTitle(largeTitle))
          .build();
      connection.listener.onScreenMessage(large.toByteArray());
    }

    modelExecutor.runAll();
    assertEquals("overflow-triggering state is not authoritative", 0,
        runtime.model().screenRevision);
    assertEquals(1, connection.resyncRequests);
    connection.listener.onScreenMessage(snapshot(4).toByteArray());
    modelExecutor.runAll();
    assertEquals(4, runtime.model().screenRevision);
  }

  @Test
  public void invalidSnapshotWhileWaiting_resendsResyncWithBackoff() {
    connection.listener.onScreenMessage(snapshot(1).toByteArray());
    connection.listener.onScreenMessage(patch(0, 2).toByteArray());
    assertEquals(1, connection.resyncRequests);

    // An invalid snapshot must not silently drop: schedule a bounded retry.
    connection.listener.onScreenMessage(invalidSnapshot().toByteArray());
    assertEquals("retry is scheduled, not sent immediately", 1, connection.resyncRequests);
    assertEquals(Arrays.asList(2000L, 1000L), scheduler.delays);

    // The stale wait timeout (old generation) must not double-send.
    scheduler.runNext();
    assertEquals(1, connection.resyncRequests);

    // The retry runnable resends resync and re-arms the wait timeout.
    scheduler.runNext();
    assertEquals(2, connection.resyncRequests);
    assertEquals(Arrays.asList(2000L, 1000L, 2000L), scheduler.delays);
  }

  @Test
  public void resyncWaitTimeout_retriesUpToLimitThenReconnectsOnce() {
    connection.listener.onScreenMessage(snapshot(1).toByteArray());
    connection.listener.onScreenMessage(patch(0, 2).toByteArray());
    assertEquals(1, connection.resyncRequests);

    // wait timeout -> retry(1s) -> resend; repeated with 2s/4s backoff.
    scheduler.runNext(); // wait timeout 1 -> schedule retry 1
    scheduler.runNext(); // retry 1 fires
    assertEquals(2, connection.resyncRequests);
    scheduler.runNext(); // wait timeout 2 -> schedule retry 2
    scheduler.runNext(); // retry 2 fires
    assertEquals(3, connection.resyncRequests);
    scheduler.runNext(); // wait timeout 3 -> schedule retry 3
    scheduler.runNext(); // retry 3 fires
    assertEquals(4, connection.resyncRequests);
    assertEquals("1s/2s/4s bounded backoff between resends",
        Arrays.asList(2000L, 1000L, 2000L, 2000L, 2000L, 4000L, 2000L), scheduler.delays);

    scheduler.runNext(); // final wait timeout -> retries exhausted
    assertEquals("retries exhausted: escalate to channel rebuild", 1, connection.reconnectRequests);
    assertEquals(4, connection.resyncRequests);
    assertEquals("channel rebuild must enter reconnecting before the new ACK",
        TerminalSessionRuntime.State.RECONNECTING, runtime.state());

    connection.listener.onConnected();
    assertEquals("new channel ACK must be allowed to start a fresh synchronization",
        TerminalSessionRuntime.State.SYNCING, runtime.state());

    // Once escalated, further gaps or invalid snapshots must not amplify.
    connection.listener.onScreenMessage(patch(0, 2).toByteArray());
    connection.listener.onScreenMessage(invalidSnapshot().toByteArray());
    assertEquals("reconnect is requested exactly once", 1, connection.reconnectRequests);
    assertEquals(4, connection.resyncRequests);
  }

  @Test
  public void staleTimeoutAfterDisconnect_doesNotAffectNewConnection() {
    connection.listener.onScreenMessage(snapshot(1).toByteArray());
    connection.listener.onScreenMessage(patch(0, 2).toByteArray());
    assertEquals(1, connection.resyncRequests);

    // Disconnect cancels the pending timeout by advancing the generation.
    connection.listener.onDisconnected("relay lost");
    scheduler.runNext();
    assertEquals("old timeout must be discarded after disconnect", 1, connection.resyncRequests);
    assertEquals(0, connection.reconnectRequests);

    // The reconnected session can still start a fresh recovery.
    connection.listener.onConnected();
    connection.listener.onScreenMessage(snapshot(2).toByteArray());
    connection.listener.onScreenMessage(patch(0, 3).toByteArray());
    assertEquals("new connection recovers independently", 2, connection.resyncRequests);
  }

  @Test
  public void validSnapshot_releasesFenceAndSubsequentPatchesApply() {
    connection.listener.onScreenMessage(snapshot(1).toByteArray());
    connection.listener.onScreenMessage(patch(0, 2).toByteArray());
    assertEquals(1, connection.resyncRequests);

    connection.listener.onScreenMessage(snapshot(2).toByteArray());
    assertEquals(2, runtime.model().screenRevision);

    connection.listener.onScreenMessage(patch(2, 3).toByteArray());
    assertEquals("patch after fence release applies normally", 3, runtime.model().screenRevision);
    assertEquals("no spurious resync after recovery", 1, connection.resyncRequests);
  }

  @Test
  public void patchDuringResync_isDroppedAndDoesNotAdvanceRevision() {
    connection.listener.onScreenMessage(snapshot(1).toByteArray());
    connection.listener.onScreenMessage(patch(0, 2).toByteArray());
    assertEquals(1, connection.resyncRequests);

    // This patch would apply cleanly (base revision matches), but the fence
    // must hold until an authoritative snapshot arrives.
    connection.listener.onScreenMessage(patch(1, 2).toByteArray());
    assertEquals("fenced patch must not advance local revision", 1, runtime.model().screenRevision);

    connection.listener.onScreenMessage(snapshot(3).toByteArray());
    connection.listener.onScreenMessage(patch(3, 4).toByteArray());
    assertEquals(4, runtime.model().screenRevision);
  }

  @Test
  public void mailboxOverflowDuringResync_resendsResyncAndDoesNotFreeze() {
    QueuingExecutor modelExecutor = new QueuingExecutor();
    runtime = new TerminalSessionRuntime("s1", new RemoteTerminalModel(),
        modelExecutor, Runnable::run, scheduler);
    connection = new FakeScreenConnection();
    runtime.attachConnection(connection);

    connection.listener.onScreenMessage(snapshot(1).toByteArray());
    connection.listener.onScreenMessage(patch(0, 2).toByteArray());
    modelExecutor.runAll();
    assertEquals(1, connection.resyncRequests);

    // The awaited snapshot is in flight inside the mailbox when a burst of
    // 64 more frames overflows it. Clearing the queue must not freeze the
    // session: the state machine has to resend resync with a fresh generation.
    connection.listener.onScreenMessage(snapshot(2).toByteArray());
    for (int i = 0; i < 64; i++) {
      connection.listener.onScreenMessage(patch(0, 2).toByteArray());
    }
    modelExecutor.runAll();
    assertEquals("overflow while waiting must resend resync", 2, connection.resyncRequests);
    assertEquals("the in-flight authoritative snapshot survives the patch burst", 2,
        runtime.model().screenRevision);

    // A fresh snapshot still releases the fence afterwards.
    connection.listener.onScreenMessage(snapshot(3).toByteArray());
    modelExecutor.runAll();
    assertEquals(3, runtime.model().screenRevision);
    connection.listener.onScreenMessage(patch(3, 4).toByteArray());
    modelExecutor.runAll();
    assertEquals("session converges, no permanent freeze", 4, runtime.model().screenRevision);
    assertEquals(0, connection.reconnectRequests);
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

  /** Fails validation: rows below the validator's minimum. */
  private static TerminalScreenProto.ScreenEnvelope invalidSnapshot() {
    return TerminalScreenProto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(1)
        .setSnapshot(TerminalScreenProto.ScreenSnapshot.newBuilder()
            .setSessionId("s1")
            .setInstanceId("i1")
            .setLayoutEpoch(1)
            .setScreenRevision(2)
            .setGeometry(TerminalScreenProto.Size.newBuilder().setRows(1).setCols(10).build())
            .setHistory(TerminalScreenProto.HistoryWindow.getDefaultInstance())
            .build())
        .build();
  }

  private static TerminalScreenProto.ScreenEnvelope patch(long baseRevision, long screenRevision) {
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

  private static TerminalScreenProto.ScreenEnvelope invalidHistoryPage(@NonNull String requestId) {
    TerminalScreenProto.HistoryPage.Builder page = TerminalScreenProto.HistoryPage.newBuilder()
        .setRequestId(requestId).setLayoutEpoch(1).setAsOfRevision(1);
    for (int i = 0; i < 501; i++) page.addLines(TerminalScreenProto.HistoryLine.getDefaultInstance());
    return TerminalScreenProto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(1)
        .setHistoryPage(page)
        .build();
  }

  private void grantLease(@NonNull String leaseId) {
    respondLease("", leaseId, true, 0L);
  }

  private void respondLease(@NonNull String requestId, @NonNull String leaseId,
                            boolean granted, long expiresAtMs) {
    TerminalScreenProto.ScreenEnvelope envelope = TerminalScreenProto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(1)
        .setLayoutLease(TerminalScreenProto.LayoutLease.newBuilder()
            .setRequestId(requestId)
            .setLeaseId(leaseId)
            .setGranted(granted)
            .setExpiresAtMs(expiresAtMs)
            .build())
        .build();
    connection.listener.onScreenMessage(envelope.toByteArray());
  }

  private static final class FakeScreenConnection implements TerminalSessionRuntime.ScreenConnection {
    final List<int[]> resizes = new ArrayList<>();
    int resyncRequests;
    int reconnectRequests;
    String historyRequestId = "";
    String leaseId = "";
    final List<String> layoutRequestIds = new ArrayList<>();
    boolean historyRequestAccepted = true;
    Listener listener;

    @Override
    public void setListener(@NonNull Listener listener) {
      this.listener = listener;
    }

    @Override
    public boolean beginSync(@NonNull ResumeToken resumeToken) {
      return true;
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
    public boolean requestHistoryPage(@NonNull String requestId, long beforeLineId, int limit) {
      historyRequestId = requestId;
      return historyRequestAccepted;
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
    public void acquireLayout(boolean interactive) {}

    @Override
    public void acquireLayout(@NonNull String requestId, boolean interactive) {
      layoutRequestIds.add(requestId);
    }

    @Override
    public void releaseLayout() {}

    @Override
    public void sendClipboardResponse(@NonNull String requestId, boolean allowed, boolean timeout,
                                      @Nullable byte[] data) {}

    @Override
    public void close() {}
  }

  /** 手动推进的调度器：任务按入队顺序逐个执行，可断言每次退避延迟。 */
  private static final class FakeTimeoutScheduler implements TerminalSessionRuntime.TimeoutScheduler {
    final List<Runnable> tasks = new ArrayList<>();
    final List<Long> delays = new ArrayList<>();

    @Override
    public void schedule(@NonNull Runnable task, long delayMs) {
      tasks.add(task);
      delays.add(delayMs);
    }

    void runNext() {
      assertFalse("no scheduled timeout to run", tasks.isEmpty());
      tasks.remove(0).run();
    }
  }

  private static final class QueuingExecutor implements Executor {
    final List<Runnable> tasks = new ArrayList<>();

    @Override
    public void execute(@NonNull Runnable command) {
      tasks.add(command);
    }

    void runAll() {
      while (!tasks.isEmpty()) {
        runNext();
      }
    }

    void runNext() {
      assertFalse("no queued executor task", tasks.isEmpty());
      tasks.remove(0).run();
    }
  }
}
