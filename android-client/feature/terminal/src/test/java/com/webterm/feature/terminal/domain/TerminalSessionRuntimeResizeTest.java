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
    public void requestHistoryPage(long beforeLineId, int limit) {}

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
