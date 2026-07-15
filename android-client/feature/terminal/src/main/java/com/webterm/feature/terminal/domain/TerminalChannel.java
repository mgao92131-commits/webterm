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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 通过 device connection 建立 webterm.screen.v1 通道的 ScreenConnection 实现。
 */
public final class TerminalChannel implements TerminalSessionRuntime.ScreenConnection {

  private static final int MAX_UNACKED_INPUTS = 256;

  private static final class PendingInput {
    final long seq;
    final byte[] payload;
    final String terminalInstanceId;
    long lastSentGeneration;

    PendingInput(long seq, byte[] payload, String terminalInstanceId, long lastSentGeneration) {
      this.seq = seq;
      this.payload = payload;
      this.terminalInstanceId = terminalInstanceId;
      this.lastSentGeneration = lastSentGeneration;
    }
  }

  private final Handler mainHandler;
  private final DeviceConnectionRegistry deviceConnectionRegistry;
  private final String baseUrl;
  private final String cookie;
  private final String sessionId;
  private final String relayDeviceId;

  private volatile DeviceConnection deviceConnection;
  private volatile String channelId;
  private volatile Listener listener;
  private volatile String layoutLeaseId = "";
  private int columns;
  private int rows;
  private final Object inputLock = new Object();
  private final String clientInstanceId = UUID.randomUUID().toString();
  private final LinkedHashMap<Long, PendingInput> unackedInputs = new LinkedHashMap<>();
  private long nextInputSeq = 1L;
  private String terminalInstanceId = "";
  private boolean identityConfirmedForConnection;
  private long inputConnectionGeneration = 1L;

  @AssistedInject
  public TerminalChannel(
      Handler mainHandler,
      DeviceConnectionRegistry deviceConnectionRegistry,
      @Assisted("baseUrl") String baseUrl,
      @Assisted("cookie") String cookie,
      @Assisted("sessionId") String sessionId,
      @Assisted("relayDeviceId") String relayDeviceId) {
    this.mainHandler = mainHandler;
    this.deviceConnectionRegistry = deviceConnectionRegistry;
    this.baseUrl = baseUrl;
    this.cookie = cookie;
    this.sessionId = sessionId;
    this.relayDeviceId = relayDeviceId == null ? "" : relayDeviceId;
  }

