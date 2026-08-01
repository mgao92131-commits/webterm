package com.webterm.terminal.model;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** HTTP LineBodyBatch 只校验当前 WS Catalog 并填充唯一 BodyCache。 */
public final class HistoryBodyReducer {
  /** @deprecated seq-range 测试兼容；运行时使用 {@link #apply(LineBodyBatchResult, BodyBatchRequestContext, TerminalSurfaceState, EvictionPins)} */
  @Deprecated
  public HistoryBodyResult apply(
      HistoryRangeResult response,
      HistoryRequestContext request,
      TerminalSurfaceState surface,
      EvictionPins pins) {
    if (response == null || request == null || surface == null) {
      return new HistoryBodyResult.Rejected(HistoryBodyFault.INVALID_LINE_BODY);
    }
    ProjectionIdentity actual;
    try {
      actual = new ProjectionIdentity(
          response.instanceId, response.layoutEpoch, response.historyGeneration);
    } catch (RuntimeException invalidIdentity) {
      return new HistoryBodyResult.Rejected(HistoryBodyFault.STALE_PROJECTION);
    }
    if (!Objects.equals(actual, request.identity())) {
      return new HistoryBodyResult.Rejected(HistoryBodyFault.STALE_PROJECTION);
    }
    if (response.status != HistoryRangeResult.Status.OK) {
      return new HistoryBodyResult.StaleIgnored(response.lines.size());
    }
    java.util.List<LineBodyRecord> bodies = new java.util.ArrayList<>(response.lines.size());
    for (HistoryBodyEntry entry : response.lines) {
      if (entry != null) {
        bodies.add(new LineBodyRecord(entry.key(), entry.body()));
      }
    }
    java.util.LinkedHashSet<LineKey> keys = new java.util.LinkedHashSet<>();
    for (HistoryBodyEntry entry : response.lines) {
      if (entry != null) keys.add(entry.key());
    }
    LineBodyBatchResult batch = new LineBodyBatchResult(
        response.requestId, response.instanceId, response.layoutEpoch,
        response.historyGeneration, LineBodyBatchResult.Status.OK,
        bodies, java.util.List.of(), response.retryAfterMs);
    return apply(batch, new BodyBatchRequestContext(request.identity(), keys), surface, pins);
  }

  public HistoryBodyResult apply(
      LineBodyBatchResult response,
      BodyBatchRequestContext request,
      TerminalSurfaceState surface,
      EvictionPins pins) {
    if (response == null || request == null || surface == null) {
      return new HistoryBodyResult.Rejected(HistoryBodyFault.INVALID_LINE_BODY);
    }
    ProjectionIdentity actual;
    try {
      actual = new ProjectionIdentity(
          response.instanceId, response.layoutEpoch, response.historyGeneration);
    } catch (RuntimeException invalidIdentity) {
      return new HistoryBodyResult.Rejected(HistoryBodyFault.STALE_PROJECTION);
    }
    if (!Objects.equals(actual, request.identity())) {
      return new HistoryBodyResult.Rejected(HistoryBodyFault.STALE_PROJECTION);
    }
    if (response.status != LineBodyBatchResult.Status.OK) {
      return new HistoryBodyResult.StaleIgnored(response.bodies.size());
    }

    TerminalSurfaceTransaction tx = surface.beginTransaction();
    int applied = 0;
    int stale = 0;
    long changedFrom = Long.MAX_VALUE;
    long changedTo = 0;
    Set<LineKey> seenKeys = new HashSet<>();
    try {
      for (LineBodyRecord entry : response.bodies) {
        if (entry == null || !request.requestedKeys().contains(entry.key())
            || !seenKeys.add(entry.key())) {
          stale++;
          continue;
        }
        if (!stillReferenced(surface, entry.key())) {
          stale++;
          continue;
        }
        LineBody previous = tx.bodyCache().body(entry.key());
        if (previous != null && !previous.equals(entry.body())) {
          return new HistoryBodyResult.Rejected(HistoryBodyFault.BODY_CONFLICT);
        }
        Long historySeq = historySeqForKey(surface, entry.key());
        if (historySeq != null) {
          tx.bodyCache().putHistory(historySeq, entry.key(), entry.body());
          changedFrom = Math.min(changedFrom, historySeq);
          changedTo = Math.max(changedTo, historySeq);
        } else {
          tx.bodyCache().putBody(entry.key(), entry.body());
        }
        applied++;
      }
      for (LineKey missing : response.missingKeys) {
        if (!request.requestedKeys().contains(missing)) {
          stale++;
          continue;
        }
        if (!stillReferenced(surface, missing)) {
          stale++;
          continue;
        }
        return new HistoryBodyResult.Rejected(HistoryBodyFault.MISSING_REFERENCED_BODY);
      }
      if (applied == 0) {
        return new HistoryBodyResult.StaleIgnored(stale);
      }
      tx.bodyCache().evictIfNeeded(pins == null ? EvictionPins.NONE : pins);
      return new HistoryBodyResult.Applied(
          tx.commit(),
          changedFrom == Long.MAX_VALUE ? 0 : changedFrom,
          changedTo,
          applied,
          stale);
    } catch (CommitValidationException conflict) {
      return new HistoryBodyResult.Rejected(
          conflict.failure == CommitFailure.LINE_CONTENT_CONFLICT
              ? HistoryBodyFault.BODY_CONFLICT
              : HistoryBodyFault.INVALID_LINE_BODY);
    } catch (RuntimeException failure) {
      return new HistoryBodyResult.Rejected(
          HistoryBodyFault.INTERNAL_CACHE_FAILURE);
    }
  }

  private static boolean stillReferenced(TerminalSurfaceState surface, LineKey key) {
    return surface.activeRows.contains(key)
        || surface.historyCatalog.historySeq(key) != null;
  }

  private static Long historySeqForKey(TerminalSurfaceState surface, LineKey key) {
    return surface.historyCatalog.historySeq(key);
  }
}
