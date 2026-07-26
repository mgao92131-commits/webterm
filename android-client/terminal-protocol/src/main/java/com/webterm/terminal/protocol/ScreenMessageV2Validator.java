package com.webterm.terminal.protocol;

import com.webterm.terminal.model.CommitFailure;
import com.webterm.terminal.model.CommitValidationException;
import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;

/** screen.v2 结构与资源边界校验；Mapper 只负责 wire -> immutable domain 转换。 */
public final class ScreenMessageV2Validator {
  private ScreenMessageV2Validator() {}

  public static void validateBaseline(TerminalScreenV2Proto.Baseline baseline) {
    requireIdentity(baseline.getInstanceId(), baseline.getLayoutEpoch());
    if (baseline.getScreenRevision() < 1 || baseline.getDictionaryGeneration() < 1
        || baseline.getHistoryGeneration() < 1) {
      throw new IllegalArgumentException("invalid Baseline generation/revision");
    }
    int rows = baseline.getGeometry().getRows();
    int cols = baseline.getGeometry().getCols();
    if (rows < 1 || rows > 200 || cols < 1 || cols > 500
        || baseline.getScreenLinesCount() != rows
        || baseline.getScreenLayout().getLineIdsCount() != rows
        || baseline.getHistoryTail().getLinesCount() > 128) {
      throw new IllegalArgumentException("invalid Baseline bounds");
    }
    validateExtent(baseline.getHistoryExtent());
    validateDictionary(baseline.getDictionary());
    for (TerminalScreenV2Proto.LineData line : baseline.getScreenLinesList()) validateLineData(line, cols);
    for (TerminalScreenV2Proto.LineData line : baseline.getHistoryTail().getLinesList()) validateLineData(line, cols);
  }

