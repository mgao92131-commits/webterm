package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;

/** 连接代际感知的有界 screen mailbox；overflow 会生成先于后续消息处理的 fence。 */
public final class ScreenMailbox {
  public enum MessageKind {
    SNAPSHOT,
    PATCH,
    HISTORY_PAGE,
    HISTORY_TRIM,
    OTHER,
    UNKNOWN
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
    public final long overflowCount;

    Fence(String reason, long discardedBytes, long overflowCount) {
      this.reason = reason;
      this.discardedBytes = discardedBytes;
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
  private final ArrayDeque<Message> messages = new ArrayDeque<>();
  private boolean drainScheduled;
  private long pendingBytes;
  private volatile long generation;
  private boolean fencePending;
  private String fenceReason = "";
  private long fenceBytes;
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
    long nextBytes = pendingBytes + payload.length;
    if (!validFrameSize || messages.size() >= maxMessages || nextBytes > maxBytes) {
      Message retainedSnapshot = validFrameSize ? newestSnapshot() : null;
      Message snapshot = kind == MessageKind.SNAPSHOT
          ? new Message(connectionEpoch, generation + 1L, source, payload, kind)
          : retainedSnapshot == null ? null
              : new Message(retainedSnapshot.connectionEpoch, generation + 1L,
                  retainedSnapshot.sourceConnection, retainedSnapshot.payload, retainedSnapshot.kind,
                  retainedSnapshot.enqueuedAtNanos);
      long discarded = pendingBytes + payload.length
          - (snapshot == null ? 0L : snapshot.payload.length);
      messages.clear();
      pendingBytes = 0L;
      generation++;
      fencePending = true;
      fenceOverflows++;
      fenceBytes = Math.max(fenceBytes, discarded);
      fenceReason = !validFrameSize
          ? "screen mailbox rejected oversized frame"
          : (nextBytes > maxBytes
              ? "screen mailbox exceeded byte budget"
              : "screen mailbox exceeded frame budget");
      // A snapshot is the one frame that can release the recovery fence. Keep the newest
      // authoritative snapshot even when later patches are what exhausted the mailbox.
      // The payload remains untouched: webterm.screen.v1 stays a protobuf-only channel.
      if (snapshot != null) {
        messages.addLast(snapshot);
        pendingBytes = snapshot.payload.length;
      }
    } else {
      messages.addLast(new Message(connectionEpoch, generation, source, payload, kind));
      pendingBytes = nextBytes;
    }
    boolean schedule = !drainScheduled;
    drainScheduled = true;
    return new Offer(schedule, pendingBytes);
  }

  public synchronized Drain poll() {
    if (fencePending) {
      Fence fence = new Fence(fenceReason, fenceBytes, fenceOverflows);
      fencePending = false;
      fenceReason = "";
      fenceBytes = 0L;
      fenceOverflows = 0L;
      return new Drain(null, fence);
    }
    Message message = messages.pollFirst();
    if (message != null) {
      pendingBytes -= message.payload.length;
      return new Drain(message, null);
    }
    return null;
  }

  /** Atomically releases the current drain or reserves the next time slice. */
  public synchronized boolean finishDrain() {
    if (fencePending || !messages.isEmpty()) return true;
    drainScheduled = false;
    return false;
  }

  /** Allows the next offer to arm a drain after an executor-level failure. */
  public synchronized void abandonDrain() {
    drainScheduled = false;
  }

  public synchronized boolean hasPending() {
    return fencePending || !messages.isEmpty();
  }

  public synchronized void reset() {
    messages.clear();
    pendingBytes = 0L;
    drainScheduled = false;
    generation++;
    fencePending = false;
    fenceReason = "";
    fenceBytes = 0L;
    fenceOverflows = 0L;
  }

  public long generation() {
    return generation;
  }

  synchronized int pendingMessages() {
    return messages.size();
  }

  @Nullable
  private Message newestSnapshot() {
    java.util.Iterator<Message> iterator = messages.descendingIterator();
    while (iterator.hasNext()) {
      Message message = iterator.next();
      if (message.kind == MessageKind.SNAPSHOT) return message;
    }
    return null;
  }
}
