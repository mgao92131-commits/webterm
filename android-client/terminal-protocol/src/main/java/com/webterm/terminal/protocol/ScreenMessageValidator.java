package com.webterm.terminal.protocol;

import com.webterm.terminal.protocol.generated.TerminalScreenProto;

/**
 * 对入站 webterm.screen.v1 消息做资源和结构校验。
 * 与 Go validation.go 对齐，防止超大/越界消息触发 OOM 或渲染错误。
 */
public final class ScreenMessageValidator {

  private static final int MIN_ROWS = 5;
  private static final int MAX_ROWS = 200;
  private static final int MIN_COLS = 10;
  private static final int MAX_COLS = 500;
  private static final int MAX_HISTORY_PAGE = 500;
  private static final int MAX_SNAPSHOT_HISTORY = 500;
  // history_append 与 snapshot history 取同量级上限（proto 头部只约束 snapshot ≤500 行）。
  private static final int MAX_HISTORY_APPEND = 500;
  private static final int MAX_ENVELOPE_BYTES = 2 * 1024 * 1024;
  private static final int MAX_TITLE_BYTES = 4096;
  private static final int MAX_CWD_BYTES = 4096;
  private static final int MAX_URI_BYTES = 8192;
  private static final int MAX_COMPACT_LINE_TEXT_BYTES = 32 * 1024;
  private static final int MAX_STYLES = 4096;
  private static final int MAX_LINKS = 4096;
  // instance id 长度上限：现有校验无同类惯例，取 256 字节。
  private static final int MAX_INSTANCE_ID_BYTES = 256;

  private ScreenMessageValidator() {}

  public static ValidationResult validateEnvelopeSize(byte[] data) {
    if (data == null || data.length > MAX_ENVELOPE_BYTES) {
      return ValidationResult.fail("envelope too large: " + (data == null ? 0 : data.length));
    }
    return ValidationResult.ok();
  }

  public static ValidationResult validateHello(TerminalScreenProto.Hello h) {
    if (h.getVersion() != 1) {
      return ValidationResult.fail("unsupported hello version: " + h.getVersion());
    }
    if (h.getCols() > 0 && (h.getCols() < MIN_COLS || h.getCols() > MAX_COLS)) {
      return ValidationResult.fail("invalid cols: " + h.getCols());
    }
    if (h.getRows() > 0 && (h.getRows() < MIN_ROWS || h.getRows() > MAX_ROWS)) {
      return ValidationResult.fail("invalid rows: " + h.getRows());
    }
    return ValidationResult.ok();
  }

  public static ValidationResult validateResize(TerminalScreenProto.Resize r) {
    if (r.getCols() < MIN_COLS || r.getCols() > MAX_COLS) {
      return ValidationResult.fail("invalid resize cols: " + r.getCols());
    }
    if (r.getRows() < MIN_ROWS || r.getRows() > MAX_ROWS) {
      return ValidationResult.fail("invalid resize rows: " + r.getRows());
    }
    if (r.getLeaseId().isEmpty()) {
      return ValidationResult.fail("resize requires layout lease");
    }
    return ValidationResult.ok();
  }

  public static ValidationResult validateInput(TerminalScreenProto.TerminalInput in) {
    if (in.getLeaseId().isEmpty()) {
      return ValidationResult.fail("input requires layout lease");
    }
    if (in.getClientInstanceId().isEmpty()) {
      return ValidationResult.fail("input requires client instance id");
    }
    if (in.getInputSeq() == 0) {
      return ValidationResult.fail("input requires positive sequence");
    }
    if (!in.hasText() && !in.hasKey() && !in.hasPaste() && !in.hasMouse() && !in.hasFocus()) {
      return ValidationResult.fail("unknown input type");
    }
    return ValidationResult.ok();
  }

  public static ValidationResult validateHistoryRequest(TerminalScreenProto.HistoryRequest r) {
    if (r.getLimit() <= 0 || r.getLimit() > MAX_HISTORY_PAGE) {
      return ValidationResult.fail("invalid history limit: " + r.getLimit());
    }
    return ValidationResult.ok();
  }

