package com.webterm.feature.terminal.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单会话屏幕管线水位指标，按 publicationVersion 与 screenRevision 追踪解码→模型→渲染链路。
 * 每个 {@link TerminalSessionRuntime} 持有一份实例，不替代进程级
 * {@link com.webterm.terminal.model.TerminalRenderMetrics}。
 *
 * <p>水位语义：
 * <ul>
 *   <li>published — 模型生成了新的 RenderUpdate</li>
 *   <li>consumed — Controller 取走了该 RenderUpdate</li>
 *   <li>handled — 已完成处理（成功绘制或 state-only）</li>
 *   <li>rendered — view.render() 正常返回</li>
 *   <li>renderFailed — view.render() 抛异常（不推进 handled/rendered）</li>
 * </ul>
 */
public final class TerminalPipelineMetrics {

  private final AtomicLong lastDecodedScreenRevision = new AtomicLong();
  private final AtomicLong lastModelScreenRevision = new AtomicLong();
  private final AtomicLong lastPublishedVersion = new AtomicLong();
  private final AtomicLong lastPublishedScreenRevision = new AtomicLong();
  private final AtomicLong lastConsumedVersion = new AtomicLong();
  private final AtomicLong lastHandledVersion = new AtomicLong();
  private final AtomicLong lastRenderedVersion = new AtomicLong();
  private final AtomicLong lastRenderFailedVersion = new AtomicLong();
  private final AtomicLong lastConsumedScreenRevision = new AtomicLong();
  private final AtomicLong lastHandledScreenRevision = new AtomicLong();
  private final AtomicLong lastRenderedScreenRevision = new AtomicLong();
  private final AtomicLong lastRenderFailedScreenRevision = new AtomicLong();
  private final AtomicLong lastRenderedAtNanos = new AtomicLong();

  private final AtomicLong renderSuccessCount = new AtomicLong();
  private final AtomicLong renderFailureCount = new AtomicLong();
  private final AtomicLong stateOnlyHandledCount = new AtomicLong();

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

  /** 仅推进模型 screenRevision 水位；publication 由 {@link #onPublicationCreated} 负责。 */
  public void onModelApplied(long screenRevision) {
    lastModelScreenRevision.set(screenRevision);
  }

  /** 模型生成新 RenderUpdate 后推进 published 水位。 */
  public void onPublicationCreated(long publishedVersion, long screenRevision) {
    lastPublishedVersion.set(publishedVersion);
    lastPublishedScreenRevision.set(screenRevision);
  }

  public void onRenderConsumed(long publicationVersion, long screenRevision) {
    lastConsumedVersion.set(publicationVersion);
    lastConsumedScreenRevision.set(screenRevision);
  }

  /**
   * Controller 完成对该 publication 的处理。
   * @param rendered true 表示已成功绘制；false 表示 state-only（无视觉 dirty）。
   */
  public void onRenderPublicationHandled(long publicationVersion, long screenRevision,
                                         boolean rendered) {
    lastHandledVersion.set(publicationVersion);
    lastHandledScreenRevision.set(screenRevision);
    if (!rendered) {
      stateOnlyHandledCount.incrementAndGet();
    }
  }

  /** view.render() 正常返回后推进 rendered 水位。 */
  public void onRenderFrameRendered(long publicationVersion, long screenRevision,
                                    long drawDurationNanos) {
    lastRenderedVersion.set(publicationVersion);
    lastRenderedScreenRevision.set(screenRevision);
    lastRenderedAtNanos.set(System.nanoTime());
    renderSuccessCount.incrementAndGet();
  }

  /**
   * view.render() 抛异常：推进 failed 水位与 failure 计数；
   * 不推进 lastRenderedVersion / lastHandledVersion。
   */
  public void onRenderFrameFailed(long publicationVersion, long screenRevision,
                                  long drawDurationNanos) {
    lastRenderFailedVersion.set(publicationVersion);
    lastRenderFailedScreenRevision.set(screenRevision);
    renderFailureCount.incrementAndGet();
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
    out.put("lastPublishedScreenRevision", lastPublishedScreenRevision.get());
    out.put("lastConsumedVersion", lastConsumedVersion.get());
    out.put("lastHandledVersion", lastHandledVersion.get());
    out.put("lastRenderedVersion", lastRenderedVersion.get());
    out.put("lastRenderFailedVersion", lastRenderFailedVersion.get());
    out.put("lastConsumedScreenRevision", lastConsumedScreenRevision.get());
    out.put("lastHandledScreenRevision", lastHandledScreenRevision.get());
    out.put("lastRenderedScreenRevision", lastRenderedScreenRevision.get());
    out.put("lastRenderFailedScreenRevision", lastRenderFailedScreenRevision.get());
    out.put("lastRenderedAtNanos", lastRenderedAtNanos.get());
    out.put("renderSuccessCount", renderSuccessCount.get());
    out.put("renderFailureCount", renderFailureCount.get());
    out.put("stateOnlyHandledCount", stateOnlyHandledCount.get());
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
