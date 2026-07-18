package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.core.contract.diagnostics.DiagnosticLevel;
import com.webterm.core.contract.diagnostics.DiagnosticSink;
import com.webterm.core.contract.diagnostics.Diagnostics;
import com.webterm.terminal.model.ModelChange;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ResumeToken;
import com.webterm.terminal.protocol.generated.TerminalScreenProto;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 验证 ScreenMailbox drain 不会因为单条消息处理异常而永久卡死。
 */
public final class TerminalSessionRuntimeMailboxRecoveryTest {

  private TerminalSessionRuntime runtime;
  private FakeScreenConnection connection;
  private CollectingDiagnosticSink sink;

  @Before
  public void setUp() {
    sink = new CollectingDiagnosticSink();
    Diagnostics.install(sink);
    runtime = new TerminalSessionRuntime("s1", new RemoteTerminalModel(),
        Runnable::run, Runnable::run,
        (task, delayMs) -> { /* no-op for this test */ });
    connection = new FakeScreenConnection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
  }

  @After
  public void tearDown() {
    Diagnostics.install(null);
  }

  @Test
  public void modelChangeCallbackException_doesNotStallMailbox() {
    // 第一条 snapshot 的 model change 回调会抛异常，模拟未保护的 RuntimeException。
    runtime.addListener(new TerminalSessionRuntime.Listener() {
      private boolean armed = true;

      @Override
      public void onModelChange(@NonNull ModelChange change) {
        if (armed) {
          armed = false;
          throw new RuntimeException("simulated callback failure");
        }
      }

      @Override
      public void onEffect(@NonNull TerminalScreenEffect effect) {}

      @Override
      public void onConnectionStateChange(@NonNull TerminalSessionRuntime.State state) {}
    });

    // 第一条消息：snapshot 已应用，但 dispatchModelChange 抛异常。
    connection.listener.onScreenMessage(snapshot(1).toByteArray());

    // 最终安全网应记录异常并启动 resync，而不是让 drainScheduled 悬空。
    assertEquals("应记录 screen_frame_processing_failed", 1,
        sink.count("screen_frame_processing_failed"));
    assertEquals("应启动 resync 恢复", 1, connection.resyncRequests);

    // 第二条权威 snapshot 必须仍能进入 Mailbox 并被处理。
    connection.listener.onScreenMessage(snapshot(2).toByteArray());
    assertEquals("Mailbox 不能永久卡死", 2, runtime.model().screenRevision);
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
    Listener listener;
    int resyncRequests;

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
