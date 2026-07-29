package com.webterm.feature.terminal.domain;

import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.core.session.ChannelFailure;
import com.webterm.core.session.ConnectionCloseReason;
import com.webterm.core.session.DeviceConnection;
import com.webterm.core.session.DeviceConnectionRegistry;
import com.webterm.core.session.MuxOutboundQueue;
import com.webterm.core.session.TransportReconnectTrigger;
import com.webterm.terminal.protocol.ScreenMessageV2Builder;
import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

import java.util.UUID;

/**
 * 通过 device connection 建立 webterm.screen.v2 通道的 ScreenConnection 实现。
 */
public final class TerminalChannel implements TerminalSessionRuntime.ScreenConnection {

  private final Handler mainHandler;
  private final DeviceConnectionRegistry deviceConnectionRegistry;
  private final String baseUrl;
  private final String cookie;
  private final String sessionId;
  private final String serverConfigId;
  private final boolean directDevice;
  private final String relayDeviceId;

  private volatile DeviceConnection deviceConnection;
  private volatile String channelId;
  private volatile String channelLifecycleId = "";
  private volatile Listener listener;
  private volatile String layoutLeaseId = "";
  private int columns;
  private int rows;

  @AssistedInject
  public TerminalChannel(
      Handler mainHandler,
      DeviceConnectionRegistry deviceConnectionRegistry,
      @Assisted("baseUrl") String baseUrl,
      @Assisted("cookie") String cookie,
      @Assisted("sessionId") String sessionId,
      @Assisted("serverConfigId") String serverConfigId,
      @Assisted("directDevice") boolean directDevice,
      @Assisted("relayDeviceId") String relayDeviceId) {
    this.mainHandler = mainHandler;
    this.deviceConnectionRegistry = deviceConnectionRegistry;
    this.baseUrl = baseUrl;
    this.cookie = cookie;
    this.sessionId = sessionId;
    this.serverConfigId = serverConfigId == null ? "" : serverConfigId;
    this.directDevice = directDevice;
    this.relayDeviceId = relayDeviceId == null ? "" : relayDeviceId;
  }

