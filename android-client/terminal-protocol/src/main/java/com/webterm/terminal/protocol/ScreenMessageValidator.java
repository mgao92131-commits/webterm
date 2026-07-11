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
  private static final int MAX_ENVELOPE_BYTES = 2 * 1024 * 1024;
  private static final int MAX_TITLE_BYTES = 4096;
  private static final int MAX_CWD_BYTES = 4096;
  private static final int MAX_URI_BYTES = 8192;
  private static final int MAX_CELL_TEXT_BYTES = 64;
  private static final int MAX_STYLES = 4096;
  private static final int MAX_LINKS = 4096;

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
    if (s.getHistory().getLinesCount() > MAX_SNAPSHOT_HISTORY) {
      return ValidationResult.fail("snapshot history too large: " + s.getHistory().getLinesCount());
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
    return validateLines(s.getScreenList(), cols);
  }

  public static ValidationResult validatePatch(TerminalScreenProto.ScreenPatch p) {
    if (p.getInstanceId().isEmpty()) {
      return ValidationResult.fail("patch missing instance id");
    }
    if (p.getNewStylesCount() > MAX_STYLES || p.getNewLinksCount() > MAX_LINKS) {
      return ValidationResult.fail("too many new styles or links");
    }
    return validateLines(p.getScreenRowsList(), MAX_COLS);
  }

  public static ValidationResult validateHistoryPage(TerminalScreenProto.HistoryPage p) {
    if (p.getLinesCount() > MAX_HISTORY_PAGE) {
      return ValidationResult.fail("history page too large: " + p.getLinesCount());
    }
    if (p.getStylesCount() > MAX_STYLES || p.getLinksCount() > MAX_LINKS) {
      return ValidationResult.fail("too many history styles or links");
    }
    for (TerminalScreenProto.HistoryLine line : p.getLinesList()) {
      for (TerminalScreenProto.CellRun run : line.getRunsList()) {
        if (run.getCol() < 0 || run.getCol() >= MAX_COLS) {
          return ValidationResult.fail("history run col out of bounds: " + run.getCol());
        }
        for (TerminalScreenProto.Cell cell : run.getCellsList()) {
          if (!validateString(cell.getText(), MAX_CELL_TEXT_BYTES) || cell.getWidth() > 2) {
            return ValidationResult.fail("invalid history cell");
          }
        }
      }
    }
    return ValidationResult.ok();
  }

  private static ValidationResult validateLines(java.util.List<TerminalScreenProto.TerminalLine> lines, int maxCols) {
    for (TerminalScreenProto.TerminalLine line : lines) {
      for (TerminalScreenProto.CellRun run : line.getRunsList()) {
        if (run.getCol() < 0 || run.getCol() >= maxCols) {
          return ValidationResult.fail("run col out of bounds: " + run.getCol());
        }
        for (TerminalScreenProto.Cell cell : run.getCellsList()) {
          if (!validateString(cell.getText(), MAX_CELL_TEXT_BYTES)) {
            return ValidationResult.fail("cell text too long");
          }
          int w = cell.getWidth();
          if (w != 0 && w != 1 && w != 2) {
            return ValidationResult.fail("invalid cell width: " + w);
          }
        }
      }
    }
    return ValidationResult.ok();
  }

  private static boolean validateString(String s, int maxBytes) {
    if (s == null) return true;
    if (s.length() > maxBytes) return false;
    byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    return bytes.length <= maxBytes;
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
