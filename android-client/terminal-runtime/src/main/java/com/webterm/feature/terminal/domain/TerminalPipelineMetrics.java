package com.webterm.feature.terminal.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单会话屏幕管线水位指标，按 publicationVersion 与 screenRevision 追踪解码→模型→渲染链路。
 * 每个 {@link TerminalSessionRuntime} 持有一份实例，不替代进程级
 * {@link com.webterm.terminal.model.TerminalRenderMetrics}。
 */
public final class TerminalPipelineMetrics {

  private final AtomicLong lastDecodedScreenRevision = new AtomicLong();
  private final AtomicLong lastModelScreenRevision = new AtomicLong();
  private final AtomicLong lastPublishedVersion = new AtomicLong();
  private final AtomicLong lastConsumedVersion = new AtomicLong();
  private final AtomicLong lastDrawnVersion = new AtomicLong();
  private final AtomicLong lastConsumedScreenRevision = new AtomicLong();
  private final AtomicLong lastDrawnScreenRevision = new AtomicLong();
  private final AtomicLong lastDrawnAtNanos = new AtomicLong();

  private final AtomicLong receivedFrameCount = new AtomicLong();
  private final AtomicLong receivedBytes = new AtomicLong();
  private final AtomicLong lastReceivedAtNanos = new AtomicLong();
  private volatile String lastReceivedKind = "";

  private final AtomicLong staleConnectionEpochDropped = new AtomicLong();
  private final AtomicLong staleMailboxGenerationDropped = new AtomicLong();
  private final AtomicLong wrongSourceConnectionDropped = new AtomicLong();

  private final AtomicLong invalidFrameSizeRejected = new AtomicLong();
  private final AtomicLong projectionOverflowDiscarded = new AtomicLong();
  private final AtomicLong urgentOverflowDiscarded = new AtomicLong();
  private final AtomicLong reliableOverflowDiscarded = new AtomicLong();
  private final AtomicLong backgroundDropped = new AtomicLong();
  private final AtomicLong unknownEnvelopeCount = new AtomicLong();

  public void onFrameReceived(String kind, int bytes) {
    receivedFrameCount.incrementAndGet();
    receivedBytes.addAndGet(Math.max(0, bytes));
    lastReceivedAtNanos.set(System.nanoTime());
    lastReceivedKind = kind == null ? "" : kind;
  }

  public void onDecodedScreenRevision(long screenRevision) {
    lastDecodedScreenRevision.set(screenRevision);
  }

  public void onModelApplied(long screenRevision, long publishedVersion) {
    lastModelScreenRevision.set(screenRevision);
    if (publishedVersion > 0L) {
      lastPublishedVersion.set(publishedVersion);
    }
  }

  public void onRenderConsumed(long publicationVersion, long screenRevision) {
    lastConsumedVersion.set(publicationVersion);
    lastConsumedScreenRevision.set(screenRevision);
  }

  public void onRenderFrameDrawn(long publicationVersion, long screenRevision,
                                 long drawDurationNanos) {
    lastDrawnVersion.set(publicationVersion);
    lastDrawnScreenRevision.set(screenRevision);
    lastDrawnAtNanos.set(System.nanoTime());
  }

  public void incrementStaleConnectionEpochDropped() {
    staleConnectionEpochDropped.incrementAndGet();
  }

  public void incrementStaleMailboxGenerationDropped() {
    staleMailboxGenerationDropped.incrementAndGet();
  }

  public void incrementWrongSourceConnectionDropped() {
    wrongSourceConnectionDropped.incrementAndGet();
  }

  public void incrementInvalidFrameSizeRejected() {
    invalidFrameSizeRejected.incrementAndGet();
  }

  public void incrementProjectionOverflowDiscarded(long count) {
    if (count > 0) {
      projectionOverflowDiscarded.addAndGet(count);
    }
  }

  public void incrementUrgentOverflowDiscarded(long count) {
    if (count > 0) {
      urgentOverflowDiscarded.addAndGet(count);
    }
  }

  public void incrementReliableOverflowDiscarded(long count) {
    if (count > 0) {
      reliableOverflowDiscarded.addAndGet(count);
    }
  }

  public void incrementBackgroundDropped(long count) {
    if (count > 0) {
      backgroundDropped.addAndGet(count);
    }
  }

  public void incrementUnknownEnvelopeCount() {
    unknownEnvelopeCount.incrementAndGet();
  }

  /** 稳定字段映射，供后续诊断导出。 */
  public Map<String, Object> snapshot() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("lastDecodedScreenRevision", lastDecodedScreenRevision.get());
    out.put("lastModelScreenRevision", lastModelScreenRevision.get());
    out.put("lastPublishedVersion", lastPublishedVersion.get());
    out.put("lastConsumedVersion", lastConsumedVersion.get());
    out.put("lastDrawnVersion", lastDrawnVersion.get());
    out.put("lastConsumedScreenRevision", lastConsumedScreenRevision.get());
    out.put("lastDrawnScreenRevision", lastDrawnScreenRevision.get());
    out.put("lastDrawnAtNanos", lastDrawnAtNanos.get());
    out.put("receivedFrameCount", receivedFrameCount.get());
    out.put("receivedBytes", receivedBytes.get());
    out.put("lastReceivedAtNanos", lastReceivedAtNanos.get());
    out.put("lastReceivedKind", lastReceivedKind);
    out.put("staleConnectionEpochDropped", staleConnectionEpochDropped.get());
    out.put("staleMailboxGenerationDropped", staleMailboxGenerationDropped.get());
    out.put("wrongSourceConnectionDropped", wrongSourceConnectionDropped.get());
    out.put("invalidFrameSizeRejected", invalidFrameSizeRejected.get());
    out.put("projectionOverflowDiscarded", projectionOverflowDiscarded.get());
    out.put("urgentOverflowDiscarded", urgentOverflowDiscarded.get());
    out.put("reliableOverflowDiscarded", reliableOverflowDiscarded.get());
    out.put("backgroundDropped", backgroundDropped.get());
    out.put("unknownEnvelopeCount", unknownEnvelopeCount.get());
    return out;
  }
}
