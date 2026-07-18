package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.RenderUpdate;
import com.webterm.terminal.model.ResumeToken;
import com.webterm.terminal.protocol.generated.TerminalScreenProto;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 覆盖 §4.1 的视口契约：tail append 才补偿 offset，顶部 prepend 不撤销反向手势，
 * 连续分页严格向旧方向推进并在硬顶停止。
 */
public final class TerminalScreenControllerTest {

  private TerminalSessionRuntime runtime;
  private TerminalScreenController controller;
  private FakeScreenConnection connection;
  private RecordingView view;
  private FakeFrameScheduler frameScheduler;
  private LifecycleOwner owner;

  @Before
  public void setUp() {
    // 同步 executor：proto 消息与回调都在当前线程完成，断言无需等待。
    runtime = new TerminalSessionRuntime("s1", new RemoteTerminalModel(),
        Runnable::run, Runnable::run);
    connection = new FakeScreenConnection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    frameScheduler = new FakeFrameScheduler();
    controller = new TerminalScreenController(runtime, new com.webterm.terminal.model.TerminalViewportState(),
        frameScheduler);
    view = new RecordingView();
    owner = mock(LifecycleOwner.class);
    when(owner.getLifecycle()).thenReturn(mock(Lifecycle.class));
    controller.attach(owner, view);
  }

  @Test
  public void renderWakeWhileScrolledUp_schedulesOneFrame() {
    controller.onScrollPixels(600, 2_000);
    assertFalse(controller.viewport().followTail);

    runtime.model().requestFullRender();
    controller.onRenderNeeded();

    assertEquals(1, frameScheduler.pendingCount());
  }

  @Test
  public void renderWakeDoesNotUndoReverseGesture() {
    controller.onScrollPixels(1_000, 1_000); // reach the hard top
    controller.onScrollPixels(-300, 1_000);  // reverse swipe toward the tail
    assertEquals(700, controller.viewport().scrollOffsetPixels);

    runtime.model().requestFullRender();
    controller.onRenderNeeded();

    assertEquals("a returned page must not undo the reverse gesture",
        700, controller.viewport().scrollOffsetPixels);
  }

  @Test
  public void renderWakeWhileFollowingTail_keepsOffsetZero() {
    runtime.model().requestFullRender();
    controller.onRenderNeeded();

    assertTrue(view.historyAppends.isEmpty());
    assertEquals(0, controller.viewport().scrollOffsetPixels);
    assertTrue(controller.viewport().followTail);
  }

  @Test
  public void failedHistoryRequest_keepsLoadingClearAndAllowsRetry() {
    connection.listener.onScreenMessage(snapshotWithHistory(100, 5, 1, true).toByteArray());

    // 通道不可用时请求失败：不得发出请求、不得留下 loading 状态。
    connection.acceptHistoryRequests = false;
    controller.requestOlderHistoryPage();
    assertTrue("failed request must not reach the connection",
        connection.historyBeforeIds.isEmpty());
    assertFalse("failed request must not leave the loading flag stuck",
        controller.viewport().loadingOlderHistory);

    // 失败不记录边界：通道恢复后允许重试同一边界。
    connection.acceptHistoryRequests = true;
    controller.requestOlderHistoryPage();
    assertEquals("the same edge must be retryable after a failed request",
        Collections.singletonList(100L), connection.historyBeforeIds);
    assertTrue(controller.viewport().loadingOlderHistory);
  }

