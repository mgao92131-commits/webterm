package com.webterm.terminal.protocol;

import com.webterm.terminal.model.CommitFailure;
import com.webterm.terminal.model.CommitValidationException;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.protocol.generated.TerminalScreenV3Proto;

/** screen.v3 结构与资源边界校验；Mapper 只负责 wire -> immutable domain 转换。 */
public final class ScreenMessageV3Validator {
  private ScreenMessageV3Validator() {}

  public static void validateBaseline(TerminalScreenV3Proto.Baseline baseline) {
    if (baseline == null || baseline.getInstanceId().isEmpty()
        || baseline.getLayoutEpoch() < 1) {
      throw baselineFault(BaselineFaultCode.INVALID_IDENTITY);
    }
    if (baseline.getScreenRevision() < 1 || baseline.getHistoryGeneration() < 1) {
      throw baselineFault(BaselineFaultCode.INVALID_GENERATION);
    }
    int rows = baseline.getGeometry().getRows();
    int cols = baseline.getGeometry().getCols();
    if (rows < 1 || rows > 200 || cols < 1 || cols > 500) {
      throw baselineFault(BaselineFaultCode.INVALID_GEOMETRY);
    }
    if (baseline.getScreenRowsCount() != rows) {
      throw baselineFault(BaselineFaultCode.SCREEN_LINE_COUNT_MISMATCH);
    }
    try {
      validateExtent(baseline.getHistoryExtent());
    } catch (IllegalArgumentException invalidExtent) {
      throw new BaselineValidationException(
          BaselineFaultCode.HISTORY_BINDING_COUNT_MISMATCH, invalidExtent);
    }
    long historyFirst = baseline.getHistoryExtent().getFirstSeq();
    long historyLast = baseline.getHistoryExtent().getLastSeq();
    long expectedBindings = historyLast >= historyFirst ? historyLast - historyFirst + 1 : 0;
    if (baseline.getHistoryBindingsCount() != expectedBindings) {
      throw baselineFault(BaselineFaultCode.HISTORY_BINDING_COUNT_MISMATCH);
    }
    long previousHistorySeq = 0;
    java.util.HashSet<LineKey> historyKeys = new java.util.HashSet<>();
    for (TerminalScreenV3Proto.HistoryBinding binding :
        baseline.getHistoryBindingsList()) {
      if (binding.getHistorySeq() <= previousHistorySeq) {
        throw baselineFault(BaselineFaultCode.HISTORY_SEQ_OUT_OF_ORDER);
      }
      if (binding.getHistorySeq() < historyFirst
          || binding.getHistorySeq() > historyLast) {
        throw baselineFault(BaselineFaultCode.HISTORY_SEQ_OUT_OF_EXTENT);
      }
      if (binding.getKey().getLineId() <= 0 || binding.getKey().getBodyVersion() <= 0) {
        throw baselineFault(BaselineFaultCode.INVALID_LINE_BODY);
      }
      LineKey key = new LineKey(
          binding.getKey().getLineId(), binding.getKey().getBodyVersion());
      if (!historyKeys.add(key)) {
        throw baselineFault(BaselineFaultCode.DUPLICATE_HISTORY_KEY);
      }
      previousHistorySeq = binding.getHistorySeq();
    }
    java.util.HashSet<Integer> styleIds = new java.util.HashSet<>();
    java.util.HashSet<Integer> linkIds = new java.util.HashSet<>();
    try {
      validateDictionary(baseline.getDictionary());
      for (TerminalScreenV3Proto.TerminalStyle style :
          baseline.getDictionary().getStylesList()) {
        if (style.getId() <= 0 || !styleIds.add(style.getId())) {
          throw new IllegalArgumentException("invalid style dictionary");
        }
      }
      for (TerminalScreenV3Proto.Hyperlink link :
          baseline.getDictionary().getLinksList()) {
        if (link.getId() <= 0 || !linkIds.add(link.getId())) {
          throw new IllegalArgumentException("invalid link dictionary");
        }
      }
    } catch (IllegalArgumentException invalidDictionary) {
      throw new BaselineValidationException(
          BaselineFaultCode.INVALID_DICTIONARY, invalidDictionary);
    }
    java.util.HashSet<LineKey> activeKeys = new java.util.HashSet<>();
    java.util.HashSet<Long> activeLineIds = new java.util.HashSet<>();
    for (TerminalScreenV3Proto.LineKey rowKey : baseline.getScreenRowsList()) {
      if (rowKey.getLineId() <= 0 || rowKey.getBodyVersion() <= 0) {
        throw baselineFault(BaselineFaultCode.INVALID_LINE_BODY);
      }
      LineKey key = new LineKey(rowKey.getLineId(), rowKey.getBodyVersion());
      if (!activeKeys.add(key) || !activeLineIds.add(rowKey.getLineId())) {
        throw baselineFault(BaselineFaultCode.DUPLICATE_ACTIVE_KEY);
      }
      if (historyKeys.contains(key)) {
        throw baselineFault(BaselineFaultCode.ACTIVE_HISTORY_KEY_CONFLICT);
      }
    }
    java.util.HashSet<LineKey> bodyKeys = new java.util.HashSet<>();
    for (TerminalScreenV3Proto.LineBodyRecord line : baseline.getScreenBodiesList()) {
      if (line.getPhysicalColumns() != cols) {
        throw baselineFault(BaselineFaultCode.LINE_COLUMN_COUNT_MISMATCH);
      }
      try {
        validateLineBodyRecord(line, cols);
      } catch (IllegalArgumentException invalidLine) {
        throw new BaselineValidationException(
            BaselineFaultCode.INVALID_LINE_BODY, invalidLine);
      }
      for (TerminalScreenV3Proto.StyleSpan span : line.getStyleSpansList()) {
        if ((span.getStyleId() != 0 && !styleIds.contains(span.getStyleId()))
            || (span.getLinkId() != 0 && !linkIds.contains(span.getLinkId()))) {
          throw baselineFault(BaselineFaultCode.INVALID_DICTIONARY);
        }
      }
      LineKey key = new LineKey(
          line.getKey().getLineId(), line.getKey().getBodyVersion());
      if (!bodyKeys.add(key)) {
        throw baselineFault(BaselineFaultCode.DUPLICATE_ACTIVE_KEY);
      }
    }
    // Baseline 必须完整携带当前屏幕正文；冷历史只带 HistoryBinding，正文由
    // VisibleBodyLoader 按需加载。最终关系：bodyKeys == activeKeys。
    for (LineKey activeKey : activeKeys) {
      if (!bodyKeys.contains(activeKey)) {
        throw baselineFault(BaselineFaultCode.INVALID_LINE_BODY);
      }
    }
    for (LineKey bodyKey : bodyKeys) {
      if (!activeKeys.contains(bodyKey)) {
        throw baselineFault(BaselineFaultCode.INVALID_LINE_BODY);
      }
    }
  }

