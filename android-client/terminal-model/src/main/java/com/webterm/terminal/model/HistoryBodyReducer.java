package com.webterm.terminal.model;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** HTTP Range 只校验当前 WS Catalog 并填充唯一 BodyCache。 */
public final class HistoryBodyReducer {
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

    TerminalSurfaceTransaction tx = surface.beginTransaction();
    long previousSeq = 0;
    long changedFrom = Long.MAX_VALUE;
    long changedTo = 0;
    int applied = 0;
    int stale = 0;
    Set<Long> seenSeqs = new HashSet<>();
    Set<LineKey> seenKeys = new HashSet<>();
    try {
      for (HistoryBodyEntry entry : response.lines) {
        if (entry == null || entry.historySeq() < request.fromSeq()
            || entry.historySeq() > request.toSeq()) {
          return new HistoryBodyResult.Rejected(
              HistoryBodyFault.INVALID_REQUEST_RANGE,
              request.fromSeq(), request.toSeq());
        }
        if (entry.historySeq() <= previousSeq || !seenSeqs.add(entry.historySeq())
            || !seenKeys.add(entry.key())) {
          return new HistoryBodyResult.Rejected(
              HistoryBodyFault.INVALID_RESPONSE_ORDER,
              request.fromSeq(), request.toSeq());
        }
        previousSeq = entry.historySeq();
        if (!surface.historyCatalog.extent().contains(entry.historySeq())) {
          stale++;
          continue;
        }
        LineKey expected = surface.historyCatalog.key(entry.historySeq());
        if (!entry.key().equals(expected)
            || surface.activeRows.contains(entry.key())) {
          stale++;
          continue;
        }
        LineBody previous = tx.bodyCache().body(entry.key());
        if (previous != null && !previous.equals(entry.body())) {
          return new HistoryBodyResult.Rejected(
              HistoryBodyFault.BODY_CONFLICT, entry.historySeq(), entry.historySeq());
        }
        tx.bodyCache().putHistory(entry.historySeq(), entry.key(), entry.body());
        changedFrom = Math.min(changedFrom, entry.historySeq());
        changedTo = Math.max(changedTo, entry.historySeq());
        applied++;
      }
      if (applied == 0) {
        return new HistoryBodyResult.StaleIgnored(stale);
      }
      tx.bodyCache().evictIfNeeded(pins == null ? EvictionPins.NONE : pins);
      return new HistoryBodyResult.Applied(
          tx.commit(), changedFrom, changedTo, applied, stale);
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
}