  @AssistedFactory
  public interface Factory {
    TerminalChannel create(
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
  public boolean beginSync(@NonNull ResumeToken resumeToken) {
    sendHello(TerminalResumePolicy.effectiveToken(resumeToken));
    return deviceConnection != null && channelId != null;
  }

  @Override
  public void setLayoutLeaseId(@NonNull String leaseId) {
    // 只记录租约；拿到租约后的首次 resize 由 TerminalSessionRuntime 用最新尺寸驱动，
    // 这里不再回发 connect() 时的占位尺寸，避免先把无头终端改成 80x24 再改回来的抖动。
    this.layoutLeaseId = leaseId == null ? "" : leaseId;
    if (!this.layoutLeaseId.isEmpty()) resendPendingInputsIfReady();
  }

  @Override
  public void sendTextInput(@NonNull String text) {
    if (deviceConnection == null || channelId == null || layoutLeaseId.isEmpty()) return;
    long seq = nextInputSeq();
    sendReliableInput(seq,
        ScreenMessageBuilder.textInput(layoutLeaseId, clientInstanceId, seq, text));
  }

  @Override
  public void sendPasteInput(@NonNull String text) {
    if (deviceConnection == null || channelId == null || layoutLeaseId.isEmpty()) return;
    long seq = nextInputSeq();
    sendReliableInput(seq,
        ScreenMessageBuilder.pasteInput(layoutLeaseId, clientInstanceId, seq, text));
  }

  @Override
  public void sendKeyInput(@NonNull String key, boolean shift, boolean alt, boolean ctrl,
                           boolean meta, boolean pressed) {
    if (deviceConnection == null || channelId == null || layoutLeaseId.isEmpty()) return;
    long seq = nextInputSeq();
    sendReliableInput(seq, ScreenMessageBuilder.keyInput(
        layoutLeaseId, clientInstanceId, seq, key, shift, alt, ctrl, meta, pressed));
  }

  @Override
  public void sendMouseInput(int row, int col, @NonNull String button, int wheelDelta,
                             boolean shift, boolean alt, boolean ctrl, boolean meta,
                             boolean pressed) {
    if (deviceConnection == null || channelId == null || layoutLeaseId.isEmpty()) return;
    TerminalScreenProto.MouseButton protoButton = mouseButtonFromString(button);
    long seq = nextInputSeq();
    sendReliableInput(seq, ScreenMessageBuilder.mouseInput(
        layoutLeaseId, clientInstanceId, seq, row, col, protoButton, wheelDelta,
        shift, alt, ctrl, meta, pressed));
  }

  @Override
  public void sendFocusInput(boolean focused) {
    if (deviceConnection == null || channelId == null || layoutLeaseId.isEmpty()) return;
    long seq = nextInputSeq();
    sendReliableInput(seq,
        ScreenMessageBuilder.focusInput(layoutLeaseId, clientInstanceId, seq, focused));
  }

  @Override
  public void requestResize(int cols, int rows) {
    // 先记录最新尺寸（重连后 hello 也会用到），通道不可用时不发但状态保持真实。
    this.columns = clamp(cols, 10, 500);
    this.rows = clamp(rows, 5, 200);
    if (deviceConnection == null || channelId == null || layoutLeaseId.isEmpty()) return;
    deviceConnection.sendTunnelFrame(channelId,
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
  public boolean requestHistoryPage(@NonNull String requestId, long beforeLineId, int limit) {
    if (deviceConnection == null || channelId == null) return false;
    return deviceConnection.sendTunnelFrame(
        channelId, ScreenMessageBuilder.historyRequest(requestId, beforeLineId, limit), true);
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
      markInputIdentityUnconfirmed();
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
    synchronized (inputLock) {
      unackedInputs.clear();
      identityConfirmedForConnection = false;
    }
  }

  private void connectNow() {
    if (deviceConnection == null || !deviceConnection.matches(baseUrl, cookie, relayDeviceId)) {
      if (deviceConnection != null) {
        deviceConnectionRegistry.releaseIfIdle(deviceConnection);
      }
      deviceConnection = deviceConnectionRegistry.forDevice(baseUrl, cookie, relayDeviceId);
      deviceConnection.updateCookie(cookie);
    }
    String localSessionId = DeviceConnection.localSessionId(sessionId, relayDeviceId);
    channelId = deviceConnection.openScreenChannel(
        localSessionId, clientInstanceId, new DeviceConnection.ChannelListener() {
      @Override
      public void onConnected(String channelId) {
        if (listener != null) listener.onConnected();
      }

      @Override
      public void onData(String channelId, byte[] payload, boolean binary) {
        if (binary && handleReliabilityEnvelope(payload)) return;
        if (listener != null) listener.onScreenMessage(payload);
      }

      @Override
      public void onFailure(String channelId, ChannelFailure failure) {
        markInputIdentityUnconfirmed();
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

  private void sendHello(@NonNull ResumeToken resumeToken) {
    if (deviceConnection == null || channelId == null) return;
    deviceConnection.sendTunnelFrame(
        channelId, ScreenMessageBuilder.hello(columns, rows, resumeToken, clientInstanceId), true);
  }

  private long nextInputSeq() {
    synchronized (inputLock) {
      return nextInputSeq++;
    }
  }

  private void sendReliableInput(long seq, @NonNull byte[] payload) {
    PendingInput dropped = null;
    synchronized (inputLock) {
      if (unackedInputs.size() >= MAX_UNACKED_INPUTS) {
        Map.Entry<Long, PendingInput> oldest = unackedInputs.entrySet().iterator().next();
        dropped = oldest.getValue();
        unackedInputs.remove(oldest.getKey());
      }
      unackedInputs.put(seq,
          new PendingInput(seq, payload, terminalInstanceId, inputConnectionGeneration));
    }
    if (dropped != null) {
      notifyInputUncertain("未确认的输入过多，最早一条的投递状态不确定");
    }
    DeviceConnection connection = deviceConnection;
    String id = channelId;
    if (connection != null && id != null) {
      connection.sendTunnelFrame(id, payload, true);
    }
  }

  private boolean handleReliabilityEnvelope(@NonNull byte[] payload) {
    if (payload.length > 2 * 1024 * 1024) return false;
    final TerminalScreenProto.ScreenEnvelope envelope;
    try {
      envelope = TerminalScreenProto.ScreenEnvelope.parseFrom(payload);
    } catch (Exception ignored) {
      return false;
    }
    switch (envelope.getPayloadCase()) {
      case INPUT_ACK:
        handleInputAck(envelope.getInputAck());
        return true;
      case INFO:
        observeTerminalInstance(envelope.getInfo().getInstanceId());
        return false;
      case SNAPSHOT:
        observeTerminalInstance(envelope.getSnapshot().getInstanceId());
        return false;
      case PATCH:
        observeTerminalInstance(envelope.getPatch().getInstanceId());
        return false;
      case RESUME_ACK:
        observeTerminalInstance(envelope.getResumeAck().getInstanceId());
        return false;
      default:
        return false;
    }
  }

  private void handleInputAck(@NonNull TerminalScreenProto.InputAck ack) {
    if (!clientInstanceId.equals(ack.getClientInstanceId()) || ack.getInputSeq() == 0) return;
    PendingInput pending;
    synchronized (inputLock) {
      pending = unackedInputs.remove(ack.getInputSeq());
    }
    if (pending == null) return;
    if (!pending.terminalInstanceId.isEmpty()
        && !pending.terminalInstanceId.equals(ack.getTerminalInstanceId())) {
      notifyInputUncertain("终端实例已变更，上一条输入的投递状态不确定");
      return;
    }
    switch (ack.getStatus()) {
      case INPUT_ACK_STATUS_WRITTEN:
      case INPUT_ACK_STATUS_IGNORED:
        return;
      case INPUT_ACK_STATUS_REJECTED:
        notifyInputUncertain("输入未被远端接受");
        return;
      case INPUT_ACK_STATUS_UNCERTAIN:
      case INPUT_ACK_STATUS_UNSPECIFIED:
      default:
        notifyInputUncertain("输入写入 PTY 的结果不确定，已禁止自动重发");
    }
  }

  private void observeTerminalInstance(@Nullable String instanceId) {
    if (instanceId == null || instanceId.isEmpty()) return;
    int uncertainCount = 0;
    synchronized (inputLock) {
      if (identityConfirmedForConnection && instanceId.equals(terminalInstanceId)) return;
      terminalInstanceId = instanceId;
      identityConfirmedForConnection = true;
      java.util.Iterator<Map.Entry<Long, PendingInput>> iterator = unackedInputs.entrySet().iterator();
      while (iterator.hasNext()) {
        PendingInput pending = iterator.next().getValue();
        if (!pending.terminalInstanceId.isEmpty()
            && !instanceId.equals(pending.terminalInstanceId)) {
          iterator.remove();
          uncertainCount++;
        }
      }
    }
    if (uncertainCount > 0) {
      notifyInputUncertain(uncertainCount + " 条未确认输入属于旧终端实例，已停止自动重发");
    }
    resendPendingInputsIfReady();
  }

  private void markInputIdentityUnconfirmed() {
    synchronized (inputLock) {
      identityConfirmedForConnection = false;
      inputConnectionGeneration++;
    }
  }

  private void resendPendingInputsIfReady() {
    DeviceConnection connection = deviceConnection;
    String id = channelId;
    String leaseId = layoutLeaseId;
    if (connection == null || id == null || leaseId.isEmpty()) return;
    List<byte[]> resend = new ArrayList<>();
    synchronized (inputLock) {
      if (!identityConfirmedForConnection) return;
      for (PendingInput pending : unackedInputs.values()) {
        if (pending.lastSentGeneration == inputConnectionGeneration) continue;
        byte[] payload = withCurrentLease(pending.payload, leaseId);
        if (payload == null) continue;
        pending.lastSentGeneration = inputConnectionGeneration;
        resend.add(payload);
      }
    }
    for (byte[] payload : resend) {
      connection.sendTunnelFrame(id, payload, true);
    }
  }

  @Nullable
  private static byte[] withCurrentLease(@NonNull byte[] payload, @NonNull String leaseId) {
    try {
      TerminalScreenProto.ScreenEnvelope envelope =
          TerminalScreenProto.ScreenEnvelope.parseFrom(payload);
      if (!envelope.hasInput()) return null;
      return envelope.toBuilder()
          .setInput(envelope.getInput().toBuilder().setLeaseId(leaseId).build())
          .build()
          .toByteArray();
    } catch (Exception ignored) {
      return null;
    }
  }

  private void notifyInputUncertain(@NonNull String message) {
    Listener current = listener;
    if (current != null) current.onInputDeliveryUncertain(message);
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