  public static ValidationResult validateSnapshot(TerminalScreenProto.ScreenSnapshot s) {
    if (!s.hasGeometry()) {
      return ValidationResult.fail("snapshot missing geometry");
    }
    int rows = s.getGeometry().getRows();
    int cols = s.getGeometry().getCols();
    if (rows < MIN_ROWS || rows > MAX_ROWS) {
      return ValidationResult.fail("invalid snapshot rows: " + rows);
    }
    if (cols < MIN_COLS || cols > MAX_COLS) {
      return ValidationResult.fail("invalid snapshot cols: " + cols);
    }
    if (!s.hasLayout() || s.getLayout().getLineIdsCount() != rows) {
      return ValidationResult.fail("snapshot layout mismatch");
    }
    java.util.Set<Long> screenIds = new java.util.HashSet<>();
    for (long id : s.getLayout().getLineIdsList()) {
      if (id <= 0 || !screenIds.add(id)) {
        return ValidationResult.fail("invalid snapshot layout line id: " + id);
      }
    }
    if (s.getHistoryTailSeqsCount() > MAX_SNAPSHOT_HISTORY
        || s.getHistoryTailLinesCount() > MAX_SNAPSHOT_HISTORY) {
      return ValidationResult.fail("snapshot history too large");
    }
    if (!validateString(s.getTitle(), MAX_TITLE_BYTES)) {
      return ValidationResult.fail("title too long");
    }
    if (!validateString(s.getWorkingDirectory(), MAX_CWD_BYTES)) {
      return ValidationResult.fail("cwd too long");
    }
    if (s.getStylesCount() > MAX_STYLES || s.getLinksCount() > MAX_LINKS) {
      return ValidationResult.fail("too many styles or links");
    }
    ValidationResult screenResult = validateLines(s.getScreenLinesList(), cols);
    if (!screenResult.ok) return screenResult;
    ValidationResult historyResult = validateLines(s.getHistoryTailLinesList(), cols);
    if (!historyResult.ok) return historyResult;
    java.util.Set<Long> screenDataIds = lineIds(s.getScreenLinesList());
    java.util.Set<Long> historyLineIds = lineIds(s.getHistoryTailLinesList());
    if (screenDataIds == null || historyLineIds == null) {
      return ValidationResult.fail("duplicate or invalid line data id");
    }
    for (long id : s.getLayout().getLineIdsList()) {
      if (!screenDataIds.contains(id)) return ValidationResult.fail("snapshot layout line data missing");
    }
    java.util.Set<Long> historySeqs = new java.util.HashSet<>();
    for (TerminalScreenProto.LineData line : s.getHistoryTailLinesList()) {
      if (line.getHistorySeq() <= 0 || !historySeqs.add(line.getHistorySeq())) {
        return ValidationResult.fail("invalid snapshot history sequence");
      }
    }
    long previousHistorySeq = -1;
    for (long seq : s.getHistoryTailSeqsList()) {
      if (seq <= 0 || (previousHistorySeq >= 0 && seq <= previousHistorySeq)
          || !historySeqs.contains(seq)) {
        return ValidationResult.fail("invalid snapshot history sequence: " + seq);
      }
      previousHistorySeq = seq;
    }
    return ValidationResult.ok();
  }

