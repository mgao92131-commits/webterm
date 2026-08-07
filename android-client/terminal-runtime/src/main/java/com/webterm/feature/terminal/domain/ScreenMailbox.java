package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.ByteBuffer;

/** 连接代际感知的四 lane 有界 mailbox；overflow fence 永远先于后续消息处理。 */
public final class ScreenMailbox {
  private static final long[] RESIDENCE_BUCKET_UPPER_NANOS = {
      1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L,
      16_000_000L, 32_000_000L, 64_000_000L, 128_000_000L,
      256_000_000L, 512_000_000L, 1_000_000_000L
  };
  static final int DEFAULT_URGENT_MAX_MESSAGES = 64;
  static final long DEFAULT_URGENT_MAX_BYTES = 512L * 1024L;
  static final int DEFAULT_RELIABLE_MAX_MESSAGES = 32;
  // Clipboard effects may approach the protocol's 2 MiB single-frame limit.
  static final long DEFAULT_RELIABLE_MAX_BYTES = 2L * 1024L * 1024L;
  static final int DEFAULT_BACKGROUND_MAX_MESSAGES = 32;
  static final long DEFAULT_BACKGROUND_MAX_BYTES = 256L * 1024L;
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
        || kind == MessageKind.LAYOUT_LEASE;
  }

  static boolean isReliableControl(@NonNull MessageKind kind) {
    return kind == MessageKind.CLIPBOARD_EFFECT;
  }

  public static final class Message {
    public final long connectionEpoch;
    public final long mailboxGeneration;
    public final TerminalSessionRuntime.ScreenConnection sourceConnection;
    public final ByteBuffer payload;
    public final MessageKind kind;
    public final long enqueuedAtNanos;

    Message(long connectionEpoch, long mailboxGeneration,
            TerminalSessionRuntime.ScreenConnection sourceConnection, byte[] payload,
            MessageKind kind) {
      this(connectionEpoch, mailboxGeneration, sourceConnection, ByteBuffer.wrap(payload),
          kind, System.nanoTime());
    }

    Message(long connectionEpoch, long mailboxGeneration,
            TerminalSessionRuntime.ScreenConnection sourceConnection, byte[] payload,
            MessageKind kind, long enqueuedAtNanos) {
      this(connectionEpoch, mailboxGeneration, sourceConnection, ByteBuffer.wrap(payload),
          kind, enqueuedAtNanos);
    }

    Message(long connectionEpoch, long mailboxGeneration,
            TerminalSessionRuntime.ScreenConnection sourceConnection, ByteBuffer payload,
            MessageKind kind, long enqueuedAtNanos) {
      this.connectionEpoch = connectionEpoch;
      this.mailboxGeneration = mailboxGeneration;
      this.sourceConnection = sourceConnection;
      this.payload = payload.asReadOnlyBuffer();
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
    public final OverflowKind overflowKind;

    Fence(String reason, long discardedBytes, long discardedMessages, long overflowCount,
          boolean rebuildChannel, OverflowKind overflowKind) {
      this.reason = reason;
      this.discardedBytes = discardedBytes;
      this.discardedMessages = discardedMessages;
      this.overflowCount = overflowCount;
      this.rebuildChannel = rebuildChannel;
      this.overflowKind = overflowKind == null ? OverflowKind.NONE : overflowKind;
    }
  }

  public enum OverflowKind {
    NONE,
    OVERSIZED_FRAME,
    PROJECTION_FRAME_BUDGET,
    PROJECTION_BYTE_BUDGET,
    URGENT_FRAME_BUDGET,
    URGENT_BYTE_BUDGET,
    RELIABLE_FRAME_BUDGET,
    RELIABLE_BYTE_BUDGET,
    AGGREGATE_FRAME_BUDGET,
    AGGREGATE_BYTE_BUDGET
  }

  public static final class Offer {
    public final boolean scheduleDrain;
    public final long pendingBytes;
    public final long droppedBackgroundMessages;
    public final long droppedBackgroundBytes;
    public final int pendingProjectionMessages;
    public final long pendingProjectionBytes;

    Offer(boolean scheduleDrain, long pendingBytes,
          long droppedBackgroundMessages, long droppedBackgroundBytes,
          int pendingProjectionMessages, long pendingProjectionBytes) {
      this.scheduleDrain = scheduleDrain;
      this.pendingBytes = pendingBytes;
      this.droppedBackgroundMessages = droppedBackgroundMessages;
      this.droppedBackgroundBytes = droppedBackgroundBytes;
      this.pendingProjectionMessages = pendingProjectionMessages;
      this.pendingProjectionBytes = pendingProjectionBytes;
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
  private final int totalMaxMessages;
  private final long totalMaxBytes;
  private final int urgentMaxMessages;
  private final long urgentMaxBytes;
  private final int reliableMaxMessages;
  private final long reliableMaxBytes;
  private final int backgroundMaxMessages;
  private final long backgroundMaxBytes;
  /** Revision-bearing lane. Encoded projection messages are never merged or reordered. */
  private final ArrayDeque<Message> projectionMessages = new ArrayDeque<>();
  /** Exit/LayoutLease. These are bounded and never silently dropped. */
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
  private OverflowKind fenceOverflowKind = OverflowKind.NONE;
  private final long metricsStartedAtNanos = System.nanoTime();
  private long projectionArrivalCount;
  private long projectionDrainCount;
  private long projectionPendingMessagesHighWater;
  private long projectionPendingBytesHighWater;
  private long totalPendingMessagesHighWater;
  private long totalPendingBytesHighWater;
  private long overflowByFrameBudgetCount;
  private long overflowByByteBudgetCount;
  private long residenceCount;
  private final long[] residenceBuckets =
      new long[RESIDENCE_BUCKET_UPPER_NANOS.length + 1];

  public ScreenMailbox(int maxMessages, long maxBytes) {
    this(maxMessages, maxBytes,
        DEFAULT_URGENT_MAX_MESSAGES, DEFAULT_URGENT_MAX_BYTES,
        DEFAULT_RELIABLE_MAX_MESSAGES, DEFAULT_RELIABLE_MAX_BYTES,
        DEFAULT_BACKGROUND_MAX_MESSAGES, DEFAULT_BACKGROUND_MAX_BYTES,
        maxMessages + DEFAULT_URGENT_MAX_MESSAGES + DEFAULT_RELIABLE_MAX_MESSAGES
            + DEFAULT_BACKGROUND_MAX_MESSAGES,
        maxBytes + DEFAULT_URGENT_MAX_BYTES + DEFAULT_RELIABLE_MAX_BYTES
            + DEFAULT_BACKGROUND_MAX_BYTES);
  }

  ScreenMailbox(int projectionMaxMessages, long projectionMaxBytes,
                int totalMaxMessages, long totalMaxBytes) {
    this(projectionMaxMessages, projectionMaxBytes,
        DEFAULT_URGENT_MAX_MESSAGES, DEFAULT_URGENT_MAX_BYTES,
        DEFAULT_RELIABLE_MAX_MESSAGES, DEFAULT_RELIABLE_MAX_BYTES,
        DEFAULT_BACKGROUND_MAX_MESSAGES, DEFAULT_BACKGROUND_MAX_BYTES,
        totalMaxMessages, totalMaxBytes);
  }

  ScreenMailbox(int projectionMaxMessages, long projectionMaxBytes,
                int urgentMaxMessages, long urgentMaxBytes,
                int backgroundMaxMessages, long backgroundMaxBytes) {
    this(projectionMaxMessages, projectionMaxBytes,
        urgentMaxMessages, urgentMaxBytes,
        DEFAULT_RELIABLE_MAX_MESSAGES, DEFAULT_RELIABLE_MAX_BYTES,
        backgroundMaxMessages, backgroundMaxBytes,
        projectionMaxMessages + urgentMaxMessages + DEFAULT_RELIABLE_MAX_MESSAGES
            + backgroundMaxMessages,
        projectionMaxBytes + urgentMaxBytes + DEFAULT_RELIABLE_MAX_BYTES
            + backgroundMaxBytes);
  }

  ScreenMailbox(int projectionMaxMessages, long projectionMaxBytes,
                int urgentMaxMessages, long urgentMaxBytes,
                int reliableMaxMessages, long reliableMaxBytes,
                int backgroundMaxMessages, long backgroundMaxBytes) {
    this(projectionMaxMessages, projectionMaxBytes,
        urgentMaxMessages, urgentMaxBytes, reliableMaxMessages, reliableMaxBytes,
        backgroundMaxMessages, backgroundMaxBytes,
        projectionMaxMessages + urgentMaxMessages + reliableMaxMessages + backgroundMaxMessages,
        projectionMaxBytes + urgentMaxBytes + reliableMaxBytes + backgroundMaxBytes);
  }

  ScreenMailbox(int projectionMaxMessages, long projectionMaxBytes,
                int urgentMaxMessages, long urgentMaxBytes,
                int reliableMaxMessages, long reliableMaxBytes,
                int backgroundMaxMessages, long backgroundMaxBytes,
                int totalMaxMessages, long totalMaxBytes) {
    if (projectionMaxMessages < 1 || projectionMaxBytes < 1
        || totalMaxMessages < 1 || totalMaxBytes < 1
        || urgentMaxMessages < 1 || urgentMaxBytes < 1
        || reliableMaxMessages < 1 || reliableMaxBytes < 1
        || backgroundMaxMessages < 1 || backgroundMaxBytes < 1) {
      throw new IllegalArgumentException("mailbox budgets must be positive");
    }
    this.projectionMaxMessages = projectionMaxMessages;
    this.projectionMaxBytes = projectionMaxBytes;
    this.totalMaxMessages = totalMaxMessages;
    this.totalMaxBytes = totalMaxBytes;
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
    return offer(connectionEpoch, source, ByteBuffer.wrap(payload), validFrameSize, kind);
  }

  public synchronized Offer offer(long connectionEpoch,
                                  @NonNull TerminalSessionRuntime.ScreenConnection source,
                                  @NonNull ByteBuffer payload,
                                  boolean validFrameSize,
                                  @NonNull MessageKind kind) {
    ByteBuffer frame = payload.asReadOnlyBuffer();
    int payloadBytes = frame.remaining();
    boolean projection = isProjectionMessage(kind);
    boolean urgent = isUrgentControl(kind);
    boolean reliable = isReliableControl(kind);
    long nextProjectionBytes = pendingProjectionBytes + (projection ? payloadBytes : 0L);
    long nextUrgentBytes = pendingUrgentBytes + (urgent ? payloadBytes : 0L);
    long nextReliableBytes = pendingReliableBytes + (reliable ? payloadBytes : 0L);
    boolean aggregateFrameExceeded = pendingMessages() + 1 > totalMaxMessages;
    boolean aggregateByteExceeded = pendingBytes + payloadBytes > totalMaxBytes;
    long droppedBackgroundMessages = 0L;
    long droppedBackgroundBytes = 0L;
    if (projection) {
      projectionArrivalCount++;
    }
    if (!validFrameSize) {
      long discarded = pendingBytes + payloadBytes;
      long discardedMessages = pendingMessages() + 1L;
      clearQueues();
      generation++;
      fencePending = true;
      fenceOverflows++;
      fenceBytes = Math.max(fenceBytes, discarded);
      fenceMessages += discardedMessages;
      fenceRebuildChannel = true;
      fenceOverflowKind = OverflowKind.OVERSIZED_FRAME;
      overflowByByteBudgetCount++;
      fenceReason = "screen mailbox rejected oversized frame";
    } else if (projection && (pendingProjectionMessages >= projectionMaxMessages
        || nextProjectionBytes > projectionMaxBytes
        || aggregateFrameExceeded || aggregateByteExceeded)) {
      long discarded = pendingProjectionBytes + payloadBytes;
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
      boolean byteExceeded = nextProjectionBytes > projectionMaxBytes || aggregateByteExceeded;
      fenceReason = aggregateByteExceeded
          ? "screen mailbox exceeded aggregate byte budget"
          : aggregateFrameExceeded
              ? "screen mailbox exceeded aggregate frame budget"
              : byteExceeded
                  ? "screen mailbox exceeded byte budget"
                  : "screen mailbox exceeded frame budget";
      fenceOverflowKind = aggregateByteExceeded
          ? OverflowKind.AGGREGATE_BYTE_BUDGET
          : aggregateFrameExceeded
              ? OverflowKind.AGGREGATE_FRAME_BUDGET
              : byteExceeded
                  ? OverflowKind.PROJECTION_BYTE_BUDGET
                  : OverflowKind.PROJECTION_FRAME_BUDGET;
      if (byteExceeded) {
        overflowByByteBudgetCount++;
      } else {
        overflowByFrameBudgetCount++;
      }
    } else if (urgent && (urgentMessages.size() >= urgentMaxMessages
        || nextUrgentBytes > urgentMaxBytes
        || aggregateFrameExceeded || aggregateByteExceeded)) {
      long discarded = pendingBytes + payloadBytes;
      long discardedMessages = pendingMessages() + 1L;
      clearQueues();
      generation++;
      fencePending = true;
      fenceOverflows++;
      fenceBytes = Math.max(fenceBytes, discarded);
      fenceMessages += discardedMessages;
      fenceRebuildChannel = true;
      boolean byteExceeded = nextUrgentBytes > urgentMaxBytes || aggregateByteExceeded;
      fenceReason = aggregateByteExceeded
          ? "screen mailbox urgent control exceeded aggregate byte budget"
          : aggregateFrameExceeded
              ? "screen mailbox urgent control exceeded aggregate frame budget"
              : byteExceeded
                  ? "screen mailbox urgent control exceeded byte budget"
                  : "screen mailbox urgent control exceeded frame budget";
      fenceOverflowKind = aggregateByteExceeded
          ? OverflowKind.AGGREGATE_BYTE_BUDGET
          : aggregateFrameExceeded
              ? OverflowKind.AGGREGATE_FRAME_BUDGET
              : byteExceeded
                  ? OverflowKind.URGENT_BYTE_BUDGET : OverflowKind.URGENT_FRAME_BUDGET;
      if (byteExceeded) {
        overflowByByteBudgetCount++;
      } else {
        overflowByFrameBudgetCount++;
      }
    } else if (reliable && (reliableMessages.size() >= reliableMaxMessages
        || nextReliableBytes > reliableMaxBytes
        || aggregateFrameExceeded || aggregateByteExceeded)) {
      long discarded = pendingBytes + payloadBytes;
      long discardedMessages = pendingMessages() + 1L;
      clearQueues();
      generation++;
      fencePending = true;
      fenceOverflows++;
      fenceBytes = Math.max(fenceBytes, discarded);
      fenceMessages += discardedMessages;
      fenceRebuildChannel = true;
      boolean byteExceeded = nextReliableBytes > reliableMaxBytes || aggregateByteExceeded;
      fenceReason = aggregateByteExceeded
          ? "screen mailbox reliable control exceeded aggregate byte budget"
          : aggregateFrameExceeded
              ? "screen mailbox reliable control exceeded aggregate frame budget"
              : byteExceeded
                  ? "screen mailbox reliable control exceeded byte budget"
                  : "screen mailbox reliable control exceeded frame budget";
      fenceOverflowKind = aggregateByteExceeded
          ? OverflowKind.AGGREGATE_BYTE_BUDGET
          : aggregateFrameExceeded
              ? OverflowKind.AGGREGATE_FRAME_BUDGET
              : byteExceeded
                  ? OverflowKind.RELIABLE_BYTE_BUDGET : OverflowKind.RELIABLE_FRAME_BUDGET;
      if (byteExceeded) {
        overflowByByteBudgetCount++;
      } else {
        overflowByFrameBudgetCount++;
      }
    } else {
      Message message = new Message(
          connectionEpoch, generation, source, frame, kind, System.nanoTime());
      if (projection) {
        projectionMessages.addLast(message);
        pendingBytes += payloadBytes;
        pendingProjectionMessages++;
        pendingProjectionBytes = nextProjectionBytes;
        projectionPendingMessagesHighWater =
            Math.max(projectionPendingMessagesHighWater, pendingProjectionMessages);
        projectionPendingBytesHighWater =
            Math.max(projectionPendingBytesHighWater, pendingProjectionBytes);
      } else if (urgent) {
        urgentMessages.addLast(message);
        pendingBytes += payloadBytes;
        pendingUrgentBytes = nextUrgentBytes;
      } else if (reliable) {
        reliableMessages.addLast(message);
        pendingBytes += payloadBytes;
        pendingReliableBytes = nextReliableBytes;
      } else {
        while (!backgroundMessages.isEmpty()
            && (backgroundMessages.size() >= backgroundMaxMessages
                || pendingBackgroundBytes + payloadBytes > backgroundMaxBytes
                || pendingMessages() + 1 > totalMaxMessages
                || pendingBytes + payloadBytes > totalMaxBytes)) {
          Message dropped = backgroundMessages.removeFirst();
          pendingBackgroundBytes -= dropped.payload.remaining();
          pendingBytes -= dropped.payload.remaining();
          droppedBackgroundMessages++;
          droppedBackgroundBytes += dropped.payload.remaining();
        }
        if (payloadBytes <= backgroundMaxBytes
            && pendingMessages() + 1 <= totalMaxMessages
            && pendingBytes + payloadBytes <= totalMaxBytes) {
          backgroundMessages.addLast(message);
          pendingBackgroundBytes += payloadBytes;
          pendingBytes += payloadBytes;
        } else {
          droppedBackgroundMessages++;
          droppedBackgroundBytes += payloadBytes;
        }
      }
    }
    totalPendingMessagesHighWater = Math.max(totalPendingMessagesHighWater, pendingMessages());
    totalPendingBytesHighWater = Math.max(totalPendingBytesHighWater, pendingBytes);
    boolean schedule = !drainScheduled;
    drainScheduled = true;
    return new Offer(
        schedule, pendingBytes, droppedBackgroundMessages, droppedBackgroundBytes,
        pendingProjectionMessages, pendingProjectionBytes);
  }

  public synchronized Drain poll() {
    if (fencePending) {
      Fence fence = new Fence(
          fenceReason, fenceBytes, fenceMessages, fenceOverflows, fenceRebuildChannel,
          fenceOverflowKind);
      fencePending = false;
      fenceReason = "";
      fenceBytes = 0L;
      fenceMessages = 0L;
      fenceOverflows = 0L;
      fenceRebuildChannel = false;
      fenceOverflowKind = OverflowKind.NONE;
      return new Drain(null, fence);
    }
    Message message = nextMessage();
    if (message != null) {
      pendingBytes -= message.payload.remaining();
      if (isProjectionMessage(message.kind)) {
        projectionDrainCount++;
        recordResidence(System.nanoTime() - message.enqueuedAtNanos);
        pendingProjectionMessages--;
        pendingProjectionBytes -= message.payload.remaining();
      } else if (isUrgentControl(message.kind)) {
        pendingUrgentBytes -= message.payload.remaining();
      } else if (isReliableControl(message.kind)) {
        pendingReliableBytes -= message.payload.remaining();
      } else {
        pendingBackgroundBytes -= message.payload.remaining();
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
    fenceOverflowKind = OverflowKind.NONE;
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

  public synchronized Map<String, Object> diagnosticsSnapshot() {
    long elapsedNanos = Math.max(1L, System.nanoTime() - metricsStartedAtNanos);
    double elapsedSeconds = elapsedNanos / 1_000_000_000.0d;
    double arrivalRate = projectionArrivalCount / elapsedSeconds;
    double drainRate = projectionDrainCount / elapsedSeconds;
    double estimatedDelayMs;
    if (pendingProjectionMessages == 0) {
      estimatedDelayMs = 0.0d;
    } else if (drainRate > 0.0d) {
      estimatedDelayMs = pendingProjectionMessages * 1000.0d / drainRate;
    } else {
      Message oldest = projectionMessages.peekFirst();
      estimatedDelayMs = oldest == null ? 0.0d
          : Math.max(0L, System.nanoTime() - oldest.enqueuedAtNanos) / 1_000_000.0d;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("projectionArrivalCount", projectionArrivalCount);
    out.put("projectionDrainCount", projectionDrainCount);
    out.put("projectionArrivalRate", arrivalRate);
    out.put("projectionDrainRate", drainRate);
    out.put("projectionPendingMessagesHighWater", projectionPendingMessagesHighWater);
    out.put("projectionPendingBytesHighWater", projectionPendingBytesHighWater);
    out.put("totalPendingMessagesHighWater", totalPendingMessagesHighWater);
    out.put("totalPendingBytesHighWater", totalPendingBytesHighWater);
    out.put("mailboxResidenceP50", percentileMillis(0.50d));
    out.put("mailboxResidenceP95", percentileMillis(0.95d));
    out.put("mailboxResidenceP99", percentileMillis(0.99d));
    out.put("estimatedQueueDelayMs", estimatedDelayMs);
    out.put("frameBudgetUtilization",
        pendingProjectionMessages / (double) projectionMaxMessages);
    out.put("byteBudgetUtilization",
        pendingProjectionBytes / (double) projectionMaxBytes);
    out.put("totalFrameBudgetUtilization", pendingMessages() / (double) totalMaxMessages);
    out.put("totalByteBudgetUtilization", pendingBytes / (double) totalMaxBytes);
    out.put("overflowByFrameBudgetCount", overflowByFrameBudgetCount);
    out.put("overflowByByteBudgetCount", overflowByByteBudgetCount);
    return out;
  }

  private void recordResidence(long nanos) {
    long safe = Math.max(0L, nanos);
    int bucket = RESIDENCE_BUCKET_UPPER_NANOS.length;
    for (int i = 0; i < RESIDENCE_BUCKET_UPPER_NANOS.length; i++) {
      if (safe < RESIDENCE_BUCKET_UPPER_NANOS[i]) {
        bucket = i;
        break;
      }
    }
    residenceBuckets[bucket]++;
    residenceCount++;
  }

  private double percentileMillis(double percentile) {
    if (residenceCount == 0L) return 0.0d;
    long target = Math.max(1L, (long) Math.ceil(residenceCount * percentile));
    long seen = 0L;
    for (int i = 0; i < residenceBuckets.length; i++) {
      seen += residenceBuckets[i];
      if (seen >= target) {
        if (i >= RESIDENCE_BUCKET_UPPER_NANOS.length) {
          return 1000.0d;
        }
        return RESIDENCE_BUCKET_UPPER_NANOS[i] / 1_000_000.0d;
      }
    }
    return 1000.0d;
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
