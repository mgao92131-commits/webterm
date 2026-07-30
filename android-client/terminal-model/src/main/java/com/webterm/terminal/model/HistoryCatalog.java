package com.webterm.terminal.model;

import java.util.Set;

/** WS 权威历史位置目录。正文是否驻留完全不影响该结构。 */
public final class HistoryCatalog {
  private final HistoryExtent extent;
  private final PersistentShardedMap<Long, LineKey> seqToKey;
  private final PersistentShardedMap<LineKey, Long> keyToSeq;
  /** LineID → 当前权威 HistorySeq；同 lineId 以最后一次绑定为准。 */
  private final PersistentShardedMap<Long, Long> lineIdToSeq;

  public HistoryCatalog() {
    this(HistoryExtent.INITIAL_EMPTY,
        new PersistentShardedMap<>(), new PersistentShardedMap<>(),
        new PersistentShardedMap<>());
  }

  private HistoryCatalog(
      HistoryExtent extent,
      PersistentShardedMap<Long, LineKey> seqToKey,
      PersistentShardedMap<LineKey, Long> keyToSeq,
      PersistentShardedMap<Long, Long> lineIdToSeq) {
    this.extent = extent;
    this.seqToKey = seqToKey;
    this.keyToSeq = keyToSeq;
    this.lineIdToSeq = lineIdToSeq;
  }

  public HistoryExtent extent() { return extent; }
  public LineKey key(long historySeq) { return seqToKey.get(historySeq); }
  public Long historySeq(LineKey key) { return keyToSeq.get(key); }
  public Long historySeqByLineId(long lineId) { return lineIdToSeq.get(lineId); }
  public Set<LineKey> keys() { return keyToSeq.keySet(); }
  public int bindingCount() { return seqToKey.size(); }

  public Editor edit() { return new Editor(this); }

  public static final class Editor {
    private HistoryExtent extent;
    private final PersistentShardedMap.Editor<Long, LineKey> seqToKey;
    private final PersistentShardedMap.Editor<LineKey, Long> keyToSeq;
    private final PersistentShardedMap.Editor<Long, Long> lineIdToSeq;

    private Editor(HistoryCatalog source) {
      extent = source.extent;
      seqToKey = source.seqToKey.edit();
      keyToSeq = source.keyToSeq.edit();
      lineIdToSeq = source.lineIdToSeq.edit();
    }

    public Editor setExtent(HistoryExtent next) {
      if (next == null) throw new IllegalArgumentException("history extent missing");
      HistoryExtent previous = extent;
      extent = next;
      if (previous.isEmpty()) return this;

      long prefixLast = next.isEmpty()
          ? previous.lastSeq : Math.min(previous.lastSeq, next.firstSeq - 1);
      removeRange(previous.firstSeq, prefixLast);
      if (!next.isEmpty()) {
        long suffixFirst = Math.max(previous.firstSeq, next.lastSeq + 1);
        removeRange(suffixFirst, previous.lastSeq);
      }
      return this;
    }

    public LineKey key(long historySeq) {
      return seqToKey.get(historySeq);
    }

    public Long historySeq(LineKey key) {
      return keyToSeq.get(key);
    }

    public Long historySeqByLineId(long lineId) {
      return lineIdToSeq.get(lineId);
    }

    public Editor remove(long historySeq) {
      LineKey previous = seqToKey.remove(historySeq);
      if (previous != null) {
        keyToSeq.remove(previous);
        Long mapped = lineIdToSeq.get(previous.lineId());
        if (mapped != null && mapped == historySeq) {
          lineIdToSeq.remove(previous.lineId());
        }
      }
      return this;
    }

    public Editor bindNew(long historySeq, LineKey key)
        throws CommitValidationException {
      if (!extent.contains(historySeq) || key == null) {
        throw new CommitValidationException(CommitFailure.INVALID_HISTORY_SEQUENCE);
      }
      LineKey previous = seqToKey.get(historySeq);
      if (previous != null && !previous.equals(key)) {
        throw new CommitValidationException(CommitFailure.HISTORY_PROMOTION_CONFLICT);
      }
      Long previousSeq = keyToSeq.get(key);
      if (previousSeq != null && previousSeq != historySeq) {
        throw new CommitValidationException(CommitFailure.HISTORY_LINE_ID_CONFLICT);
      }
      seqToKey.put(historySeq, key);
      keyToSeq.put(key, historySeq);
      lineIdToSeq.put(key.lineId(), historySeq);
      return this;
    }

    /** WS 权威绑定，允许 Resize Pop 后相同 HistorySeq 更换 LineKey。 */
    public Editor bindAuthoritative(long historySeq, LineKey key)
        throws CommitValidationException {
      if (!extent.contains(historySeq) || key == null) {
        throw new CommitValidationException(CommitFailure.INVALID_HISTORY_SEQUENCE);
      }
      LineKey previous = seqToKey.get(historySeq);
      if (key.equals(previous)) return this;
      if (previous != null) {
        keyToSeq.remove(previous);
        Long mapped = lineIdToSeq.get(previous.lineId());
        if (mapped != null && mapped == historySeq
            && previous.lineId() != key.lineId()) {
          lineIdToSeq.remove(previous.lineId());
        }
      }
      Long previousSeq = keyToSeq.get(key);
      if (previousSeq != null && previousSeq != historySeq) {
        throw new CommitValidationException(CommitFailure.HISTORY_LINE_ID_CONFLICT);
      }
      seqToKey.put(historySeq, key);
      keyToSeq.put(key, historySeq);
      lineIdToSeq.put(key.lineId(), historySeq);
      return this;
    }

    public HistoryCatalog commit() {
      return new HistoryCatalog(
          extent, seqToKey.commit(), keyToSeq.commit(), lineIdToSeq.commit());
    }

    private void removeRange(long firstSeq, long lastSeq) {
      if (firstSeq > lastSeq) return;
      for (long seq = firstSeq; seq <= lastSeq; seq++) {
        remove(seq);
      }
    }
  }
}