  /**
   * 校验 patch 的结构与资源上限（计划 §10.1）。
   *
   * @param rows 调用方持有的当前模型行数（RemoteTerminalModel.rows）。
   * @param columns 调用方持有的当前模型列数；用于拒绝 Compact metadata 越过右边界。
   */
  public static ValidationResult validatePatch(TerminalScreenProto.ScreenPatch p, int rows, int columns) {
    if (p.getInstanceId().isEmpty()) {
      return ValidationResult.fail("patch missing instance id");
    }
    if (!validateString(p.getInstanceId(), MAX_INSTANCE_ID_BYTES)) {
      return ValidationResult.fail("instance id too long");
    }
    // 兼容容忍（计划 §15）：Task 0 之前的旧 Go Agent 初始 layout_epoch 为 0，
    // 其 patch 可能携带 layout_epoch=0。此处有意不实施 §10.1 的 layout_epoch >= 1
    // 校验，否则新 Android 会拒绝旧 Go 的全部 patch；待混合版本窗口结束后再收紧。
    if (p.getBaseRevision() < 1) {
      return ValidationResult.fail("patch base_revision < 1: " + p.getBaseRevision());
    }
    if (p.getScreenRevision() <= p.getBaseRevision()) {
      return ValidationResult.fail("patch screen_revision must exceed base_revision: "
          + p.getScreenRevision() + " <= " + p.getBaseRevision());
    }
    // 兼容容忍（计划 §15）：旧 Go 可能因 bell 等输出 bump revision 而发送无任何
    // 实际变化字段的空 patch。接收端按 no-op 容忍，不实施 §10.1 的
    // “metadata-only patch 至少包含一个实际变化字段” 拒绝逻辑；
    // 空 patch 的抑制由新版服务端在源头保证。
    if (p.hasLayout() && p.getLayout().getLineIdsCount() != rows) {
      return ValidationResult.fail("patch layout length mismatch");
    }
    if (p.hasLayout()) {
      java.util.Set<Long> layoutIds = new java.util.HashSet<>();
      for (long id : p.getLayout().getLineIdsList()) {
        if (id <= 0 || !layoutIds.add(id)) {
          return ValidationResult.fail("invalid patch layout line id: " + id);
        }
      }
    }
    if (p.getHistoryAppendSeqsCount() > MAX_HISTORY_APPEND) {
      return ValidationResult.fail("history append too large: " + p.getHistoryAppendSeqsCount());
    }
    // 稳定屏幕 LineID 在删除/resize 后允许缺口，但历史顺序仍必须严格递增。
    long prevHistorySeq = -1;
    for (long seq : p.getHistoryAppendSeqsList()) {
      if (seq <= 0 || (prevHistorySeq >= 0 && seq <= prevHistorySeq)) {
        return ValidationResult.fail("history sequences are not increasing: " + seq);
      }
      prevHistorySeq = seq;
    }
    ValidationResult dictResult = validateStylesLinks(p.getNewStylesList(), p.getNewLinksList());
    if (!dictResult.ok) return dictResult;
    // title/cwd 为 optional：absent 时 getter 返回空串，present 空串（清空）同样合法，
    // 只限制长度上限，与 validateSnapshot 保持一致。
    if (!validateString(p.getTitle(), MAX_TITLE_BYTES)) {
      return ValidationResult.fail("title too long");
    }
    if (!validateString(p.getWorkingDirectory(), MAX_CWD_BYTES)) {
      return ValidationResult.fail("cwd too long");
    }
    ValidationResult lineResult = validateLines(p.getLineUpdatesList(),
        columns > 0 ? columns : MAX_COLS);
    if (!lineResult.ok) return lineResult;
    if (lineIds(p.getLineUpdatesList()) == null) {
      return ValidationResult.fail("duplicate or invalid line update id");
    }
    java.util.Set<Long> updateHistorySeqs = new java.util.HashSet<>();
    for (TerminalScreenProto.LineData line : p.getLineUpdatesList()) {
      if (line.getHistorySeq() != 0 && !updateHistorySeqs.add(line.getHistorySeq())) {
        return ValidationResult.fail("duplicate history update sequence");
      }
    }
    for (long seq : p.getHistoryAppendSeqsList()) {
      if (!updateHistorySeqs.contains(seq)) {
        return ValidationResult.fail("history append line data missing");
      }
    }
    return ValidationResult.ok();
  }

  public static ValidationResult validateHistoryPage(TerminalScreenProto.HistoryPage p) {
    if (p.getLinesCount() > MAX_HISTORY_PAGE) {
      return ValidationResult.fail("history page too large: " + p.getLinesCount());
    }
    if (p.getStylesCount() > MAX_STYLES || p.getLinksCount() > MAX_LINKS) {
      return ValidationResult.fail("too many history styles or links");
    }
    ValidationResult lineResult = validateLines(p.getLinesList(), MAX_COLS);
    if (!lineResult.ok) return lineResult;
    if (lineIds(p.getLinesList()) == null) {
      return ValidationResult.fail("duplicate or invalid history line id");
    }
    long previousHistorySeq = -1;
    for (TerminalScreenProto.LineData line : p.getLinesList()) {
      long historySeq = line.getHistorySeq();
      if (historySeq <= 0 || (previousHistorySeq >= 0 && historySeq <= previousHistorySeq)) {
        return ValidationResult.fail("invalid history page sequence: " + historySeq);
      }
      previousHistorySeq = historySeq;
    }
    return ValidationResult.ok();
  }

