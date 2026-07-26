package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;

/** 连接代际感知的三 lane 有界 mailbox；overflow fence 永远先于后续消息处理。 */
public final class ScreenMailbox {
  static final int DEFAULT_URGENT_MAX_MESSAGES = 64;
  static final long DEFAULT_URGENT_MAX_BYTES = 512L * 1024L;
  static final int DEFAULT_RELIABLE_MAX_MESSAGES = 32;
  static final long DEFAULT_RELIABLE_MAX_BYTES = 4L * 1024L * 1024L;
  static final int DEFAULT_BACKGROUND_MAX_MESSAGES = 32;
  static final long DEFAULT_BACKGROUND_MAX_BYTES = 4L * 1024L * 1024L;
  // Weighted round-robin slots. Keeping the order explicit makes the maximum wait bounded and
  // testable while preserving FIFO inside every lane.
  private static final int LANE_URGENT = 1;
  private static final int LANE_PROJECTION = 2;
  private static final int LANE_RELIABLE = 3;
  private static final int LANE_BACKGROUND = 4;
  private static final int[] SCHEDULE = {
      LANE_URGENT, LANE_PROJECTION, LANE_URGENT, LANE_RELIABLE,
      LANE_URGENT, LANE_PROJECTION, LANE_BACKGROUND,
      LANE_URGENT, LANE_PROJECTION, LANE_RELIABLE, LANE_PROJECTION
  };
  static final int SCHEDULE_LENGTH = SCHEDULE.length;

  public enum MessageKind {
    BASELINE,
    RESUME_ACCEPTED,
    TERMINAL_COMMIT,
    HISTORY_RANGE,
    INPUT_ACK,
    LAYOUT_LEASE,
    CLIPBOARD_EFFECT,
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

