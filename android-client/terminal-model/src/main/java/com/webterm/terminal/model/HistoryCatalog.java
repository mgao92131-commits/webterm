package com.webterm.terminal.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** WS 权威历史位置目录。正文是否驻留完全不影响该结构。 */
public final class HistoryCatalog {
  private final HistoryExtent extent;
  private final Map<Long, LineKey> seqToKey;
  private final Map<LineKey, Long> keyToSeq;

  public HistoryCatalog() {
    this(HistoryExtent.INITIAL_EMPTY, Collections.emptyMap(), Collections.emptyMap());
  }

  private HistoryCatalog(
      HistoryExtent extent, Map<Long, LineKey> seqToKey, Map<LineKey, Long> keyToSeq) {
    this.extent = extent;
    this.seqToKey = Collections.unmodifiableMap(seqToKey);
    this.keyToSeq = Collections.unmodifiableMap(keyToSeq);
  }

  public HistoryExtent extent() { return extent; }
  public LineKey key(long historySeq) { return seqToKey.get(historySeq); }
  public Long historySeq(LineKey key) { return keyToSeq.get(key); }
  public Set<LineKey> keys() { return keyToSeq.keySet(); }
  public int bindingCount() { return seqToKey.size(); }

  public Editor edit() { return new Editor(this); }

  public static final class Editor {
    private HistoryExtent extent;
    private final Map<Long, LineKey> seqToKey;
    private final Map<LineKey, Long> keyToSeq;

    private Editor(HistoryCatalog source) {
      extent = source.extent;
      seqToKey = new HashMap<>(source.seqToKey);
      keyToSeq = new HashMap<>(source.keyToSeq);
    }

    public Editor setExtent(HistoryExtent next) {
      if (next == null) throw new IllegalArgumentException("history extent missing");
      extent = next;
      seqToKey.entrySet().removeIf(entry -> !next.contains(entry.getKey()));
      rebuildReverse();
      return this;
    }

    public LineKey key(long historySeq) {
      return seqToKey.get(historySeq);
    }

    public Long historySeq(LineKey key) {
      return keyToSeq.get(key);
    }

    public Editor remove(long historySeq) {
      LineKey previous = seqToKey.remove(historySeq);
      if (previous != null) keyToSeq.remove(previous);
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
      if (previous != null) keyToSeq.remove(previous);
      Long previousSeq = keyToSeq.get(key);
      if (previousSeq != null && previousSeq != historySeq) {
        throw new CommitValidationException(CommitFailure.HISTORY_LINE_ID_CONFLICT);
      }
      seqToKey.put(historySeq, key);
      keyToSeq.put(key, historySeq);
      return this;
    }

    public HistoryCatalog commit() {
      return new HistoryCatalog(
          extent, new HashMap<>(seqToKey), new HashMap<>(keyToSeq));
    }

    private void rebuildReverse() {
      keyToSeq.clear();
      for (Map.Entry<Long, LineKey> entry : seqToKey.entrySet()) {
        Long previous = keyToSeq.put(entry.getValue(), entry.getKey());
        if (previous != null) {
          throw new IllegalStateException("duplicate history LineKey");
        }
      }
    }
  }
}