  private static BaselineValidationException baselineFault(BaselineFaultCode code) {
    return new BaselineValidationException(code);
  }

  public static void validateTerminalCommit(
      TerminalScreenV3Proto.TerminalCommit commit, int rows)
      throws CommitValidationException {
    try {
      requireIdentity(commit.getInstanceId(), commit.getLayoutEpoch());
    } catch (IllegalArgumentException invalidIdentity) {
      throw new CommitValidationException(CommitFailure.IDENTITY_MISMATCH, invalidIdentity);
    }
    if (rows < 1 || rows > 200 || commit.getHistoryGeneration() < 1
        || commit.getBaseRevision() < 1 || commit.getRevision() <= commit.getBaseRevision()) {
      throw new CommitValidationException(CommitFailure.REVISION_GAP);
    }
    boolean observable = commit.hasScreen() || commit.hasHistory() || commit.hasCursor()
        || commit.hasModes() || commit.hasPalette() || commit.hasActiveBuffer()
        || commit.getBodyUpsertsCount() > 0
        || commit.getDictionaryAdditions().getStylesCount() > 0
        || commit.getDictionaryAdditions().getLinksCount() > 0;
    if (!observable) throw new CommitValidationException(CommitFailure.INVALID_LINE_DATA);
    for (TerminalScreenV3Proto.LineBodyRecord upsert : commit.getBodyUpsertsList()) {
      try {
        validateLineBodyRecord(upsert, 500);
      } catch (IllegalArgumentException invalidLine) {
        throw new CommitValidationException(CommitFailure.INVALID_LINE_DATA, invalidLine);
      }
    }
    if (commit.hasScreen()) {
      if (commit.getScreen().getWritesCount() > rows) {
        throw new CommitValidationException(CommitFailure.INVALID_LINE_DATA);
      }
      boolean[] seen = new boolean[rows];
      for (TerminalScreenV3Proto.ScreenRowWrite write : commit.getScreen().getWritesList()) {
        int row = write.getRow();
        if (write.getKey().getLineId() <= 0 || write.getKey().getBodyVersion() <= 0
            || row < 0 || row >= rows || seen[row]) {
          throw new CommitValidationException(row >= 0 && row < rows && seen[row]
              ? CommitFailure.DUPLICATE_SCREEN_ROW : CommitFailure.INVALID_LINE_DATA);
        }
        seen[row] = true;
      }
      if (commit.getScreen().hasScroll()) {
        TerminalScreenV3Proto.ScreenScroll scroll = commit.getScreen().getScroll();
        int height = scroll.getBottomRowExclusive() - scroll.getTopRow();
        long magnitude = Math.abs((long) scroll.getDeltaRows());
        if (scroll.getTopRow() != 0 || scroll.getBottomRowExclusive() != rows
            || height <= 0 || magnitude == 0 || magnitude >= height) {
          throw new CommitValidationException(CommitFailure.INVALID_SCROLL);
        }
      }
    }
    if (commit.hasHistory()) {
      if (!commit.getHistory().hasFinalExtent()) {
        throw new CommitValidationException(CommitFailure.INVALID_HISTORY_SEQUENCE);
      }
      try {
        validateExtent(commit.getHistory().getFinalExtent());
      } catch (IllegalArgumentException invalidExtent) {
        throw new CommitValidationException(
            CommitFailure.INVALID_HISTORY_SEQUENCE, invalidExtent);
      }
      long previous = 0;
      long first = commit.getHistory().getFinalExtent().getFirstSeq();
      long last = commit.getHistory().getFinalExtent().getLastSeq();
      for (TerminalScreenV3Proto.HistoryBinding push : commit.getHistory().getPushesList()) {
        long seq = push.getHistorySeq();
        if (seq == 0 || seq <= previous || seq < first || seq > last) {
          throw new CommitValidationException(CommitFailure.INVALID_HISTORY_SEQUENCE);
        }
        previous = seq;
        if (push.getKey().getLineId() == 0 || push.getKey().getBodyVersion() == 0) {
          throw new CommitValidationException(CommitFailure.INVALID_HISTORY_SEQUENCE);
        }
      }
    }
    try {
      validateDictionary(commit.getDictionaryAdditions());
    } catch (IllegalArgumentException invalidDictionary) {
      throw new CommitValidationException(CommitFailure.INVALID_LINE_DATA, invalidDictionary);
    }
  }

