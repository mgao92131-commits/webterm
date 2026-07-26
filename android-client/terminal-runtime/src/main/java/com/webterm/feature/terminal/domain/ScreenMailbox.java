package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;

/** 连接代际感知的三 lane 有界 mailbox；overflow fence 永远先于后续消息处理。 */
public final class ScreenMailbox {
  static final int DEFAULT_URGENT_MAX_MESSAGES = 64;
  static final long DEFAULT_URGENT_MAX_BYTES = 512L * 1024L;
  static final int DEFAULT_BACKGROUND_MAX_MESSAGES = 32;
  static final long DEFAULT_BACKGROUND_MAX_BYTES = 4L * 1024L * 1024L;
  static final int MAX_CONSECUTIVE_URGENT = 4;
  static final int MAX_CONSECUTIVE_PROJECTION = 8;

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

  static boolean isUrgentControl(@NonNull MessageKind kind) {
    return kind == MessageKind.EXIT
        || kind == MessageKind.INPUT_ACK
        || kind == MessageKind.LAYOUT_LEASE;
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
    public final long droppedBackgroundMessages;
    public final long droppedBackgroundBytes;

    Offer(boolean scheduleDrain, long pendingBytes,
          long droppedBackgroundMessages, long droppedBackgroundBytes) {
      this.scheduleDrain = scheduleDrain;
      this.pendingBytes = pendingBytes;
      this.droppedBackgroundMessages = droppedBackgroundMessages;
      this.droppedBackgroundBytes = droppedBackgroundBytes;
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

  private final int projectionMaxMessages;
  private final long projectionMaxBytes;
  private final int urgentMaxMessages;
  private final long urgentMaxBytes;
  private final int backgroundMaxMessages;
  private final long backgroundMaxBytes;
  /** Revision-bearing lane. Encoded projection messages are never merged or reordered. */
  private final ArrayDeque<Message> projectionMessages = new ArrayDeque<>();
  /** Exit/InputAck/LayoutLease. These are bounded and never silently dropped. */
  private final ArrayDeque<Message> urgentMessages = new ArrayDeque<>();
  /** Revision-independent pageable history, Pong and ordinary effects. */
  private final ArrayDeque<Message> backgroundMessages = new ArrayDeque<>();
  private boolean drainScheduled;
  private long pendingBytes;
  private int pendingProjectionMessages;
  private long pendingProjectionBytes;
  private long pendingUrgentBytes;
  private long pendingBackgroundBytes;
  private int consecutiveUrgent;
  private int consecutiveProjection;
  private volatile long generation;
  private boolean fencePending;
  private String fenceReason = "";
  private long fenceBytes;
  private long fenceMessages;
  private long fenceOverflows;

  public ScreenMailbox(int maxMessages, long maxBytes) {
    this(maxMessages, maxBytes,
        DEFAULT_URGENT_MAX_MESSAGES, DEFAULT_URGENT_MAX_BYTES,
        DEFAULT_BACKGROUND_MAX_MESSAGES, DEFAULT_BACKGROUND_MAX_BYTES);
  }

  ScreenMailbox(int projectionMaxMessages, long projectionMaxBytes,
                int urgentMaxMessages, long urgentMaxBytes,
                int backgroundMaxMessages, long backgroundMaxBytes) {
    if (projectionMaxMessages < 1 || projectionMaxBytes < 1
        || urgentMaxMessages < 1 || urgentMaxBytes < 1
        || backgroundMaxMessages < 1 || backgroundMaxBytes < 1) {
      throw new IllegalArgumentException("mailbox budgets must be positive");
    }
    this.projectionMaxMessages = projectionMaxMessages;
    this.projectionMaxBytes = projectionMaxBytes;
    this.urgentMaxMessages = urgentMaxMessages;
    this.urgentMaxBytes = urgentMaxBytes;
    this.backgroundMaxMessages = backgroundMaxMessages;
    this.backgroundMaxBytes = backgroundMaxBytes;
  }

  public synchronized Offer offer(long connectionEpoch,
                                  @NonNull TerminalSessionRuntime.ScreenConnection source,
                                  @NonNull byte[] payload,
                                  boolean validFrameSize,
                                  @NonNull MessageKind kind) {
    boolean projection = isProjectionMessage(kind);
    boolean urgent = isUrgentControl(kind);
    long nextProjectionBytes = pendingProjectionBytes + (projection ? payload.length : 0L);
    long nextUrgentBytes = pendingUrgentBytes + (urgent ? payload.length : 0L);
    long droppedBackgroundMessages = 0L;
    long droppedBackgroundBytes = 0L;
    if (!validFrameSize || (projection && (pendingProjectionMessages >= projectionMaxMessages
        || nextProjectionBytes > projectionMaxBytes))) {
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
          : (nextProjectionBytes > projectionMaxBytes
              ? "screen mailbox exceeded byte budget"
              : "screen mailbox exceeded frame budget");
    } else if (urgent && (urgentMessages.size() >= urgentMaxMessages
        || nextUrgentBytes > urgentMaxBytes)) {
      long discarded = pendingBytes + payload.length;
      long discardedMessages =
          projectionMessages.size() + urgentMessages.size() + backgroundMessages.size() + 1L;
      clearQueues();
      generation++;
      fencePending = true;
      fenceOverflows++;
      fenceBytes = Math.max(fenceBytes, discarded);
      fenceMessages += discardedMessages;
      fenceReason = nextUrgentBytes > urgentMaxBytes
          ? "screen mailbox urgent control exceeded byte budget"
          : "screen mailbox urgent control exceeded frame budget";
    } else {
      Message message = new Message(connectionEpoch, generation, source, payload, kind);
      if (projection) {
        projectionMessages.addLast(message);
        pendingBytes += payload.length;
        pendingProjectionMessages++;
        pendingProjectionBytes = nextProjectionBytes;
      } else if (urgent) {
        urgentMessages.addLast(message);
        pendingBytes += payload.length;
        pendingUrgentBytes = nextUrgentBytes;
      } else {
        while (!backgroundMessages.isEmpty()
            && (backgroundMessages.size() >= backgroundMaxMessages
                || pendingBackgroundBytes + payload.length > backgroundMaxBytes)) {
          Message dropped = backgroundMessages.removeFirst();
          pendingBackgroundBytes -= dropped.payload.length;
          pendingBytes -= dropped.payload.length;
          droppedBackgroundMessages++;
          droppedBackgroundBytes += dropped.payload.length;
        }
        if (payload.length <= backgroundMaxBytes) {
          backgroundMessages.addLast(message);
          pendingBackgroundBytes += payload.length;
          pendingBytes += payload.length;
        } else {
          droppedBackgroundMessages++;
          droppedBackgroundBytes += payload.length;
        }
      }
    }
    boolean schedule = !drainScheduled;
    drainScheduled = true;
    return new Offer(
        schedule, pendingBytes, droppedBackgroundMessages, droppedBackgroundBytes);
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
    Message message = nextMessage();
    if (message != null) {
      pendingBytes -= message.payload.length;
      if (isProjectionMessage(message.kind)) {
        pendingProjectionMessages--;
        pendingProjectionBytes -= message.payload.length;
      } else if (isUrgentControl(message.kind)) {
        pendingUrgentBytes -= message.payload.length;
      } else {
        pendingBackgroundBytes -= message.payload.length;
      }
      return new Drain(message, null);
    }
    return null;
  }

  /** Atomically releases the current drain or reserves the next time slice. */
  public synchronized boolean finishDrain() {
    if (fencePending || !projectionMessages.isEmpty()
        || !urgentMessages.isEmpty() || !backgroundMessages.isEmpty()) return true;
    drainScheduled = false;
    return false;
  }

  /** Allows the next offer to arm a drain after an executor-level failure. */
  public synchronized void abandonDrain() {
    drainScheduled = false;
  }

  public synchronized boolean hasPending() {
    return fencePending || !projectionMessages.isEmpty()
        || !urgentMessages.isEmpty() || !backgroundMessages.isEmpty();
  }

  public synchronized void reset() {
    clearQueues();
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
    return projectionMessages.size() + urgentMessages.size() + backgroundMessages.size();
  }

  synchronized long pendingBytes() {
    return pendingBytes;
  }

  private Message nextMessage() {
    if (!urgentMessages.isEmpty() && consecutiveUrgent < MAX_CONSECUTIVE_URGENT) {
      consecutiveUrgent++;
      return urgentMessages.pollFirst();
    }
    if (!backgroundMessages.isEmpty()
        && (consecutiveProjection >= MAX_CONSECUTIVE_PROJECTION
            || consecutiveUrgent >= MAX_CONSECUTIVE_URGENT
            || projectionMessages.isEmpty())) {
      consecutiveProjection = 0;
      consecutiveUrgent = 0;
      return backgroundMessages.pollFirst();
    }
    if (!projectionMessages.isEmpty()) {
      consecutiveProjection++;
      consecutiveUrgent = 0;
      return projectionMessages.pollFirst();
    }
    if (!urgentMessages.isEmpty()) {
      consecutiveUrgent = 1;
      return urgentMessages.pollFirst();
    }
    if (!backgroundMessages.isEmpty()) {
      consecutiveProjection = 0;
      consecutiveUrgent = 0;
      return backgroundMessages.pollFirst();
    }
    return null;
  }

  private void clearQueues() {
    projectionMessages.clear();
    urgentMessages.clear();
    backgroundMessages.clear();
    pendingBytes = 0L;
    pendingProjectionMessages = 0;
    pendingProjectionBytes = 0L;
    pendingUrgentBytes = 0L;
    pendingBackgroundBytes = 0L;
    consecutiveUrgent = 0;
    consecutiveProjection = 0;
  }

}