  public static void validateTerminalCommit(
      TerminalScreenV2Proto.TerminalCommit commit, int rows)
      throws CommitValidationException {
    try {
      requireIdentity(commit.getInstanceId(), commit.getLayoutEpoch());
    } catch (IllegalArgumentException invalidIdentity) {
      throw new CommitValidationException(CommitFailure.IDENTITY_MISMATCH, invalidIdentity);
    }
    if (rows < 1 || rows > 200 || commit.getDictionaryGeneration() < 1
        || commit.getHistoryGeneration() < 1
        || commit.getBaseRevision() < 1 || commit.getRevision() <= commit.getBaseRevision()) {
      throw new CommitValidationException(CommitFailure.REVISION_GAP);
    }
    boolean observable = commit.hasScreen() || commit.hasHistory() || commit.hasCursor()
        || commit.hasModes() || commit.hasPalette() || commit.hasActiveBuffer()
        || commit.getDictionaryAdditions().getStylesCount() > 0
        || commit.getDictionaryAdditions().getLinksCount() > 0;
    if (!observable) throw new CommitValidationException(CommitFailure.INVALID_LINE_DATA);
    if (commit.hasScreen()) {
      if (commit.getScreen().getWritesCount() > rows) {
        throw new CommitValidationException(CommitFailure.INVALID_LINE_DATA);
      }
      boolean[] seen = new boolean[rows];
      for (TerminalScreenV2Proto.ScreenRowWrite write : commit.getScreen().getWritesList()) {
        int row = write.getRow();
        if (!write.hasLine() || row < 0 || row >= rows || seen[row]
            || write.getLine().getHistorySeq() != 0) {
          throw new CommitValidationException(row >= 0 && row < rows && seen[row]
              ? CommitFailure.DUPLICATE_SCREEN_ROW : CommitFailure.INVALID_LINE_DATA);
        }
        seen[row] = true;
        validateCommitLineData(write.getLine(), 500);
      }
      if (commit.getScreen().hasScroll()) {
        TerminalScreenV2Proto.ScreenScroll scroll = commit.getScreen().getScroll();
        int height = scroll.getBottomRowExclusive() - scroll.getTopRow();
        long magnitude = Math.abs((long) scroll.getDeltaRows());
        if (scroll.getTopRow() != 0 || scroll.getBottomRowExclusive() != rows
            || height <= 0 || magnitude == 0 || magnitude >= height) {
          throw new CommitValidationException(CommitFailure.INVALID_SCROLL);
        }
      }
    }
    if (commit.hasHistory()) {
      if (!commit.getHistory().hasFinalExtent()
          || commit.getHistory().getAppendedLinesCount() > 128
          || commit.getHistory().getPromotionsCount() > rows) {
        throw new CommitValidationException(CommitFailure.INVALID_HISTORY_SEQUENCE);
      }
      try {
        validateExtent(commit.getHistory().getFinalExtent());
      } catch (IllegalArgumentException invalidExtent) {
        throw new CommitValidationException(
            CommitFailure.INVALID_HISTORY_SEQUENCE, invalidExtent);
      }
      long previous = 0;
      long appendedBytes = 0;
      long first = commit.getHistory().getFinalExtent().getFirstSeq();
      long last = commit.getHistory().getFinalExtent().getLastSeq();
      for (TerminalScreenV2Proto.LineData line : commit.getHistory().getAppendedLinesList()) {
        long seq = line.getHistorySeq();
        if (seq == 0 || seq <= previous || seq < first || seq > last) {
          throw new CommitValidationException(CommitFailure.INVALID_HISTORY_SEQUENCE);
        }
        previous = seq;
        validateCommitLineData(line, 500);
        appendedBytes += line.getUtf8Text().size() + line.getGlyphMeta().size()
            + (long) line.getStyleSpansCount() * 16L;
        if (appendedBytes > (1L << 20)) {
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

  private static void validateCommitLineData(
      TerminalScreenV2Proto.LineData line, int columns)
      throws CommitValidationException {
    try {
      validateLineData(line, columns);
    } catch (IllegalArgumentException invalidLine) {
      throw new CommitValidationException(CommitFailure.INVALID_LINE_DATA, invalidLine);
    }
  }

  public static void validateHistoryRange(
      TerminalScreenV2Proto.HistoryRangeResponse response) {
    requireIdentity(response.getInstanceId(), response.getLayoutEpoch());
    if (response.getRequestId().isEmpty()
        || response.getStatus()
            == TerminalScreenV2Proto.HistoryRangeStatus.HISTORY_RANGE_STATUS_UNSPECIFIED
        || response.getLinesCount() > 256) {
      throw new IllegalArgumentException("invalid HistoryRangeResponse bounds");
    }
    validateExtent(response.getAvailableExtent());
    validateDictionary(response.getDictionary());
    if (response.getHistoryGeneration() < 1) {
      throw new IllegalArgumentException("invalid HistoryRange generation");
    }
    switch (response.getStatus()) {
      case HISTORY_RANGE_STATUS_STALE_PROJECTION:
      case HISTORY_RANGE_STATUS_RETRYABLE:
        if (response.getLinesCount() != 0) {
          throw new IllegalArgumentException(
              "non-data HistoryRange status must not contain lines");
        }
        break;
      case HISTORY_RANGE_STATUS_OK:
      case HISTORY_RANGE_STATUS_TRIMMED:
        break;
      default:
        throw new IllegalArgumentException("invalid HistoryRangeResponse status");
    }
  }

  private static void requireIdentity(String instanceId, long layoutEpoch) {
    if (instanceId == null || instanceId.isEmpty() || layoutEpoch < 1) {
      throw new IllegalArgumentException("missing projection identity");
    }
  }

  private static void validateExtent(TerminalScreenV2Proto.HistoryExtent extent) {
    long first = extent.getFirstSeq();
    long last = extent.getLastSeq();
    if (first == 0 && last == 0) return; // 宽容 proto3 默认空值，Mapper 规范化为 1..0。
    if (first < 1 || last < 0 || last == Long.MAX_VALUE || first > last + 1) {
      throw new IllegalArgumentException("invalid history extent");
    }
  }

  private static void validateDictionary(TerminalScreenV2Proto.Dictionary dictionary) {
    if (dictionary.getStylesCount() > 4096 || dictionary.getLinksCount() > 4096) {
      throw new IllegalArgumentException("dictionary exceeds limit");
    }
  }

  private static void validateLineData(TerminalScreenV2Proto.LineData line, int columns) {
    if (line.getLineId() == 0 || line.getLineVersion() == 0
        || line.getUtf8Text().size() > (1 << 20) || line.getGlyphMeta().size() > (1 << 16)) {
      throw new IllegalArgumentException("invalid LineData bounds");
    }
    int previousEnd = 0;
    for (TerminalScreenV2Proto.StyleSpan span : line.getStyleSpansList()) {
      if (span.getStartCol() < previousEnd || span.getStartCol() < 0
          || span.getEndCol() <= span.getStartCol() || span.getEndCol() > columns) {
        throw new IllegalArgumentException("invalid style span");
      }
      previousEnd = span.getEndCol();
    }
  }
}
