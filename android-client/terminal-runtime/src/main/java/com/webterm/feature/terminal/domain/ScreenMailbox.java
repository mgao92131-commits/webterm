package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;

import java.util.ArrayDeque;

/** 连接代际感知的有界 screen mailbox；overflow 会生成先于后续消息处理的 fence。 */
public final class ScreenMailbox {
  public static final class Message {
    public final long connectionEpoch;
    public final long mailboxGeneration;
    public final TerminalSessionRuntime.ScreenConnection sourceConnection;
    public final byte[] payload;

    Message(long connectionEpoch, long mailboxGeneration,
            TerminalSessionRuntime.ScreenConnection sourceConnection, byte[] payload) {
      this.connectionEpoch = connectionEpoch;
      this.mailboxGeneration = mailboxGeneration;
      this.sourceConnection = sourceConnection;
      this.payload = payload;
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
                                  boolean validFrameSize) {
    long nextBytes = pendingBytes + payload.length;
    if (!validFrameSize || messages.size() >= maxMessages || nextBytes > maxBytes) {
      long discarded = pendingBytes + payload.length;
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
    } else {
      messages.addLast(new Message(connectionEpoch, generation, source, payload));
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
    drainScheduled = false;
    return null;
  }

  public synchronized void reset() {
    messages.clear();
    pendingBytes = 0L;
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
}
