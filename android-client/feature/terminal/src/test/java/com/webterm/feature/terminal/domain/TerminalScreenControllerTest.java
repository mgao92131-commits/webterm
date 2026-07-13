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

import com.webterm.terminal.model.ModelChange;
import com.webterm.terminal.model.RemoteTerminalModel;
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

  @Before
  public void setUp() {
    // 同步 executor：proto 消息与回调都在当前线程完成，断言无需等待。
    runtime = new TerminalSessionRuntime("s1", new RemoteTerminalModel(),
        Runnable::run, Runnable::run);
    connection = new FakeScreenConnection();
    runtime.attachConnection(connection);
    controller = new TerminalScreenController(runtime);
    view = new RecordingView();
    LifecycleOwner owner = mock(LifecycleOwner.class);
    when(owner.getLifecycle()).thenReturn(mock(Lifecycle.class));
    controller.attach(owner, view);
  }

  @Test
  public void tailAppendWhileScrolledUp_compensatesViewportOnce() {
    controller.onScrollPixels(600, 2_000);
    assertFalse(controller.viewport().followTail);

    controller.onModelChange(new ModelChange(false, null, true, false, false, false, 3, 0));

    assertEquals(Collections.singletonList(3), view.historyAppends);
  }

  @Test
  public void historyPrepend_doesNotCompensateOrUndoReverseGesture() {
    controller.onScrollPixels(1_000, 1_000); // reach the hard top
    controller.onScrollPixels(-300, 1_000);  // reverse swipe toward the tail
    assertEquals(700, controller.viewport().scrollOffsetPixels);

    controller.onModelChange(new ModelChange(false, null, true, false, false, false, 0, 250));

    assertTrue("prepend must not trigger tail-append compensation",
        view.historyAppends.isEmpty());
    assertEquals("a returned page must not undo the reverse gesture",
        700, controller.viewport().scrollOffsetPixels);
  }

  @Test
  public void tailAppendWhileFollowingTail_keepsOffsetZero() {
    controller.onModelChange(new ModelChange(false, null, true, false, false, false, 4, 0));

    assertTrue(view.historyAppends.isEmpty());
    assertEquals(0, controller.viewport().scrollOffsetPixels);
    assertTrue(controller.viewport().followTail);
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

    @Override
    public void render(@NonNull RemoteTerminalModel model,
                       @NonNull com.webterm.terminal.model.TerminalViewportState viewport) {}

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

  private static final class FakeScreenConnection implements TerminalSessionRuntime.ScreenConnection {
    final List<Long> historyBeforeIds = new ArrayList<>();
    String lastHistoryRequestId = "";
    Listener listener;

    @Override
    public void setListener(@NonNull Listener listener) {
      this.listener = listener;
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
    public void requestHistoryPage(@NonNull String requestId, long beforeLineId, int limit) {
      lastHistoryRequestId = requestId;
      historyBeforeIds.add(beforeLineId);
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