  @AssistedFactory
  public interface Factory {
    TerminalChannel create(
        @Assisted("baseUrl") String baseUrl,
        @Assisted("cookie") String cookie,
        @Assisted("sessionId") String sessionId,
        @Assisted("serverConfigId") String serverConfigId,
        @Assisted("directDevice") boolean directDevice,
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
  public boolean beginSync(@Nullable TerminalScreenV2Proto.ResumeToken resume) {
    return beginSync(resume, false);
  }

  @Override
  public boolean beginSync(@Nullable TerminalScreenV2Proto.ResumeToken resume,
                           boolean forceBaseline) {
    return sendHello(resume, forceBaseline);
  }

  @Override
  public void setLayoutLeaseId(@NonNull String leaseId) {
    // 只记录租约；拿到租约后的首次 resize 由 TerminalSessionRuntime 用最新尺寸驱动，
    // 这里不再回发 connect() 时的占位尺寸，避免先把无头终端改成 80x24 再改回来的抖动。
    this.layoutLeaseId = leaseId == null ? "" : leaseId;
  }

  @Override
  public boolean sendTextInput(@NonNull String text) {
    return sendInputFrame(ScreenMessageV2Builder.textInput(layoutLeaseId, text));
  }

  @Override
  public boolean sendPasteInput(@NonNull String text) {
    return sendInputFrame(ScreenMessageV2Builder.pasteInput(layoutLeaseId, text));
  }

  @Override
  public boolean sendKeyInput(@NonNull String key, boolean shift, boolean alt, boolean ctrl,
                           boolean meta, boolean pressed) {
    return sendInputFrame(ScreenMessageV2Builder.keyInput(
        layoutLeaseId, key, shift, alt, ctrl, meta, pressed));
  }

  @Override
  public boolean sendMouseInput(int row, int col, @NonNull String button, int wheelDelta,
                             boolean shift, boolean alt, boolean ctrl, boolean meta,
                             boolean pressed) {
    TerminalScreenV2Proto.MouseButton protoButton = mouseButtonFromString(button);
    return sendInputFrame(ScreenMessageV2Builder.mouseInput(
        layoutLeaseId, row, col, protoButton, wheelDelta, shift, alt, ctrl, meta, pressed));
  }

  @Override
  public void sendFocusInput(boolean focused) {
    if (deviceConnection == null || channelId == null || layoutLeaseId.isEmpty()) return;
    sendFrame(ScreenMessageV2Builder.focusInput(layoutLeaseId, focused),
        MuxOutboundQueue.FrameKind.INPUT, null);
  }

  @Override
  public boolean requestResize(int cols, int rows) {
    // 先记录最新尺寸（重连后 hello 也会用到），通道不可用时不发但状态保持真实。
    this.columns = clamp(cols, 10, 500);
    this.rows = clamp(rows, 5, 200);
    if (deviceConnection == null || channelId == null || layoutLeaseId.isEmpty()) return false;
    return sendFrame(ScreenMessageV2Builder.resize(layoutLeaseId, this.columns, this.rows),
        MuxOutboundQueue.FrameKind.CONTROL, null);
  }

  @Override
  public void acquireLayout(boolean interactive) {
    acquireLayout("", interactive);
  }

  @Override
  public void acquireLayout(@NonNull String requestId, boolean interactive) {
    if (deviceConnection == null || channelId == null) return;
    sendFrame(ScreenMessageV2Builder.acquireLayout(requestId, interactive),
        MuxOutboundQueue.FrameKind.CONTROL, null);
  }

  @Override
  public void releaseLayout() {
    String releasedLeaseId = layoutLeaseId;
    layoutLeaseId = "";
    if (deviceConnection == null || channelId == null) return;
    if (!releasedLeaseId.isEmpty()) {
      sendFrame(ScreenMessageV2Builder.releaseLayout(releasedLeaseId),
          MuxOutboundQueue.FrameKind.CONTROL, null);
    }
  }

  @Override
  public void sendClipboardResponse(@NonNull String requestId, boolean allowed, boolean timeout, @Nullable byte[] data) {
    if (deviceConnection == null || channelId == null) return;
    sendFrame(ScreenMessageV2Builder.clipboardResponse(requestId, allowed, timeout, data),
        MuxOutboundQueue.FrameKind.CONTROL, null);
  }

  @Override
  public boolean requestResync(
      long layoutEpoch, long screenRevision, @NonNull String reason) {
    return sendFrame(
        ScreenMessageV2Builder.resync(layoutEpoch, screenRevision),
        MuxOutboundQueue.FrameKind.CONTROL,
        null);
  }

  @Override
  public long transportGeneration() {
    DeviceConnection current = deviceConnection;
    return current != null ? current.transportGeneration() : 0L;
  }

  @Override
  public String serverDiagnosticIdentity() {
    return serverConfigId.isEmpty() ? baseUrl : serverConfigId;
  }

  @Override
  public String deviceDiagnosticIdentity() {
    return directDevice ? "direct:" + serverConfigId : relayDeviceId;
  }

  @Override
  public String channelDiagnosticIdentity() {
    return channelId == null ? "" : channelId;
  }

  @Override
  public String channelLifecycleDiagnosticIdentity() {
    return channelLifecycleId;
  }

  @Override
  public void requestReconnect(@NonNull String reason) {
    // Runtime 从 modelExecutor 触发最终恢复。DeviceConnection 内部已由专用
    // event loop 串行化；这里投递到 mainHandler，只为了保证 TerminalChannel 自身
    // 字段与页面生命周期串行。若页面已 close，字段会先被清空，
    // 排队任务自然失效，不会把已离开的终端重新打开。
    mainHandler.post(() -> {
      if (deviceConnection == null) return;
      if (channelId != null) {
        deviceConnection.closeChannel(channelId);
      }
      channelId = null;
      connectNow();
    });
  }

  @Override
  public void requestHelloSendFailedReconnect() {
    mainHandler.post(() -> {
      if (deviceConnection == null) return;
      deviceConnection.requestTransportReconnect(
          TransportReconnectTrigger.SCREEN_HELLO_SEND_FAILED,
          "screen Hello send failed");
    });
  }

  @Override
  public void close() {
    if (deviceConnection != null && channelId != null) {
      DeviceConnection connection = deviceConnection;
      String id = channelId;
      connection.closeChannelAndReleaseIfIdle(id, ConnectionCloseReason.RUNTIME_CLOSED,
          () -> deviceConnectionRegistry.removeIfSame(connection));
    }
    deviceConnection = null;
    channelId = null;
    channelLifecycleId = "";
  }

  private void connectNow() {
    if (deviceConnection == null || !deviceConnection.matches(baseUrl, cookie, relayDeviceId)) {
      if (deviceConnection != null) {
        deviceConnectionRegistry.releaseIfIdle(deviceConnection);
      }
      if (directDevice) {
        // Direct：与后台服务共享同一条 direct:{configId} 连接，不携带 x-device-id。
        deviceConnection = deviceConnectionRegistry.forDirectDevice(serverConfigId, baseUrl, cookie);
      } else {
        deviceConnection = deviceConnectionRegistry.forRelayDevice(baseUrl, cookie, relayDeviceId);
      }
      deviceConnection.updateCookie(cookie);
    }
    String localSessionId = DeviceConnection.localSessionId(sessionId, relayDeviceId);
    // 每次显式重建都使用新的 logical tunnel owner。ws-connected 不携带本地代际，
    // 若复用旧 tunnel id，旧握手的迟到控制帧可能被误认成新连接。
    String logicalChannelOwnerId = UUID.randomUUID().toString();
    channelLifecycleId = logicalChannelOwnerId;
    DeviceConnection channelConnection = deviceConnection;
    channelId = channelConnection.openScreenChannel(
        localSessionId, logicalChannelOwnerId, new DeviceConnection.ChannelListener() {
      @Override
      public void onConnected(String callbackChannelId) {
        if (!isCurrentCallback(channelConnection, callbackChannelId)) return;
        if (listener != null) listener.onConnected();
      }

      @Override
      public void onData(String callbackChannelId, byte[] payload, boolean binary) {
        if (!isCurrentCallback(channelConnection, callbackChannelId)) return;
        if (listener != null) listener.onScreenMessage(payload);
      }

      @Override
      public void onFailure(String callbackChannelId, ChannelFailure failure) {
        if (!isCurrentCallback(channelConnection, callbackChannelId)) return;
        switch (failure.kind) {
          case CHANNEL_NOT_FOUND:
          case REMOTE_CLOSED:
            // 会话不存在或服务端确认终端已结束，不再重开。
            if (listener != null) listener.onClosed();
            break;
          case AUTH_REQUIRED:
            // 401 只表示凭据过期，不表示远端 PTY 已结束。交给 Activity 级认证
            // 协调器刷新 cookie，成功后通过 reconnectFresh 重建 screen channel。
            if (listener != null) listener.onAuthenticationRequired(failure.message);
            break;
          case CLIENT_CLOSED:
            // 当前 channel 被新的 runtime owner 接管。主动 close() 不会产生此回调；
            // 因此这里必须关闭旧 runtime，防止它继续持有一个已失效的 HOT connection。
            if (listener != null) listener.onClosed();
            break;
          case MUX_TEMPORARY:
          case SERVER_TEMPORARY:
          default:
            // 可恢复：DeviceConnection 自身重连/重开 channel，仅通知上层展示断线状态。
            if (listener != null) listener.onDisconnected(failure.message);
            break;
        }
      }

      @Override
      public void onReconnectAttempt(int attempt) {
        if (channelConnection != deviceConnection) return;
        if (listener != null) listener.onDisconnected("reconnect attempt " + attempt);
      }
    });
  }

  private boolean isCurrentCallback(
      DeviceConnection callbackConnection, String callbackChannelId) {
    String currentChannelId = channelId;
    return callbackConnection == deviceConnection
        && currentChannelId != null
        && currentChannelId.equals(callbackChannelId);
  }

  private boolean sendHello(@Nullable TerminalScreenV2Proto.ResumeToken resume) {
    return sendHello(resume, false);
  }

  private boolean sendHello(@Nullable TerminalScreenV2Proto.ResumeToken resume,
                            boolean forceBaseline) {
    if (deviceConnection == null || channelId == null) return false;
    TerminalScreenV2Proto.InitialSyncMode mode = forceBaseline
        ? TerminalScreenV2Proto.InitialSyncMode.INITIAL_SYNC_MODE_FORCE_BASELINE
        : TerminalScreenV2Proto.InitialSyncMode.INITIAL_SYNC_MODE_AUTO;
    return sendFrame(ScreenMessageV2Builder.hello(
            columns, rows, resume, mode),
        MuxOutboundQueue.FrameKind.CONTROL, null);
  }

  private boolean sendInputFrame(byte[] payload) {
    if (deviceConnection == null || channelId == null || layoutLeaseId.isEmpty()) return false;
    return deviceConnection.sendTunnelFrame(channelId, payload, true,
        MuxOutboundQueue.FrameKind.INPUT, result -> {
          Listener current = listener;
          if (current != null) current.onInputSendResult(result.name());
        });
  }

  private boolean sendFrame(byte[] payload, MuxOutboundQueue.FrameKind kind,
                            DeviceConnection.TunnelSendCallback callback) {
    if (deviceConnection == null || channelId == null || payload == null) return false;
    return deviceConnection.sendTunnelFrame(channelId, payload, true, kind, callback);
  }

  /**
   * 现场捕获专用：返回当前 device connection（可能为 null，如尚未连接）。
   * 仅供诊断捕获通道（webterm.capture.v1）打开独立逻辑通道使用，不参与 screen 业务。
   */
  @androidx.annotation.Nullable
  public DeviceConnection captureDeviceConnection() {
    return deviceConnection;
  }

  /** 现场捕获专用：与 screen 通道一致的 localSessionId，使 capture 通道路由到同一 Agent 会话。 */
  @NonNull
  public String captureLocalSessionId() {
    return DeviceConnection.localSessionId(sessionId, relayDeviceId);
  }

  private static TerminalScreenV2Proto.MouseButton mouseButtonFromString(@NonNull String button) {
    switch (button) {
      case "left":
        return TerminalScreenV2Proto.MouseButton.MOUSE_BUTTON_LEFT;
      case "middle":
        return TerminalScreenV2Proto.MouseButton.MOUSE_BUTTON_MIDDLE;
      case "right":
        return TerminalScreenV2Proto.MouseButton.MOUSE_BUTTON_RIGHT;
      case "wheel":
        return TerminalScreenV2Proto.MouseButton.MOUSE_BUTTON_WHEEL;
      case "move":
        return TerminalScreenV2Proto.MouseButton.MOUSE_BUTTON_MOVE;
      default:
        return TerminalScreenV2Proto.MouseButton.MOUSE_BUTTON_UNSPECIFIED;
    }
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
