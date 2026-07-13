package com.webterm.feature.terminal.domain;

import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.core.session.RelayMuxSessionManager;
import com.webterm.core.session.RelayMuxSessionRegistry;
import com.webterm.terminal.protocol.ScreenMessageBuilder;
import com.webterm.terminal.protocol.generated.TerminalScreenProto;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

/**
 * 通过 relay mux 建立 webterm.screen.v1 通道的 ScreenConnection 实现。
 */
public final class ScreenMuxConnection implements TerminalSessionRuntime.ScreenConnection {

  private final Handler mainHandler;
  private final RelayMuxSessionRegistry relayMuxRegistry;
  private final String baseUrl;
  private final String cookie;
  private final String sessionId;
  private final String relayDeviceId;

  private RelayMuxSessionManager relayMuxSession;
  private String relayChannelId;
  private Listener listener;
  private String layoutLeaseId = "";
  private int columns;
  private int rows;

  @AssistedInject
  public ScreenMuxConnection(
      Handler mainHandler,
      RelayMuxSessionRegistry relayMuxRegistry,
      @Assisted("baseUrl") String baseUrl,
      @Assisted("cookie") String cookie,
      @Assisted("sessionId") String sessionId,
      @Assisted("relayDeviceId") String relayDeviceId) {
    this.mainHandler = mainHandler;
    this.relayMuxRegistry = relayMuxRegistry;
    this.baseUrl = baseUrl;
    this.cookie = cookie;
    this.sessionId = sessionId;
    this.relayDeviceId = relayDeviceId == null ? "" : relayDeviceId;
  }

  @AssistedFactory
  public interface Factory {
    ScreenMuxConnection create(
        @Assisted("baseUrl") String baseUrl,
        @Assisted("cookie") String cookie,
        @Assisted("sessionId") String sessionId,
        @Assisted("relayDeviceId") String relayDeviceId);
  }

  public void connect(int columns, int rows) {
    this.columns = clamp(columns, 10, 500);
    this.rows = clamp(rows, 5, 200);
    connectNow();
  }

  @Override
  public void setListener(@NonNull Listener listener) {
    this.listener = listener;
  }

  @Override
  public void setLayoutLeaseId(@NonNull String leaseId) {
    // 只记录租约；拿到租约后的首次 resize 由 TerminalSessionRuntime 用最新尺寸驱动，
    // 这里不再回发 connect() 时的占位尺寸，避免先把无头终端改成 80x24 再改回来的抖动。
    this.layoutLeaseId = leaseId == null ? "" : leaseId;
  }

  @Override
  public void sendTextInput(@NonNull String text) {
    if (relayMuxSession == null || relayChannelId == null) return;
    relayMuxSession.sendTunnelFrame(relayChannelId, ScreenMessageBuilder.textInput(layoutLeaseId, text), true);
  }

  @Override
  public void sendPasteInput(@NonNull String text) {
    if (relayMuxSession == null || relayChannelId == null) return;
    relayMuxSession.sendTunnelFrame(relayChannelId, ScreenMessageBuilder.pasteInput(layoutLeaseId, text), true);
  }

  @Override
  public void sendKeyInput(@NonNull String key, boolean shift, boolean alt, boolean ctrl,
                           boolean meta, boolean pressed) {
    if (relayMuxSession == null || relayChannelId == null) return;
    relayMuxSession.sendTunnelFrame(relayChannelId,
        ScreenMessageBuilder.keyInput(layoutLeaseId, key, shift, alt, ctrl, meta, pressed), true);
  }

  @Override
  public void sendMouseInput(int row, int col, @NonNull String button, int wheelDelta,
                             boolean shift, boolean alt, boolean ctrl, boolean meta,
                             boolean pressed) {
    if (relayMuxSession == null || relayChannelId == null) return;
    TerminalScreenProto.MouseButton protoButton = mouseButtonFromString(button);
    relayMuxSession.sendTunnelFrame(relayChannelId,
        ScreenMessageBuilder.mouseInput(layoutLeaseId, row, col, protoButton, wheelDelta,
            shift, alt, ctrl, meta, pressed), true);
  }

  @Override
  public void sendFocusInput(boolean focused) {
    if (relayMuxSession == null || relayChannelId == null) return;
    relayMuxSession.sendTunnelFrame(relayChannelId,
        ScreenMessageBuilder.focusInput(layoutLeaseId, focused), true);
  }

  @Override
  public void requestResize(int cols, int rows) {
    // 先记录最新尺寸（重连后 hello 也会用到），通道不可用时不发但状态保持真实。
    this.columns = clamp(cols, 10, 500);
    this.rows = clamp(rows, 5, 200);
    if (relayMuxSession == null || relayChannelId == null) return;
    relayMuxSession.sendTunnelFrame(relayChannelId,
        ScreenMessageBuilder.resize(layoutLeaseId, this.columns, this.rows), true);
  }