  @Test
  public void onPause_cancelsPendingRender_andOnResume_requestsFreshRender() {
    assertTrue("attach schedules the first render", controller.renderScheduled());

    controller.lifecycleObserver().onStateChanged(
        mock(LifecycleOwner.class), Lifecycle.Event.ON_PAUSE);
    assertFalse("ON_PAUSE must cancel the queued render and reset the flag",
        controller.renderScheduled());

    // 暂停期间 listener 已移除：模型变更不再触达 controller，不能重新排队渲染。
    connection.listener.onScreenMessage(snapshotWithHistory(100, 5, 1, true).toByteArray());
    assertFalse("a paused controller must not schedule renders",
        controller.renderScheduled());

    controller.lifecycleObserver().onStateChanged(
        mock(LifecycleOwner.class), Lifecycle.Event.ON_RESUME);
    assertTrue("ON_RESUME must request a fresh snapshot render",
        controller.renderScheduled());
  }

  @Test
  public void sameVsyncWindow_schedulesOnlyOneRenderWake() {
    frameScheduler.runAll(); // attachment's initial full render
    runtime.model().requestFullRender();
    controller.onRenderNeeded();
    controller.onRenderNeeded();

    assertEquals(1, frameScheduler.pendingCount());
    frameScheduler.runAll();

    assertEquals(2, view.renderCount);
    assertTrue(view.lastUpdate.dirty.fullInvalidate);
  }

  @Test
  public void recoveredSnapshotMetadataUsesTheSameEffectPathAsLiveUpdates() {
    frameScheduler.runAll(); // attachment's initial render request
    List<String> metadata = new ArrayList<>();
    controller.setEffectListener(effect -> {
      if (effect.type() == TerminalScreenEffect.Type.TITLE) {
        metadata.add("title=" + effect.asTitle());
      } else if (effect.type() == TerminalScreenEffect.Type.WORKING_DIRECTORY) {
        metadata.add("cwd=" + effect.asWorkingDirectory());
      }
    });

    connection.listener.onScreenMessage(
        snapshotWithMetadata("vim: README.md", "/work/webterm").toByteArray());
    frameScheduler.runAll();

    assertEquals(Arrays.asList("title=vim: README.md", "cwd=/work/webterm"), metadata);
  }

  @Test
  public void detachedOldFrameCallback_cannotRenderNewAttachment() {
    Runnable old = frameScheduler.firstPending();
    controller.detach(owner);
    controller.attach(owner, view);

    old.run();
    assertEquals("cancelled callback must be inert", 0, view.renderCount);

    frameScheduler.runAll();
    assertEquals(1, view.renderCount);
  }

  @Test
  public void consecutiveHistoryPagesReachHardTopWithoutRepeatingRequest() {
    // History line ids are positive; id 0 is reserved for screen rows. The
    // server's hard top is firstAvailableLineId == 1.
    connection.listener.onScreenMessage(snapshotWithHistory(100, 5, 1, true).toByteArray());

    controller.requestOlderHistoryPage();
    assertEquals(Collections.singletonList(100L), connection.historyBeforeIds);

    // The in-flight page suppresses a duplicate request for the same edge.
    controller.requestOlderHistoryPage();
    assertEquals(Collections.singletonList(100L), connection.historyBeforeIds);

    connection.listener.onScreenMessage(
        historyPage(connection.lastHistoryRequestId, 50, 100, 1, true).toByteArray());
    controller.requestOlderHistoryPage();
    assertEquals("beforeId must advance strictly toward older history",
        Arrays.asList(100L, 50L), connection.historyBeforeIds);

    connection.listener.onScreenMessage(
        historyPage(connection.lastHistoryRequestId, 1, 50, 1, false).toByteArray());
    controller.requestOlderHistoryPage();
    assertEquals("the hard top must not re-request the same page",
        Arrays.asList(100L, 50L), connection.historyBeforeIds);
    assertFalse(runtime.model().hasMoreHistoryBefore());
  }

