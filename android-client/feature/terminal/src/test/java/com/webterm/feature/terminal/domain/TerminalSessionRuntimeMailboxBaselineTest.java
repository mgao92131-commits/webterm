package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ResumeToken;
import com.webterm.terminal.protocol.generated.TerminalScreenProto;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * 屏幕消息 mailbox 有界性的可重复基线（计划 §5.2：mailbox 最大深度和溢出次数）。
 *
 * <p>用排队的 modelExecutor 模拟「网络喂帧快于模型消费」：连续投递 500 帧 snapshot，
 * 通过反射观测 mailbox 深度峰值，统计溢出次数与由此产生的 resync 请求数。
 * 溢出收敛语义本身由 TerminalSessionRuntimeResizeTest 覆盖，这里只提供可重复的
 * 溢出计数场景与结构化报告（[PerfBaseline] 前缀），不设时间阈值断言。</p>
 */
public final class TerminalSessionRuntimeMailboxBaselineTest {

  /** mailbox 上限与生产代码 MAX_PENDING_SCREEN_MESSAGES 保持一致。 */
  private static final int MAILBOX_CAPACITY = 64;
  private static final int BURST_FRAMES = 500;

  @Test
  public void mailboxBurst_baseline() throws Exception {
    QueuingExecutor modelExecutor = new QueuingExecutor();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("s1", new RemoteTerminalModel(),
        modelExecutor, Runnable::run, (task, delayMs) -> {});
    FakeScreenConnection connection = new FakeScreenConnection();
    runtime.attachConnection(connection);

    Field mailboxField = TerminalSessionRuntime.class.getDeclaredField("pendingScreenMessages");
    mailboxField.setAccessible(true);
    Collection<?> mailbox = (Collection<?>) mailboxField.get(runtime);

    int maxDepth = 0;
    long feedStart = System.nanoTime();
    for (int revision = 1; revision <= BURST_FRAMES; revision++) {
      connection.listener.onScreenMessage(snapshot(revision).toByteArray());
      maxDepth = Math.max(maxDepth, mailbox.size());
    }
    long feedMs = (System.nanoTime() - feedStart) / 1_000_000L;

    modelExecutor.runAll();

    // 期望溢出次数：第 65 帧起每 64 帧溢出一次（500 帧 → 7 次）。
    int expectedOverflows = (BURST_FRAMES - 1) / MAILBOX_CAPACITY;
    assertEquals("mailbox depth must stay within the 64-frame bound",
        MAILBOX_CAPACITY, maxDepth);
    assertEquals("a burst coalesces its overflow fence into one resync",
        1, connection.resyncRequests);
    assertEquals("overflow must not escalate to channel rebuild",
        0, connection.reconnectRequests);
    assertEquals("all queued snapshots are applied by the drain before recovery runs",
        BURST_FRAMES, runtime.model().screenRevision);
    assertTrue("mailbox is empty after drain", mailbox.isEmpty());

    System.out.printf(Locale.US,
        "[PerfBaseline] scenario=mailbox-burst burst_frames=%d max_depth=%d overflow_count=%d"
            + " resync_requests=%d reconnect_requests=%d feed_wall_ms=%d%n",
        BURST_FRAMES, maxDepth, expectedOverflows, connection.resyncRequests,
        connection.reconnectRequests, feedMs);
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

  private static final class FakeScreenConnection implements TerminalSessionRuntime.ScreenConnection {
    int resyncRequests;
    int reconnectRequests;
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
