package com.webterm.feature.terminal.domain;

import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 单个 terminal runtime 的可靠输入账本。
 *
 * <p>只跟踪 input sequence、实例身份、预算、ACK、超时和安全重发事实；本类不关闭
 * channel、不重建 physical Mux，也不选择恢复策略。</p>
 */
public final class ReliableInputTracker {
  static final int DEFAULT_MAX_INPUTS = 256;
  static final long DEFAULT_MAX_BYTES = 4L * 1024L * 1024L;
  static final long DEFAULT_ACK_TIMEOUT_MS = 60_000L;

  public enum EventType {
    INPUT_WRITTEN,
    INPUT_IGNORED,
    INPUT_REJECTED,
    INPUT_UNCERTAIN,
    INPUT_QUEUE_FULL,
    INPUT_ACK_TIMEOUT,
    TERMINAL_INSTANCE_CHANGED,
    CHANNEL_UNAVAILABLE,
    TRANSPORT_REJECTED
  }

  public static final class Event {
    public final EventType type;
    public final String message;

    Event(EventType type, String message) {
      this.type = type;
      this.message = message == null ? "" : message;
    }
  }

  public enum SendResult {
    WEBSOCKET_ENQUEUED,
    QUEUE_FULL,
    CHANNEL_NOT_OPEN,
    TRANSPORT_REJECTED,
    CONNECTION_STOPPED
  }

  public interface SendCallback { void onResult(@NonNull SendResult result); }
  public interface Transport {
    boolean enqueue(@NonNull byte[] payload, @NonNull SendCallback callback);
  }
  public interface Listener { void onEvent(@NonNull Event event); }
  public interface PayloadFactory {
    byte[] create(@NonNull String clientInstanceId, long inputSeq);
  }
  interface Clock { long nowMs(); }

  private enum DeliveryState { UNSENT, LOCAL_QUEUED, WEBSOCKET_ENQUEUED }

  private static final class PendingInput {
    final long seq;
    final byte[] payload;
    final String terminalInstanceId;
    final long createdAtMs;
    long lastSentGeneration;
    long queuedGeneration;
    DeliveryState state = DeliveryState.UNSENT;

    PendingInput(long seq, byte[] payload, String terminalInstanceId, long createdAtMs) {
      this.seq = seq;
      this.payload = payload;
      this.terminalInstanceId = terminalInstanceId;
      this.createdAtMs = createdAtMs;
    }
  }

  private final Object lock = new Object();
  private final Handler scheduler;
  private final Transport transport;
  private final Listener listener;
  private final Clock clock;
  private final int maxInputs;
  private final long maxBytes;
  private final long ackTimeoutMs;
  private final String clientInstanceId = UUID.randomUUID().toString();
  private final LinkedHashMap<Long, PendingInput> pendingInputs = new LinkedHashMap<>();
  private long pendingBytes;
  private boolean expiryScheduled;
  private long nextInputSeq = 1L;
  private String terminalInstanceId = "";
  private boolean identityConfirmed;
  private long connectionGeneration = 1L;
  private final Runnable expiryRunnable;

  public ReliableInputTracker(@NonNull Handler scheduler,
                              @NonNull Transport transport,
                              @NonNull Listener listener) {
    this(scheduler, transport, listener, android.os.SystemClock::elapsedRealtime,
        DEFAULT_MAX_INPUTS, DEFAULT_MAX_BYTES, DEFAULT_ACK_TIMEOUT_MS);
  }

  ReliableInputTracker(Handler scheduler, Transport transport, Listener listener, Clock clock,
                       int maxInputs, long maxBytes, long ackTimeoutMs) {
    this.scheduler = scheduler;
    this.transport = transport;
    this.listener = listener;
    this.clock = clock;
    this.maxInputs = maxInputs;
    this.maxBytes = maxBytes;
    this.ackTimeoutMs = ackTimeoutMs;
    this.expiryRunnable = this::expirePendingInputs;
  }

  @NonNull
  public String clientInstanceId() {
    return clientInstanceId;
  }

