package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
  public void asyncRenderWakeCallbackException_isIsolatedWithoutResync() throws Exception {
    ExecutorService callbacks = Executors.newSingleThreadExecutor();
    runtime = new TerminalSessionRuntime("s1", new RemoteTerminalModel(), Runnable::run, callbacks,
        (task, delayMs) -> { /* no-op for this test */ });
    connection = new FakeScreenConnection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    CountDownLatch firstHealthyRender = new CountDownLatch(1);
    CountDownLatch secondHealthyRender = new CountDownLatch(1);
    AtomicInteger healthyRenderCalls = new AtomicInteger();

    // 第一条 render-needed 回调会抛异常，模拟真实主线程 callback executor。
    runtime.addListener(new TerminalSessionRuntime.Listener() {
      private boolean armed = true;

      @Override
      public void onRenderNeeded() {
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
    runtime.addListener(new TerminalSessionRuntime.Listener() {
      @Override public void onRenderNeeded() {
        if (healthyRenderCalls.getAndIncrement() == 0) {
          firstHealthyRender.countDown();
        } else {
          secondHealthyRender.countDown();
        }
      }
      @Override public void onEffect(@NonNull TerminalScreenEffect effect) {}
      @Override public void onConnectionStateChange(@NonNull TerminalSessionRuntime.State state) {}
    });

    // 同一未消费周期允许合并为一次 wake。先等第一轮实际回调完成，再验证后续
    // 模型更新仍可重新唤醒，且异常 Listener 不影响同一轮的健康 Listener。
    connection.listener.onScreenMessage(snapshot(1).toByteArray());
    assertTrue("healthy listener should receive the first asynchronous callback",
        firstHealthyRender.await(5, TimeUnit.SECONDS));
    connection.listener.onScreenMessage(snapshot(2).toByteArray());
    assertTrue("healthy listener should receive a later asynchronous callback",
        secondHealthyRender.await(5, TimeUnit.SECONDS));
    callbacks.shutdownNow();

    assertEquals("UI callback failures must not request a screen resync", 0, connection.resyncRequests);
    assertEquals("Mailbox and model remain healthy", 2, runtime.model().screenRevision);
    assertEquals("listener failure is isolated as a UI diagnostic", 1, sink.count("ui_callback_failed"));
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