  private static ValidationResult validateLine(TerminalScreenProto.LineData line, int maxCols) {
    if (line.getLineId() <= 0 || line.getLineVersion() <= 0) {
      return ValidationResult.fail("line id and version must be positive");
    }
    if (!line.getText().isEmpty() || !line.getCellMeta().isEmpty()) {
      if (line.getRunsCount() != 0) return ValidationResult.fail("history line mixes compact and runs");
      return validateCompactLine(line.getText(), line.getCellMeta().toByteArray(),
          line.getStyleSpansList(), maxCols);
    }
    if (line.getStyleSpansCount() != 0) return ValidationResult.fail("history style spans require compact text");
    int runTextBytes = 0;
    for (TerminalScreenProto.CellRun run : line.getRunsList()) {
      if (run.getCol() < 0 || run.getCol() >= maxCols) {
        return ValidationResult.fail("history run col out of bounds: " + run.getCol());
      }
      for (TerminalScreenProto.Cell cell : run.getCellsList()) {
        int width = cell.getWidth();
        runTextBytes += utf8ByteLength(cell.getText());
        if (runTextBytes > MAX_COMPACT_LINE_TEXT_BYTES || (width != 1 && width != 2)) {
          return ValidationResult.fail("invalid history cell");
        }
      }
    }
    return ValidationResult.ok();
  }

  /**
   * 字典项校验，对齐 Go validation.go 的 validateStylesLinks：数量上限、保留 ID 与
   * URI 长度。cell.style_id/link_id 对字典的引用完整性需结合字典上下文在应用时校验
   * （Task 6），无状态校验器不检查引用。
   */
  private static ValidationResult validateStylesLinks(
      java.util.List<TerminalScreenProto.TerminalStyle> styles,
      java.util.List<TerminalScreenProto.Hyperlink> links) {
    if (styles.size() > MAX_STYLES || links.size() > MAX_LINKS) {
      return ValidationResult.fail("too many new styles or links");
    }
    for (TerminalScreenProto.TerminalStyle style : styles) {
      // ID 0 为默认样式保留值，服务端字典项从 1 开始分配（见 Go StyleTable.Lookup）。
      if (style.getId() == 0) {
        return ValidationResult.fail("style id 0 is reserved");
      }
    }
    for (TerminalScreenProto.Hyperlink link : links) {
      if (link.getId() == 0) {
        return ValidationResult.fail("link id 0 is reserved");
      }
      if (!validateString(link.getUri(), MAX_URI_BYTES)) {
        return ValidationResult.fail("hyperlink uri too long");
      }
    }
    return ValidationResult.ok();
  }

  private static ValidationResult validateLines(java.util.List<TerminalScreenProto.LineData> lines, int maxCols) {
    for (TerminalScreenProto.LineData line : lines) {
      if (line.getLineId() <= 0 || line.getLineVersion() <= 0) {
        return ValidationResult.fail("line id and version must be positive");
      }
      if (!line.getText().isEmpty() || !line.getCellMeta().isEmpty()) {
        if (line.getRunsCount() != 0) return ValidationResult.fail("line mixes compact and runs");
        ValidationResult compactResult = validateCompactLine(line.getText(), line.getCellMeta().toByteArray(),
            line.getStyleSpansList(), maxCols);
        if (!compactResult.ok) return compactResult;
        continue;
      }
      if (line.getStyleSpansCount() != 0) return ValidationResult.fail("style spans require compact text");
      int runTextBytes = 0;
      for (TerminalScreenProto.CellRun run : line.getRunsList()) {
        if (run.getCol() < 0 || run.getCol() >= maxCols) {
          return ValidationResult.fail("run col out of bounds: " + run.getCol());
        }
        for (TerminalScreenProto.Cell cell : run.getCellsList()) {
          runTextBytes += utf8ByteLength(cell.getText());
          if (runTextBytes > MAX_COMPACT_LINE_TEXT_BYTES) {
            return ValidationResult.fail("line text too long");
          }
          int w = cell.getWidth();
          if (w != 1 && w != 2) {
            return ValidationResult.fail("invalid cell width: " + w);
          }
        }
      }
    }
    return ValidationResult.ok();
  }

  /** Returns null for a duplicate/invalid Line ID so structural checks share one rule. */
  private static java.util.Set<Long> lineIds(java.util.List<TerminalScreenProto.LineData> lines) {
    java.util.Set<Long> ids = new java.util.HashSet<>();
    for (TerminalScreenProto.LineData line : lines) {
      if (line.getLineId() <= 0 || line.getLineVersion() <= 0 || !ids.add(line.getLineId())) {
        return null;
      }
    }
    return ids;
  }

