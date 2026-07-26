package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;

/** 连接代际感知的有界 screen mailbox；overflow 会生成先于后续消息处理的 fence。 */
public final class ScreenMailbox {
  public enum MessageKind {
    BASELINE,
    RESUME_ACCEPTED,
    TERMINAL_COMMIT,
    HISTORY_RANGE,
    INPUT_ACK,
    LAYOUT_LEASE,
    EFFECT,
    EXIT,
    PONG,
    OTHER,
    UNKNOWN
  }

  static boolean isProjectionMessage(@NonNull MessageKind kind) {
    return kind == MessageKind.BASELINE
        || kind == MessageKind.RESUME_ACCEPTED
        || kind == MessageKind.TERMINAL_COMMIT
        || kind == MessageKind.UNKNOWN;
  }

  public static final class Message {
    public final long connectionEpoch;
    public final long mailboxGeneration;
    public final TerminalSessionRuntime.ScreenConnection sourceConnection;
    public final byte[] payload;
    public final MessageKind kind;
    public final long enqueuedAtNanos;

    Message(long connectionEpoch, long mailboxGeneration,
            TerminalSessionRuntime.ScreenConnection sourceConnection, byte[] payload,
            MessageKind kind) {
      this(connectionEpoch, mailboxGeneration, sourceConnection, payload, kind, System.nanoTime());
    }

    Message(long connectionEpoch, long mailboxGeneration,
            TerminalSessionRuntime.ScreenConnection sourceConnection, byte[] payload,
            MessageKind kind, long enqueuedAtNanos) {
      this.connectionEpoch = connectionEpoch;
      this.mailboxGeneration = mailboxGeneration;
      this.sourceConnection = sourceConnection;
      this.payload = payload;
      this.kind = kind;
      this.enqueuedAtNanos = enqueuedAtNanos;
    }
  }

  public static final class Fence {
    public final String reason;
    public final long discardedBytes;
    public final long discardedMessages;
    public final long overflowCount;

    Fence(String reason, long discardedBytes, long discardedMessages, long overflowCount) {
      this.reason = reason;
      this.discardedBytes = discardedBytes;
      this.discardedMessages = discardedMessages;
      this.overflowCount = overflowCount;
    }
  }

  public static final class Offer {
    public final boolean scheduleDrain;
    public final long pendingBytes;

    Offer(boolean scheduleDrain, long pendingBytes) {
      this.scheduleDrain = scheduleDrain;
      this.pendingBytes = pendingBytes;
    }
  }

  public static final class Drain {
    public final Message message;
    public final Fence fence;

    Drain(Message message, Fence fence) {
      this.message = message;
      this.fence = fence;
    }
  }

  private final int maxMessages;
  private final long maxBytes;
  /** Revision-bearing lane. Encoded projection messages are never merged or reordered. */
  private final ArrayDeque<Message> projectionMessages = new ArrayDeque<>();
  /** Revision-independent history/control lane. */
  private final ArrayDeque<Message> controlMessages = new ArrayDeque<>();
  private boolean drainScheduled;
  private long pendingBytes;
  private int pendingProjectionMessages;
  private long pendingProjectionBytes;
  private volatile long generation;
  private boolean fencePending;
  private String fenceReason = "";
  private long fenceBytes;
  private long fenceMessages;
  private long fenceOverflows;

  public ScreenMailbox(int maxMessages, long maxBytes) {
    this.maxMessages = maxMessages;
    this.maxBytes = maxBytes;
  }

  public synchronized Offer offer(long connectionEpoch,
                                  @NonNull TerminalSessionRuntime.ScreenConnection source,
                                  @NonNull byte[] payload,
                                  boolean validFrameSize,
                                  @NonNull MessageKind kind) {
    boolean projection = isProjectionMessage(kind);
    long nextProjectionBytes = pendingProjectionBytes + (projection ? payload.length : 0L);
    if (!validFrameSize || (projection && (pendingProjectionMessages >= maxMessages
        || nextProjectionBytes > maxBytes))) {
      long discarded = pendingProjectionBytes + payload.length;
      long discardedMessages = pendingProjectionMessages + 1L;
      projectionMessages.clear();
      pendingBytes -= pendingProjectionBytes;
      pendingProjectionMessages = 0;
      pendingProjectionBytes = 0L;
      generation++;
      fencePending = true;
      fenceOverflows++;
      fenceBytes = Math.max(fenceBytes, discarded);
      fenceMessages += discardedMessages;
      fenceReason = !validFrameSize
          ? "screen mailbox rejected oversized frame"
          : (nextProjectionBytes > maxBytes
              ? "screen mailbox exceeded byte budget"
              : "screen mailbox exceeded frame budget");
    } else {
      Message message = new Message(connectionEpoch, generation, source, payload, kind);
      if (projection) projectionMessages.addLast(message);
      else controlMessages.addLast(message);
      pendingBytes += payload.length;
      if (projection) {
        pendingProjectionMessages++;
        pendingProjectionBytes = nextProjectionBytes;
      }
    }
    boolean schedule = !drainScheduled;
    drainScheduled = true;
    return new Offer(schedule, pendingBytes);
  }

  public synchronized Drain poll() {
    if (fencePending) {
      Fence fence = new Fence(fenceReason, fenceBytes, fenceMessages, fenceOverflows);
      fencePending = false;
      fenceReason = "";
      fenceBytes = 0L;
      fenceMessages = 0L;
      fenceOverflows = 0L;
      return new Drain(null, fence);
    }
    Message message = projectionMessages.pollFirst();
    if (message == null) message = controlMessages.pollFirst();
    if (message != null) {
      pendingBytes -= message.payload.length;
      if (isProjectionMessage(message.kind)) {
        pendingProjectionMessages--;
        pendingProjectionBytes -= message.payload.length;
      }
      return new Drain(message, null);
    }
    return null;
  }

  /** Atomically releases the current drain or reserves the next time slice. */
  public synchronized boolean finishDrain() {
    if (fencePending || !projectionMessages.isEmpty() || !controlMessages.isEmpty()) return true;
    drainScheduled = false;
    return false;
  }

  /** Allows the next offer to arm a drain after an executor-level failure. */
  public synchronized void abandonDrain() {
    drainScheduled = false;
  }

  public synchronized boolean hasPending() {
    return fencePending || !projectionMessages.isEmpty() || !controlMessages.isEmpty();
  }

  public synchronized void reset() {
    projectionMessages.clear();
    controlMessages.clear();
    pendingBytes = 0L;
    pendingProjectionMessages = 0;
    pendingProjectionBytes = 0L;
    drainScheduled = false;
    generation++;
    fencePending = false;
    fenceReason = "";
    fenceBytes = 0L;
    fenceMessages = 0L;
    fenceOverflows = 0L;
  }

  public long generation() {
    return generation;
  }

  synchronized int pendingMessages() {
    return projectionMessages.size() + controlMessages.size();
  }

  synchronized long pendingBytes() {
    return pendingBytes;
  }

}