  private static TerminalScreenProto.ScreenEnvelope snapshotWithHistory(
      long firstHistoryId, int count, long firstAvailableLineId, boolean hasMoreBefore) {
    TerminalScreenProto.HistoryWindow.Builder history = TerminalScreenProto.HistoryWindow.newBuilder()
        .setFirstAvailableLineId(firstAvailableLineId)
        .setFirstIncludedLineId(firstHistoryId)
        .setLastIncludedLineId(firstHistoryId + count - 1)
        .setHasMoreBefore(hasMoreBefore);
    for (long id = firstHistoryId; id < firstHistoryId + count; id++) {
      history.addLines(TerminalScreenProto.HistoryLine.newBuilder().setId(id));
    }
    return TerminalScreenProto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(1)
        .setSnapshot(TerminalScreenProto.ScreenSnapshot.newBuilder()
            .setSessionId("s1")
            .setInstanceId("i1")
            .setLayoutEpoch(1)
            .setScreenRevision(1)
            .setGeometry(TerminalScreenProto.Size.newBuilder().setRows(5).setCols(10).build())
            .setHistory(history)
            .build())
        .build();
  }

  private static TerminalScreenProto.ScreenEnvelope snapshotWithMetadata(String title, String cwd) {
    TerminalScreenProto.ScreenEnvelope base = snapshotWithHistory(100, 5, 1, true);
    return base.toBuilder()
        .setSnapshot(base.getSnapshot().toBuilder()
            .setTitle(title)
            .setWorkingDirectory(cwd))
        .build();
  }

  private static TerminalScreenProto.ScreenEnvelope historyPage(
      @NonNull String requestId, long firstHistoryId, long lastHistoryIdExclusive,
      long firstAvailableLineId, boolean hasMoreBefore) {
    TerminalScreenProto.HistoryPage.Builder page = TerminalScreenProto.HistoryPage.newBuilder()
        .setRequestId(requestId)
        .setLayoutEpoch(1)
        .setAsOfRevision(1)
        .setFirstAvailableLineId(firstAvailableLineId)
        .setHasMoreBefore(hasMoreBefore);
    for (long id = firstHistoryId; id < lastHistoryIdExclusive; id++) {
      page.addLines(TerminalScreenProto.HistoryLine.newBuilder().setId(id));
    }
    return TerminalScreenProto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(1)
        .setHistoryPage(page)
        .build();
  }

  private static final class RecordingView implements TerminalScreenController.View {
    final List<Integer> historyAppends = new ArrayList<>();
    int renderCount;
    RenderUpdate lastUpdate;

    @Override
    public void bindModel(@NonNull RemoteTerminalModel model) {}

    @Override
    public void render(@NonNull RenderUpdate update,
                       @NonNull com.webterm.terminal.model.TerminalViewportState viewport) {
      renderCount++;
      lastUpdate = update;
    }

    @Override
    public void onCursorChanged() {}

    @Override
    public void onTitleChanged(@Nullable String title) {}

    @Override
    public void requestInvalidate() {}

    @Override
    public void onHistoryAppended(int lineCount) {
      historyAppends.add(lineCount);
    }
  }

  private static final class FakeFrameScheduler implements FrameScheduler {
    final List<Runnable> callbacks = new ArrayList<>();

    @Override public void postFrame(@NonNull Runnable callback) { callbacks.add(callback); }
    @Override public void cancelFrame(@NonNull Runnable callback) { callbacks.remove(callback); }

    int pendingCount() { return callbacks.size(); }
    Runnable firstPending() { return callbacks.get(0); }
    void runAll() {
      while (!callbacks.isEmpty()) callbacks.remove(0).run();
    }
  }

  private static final class FakeScreenConnection implements TerminalSessionRuntime.ScreenConnection {
    final List<Long> historyBeforeIds = new ArrayList<>();
    String lastHistoryRequestId = "";
    boolean acceptHistoryRequests = true;
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
    public void requestResize(int cols, int rows) {}

    @Override
    public boolean requestHistoryPage(@NonNull String requestId, long beforeLineId, int limit) {
      if (!acceptHistoryRequests) return false;
      lastHistoryRequestId = requestId;
      historyBeforeIds.add(beforeLineId);
      return true;
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
}
