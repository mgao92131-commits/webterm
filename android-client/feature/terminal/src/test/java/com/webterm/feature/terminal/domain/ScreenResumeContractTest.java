package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.protocol.generated.TerminalScreenProto;

import org.junit.Ignore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 屏幕增量恢复契约测试（计划 §12 Task 1）。
 *
 * <p>已生效的用例固化 §15 混合版本兼容行为；@Ignore 桩冻结尚未实现的 Task 5/6
 * 契约（JUnit4 无 @Disabled，@Ignore 为等价语义），实现到位后移除 @Ignore 并补全方法体。</p>
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

  @Ignore("Task 6：累计恢复 Patch 的原子应用尚未实现（计划 §6、§10.2）")
  @Test
  public void cumulativeResumePatchAppliedAtomicallyWithRevisionJump() {
    // Task 6：patch 从 revision 100 直接跳到 150（不回放中间 revision），整帧原子应用；
    // 任一校验失败不得留下部分 mutation。
  }

  @Ignore("Task 6：history watermark 与恢复 Patch 的原子应用尚未实现（计划 §5.2）")
  @Test
  public void historyWatermarkAppliedAtomicallyIndependentOfHistoryTrimOrder() {
    // Task 6：恢复 Patch 的 first_available_history_line_id 与 history_append 在同一
    // model executor 事务内应用，已 trim 行不得复活；与 HistoryTrim 乱序无关。
  }

  @Ignore("Task 5/6：SYNCING 状态机与恢复完成门尚未实现（计划 §7.2）")
  @Test
  public void connectedOnlyAfterResumeAckOrAtomicResumeFrame() {
    // Task 5/6：收到并校验 ResumeAck，或成功原子应用恢复 Patch/Snapshot 之后，
    // 才允许进入 CONNECTED。
  }

  @Ignore("Task 6：校验失败的单一 resync fence 收敛尚未实现（计划 §10.2）")
  @Test
  public void validationFailureRejectsWholeFrameAndSendsSingleResyncRequest() {
    // Task 6：校验失败整帧拒绝、不推进本地 revision、进入单一 resync fence、
    // 只发送一次 ResyncRequest，只允许权威 Snapshot 解除 fence。
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