  public static void validateLineBodyRecord(TerminalScreenV3Proto.LineBodyRecord line) {
    if (line == null || line.getKey().getLineId() <= 0
        || line.getKey().getBodyVersion() <= 0
        || line.getPhysicalColumns() < 1 || line.getPhysicalColumns() > 500) {
      throw new IllegalArgumentException("invalid LineBodyRecord identity or geometry");
    }
    validateLineBodyRecord(line, line.getPhysicalColumns());
  }

  private static void requireIdentity(String instanceId, long layoutEpoch) {
    if (instanceId == null || instanceId.isEmpty() || layoutEpoch < 1) {
      throw new IllegalArgumentException("missing projection identity");
    }
  }

  private static void validateExtent(TerminalScreenV3Proto.HistoryExtent extent) {
    long first = extent.getFirstSeq();
    long last = extent.getLastSeq();
    if (first == 0 && last == 0) return;
    if (first < 1 || last < 0 || last == Long.MAX_VALUE || first > last + 1) {
      throw new IllegalArgumentException("invalid history extent");
    }
  }

  private static void validateDictionary(TerminalScreenV3Proto.Dictionary dictionary) {
    if (dictionary.getStylesCount() > 4096 || dictionary.getLinksCount() > 4096) {
      throw new IllegalArgumentException("dictionary exceeds limit");
    }
  }

  private static void validateLineBodyRecord(
      TerminalScreenV3Proto.LineBodyRecord line, int columns) {
    if (line.getKey().getLineId() == 0 || line.getKey().getBodyVersion() == 0
        || line.getUtf8Text().size() > (1 << 20) || line.getGlyphMeta().size() > (1 << 16)) {
      throw new IllegalArgumentException("invalid LineBodyRecord bounds");
    }
    int previousEnd = 0;
    for (TerminalScreenV3Proto.StyleSpan span : line.getStyleSpansList()) {
      if (span.getStartCol() < previousEnd || span.getStartCol() < 0
          || span.getEndCol() <= span.getStartCol() || span.getEndCol() > columns) {
        throw new IllegalArgumentException("invalid style span");
      }
      previousEnd = span.getEndCol();
    }
  }
}