  private static ValidationResult validateCompactLine(String text, byte[] cellMeta,
                                                      java.util.List<TerminalScreenProto.StyleSpan> spans,
                                                      int maxCols) {
    if (text.isEmpty() != (cellMeta.length == 0)) {
      return ValidationResult.fail("compact text and cell metadata must appear together");
    }
    if (text.isEmpty()) {
      return spans.isEmpty() ? ValidationResult.ok()
          : ValidationResult.fail("empty compact line must not have style spans");
    }
    if (!isWellFormedUtf16(text) || utf8ByteLength(text) > MAX_COMPACT_LINE_TEXT_BYTES
        || cellMeta.length > maxCols) {
      return ValidationResult.fail("compact line exceeds resource limit");
    }
    int textCodePoints = text.codePointCount(0, text.length());
    int metadataCodePoints = 0;
    int terminalColumns = 0;
    int[] widths = new int[cellMeta.length];
    for (int i = 0; i < cellMeta.length; i++) {
      int value = cellMeta[i] & 0xff;
      int codePointCount = value & 0x7f;
      if (codePointCount == 0) return ValidationResult.fail("compact metadata has zero code point count");
      int width = (value & 0x80) != 0 ? 2 : 1;
      if (terminalColumns + width > maxCols) {
        return ValidationResult.fail("compact metadata exceeds terminal columns");
      }
      widths[i] = width;
      metadataCodePoints += codePointCount;
      terminalColumns += width;
    }
    if (textCodePoints != metadataCodePoints) {
      return ValidationResult.fail("compact metadata code point count does not match text");
    }
    if (spans.size() > cellMeta.length) return ValidationResult.fail("too many compact style spans");
    int previousEnd = 0;
    for (TerminalScreenProto.StyleSpan span : spans) {
      if (span.getStartCol() < previousEnd || span.getStartCol() < 0
          || span.getEndCol() <= span.getStartCol() || span.getEndCol() > terminalColumns) {
        return ValidationResult.fail("invalid compact style span");
      }
      previousEnd = span.getEndCol();
    }
    int col = 0;
    for (int width : widths) {
      if (width == 2 && !sameStyleAt(spans, col, col + 1)) {
        return ValidationResult.fail("compact style span splits wide cell");
      }
      col += width;
    }
    return ValidationResult.ok();
  }

  private static boolean sameStyleAt(java.util.List<TerminalScreenProto.StyleSpan> spans,
                                     int first, int second) {
    TerminalScreenProto.StyleSpan firstSpan = styleSpanAt(spans, first);
    TerminalScreenProto.StyleSpan secondSpan = styleSpanAt(spans, second);
    int firstStyle = firstSpan == null ? 0 : firstSpan.getStyleId();
    int firstLink = firstSpan == null ? 0 : firstSpan.getLinkId();
    int secondStyle = secondSpan == null ? 0 : secondSpan.getStyleId();
    int secondLink = secondSpan == null ? 0 : secondSpan.getLinkId();
    return firstStyle == secondStyle && firstLink == secondLink;
  }

  private static TerminalScreenProto.StyleSpan styleSpanAt(
      java.util.List<TerminalScreenProto.StyleSpan> spans, int col) {
    for (TerminalScreenProto.StyleSpan span : spans) {
      if (span.getStartCol() > col) return null;
      if (span.getStartCol() <= col && col < span.getEndCol()) return span;
    }
    return null;
  }

  private static boolean isWellFormedUtf16(String text) {
    for (int index = 0; index < text.length(); index++) {
      char ch = text.charAt(index);
      if (Character.isHighSurrogate(ch)) {
        if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) return false;
      } else if (Character.isLowSurrogate(ch)) {
        return false;
      }
    }
    return true;
  }

  private static boolean validateString(String s, int maxBytes) {
    if (s == null) return true;
    if (s.length() > maxBytes) return false;
    return utf8ByteLength(s) <= maxBytes;
  }

  private static int utf8ByteLength(String text) {
    return text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
  }

  public static final class ValidationResult {
    public final boolean ok;
    public final String reason;

    private ValidationResult(boolean ok, String reason) {
      this.ok = ok;
      this.reason = reason;
    }

    public static ValidationResult ok() {
      return new ValidationResult(true, null);
    }

    public static ValidationResult fail(String reason) {
      return new ValidationResult(false, reason);
    }
  }
}
