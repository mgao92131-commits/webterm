package com.webterm.terminal.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 常驻历史位置目录：HistorySeq → LineID + LineVersion。正文是否驻留与此结构无关。 */
public final class HistoryIndex {
  private HistoryExtent extent = HistoryExtent.INITIAL_EMPTY;
  private Map<Long, HistoryLineRef> seqToRef = Collections.emptyMap();
  private Map<Long, Long> lineIdToSeq = Collections.emptyMap();

  public HistoryExtent extent() {
    return extent;
  }

  public HistoryLineRef ref(long historySeq) {
    return seqToRef.get(historySeq);
  }

  public Long lineId(long historySeq) {
    HistoryLineRef ref = seqToRef.get(historySeq);
    return ref == null ? null : ref.lineId;
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
    private final Map<Long, HistoryLineRef> workingSeq = new HashMap<>(seqToRef);
    private final Map<Long, Long> workingLine = new HashMap<>(lineIdToSeq);
    private boolean committed;

    public Editor setExtent(HistoryExtent next) {
      ensureOpen();
      if (next == null) throw new IllegalArgumentException("history extent missing");
      workingExtent = next;
      workingSeq.entrySet().removeIf(entry -> !next.contains(entry.getKey()));
      rebuildReverse();
      return this;
    }

    public Editor bind(long historySeq, long lineId, long lineVersion)
        throws CommitValidationException {
      ensureOpen();
      if (!workingExtent.contains(historySeq) || lineId <= 0 || lineVersion <= 0) {
        throw new CommitValidationException(CommitFailure.INVALID_HISTORY_SEQUENCE);
      }
      HistoryLineRef previous = workingSeq.get(historySeq);
      if (previous != null
          && (previous.lineId != lineId || previous.lineVersion != lineVersion)) {
        throw new CommitValidationException(CommitFailure.HISTORY_PROMOTION_CONFLICT);
      }
      Long previousSeq = workingLine.get(lineId);
      if (previousSeq != null && previousSeq != historySeq) {
        throw new CommitValidationException(CommitFailure.HISTORY_LINE_ID_CONFLICT);
      }
      workingSeq.put(historySeq, new HistoryLineRef(lineId, lineVersion));
      workingLine.put(lineId, historySeq);
      return this;
    }

    /** WS 权威绑定：允许尾部 Pop 后同一 HistorySeq 重新绑定新的行身份。 */
    public Editor bindAuthoritative(long historySeq, long lineId, long lineVersion)
        throws CommitValidationException {
      ensureOpen();
      if (!workingExtent.contains(historySeq) || lineId <= 0 || lineVersion <= 0) {
        throw new CommitValidationException(CommitFailure.INVALID_HISTORY_SEQUENCE);
      }
      HistoryLineRef previous = workingSeq.get(historySeq);
      if (previous != null && previous.lineId == lineId
          && previous.lineVersion == lineVersion) {
        return this;
      }
      if (previous != null) {
        workingLine.remove(previous.lineId);
      }
      Long previousSeq = workingLine.get(lineId);
      if (previousSeq != null && previousSeq != historySeq) {
        throw new CommitValidationException(CommitFailure.HISTORY_LINE_ID_CONFLICT);
      }
      workingSeq.put(historySeq, new HistoryLineRef(lineId, lineVersion));
      workingLine.put(lineId, historySeq);
      return this;
    }

    public Editor removeBinding(long historySeq) {
      ensureOpen();
      HistoryLineRef previous = workingSeq.remove(historySeq);
      if (previous != null) {
        workingLine.remove(previous.lineId);
      }
      return this;
    }

    public Long historySeq(long lineId) {
      ensureOpen();
      return workingLine.get(lineId);
    }

    public HistoryLineRef ref(long historySeq) {
      ensureOpen();
      return workingSeq.get(historySeq);
    }

    public void commit() {
      ensureOpen();
      extent = workingExtent;
      seqToRef = Collections.unmodifiableMap(new HashMap<>(workingSeq));
      lineIdToSeq = Collections.unmodifiableMap(new HashMap<>(workingLine));
      committed = true;
    }

    private void rebuildReverse() {
      workingLine.clear();
      for (Map.Entry<Long, HistoryLineRef> entry : workingSeq.entrySet()) {
        workingLine.put(entry.getValue().lineId, entry.getKey());
      }
    }

    private void ensureOpen() {
      if (committed) throw new IllegalStateException("HistoryIndex editor already committed");
    }
  }
}
