package com.webterm.feature.terminal.domain;

import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.core.session.ChannelFailure;
import com.webterm.core.session.DeviceConnection;
import com.webterm.core.session.DeviceConnectionRegistry;
import com.webterm.terminal.protocol.ScreenMessageBuilder;
import com.webterm.terminal.protocol.generated.TerminalScreenProto;
import com.webterm.terminal.model.ResumeToken;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

import java.util.UUID;

/**
 * 通过 device connection 建立 webterm.screen.v1 通道的 ScreenConnection 实现。
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
  private volatile Listener listener;
  private volatile String layoutLeaseId = "";
  private int columns;
  private int rows;
  private final ReliableInputTracker reliableInputTracker;

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
    this.reliableInputTracker = new ReliableInputTracker(
        mainHandler,
        (payload, callback) -> {
          DeviceConnection connection = deviceConnection;
          String id = channelId;
          if (connection == null || id == null) {
            callback.onResult(ReliableInputTracker.SendResult.CHANNEL_NOT_OPEN);
            return false;
          }
          return connection.tryEnqueueTunnelFrame(id, payload, true,
              result -> callback.onResult(mapSendResult(result)));
        },
        event -> {
          Listener current = listener;
          if (current == null) return;
          current.onInputDeliveryEvent(event);
          switch (event.type) {
            case INPUT_REJECTED:
            case INPUT_UNCERTAIN:
            case INPUT_QUEUE_FULL:
            case INPUT_ACK_TIMEOUT:
            case TERMINAL_INSTANCE_CHANGED:
            case CHANNEL_UNAVAILABLE:
            case TRANSPORT_REJECTED:
              current.onInputDeliveryUncertain(event.message);
              break;
            default:
              break;
          }
        });
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
  public boolean beginSync(@NonNull ResumeToken resumeToken) {
    return sendHello(TerminalResumePolicy.effectiveToken(resumeToken));
  }

  @Override
  public void setLayoutLeaseId(@NonNull String leaseId) {
    // 只记录租约；拿到租约后的首次 resize 由 TerminalSessionRuntime 用最新尺寸驱动，
    // 这里不再回发 connect() 时的占位尺寸，避免先把无头终端改成 80x24 再改回来的抖动。
    this.layoutLeaseId = leaseId == null ? "" : leaseId;
    if (!this.layoutLeaseId.isEmpty()) reliableInputTracker.resendPending(this.layoutLeaseId);
  }

  @Override
  public void sendTextInput(@NonNull String text) {
    if (deviceConnection == null || channelId == null || layoutLeaseId.isEmpty()) return;
    reliableInputTracker.send((clientId, seq) ->
        ScreenMessageBuilder.textInput(layoutLeaseId, clientId, seq, text));
  }

  @Override
  public void sendPasteInput(@NonNull String text) {
    if (deviceConnection == null || channelId == null || layoutLeaseId.isEmpty()) return;
    reliableInputTracker.send((clientId, seq) ->
        ScreenMessageBuilder.pasteInput(layoutLeaseId, clientId, seq, text));
  }

  @Override
  public void sendKeyInput(@NonNull String key, boolean shift, boolean alt, boolean ctrl,
                           boolean meta, boolean pressed) {
    if (deviceConnection == null || channelId == null || layoutLeaseId.isEmpty()) return;
    reliableInputTracker.send((clientId, seq) -> ScreenMessageBuilder.keyInput(
        layoutLeaseId, clientId, seq, key, shift, alt, ctrl, meta, pressed));
  }

  @Override
  public void sendMouseInput(int row, int col, @NonNull String button, int wheelDelta,
                             boolean shift, boolean alt, boolean ctrl, boolean meta,
                             boolean pressed) {
    if (deviceConnection == null || channelId == null || layoutLeaseId.isEmpty()) return;
    TerminalScreenProto.MouseButton protoButton = mouseButtonFromString(button);
    reliableInputTracker.send((clientId, seq) -> ScreenMessageBuilder.mouseInput(
        layoutLeaseId, clientId, seq, row, col, protoButton, wheelDelta,
        shift, alt, ctrl, meta, pressed));
  }

  @Override
  public void sendFocusInput(boolean focused) {
    if (deviceConnection == null || channelId == null || layoutLeaseId.isEmpty()) return;
    reliableInputTracker.send((clientId, seq) ->
        ScreenMessageBuilder.focusInput(layoutLeaseId, clientId, seq, focused));
  }

  @Override
  public boolean requestResize(int cols, int rows) {
    // 先记录最新尺寸（重连后 hello 也会用到），通道不可用时不发但状态保持真实。
    this.columns = clamp(cols, 10, 500);
    this.rows = clamp(rows, 5, 200);
    if (deviceConnection == null || channelId == null || layoutLeaseId.isEmpty()) return false;
    return deviceConnection.sendTunnelFrame(channelId,
        ScreenMessageBuilder.resize(layoutLeaseId, this.columns, this.rows), true);
  }

  @Override
  public void acquireLayout(boolean interactive) {
    acquireLayout("", interactive);
  }

  @Override
  public void acquireLayout(@NonNull String requestId, boolean interactive) {
    if (deviceConnection == null || channelId == null) return;
    deviceConnection.sendTunnelFrame(
        channelId, ScreenMessageBuilder.acquireLayout(requestId, interactive), true);
  }

  @Override
  public void releaseLayout() {
    String releasedLeaseId = layoutLeaseId;
    layoutLeaseId = "";
    if (deviceConnection == null || channelId == null) return;
    if (!releasedLeaseId.isEmpty()) {
      deviceConnection.sendTunnelFrame(
          channelId, ScreenMessageBuilder.releaseLayout(releasedLeaseId), true);
    }
  }

  @Override
  public void sendClipboardResponse(@NonNull String requestId, boolean allowed, boolean timeout, @Nullable byte[] data) {
    if (deviceConnection == null || channelId == null) return;
    deviceConnection.sendTunnelFrame(channelId,
        ScreenMessageBuilder.clipboardResponse(requestId, allowed, timeout, data), true);
  }

  @Override
  public boolean requestHistoryPage(@NonNull String requestId, long beforeHistorySeq, int limit) {
    if (deviceConnection == null || channelId == null) return false;
    return deviceConnection.sendTunnelFrame(
        channelId, ScreenMessageBuilder.historyRequest(requestId, beforeHistorySeq, limit), true);
  }

  @Override
  public void requestResync(long layoutEpoch, long screenRevision, @NonNull String reason) {
    if (deviceConnection == null || channelId == null) return;
    deviceConnection.sendTunnelFrame(channelId,
        ScreenMessageBuilder.resync(layoutEpoch, screenRevision, reason), true);
  }

  @Override
  public void requestReconnect(@NonNull String reason) {
    // Runtime 从 modelExecutor 触发最终恢复。DeviceConnection 内部已由专用
    // event loop 串行化；这里投递到 mainHandler，只为了保证 TerminalChannel 自身
    // 字段与页面生命周期串行。若页面已 close，字段会先被清空，
    // 排队任务自然失效，不会把已离开的终端重新打开。
    mainHandler.post(() -> {
      if (deviceConnection == null) return;
      reliableInputTracker.markConnectionUnconfirmed();
      if (channelId != null) {
        deviceConnection.closeChannel(channelId);
      }
      channelId = null;
      connectNow();
    });
  }

  @Override
  public void close() {
    if (deviceConnection != null && channelId != null) {
      deviceConnection.closeChannel(channelId);
      deviceConnectionRegistry.releaseIfIdle(deviceConnection);
    }
    deviceConnection = null;
    channelId = null;
    reliableInputTracker.clear();
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
    // 若复用旧 tunnel id，旧握手的迟到 ACK 可能被误认成新连接；稳定的
    // clientInstanceId 仍单独用于输入去重，不受通道代际轮换影响。
    String logicalChannelOwnerId = UUID.randomUUID().toString();
    channelId = deviceConnection.openScreenChannel(
        localSessionId, logicalChannelOwnerId, new DeviceConnection.ChannelListener() {
      @Override
      public void onConnected(String channelId) {
        if (listener != null) listener.onConnected();
      }

      @Override
      public void onData(String channelId, byte[] payload, boolean binary) {
        if (listener != null) listener.onScreenMessage(payload);
      }

      @Override
      public void onFailure(String channelId, ChannelFailure failure) {
        reliableInputTracker.markConnectionUnconfirmed();
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
        if (listener != null) listener.onDisconnected("reconnect attempt " + attempt);
      }
    });
  }

  private boolean sendHello(@NonNull ResumeToken resumeToken) {
    if (deviceConnection == null || channelId == null) return false;
    return deviceConnection.sendTunnelFrame(
        channelId, ScreenMessageBuilder.hello(
            columns, rows, resumeToken, reliableInputTracker.clientInstanceId()), true);
  }

  @Override
  @NonNull
  public ReliableInputTracker reliableInputTracker() {
    return reliableInputTracker;
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

  private static ReliableInputTracker.SendResult mapSendResult(
      DeviceConnection.TunnelSendResult result) {
    switch (result) {
      case WEBSOCKET_ENQUEUED:
        return ReliableInputTracker.SendResult.WEBSOCKET_ENQUEUED;
      case LOCAL_QUEUE_FULL:
        return ReliableInputTracker.SendResult.QUEUE_FULL;
      case CHANNEL_NOT_OPEN:
        return ReliableInputTracker.SendResult.CHANNEL_NOT_OPEN;
      case TRANSPORT_REJECTED:
        return ReliableInputTracker.SendResult.TRANSPORT_REJECTED;
      case CONNECTION_STOPPED:
      default:
        return ReliableInputTracker.SendResult.CONNECTION_STOPPED;
    }
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
