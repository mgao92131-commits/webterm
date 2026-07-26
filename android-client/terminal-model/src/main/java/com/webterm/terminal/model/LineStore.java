package com.webterm.terminal.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Surface 内唯一的终端正文存储。ActiveRows 与 HistoryIndex 只保存 LineID，
 * 行进入历史不会在这里创建第二份正文。
 */
public final class LineStore {
  private Map<Long, TerminalLine> lines = Collections.emptyMap();

  public TerminalLine line(long lineId) {
    return lines.get(lineId);
  }

  public int size() {
    return lines.size();
  }

  public Editor edit() {
    return new Editor(lines);
  }

  public final class Editor {
    private final Map<Long, TerminalLine> working;
    private boolean committed;

    private Editor(Map<Long, TerminalLine> source) {
      working = new HashMap<>(source);
    }

    /**
     * 写入或验证不可变行版本。historySeq 是位置，不属于 LineStore 正文，
     * 因此存储时统一归零。
     */
    public TerminalLine put(TerminalLine candidate) throws CommitValidationException {
      ensureOpen();
      if (candidate == null || candidate.id <= 0 || candidate.version <= 0) {
        throw new CommitValidationException(CommitFailure.INVALID_LINE_DATA);
      }
      TerminalLine canonical = candidate.historySeq == 0
          ? candidate : candidate.withHistorySeq(0);
      TerminalLine previous = working.get(canonical.id);
      if (previous != null) {
        if (canonical.version < previous.version) {
          throw new CommitValidationException(CommitFailure.LINE_VERSION_REGRESSION);
        }
        if (canonical.version == previous.version) {
          if (!canonical.sameContent(previous)) {
            throw new CommitValidationException(CommitFailure.LINE_CONTENT_CONFLICT);
          }
          return previous;
        }
      }
      working.put(canonical.id, canonical);
      return canonical;
    }

    public TerminalLine line(long lineId) {
      ensureOpen();
      return working.get(lineId);
    }

    public void retainOnly(java.util.Set<Long> retainedLineIds) {
      ensureOpen();
      working.keySet().retainAll(retainedLineIds);
    }

    public void commit() {
      ensureOpen();
      lines = Collections.unmodifiableMap(new HashMap<>(working));
      committed = true;
    }

    private void ensureOpen() {
      if (committed) throw new IllegalStateException("LineStore editor already committed");
    }
  }
}
