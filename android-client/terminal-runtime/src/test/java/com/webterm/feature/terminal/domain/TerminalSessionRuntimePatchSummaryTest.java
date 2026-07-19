package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.core.contract.diagnostics.DiagnosticLevel;
import com.webterm.core.contract.diagnostics.DiagnosticSink;
import com.webterm.core.contract.diagnostics.Diagnostics;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ResumeToken;
import com.webterm.terminal.protocol.generated.TerminalScreenProto;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 覆盖 Patch 运行汇总的异步窗口：
 * 1. 只有一个 Patch 时，1 秒延迟后也要输出 patch_applied_summary；
 * 2. Snapshot 使旧窗口的定时器失效，不会输出过期汇总；
 * 3. 旧窗口失效后，新 Patch 窗口能正常输出。
 */
public final class TerminalSessionRuntimePatchSummaryTest {

  private TerminalSessionRuntime runtime;
  private FakeScreenConnection connection;
  private FakeTimeoutScheduler scheduler;
  private CollectingDiagnosticSink sink;

  @Before
  public void setUp() {
    scheduler = new FakeTimeoutScheduler();
    sink = new CollectingDiagnosticSink();
    Diagnostics.install(sink);
    runtime = new TerminalSessionRuntime("s1", new RemoteTerminalModel(),
        Runnable::run, Runnable::run, scheduler);
    connection = new FakeScreenConnection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    connection.listener.onScreenMessage(snapshot(1).toByteArray());
    scheduler.tasks.clear();
    scheduler.delays.clear();
  }

  @After
  public void tearDown() {
    Diagnostics.install(null);
  }

  @Test
  public void singlePatch_isSummarizedAfterDelay() {
    connection.listener.onScreenMessage(patch(1, 2, 1, 1).toByteArray());

    assertEquals("patch 汇总定时器应在第一个 Patch 时安排", 1, scheduler.tasks.size());
    assertEquals("patch 汇总延迟为 1 秒", 1000L, (long) scheduler.delays.get(0));
    assertEquals("summary 尚未输出", 0, sink.count("patch_applied_summary"));

    scheduler.runNext();

    assertEquals("1 秒后应输出 patch_applied_summary", 1, sink.count("patch_applied_summary"));
    Map<String, ?> fields = sink.last("patch_applied_summary");
    assertEquals("s1", fields.get("sessionId"));
    assertEquals("i1", fields.get("instanceId"));
    assertEquals(1L, fields.get("layoutEpoch"));
    assertEquals(1L, fields.get("firstBaseRevision"));
    assertEquals(2L, fields.get("lastScreenRevision"));
    assertEquals(1, fields.get("patchCount"));
    assertTrue((long) fields.get("payloadBytes") > 0);
    assertEquals(1, fields.get("changedRows"));
    assertEquals(1, fields.get("historyAppend"));
  }

  @Test
  public void snapshotBeforeTimeout_invalidatesStaleSummary() {
    connection.listener.onScreenMessage(patch(1, 2, 1, 0).toByteArray());
    assertEquals(1, scheduler.tasks.size());

    // 权威 snapshot 替换投影并重置汇总窗口。
    connection.listener.onScreenMessage(snapshot(3).toByteArray());
    assertEquals("snapshot 不安排新汇总任务", 1, scheduler.tasks.size());

    scheduler.runNext();

    assertEquals("旧窗口定时器不得输出 patch_applied_summary", 0,
        sink.count("patch_applied_summary"));
  }

  @Test
  public void staleWindowDoesNotBlockNewWindow() {
    // 第一个窗口：一个 Patch，但在超时前被 snapshot 作废。
    connection.listener.onScreenMessage(patch(1, 2, 1, 0).toByteArray());
    connection.listener.onScreenMessage(snapshot(3).toByteArray());

    // 第二个窗口：新 Patch 应能正常输出汇总。
    connection.listener.onScreenMessage(patch(3, 4, 2, 0).toByteArray());
    assertEquals("新窗口应安排自己的定时器", 2, scheduler.tasks.size());

    // 先跑旧定时器，应被 generation 过滤。
    scheduler.runNext();
    assertEquals("旧窗口不得输出", 0, sink.count("patch_applied_summary"));

    // 再跑新定时器，应输出新窗口汇总。
    scheduler.runNext();
    assertEquals("新窗口应输出 patch_applied_summary", 1, sink.count("patch_applied_summary"));
    Map<String, ?> fields = sink.last("patch_applied_summary");
    assertEquals(3L, fields.get("firstBaseRevision"));
    assertEquals(4L, fields.get("lastScreenRevision"));
    assertEquals(2, fields.get("changedRows"));
  }

  private static TerminalScreenProto.ScreenEnvelope snapshot(long revision) {
    return TerminalScreenProto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(1)
        .setSnapshot(TestScreenFrames.snapshotBuilder(revision).build())
        .build();
  }

  private static TerminalScreenProto.ScreenEnvelope patch(long baseRevision, long screenRevision,
                                                          int screenRows, int historyAppend) {
    TerminalScreenProto.ScreenPatch.Builder pb = TerminalScreenProto.ScreenPatch.newBuilder()
        .setInstanceId("i1")
        .setLayoutEpoch(1)
        .setBaseRevision(baseRevision)
        .setScreenRevision(screenRevision);
    for (int row = 0; row < screenRows; row++) {
      pb.addLineUpdates(TestScreenFrames.line(row + 1L, 2, "x"));
    }
    for (int i = 0; i < historyAppend; i++) {
      long historySeq = 100L + i;
      pb.addLineUpdates(TestScreenFrames.line(historySeq, 1, "h").toBuilder()
          .setHistorySeq(historySeq).build());
      pb.addHistoryAppendIds(historySeq);
    }
    return TerminalScreenProto.ScreenEnvelope.newBuilder()
        .setProtocolVersion(1)
        .setPatch(pb.build())
        .build();
  }

  private static final class FakeScreenConnection implements TerminalSessionRuntime.ScreenConnection {
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
    public void requestResync(long layoutEpoch, long screenRevision, @NonNull String reason) {}

    @Override
    public void requestReconnect(@NonNull String reason) {}

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

  private static final class CollectingDiagnosticSink implements DiagnosticSink {
    final List<RecordedEvent> events = new ArrayList<>();

    @Override
    public void record(DiagnosticLevel level, String area, String event,
                       Map<String, ?> fields) {
      events.add(new RecordedEvent(level, area, event, fields));
    }

    int count(String event) {
      int n = 0;
      for (RecordedEvent e : events) {
        if (e.event.equals(event)) n++;
      }
      return n;
    }

    Map<String, ?> last(String event) {
      for (int i = events.size() - 1; i >= 0; i--) {
        if (events.get(i).event.equals(event)) return events.get(i).fields;
      }
      return null;
    }
  }

  private static final class RecordedEvent {
    final DiagnosticLevel level;
    final String area;
    final String event;
    final Map<String, ?> fields;

    RecordedEvent(DiagnosticLevel level, String area, String event, Map<String, ?> fields) {
      this.level = level;
      this.area = area;
      this.event = event;
      this.fields = fields;
    }
  }
}
