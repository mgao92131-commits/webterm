package com.webterm.terminal.protocol;

import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;

/** screen.v2 结构与资源边界校验；Mapper 只负责 wire -> immutable domain 转换。 */
public final class ScreenMessageV2Validator {
  private ScreenMessageV2Validator() {}

  public static void validateBaseline(TerminalScreenV2Proto.Baseline baseline) {
    requireIdentity(baseline.getInstanceId(), baseline.getLayoutEpoch());
    if (baseline.getStreamGeneration() < 1 || baseline.getScreenRevision() < 1) {
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
  }

  public static void validatePatch(TerminalScreenV2Proto.ScreenPatch patch) {
    requireIdentity(patch.getInstanceId(), patch.getLayoutEpoch());
    if (patch.getStreamGeneration() < 1
        || patch.getBaseScreenRevision() < 1
        || patch.getScreenRevision() <= patch.getBaseScreenRevision()) {
      throw new IllegalArgumentException("invalid ScreenPatch revision");
    }
    boolean observable = patch.hasScreenLayout()
        || patch.getScreenLineUpdatesCount() > 0
        || patch.hasCursor()
        || patch.hasModes()
        || patch.hasPalette()
        || patch.hasActiveBuffer()
        || patch.hasTitle()
        || patch.hasWorkingDirectory();
    if (!observable) throw new IllegalArgumentException("empty ScreenPatch");
    validateDictionary(patch.getDictionary());
  }

  public static void validateHistoryDelta(TerminalScreenV2Proto.HistoryDelta delta) {
    requireIdentity(delta.getInstanceId(), delta.getLayoutEpoch());
    if (delta.getStreamGeneration() < 1 || delta.getLinesCount() > 256) {
      throw new IllegalArgumentException("invalid HistoryDelta bounds");
    }
    validateExtent(delta.getAvailableExtent());
    validateDictionary(delta.getDictionary());
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
    if (first < 1 || last < 0 || (last != Long.MAX_VALUE && last + 1 < first)) {
      throw new IllegalArgumentException("invalid history extent");
    }
  }

  private static void validateDictionary(TerminalScreenV2Proto.Dictionary dictionary) {
    if (dictionary.getStylesCount() > 4096 || dictionary.getLinksCount() > 4096) {
      throw new IllegalArgumentException("dictionary exceeds limit");
    }
  }
}