  static boolean isReliableControl(@NonNull MessageKind kind) {
    return kind == MessageKind.CLIPBOARD_EFFECT;
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
    public final boolean rebuildChannel;

    Fence(String reason, long discardedBytes, long discardedMessages, long overflowCount,
          boolean rebuildChannel) {
      this.reason = reason;
      this.discardedBytes = discardedBytes;
      this.discardedMessages = discardedMessages;
      this.overflowCount = overflowCount;
      this.rebuildChannel = rebuildChannel;
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
  private final int reliableMaxMessages;
  private final long reliableMaxBytes;
  private final int backgroundMaxMessages;
  private final long backgroundMaxBytes;
  /** Revision-bearing lane. Encoded projection messages are never merged or reordered. */
  private final ArrayDeque<Message> projectionMessages = new ArrayDeque<>();
  /** Exit/InputAck/LayoutLease. These are bounded and never silently dropped. */
  private final ArrayDeque<Message> urgentMessages = new ArrayDeque<>();
  /** Clipboard read/write effects. Bounded, reliable, and never silently dropped. */
  private final ArrayDeque<Message> reliableMessages = new ArrayDeque<>();
  /** Revision-independent pageable history, Pong and ordinary effects. */
  private final ArrayDeque<Message> backgroundMessages = new ArrayDeque<>();
  private boolean drainScheduled;
  private long pendingBytes;
  private int pendingProjectionMessages;
  private long pendingProjectionBytes;
  private long pendingUrgentBytes;
  private long pendingReliableBytes;
  private long pendingBackgroundBytes;
  private int scheduleIndex;
  private volatile long generation;
  private boolean fencePending;
  private String fenceReason = "";
  private long fenceBytes;
  private long fenceMessages;
  private long fenceOverflows;
  private boolean fenceRebuildChannel;

  public ScreenMailbox(int maxMessages, long maxBytes) {
    this(maxMessages, maxBytes,
        DEFAULT_URGENT_MAX_MESSAGES, DEFAULT_URGENT_MAX_BYTES,
        DEFAULT_RELIABLE_MAX_MESSAGES, DEFAULT_RELIABLE_MAX_BYTES,
        DEFAULT_BACKGROUND_MAX_MESSAGES, DEFAULT_BACKGROUND_MAX_BYTES);
  }

  ScreenMailbox(int projectionMaxMessages, long projectionMaxBytes,
                int urgentMaxMessages, long urgentMaxBytes,
                int backgroundMaxMessages, long backgroundMaxBytes) {
    this(projectionMaxMessages, projectionMaxBytes,
        urgentMaxMessages, urgentMaxBytes,
        DEFAULT_RELIABLE_MAX_MESSAGES, DEFAULT_RELIABLE_MAX_BYTES,
        backgroundMaxMessages, backgroundMaxBytes);
  }

  ScreenMailbox(int projectionMaxMessages, long projectionMaxBytes,
                int urgentMaxMessages, long urgentMaxBytes,
                int reliableMaxMessages, long reliableMaxBytes,
                int backgroundMaxMessages, long backgroundMaxBytes) {
    if (projectionMaxMessages < 1 || projectionMaxBytes < 1
        || urgentMaxMessages < 1 || urgentMaxBytes < 1
        || reliableMaxMessages < 1 || reliableMaxBytes < 1
        || backgroundMaxMessages < 1 || backgroundMaxBytes < 1) {
      throw new IllegalArgumentException("mailbox budgets must be positive");
    }
    this.projectionMaxMessages = projectionMaxMessages;
    this.projectionMaxBytes = projectionMaxBytes;
    this.urgentMaxMessages = urgentMaxMessages;
    this.urgentMaxBytes = urgentMaxBytes;
    this.reliableMaxMessages = reliableMaxMessages;
    this.reliableMaxBytes = reliableMaxBytes;
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
    boolean reliable = isReliableControl(kind);
    long nextProjectionBytes = pendingProjectionBytes + (projection ? payload.length : 0L);
    long nextUrgentBytes = pendingUrgentBytes + (urgent ? payload.length : 0L);
    long nextReliableBytes = pendingReliableBytes + (reliable ? payload.length : 0L);
    long droppedBackgroundMessages = 0L;
    long droppedBackgroundBytes = 0L;
    if (!validFrameSize) {
      long discarded = pendingBytes + payload.length;
      long discardedMessages = pendingMessages() + 1L;
      clearQueues();
      generation++;
      fencePending = true;
      fenceOverflows++;
      fenceBytes = Math.max(fenceBytes, discarded);
      fenceMessages += discardedMessages;
      fenceRebuildChannel = true;
      fenceReason = "screen mailbox rejected oversized frame";
    } else if (projection && (pendingProjectionMessages >= projectionMaxMessages
        || nextProjectionBytes > projectionMaxBytes)) {
      long discarded = pendingProjectionBytes + payload.length;
      long discardedMessages = pendingProjectionMessages + 1L;
      projectionMessages.clear();
      pendingBytes -= pendingProjectionBytes;
      pendingProjectionMessages = 0;
      pendingProjectionBytes = 0L;
      scheduleIndex = 0;
      generation++;
      fencePending = true;
      fenceOverflows++;
      fenceBytes = Math.max(fenceBytes, discarded);
      fenceMessages += discardedMessages;
      fenceReason = nextProjectionBytes > projectionMaxBytes
          ? "screen mailbox exceeded byte budget"
          : "screen mailbox exceeded frame budget";
    } else if (urgent && (urgentMessages.size() >= urgentMaxMessages
        || nextUrgentBytes > urgentMaxBytes)) {
      long discarded = pendingBytes + payload.length;
      long discardedMessages = pendingMessages() + 1L;
      clearQueues();
      generation++;
      fencePending = true;
      fenceOverflows++;
      fenceBytes = Math.max(fenceBytes, discarded);
      fenceMessages += discardedMessages;
      fenceRebuildChannel = true;
      fenceReason = nextUrgentBytes > urgentMaxBytes
          ? "screen mailbox urgent control exceeded byte budget"
          : "screen mailbox urgent control exceeded frame budget";
    } else if (reliable && (reliableMessages.size() >= reliableMaxMessages
        || nextReliableBytes > reliableMaxBytes)) {
      long discarded = pendingBytes + payload.length;
      long discardedMessages = pendingMessages() + 1L;
      clearQueues();
      generation++;
      fencePending = true;
      fenceOverflows++;
      fenceBytes = Math.max(fenceBytes, discarded);
      fenceMessages += discardedMessages;
      fenceRebuildChannel = true;
      fenceReason = nextReliableBytes > reliableMaxBytes
          ? "screen mailbox reliable control exceeded byte budget"
          : "screen mailbox reliable control exceeded frame budget";
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
      } else if (reliable) {
        reliableMessages.addLast(message);
        pendingBytes += payload.length;
        pendingReliableBytes = nextReliableBytes;
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
      Fence fence = new Fence(
          fenceReason, fenceBytes, fenceMessages, fenceOverflows, fenceRebuildChannel);
      fencePending = false;
      fenceReason = "";
      fenceBytes = 0L;
      fenceMessages = 0L;
      fenceOverflows = 0L;
      fenceRebuildChannel = false;
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
      } else if (isReliableControl(message.kind)) {
        pendingReliableBytes -= message.payload.length;
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
        || !urgentMessages.isEmpty() || !reliableMessages.isEmpty()
        || !backgroundMessages.isEmpty()) return true;
    drainScheduled = false;
    return false;
  }

  /** Allows the next offer to arm a drain after an executor-level failure. */
  public synchronized void abandonDrain() {
    drainScheduled = false;
  }

  public synchronized boolean hasPending() {
    return fencePending || !projectionMessages.isEmpty()
        || !urgentMessages.isEmpty() || !reliableMessages.isEmpty()
        || !backgroundMessages.isEmpty();
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
    fenceRebuildChannel = false;
  }

  public long generation() {
    return generation;
  }

  synchronized int pendingMessages() {
    return projectionMessages.size() + urgentMessages.size()
        + reliableMessages.size() + backgroundMessages.size();
  }

  synchronized long pendingBytes() {
    return pendingBytes;
  }

  private Message nextMessage() {
    for (int attempts = 0; attempts < SCHEDULE.length; attempts++) {
      int lane = SCHEDULE[scheduleIndex];
      scheduleIndex = (scheduleIndex + 1) % SCHEDULE.length;
      Message message;
      switch (lane) {
        case LANE_URGENT:
          message = urgentMessages.pollFirst();
          break;
        case LANE_PROJECTION:
          message = projectionMessages.pollFirst();
          break;
        case LANE_RELIABLE:
          message = reliableMessages.pollFirst();
          break;
        case LANE_BACKGROUND:
          message = backgroundMessages.pollFirst();
          break;
        default:
          throw new IllegalStateException("unknown mailbox lane");
      }
      if (message != null) return message;
    }
    return null;
  }

  private void clearQueues() {
    projectionMessages.clear();
    urgentMessages.clear();
    reliableMessages.clear();
    backgroundMessages.clear();
    pendingBytes = 0L;
    pendingProjectionMessages = 0;
    pendingProjectionBytes = 0L;
    pendingUrgentBytes = 0L;
    pendingReliableBytes = 0L;
    pendingBackgroundBytes = 0L;
    scheduleIndex = 0;
  }

}
