package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.SegmentKey;
import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/** Segment 加载路径：demand API 可安全调用；无 Source/无封存时不发起请求。 */
public class TerminalSessionRuntimeV2HistoryPagingTest {
  @Test
  public void demandWithoutSealedCatalogDoesNotFetch() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    TerminalSessionRuntime runtime = new TerminalSessionRuntime("owner", model, Runnable::run);
    FakeV2Connection connection = connect(runtime);

    AtomicReference<SegmentKey> requested = new AtomicReference<>();
    runtime.setHistorySegmentSource(new HistorySegmentSource() {
      @Override public RequestHandle fetch(@NonNull SegmentKey key, @NonNull Callback callback) {
        requested.set(key);
        return () -> {};
      }
      @Override public void close() {}
    });

    runtime.onVisibleHistoryDemand(1, 64, 1, -1, 20);
    assertNull(requested.get());
    assertNotNull(connection);
  }

  private static FakeV2Connection connect(TerminalSessionRuntime runtime) {
    FakeV2Connection connection = new FakeV2Connection();
    runtime.attachConnection(connection);
    connection.listener.onConnected();
    return connection;
  }

  private static final class FakeV2Connection implements TerminalSessionRuntime.ScreenConnection {
    TerminalSessionRuntime.ScreenConnection.Listener listener;
    @Override public void setListener(@NonNull Listener listener) { this.listener = listener; }
    @Override public boolean beginSync(@Nullable TerminalScreenV2Proto.ResumeToken resume) {
      return true;
    }
    @Override public void setLayoutLeaseId(@NonNull String leaseId) {}
    @Override public void sendTextInput(@NonNull String text) {}
    @Override public void sendPasteInput(@NonNull String text) {}
    @Override public void sendKeyInput(@NonNull String key, boolean shift, boolean alt,
                                       boolean ctrl, boolean meta, boolean pressed) {}
    @Override public void sendMouseInput(int row, int col, @NonNull String button, int wheelDelta,
                                         boolean shift, boolean alt, boolean ctrl, boolean meta,
                                         boolean pressed) {}
    @Override public void sendFocusInput(boolean focused) {}
    @Override public boolean requestResize(int cols, int rows) { return true; }
    @Override public void acquireLayout(boolean interactive) {}
    @Override public void releaseLayout() {}
    @Override public void sendClipboardResponse(@NonNull String requestId, boolean allowed,
                                                boolean timeout, @Nullable byte[] data) {}
    @Override public void close() {}
  }
}