  @Override
  public void acquireLayout(boolean interactive) {
    if (relayMuxSession == null || relayChannelId == null) return;
    relayMuxSession.sendTunnelFrame(relayChannelId, ScreenMessageBuilder.acquireLayout(interactive), true);
  }

  @Override
  public void releaseLayout() {
    if (relayMuxSession == null || relayChannelId == null) return;
    relayMuxSession.sendTunnelFrame(relayChannelId, ScreenMessageBuilder.releaseLayout(layoutLeaseId), true);
    layoutLeaseId = "";
  }

  @Override
  public void sendClipboardResponse(@NonNull String requestId, boolean allowed, boolean timeout, @Nullable byte[] data) {
    if (relayMuxSession == null || relayChannelId == null) return;
    relayMuxSession.sendTunnelFrame(relayChannelId,
        ScreenMessageBuilder.clipboardResponse(requestId, allowed, timeout, data), true);
  }

  @Override
  public void requestHistoryPage(@NonNull String requestId, long beforeLineId, int limit) {
    if (relayMuxSession == null || relayChannelId == null) return;
    relayMuxSession.sendTunnelFrame(relayChannelId, ScreenMessageBuilder.historyRequest(requestId, beforeLineId, limit), true);
  }

  @Override
  public void requestResync(long layoutEpoch, long screenRevision, @NonNull String reason) {
    if (relayMuxSession == null || relayChannelId == null) return;
    relayMuxSession.sendTunnelFrame(relayChannelId,
        ScreenMessageBuilder.resync(layoutEpoch, screenRevision, reason), true);
  }

  @Override
  public void close() {
    if (relayMuxSession != null && relayChannelId != null) {
      relayMuxSession.closeChannel(relayChannelId);
      relayMuxRegistry.releaseIfIdle(relayMuxSession);
    }
    relayMuxSession = null;
    relayChannelId = null;
  }

  private void connectNow() {
    if (relayMuxSession == null || !relayMuxSession.matches(baseUrl, cookie, relayDeviceId)) {
      if (relayMuxSession != null) {
        relayMuxRegistry.releaseIfIdle(relayMuxSession);
      }
      relayMuxSession = relayMuxRegistry.forDevice(baseUrl, cookie, relayDeviceId);
      relayMuxSession.updateCookie(cookie);
    }
    String localSessionId = RelayMuxSessionManager.localSessionId(sessionId, relayDeviceId);
    relayChannelId = relayMuxSession.openScreenChannel(localSessionId, new RelayMuxSessionManager.ChannelListener() {
      @Override
      public void onConnected(String channelId) {
        sendHello();
        if (listener != null) listener.onConnected();
      }

      @Override
      public void onError(String channelId, int code, String message) {
        if (listener != null) listener.onDisconnected("channel error " + code + ": " + message);
      }

      @Override
      public void onData(String channelId, byte[] payload, boolean binary) {
        if (listener != null) listener.onScreenMessage(payload);
      }

      @Override
      public void onMuxDisconnected(String reason) {
        if (listener != null) listener.onDisconnected(reason);
      }

      @Override
      public void onClosed(String channelId, int code, String reason) {
        if (listener != null) listener.onDisconnected(reason);
      }

      @Override
      public void onChannelGone(String channelId, int code, String reason) {
        if (listener != null) listener.onClosed();
      }

      @Override
      public void onReconnectAttempt(int attempt) {
        if (listener != null) listener.onDisconnected("reconnect attempt " + attempt);
      }
    });
  }

  private void sendHello() {
    if (relayMuxSession == null || relayChannelId == null) return;
    relayMuxSession.sendTunnelFrame(relayChannelId, ScreenMessageBuilder.hello(columns, rows), true);
  }

  private static TerminalScreenProto.MouseButton mouseButtonFromString(@NonNull String button) {
    switch (button) {
      case "left":
        return TerminalScreenProto.MouseButton.MOUSE_BUTTON_LEFT;
      case "middle":
        return TerminalScreenProto.MouseButton.MOUSE_BUTTON_MIDDLE;
      case "right":
        return TerminalScreenProto.MouseButton.MOUSE_BUTTON_RIGHT;
      case "wheel":
        return TerminalScreenProto.MouseButton.MOUSE_BUTTON_WHEEL;
      case "move":
        return TerminalScreenProto.MouseButton.MOUSE_BUTTON_MOVE;
      default:
        return TerminalScreenProto.MouseButton.MOUSE_BUTTON_UNSPECIFIED;
    }
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