  public void send(@NonNull PayloadFactory factory) {
    long seq;
    PendingInput pending;
    synchronized (lock) {
      seq = nextInputSeq++;
      if (!identityConfirmed || terminalInstanceId.isEmpty()) {
        emit(EventType.INPUT_UNCERTAIN, "终端实例尚未确认，输入未发送");
        return;
      }
      byte[] payload = factory.create(clientInstanceId, seq);
      if (payload == null) {
        emit(EventType.INPUT_REJECTED, "输入编码失败");
        return;
      }
      if (pendingInputs.size() >= maxInputs || pendingBytes + payload.length > maxBytes) {
        emit(EventType.INPUT_QUEUE_FULL, "未确认输入队列已满，本次输入未发送");
        return;
      }
      pending = new PendingInput(seq, payload, terminalInstanceId, clock.nowMs());
      pendingInputs.put(seq, pending);
      pendingBytes += payload.length;
      scheduleExpiryLocked();
    }
    enqueue(pending, pending.payload, connectionGeneration);
  }

  public void observeTerminalInstance(@Nullable String instanceId) {
    if (instanceId == null || instanceId.isEmpty()) return;
    int discarded = 0;
    synchronized (lock) {
      if (identityConfirmed && instanceId.equals(terminalInstanceId)) return;
      terminalInstanceId = instanceId;
      identityConfirmed = true;
      java.util.Iterator<Map.Entry<Long, PendingInput>> iterator =
          pendingInputs.entrySet().iterator();
      while (iterator.hasNext()) {
        PendingInput pending = iterator.next().getValue();
        if (!pending.terminalInstanceId.isEmpty()
            && !instanceId.equals(pending.terminalInstanceId)) {
          iterator.remove();
          pendingBytes -= pending.payload.length;
          discarded++;
        }
      }
    }
    if (discarded > 0) {
      emit(EventType.TERMINAL_INSTANCE_CHANGED,
          discarded + " 条未确认输入属于旧终端实例，已停止自动重发");
    }
  }

  public void markConnectionUnconfirmed() {
    synchronized (lock) {
      identityConfirmed = false;
      connectionGeneration++;
    }
  }

  public void resendPending(@NonNull String leaseId) {
    if (leaseId.isEmpty()) return;
    List<PendingInput> resend = new ArrayList<>();
    List<byte[]> payloads = new ArrayList<>();
    long generation;
    synchronized (lock) {
      if (!identityConfirmed) return;
      generation = connectionGeneration;
      for (PendingInput pending : pendingInputs.values()) {
        if (pending.lastSentGeneration == generation
            || (pending.state == DeliveryState.LOCAL_QUEUED
                && pending.queuedGeneration == generation)) continue;
        byte[] payload = withCurrentLease(pending.payload, leaseId);
        if (payload == null) continue;
        resend.add(pending);
        payloads.add(payload);
      }
    }
    for (int i = 0; i < resend.size(); i++) enqueue(resend.get(i), payloads.get(i), generation);
  }

  public void handleInputAck(@NonNull TerminalScreenV2Proto.InputAck ack) {
    handleInputAck(ack.getClientInstanceId(), ack.getInputSeq(), ack.getTerminalInstanceId(),
        ack.getStatusValue());
  }

  private void handleInputAck(@NonNull String ackClientInstanceId, long inputSeq,
                              @NonNull String ackTerminalInstanceId, int statusValue) {
    if (!clientInstanceId.equals(ackClientInstanceId) || inputSeq == 0) return;
    PendingInput pending;
    synchronized (lock) {
      pending = pendingInputs.remove(inputSeq);
      if (pending != null) pendingBytes -= pending.payload.length;
    }
    if (pending == null) return;
    if (!pending.terminalInstanceId.isEmpty()
        && !pending.terminalInstanceId.equals(ackTerminalInstanceId)) {
      emit(EventType.TERMINAL_INSTANCE_CHANGED,
          "终端实例已变更，上一条输入的投递状态不确定");
      return;
    }
    switch (statusValue) {
      case 1:
        emit(EventType.INPUT_WRITTEN, "输入已写入 PTY");
        return;
      case 2:
        emit(EventType.INPUT_IGNORED, "输入无需写入 PTY");
        return;
      case 3:
        emit(EventType.INPUT_REJECTED, "输入未被远端接受");
        return;
      case 4:
      case 0:
      default:
        emit(EventType.INPUT_UNCERTAIN, "输入写入 PTY 的结果不确定，已禁止自动重发");
    }
  }

