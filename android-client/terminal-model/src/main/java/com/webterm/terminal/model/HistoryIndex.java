package com.webterm.terminal.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 已加载历史位置索引：historySeq → LineID。正文始终位于 LineStore。 */
public final class HistoryIndex {
  private HistoryExtent extent = HistoryExtent.INITIAL_EMPTY;
  private Map<Long, Long> seqToLineId = Collections.emptyMap();
  private Map<Long, Long> lineIdToSeq = Collections.emptyMap();

  public HistoryExtent extent() {
    return extent;
  }

  public Long lineId(long historySeq) {
    return seqToLineId.get(historySeq);
  }

  public Long historySeq(long lineId) {
    return lineIdToSeq.get(lineId);
  }

  public Set<Long> loadedLineIds() {
    return Collections.unmodifiableSet(new HashSet<>(lineIdToSeq.keySet()));
  }

  public Editor edit() {
    return new Editor();
  }

  public final class Editor {
    private HistoryExtent workingExtent = extent;
    private final Map<Long, Long> workingSeq = new HashMap<>(seqToLineId);
    private final Map<Long, Long> workingLine = new HashMap<>(lineIdToSeq);
    private boolean committed;

    public Editor setExtent(HistoryExtent next) {
      ensureOpen();
      if (next == null) throw new IllegalArgumentException("history extent missing");
      workingExtent = next;
      workingSeq.entrySet().removeIf(entry -> !next.contains(entry.getKey()));
      workingLine.clear();
      for (Map.Entry<Long, Long> entry : workingSeq.entrySet()) {
        workingLine.put(entry.getValue(), entry.getKey());
      }
      return this;
    }

    public Editor bind(long historySeq, long lineId) throws CommitValidationException {
      ensureOpen();
      if (!workingExtent.contains(historySeq) || lineId <= 0) {
        throw new CommitValidationException(CommitFailure.INVALID_HISTORY_SEQUENCE);
      }
      Long previousLine = workingSeq.get(historySeq);
      if (previousLine != null && previousLine != lineId) {
        throw new CommitValidationException(CommitFailure.HISTORY_PROMOTION_CONFLICT);
      }
      Long previousSeq = workingLine.get(lineId);
      if (previousSeq != null && previousSeq != historySeq) {
        throw new CommitValidationException(CommitFailure.HISTORY_LINE_ID_CONFLICT);
      }
      workingSeq.put(historySeq, lineId);
      workingLine.put(lineId, historySeq);
      return this;
    }

    public Long historySeq(long lineId) {
      ensureOpen();
      return workingLine.get(lineId);
    }

    public void retainLineIds(Set<Long> retainedLineIds) {
      ensureOpen();
      workingLine.keySet().retainAll(retainedLineIds);
      workingSeq.entrySet().removeIf(entry -> !retainedLineIds.contains(entry.getValue()));
    }

    public void commit() {
      ensureOpen();
      extent = workingExtent;
      seqToLineId = Collections.unmodifiableMap(new HashMap<>(workingSeq));
      lineIdToSeq = Collections.unmodifiableMap(new HashMap<>(workingLine));
      committed = true;
    }

    private void ensureOpen() {
      if (committed) throw new IllegalStateException("HistoryIndex editor already committed");
    }
  }
}