  public void clear() {
    synchronized (lock) {
      pendingInputs.clear();
      pendingBytes = 0L;
      expiryScheduled = false;
      identityConfirmed = false;
    }
    scheduler.removeCallbacks(expiryRunnable);
  }

  int pendingCount() {
    synchronized (lock) {
      return pendingInputs.size();
    }
  }

  private void enqueue(PendingInput pending, byte[] payload, long generation) {
    synchronized (lock) {
      if (pendingInputs.get(pending.seq) != pending) return;
      if (pending.lastSentGeneration == generation
          || (pending.state == DeliveryState.LOCAL_QUEUED
              && pending.queuedGeneration == generation)) return;
      pending.state = DeliveryState.LOCAL_QUEUED;
      pending.queuedGeneration = generation;
    }
    transport.enqueue(payload, result -> onSendResult(pending.seq, generation, result));
  }

  private void onSendResult(long seq, long generation, SendResult result) {
    synchronized (lock) {
      PendingInput pending = pendingInputs.get(seq);
      if (pending == null || pending.queuedGeneration != generation) return;
      if (result == SendResult.WEBSOCKET_ENQUEUED) {
        pending.lastSentGeneration = generation;
        pending.state = DeliveryState.WEBSOCKET_ENQUEUED;
        return;
      }
      pending.state = DeliveryState.UNSENT;
      pending.queuedGeneration = 0L;
    }
    switch (result) {
      case QUEUE_FULL:
        emit(EventType.INPUT_QUEUE_FULL, "输入未进入设备发送队列");
        break;
      case CHANNEL_NOT_OPEN:
      case CONNECTION_STOPPED:
        emit(EventType.CHANNEL_UNAVAILABLE, "screen channel 当前不可用");
        break;
      case TRANSPORT_REJECTED:
        emit(EventType.TRANSPORT_REJECTED, "物理传输拒绝输入帧");
        break;
      default:
        break;
    }
  }

  private void scheduleExpiryLocked() {
    if (expiryScheduled || pendingInputs.isEmpty()) return;
    PendingInput oldest = pendingInputs.entrySet().iterator().next().getValue();
    long delayMs = Math.max(1L, ackTimeoutMs - (clock.nowMs() - oldest.createdAtMs));
    expiryScheduled = true;
    scheduler.postDelayed(expiryRunnable, delayMs);
  }

  private void expirePendingInputs() {
    int expired = 0;
    long now = clock.nowMs();
    synchronized (lock) {
      java.util.Iterator<Map.Entry<Long, PendingInput>> iterator =
          pendingInputs.entrySet().iterator();
      while (iterator.hasNext()) {
        PendingInput pending = iterator.next().getValue();
        if (now - pending.createdAtMs < ackTimeoutMs) break;
        iterator.remove();
        pendingBytes -= pending.payload.length;
        expired++;
      }
      expiryScheduled = false;
      scheduleExpiryLocked();
    }
    if (expired > 0) {
      emit(EventType.INPUT_ACK_TIMEOUT,
          expired + " 条输入超过确认时限，PTY 写入结果不确定");
    }
  }

  private void emit(EventType type, String message) {
    listener.onEvent(new Event(type, message));
  }

  @Nullable
  private static byte[] withCurrentLease(byte[] payload, String leaseId) {
    try {
      TerminalScreenV2Proto.ScreenEnvelope envelope =
          TerminalScreenV2Proto.ScreenEnvelope.parseFrom(payload);
      if (envelope.getProtocolVersion() == 2 && envelope.hasInput()) {
        return envelope.toBuilder()
            .setInput(envelope.getInput().toBuilder().setLeaseId(leaseId).build())
            .build()
            .toByteArray();
      }
    } catch (Exception ignored) {
      return null;
    }
    return null;
  }
}
