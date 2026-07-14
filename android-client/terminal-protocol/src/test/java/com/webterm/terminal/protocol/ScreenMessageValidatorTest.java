package com.webterm.terminal.protocol;

import com.webterm.terminal.protocol.generated.TerminalScreenProto;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * ScreenMessageValidator 的 patch 结构与资源校验测试（计划 §10.1）。
 *
 * <p>同时固化 §15 混合版本兼容容忍：空 patch 与 layout_epoch=0 的 patch 必须被接受，
 * 否则新 Android 会拒绝 Task 0 之前的旧 Go Agent。</p>
 */
public final class ScreenMessageValidatorTest {

  private static final int ROWS = 5;
  private static final int MAX_ENVELOPE_BYTES = 2 * 1024 * 1024;

  private static TerminalScreenProto.ScreenPatch.Builder validPatch() {
    return TerminalScreenProto.ScreenPatch.newBuilder()
        .setInstanceId("instance-1")
        .setLayoutEpoch(1)
        .setBaseRevision(1)
        .setScreenRevision(2);
  }

  private static TerminalScreenProto.TerminalLine screenRow(int row) {
    return TerminalScreenProto.TerminalLine.newBuilder().setRow(row).build();
  }

  private static TerminalScreenProto.HistoryLine historyLine(long id) {
    return TerminalScreenProto.HistoryLine.newBuilder().setId(id).build();
  }

  private static String repeat(char c, int n) {
    return new String(new char[n]).replace('\0', c);
  }

  private static ScreenMessageValidator.ValidationResult validate(
      TerminalScreenProto.ScreenPatch.Builder builder) {
    return ScreenMessageValidator.validatePatch(builder.build(), ROWS);
  }

  @Test
  public void validPatchPasses() {
    TerminalScreenProto.ScreenPatch.Builder p = validPatch()
        .addScreenRows(screenRow(0))
        .addScreenRows(screenRow(ROWS - 1))
        .addHistoryAppend(historyLine(1))
        .addHistoryAppend(historyLine(2))
        .addPromotedRows(TerminalScreenProto.PromotedRow.newBuilder()
            .setScreenRow(1).setHistoryLineId(3))
        .addNewStyles(TerminalScreenProto.TerminalStyle.newBuilder().setId(1))
        .addNewLinks(TerminalScreenProto.Hyperlink.newBuilder()
            .setId(1).setUri("https://example.com"))
        .setTitle("vim")
        .setWorkingDirectory("/home/u");
    assertTrue(validate(p).ok);
  }

  @Test
  public void screenRevisionMustExceedBaseRevision() {
    assertFalse(validate(validPatch().setBaseRevision(2).setScreenRevision(2)).ok);
    assertFalse(validate(validPatch().setBaseRevision(3).setScreenRevision(2)).ok);
    assertTrue(validate(validPatch().setBaseRevision(2).setScreenRevision(3)).ok);
  }

  @Test
  public void baseRevisionMustBeAtLeastOne() {
    assertFalse(validate(validPatch().setBaseRevision(0).setScreenRevision(1)).ok);
    assertTrue(validate(validPatch().setBaseRevision(1).setScreenRevision(2)).ok);
  }

  @Test
  public void instanceIdRequiredAndLengthLimited() {
    assertFalse(validate(validPatch().setInstanceId("")).ok);
    assertTrue(validate(validPatch().setInstanceId(repeat('i', 256))).ok);
    assertFalse(validate(validPatch().setInstanceId(repeat('i', 257))).ok);
  }

  @Test
  public void screenRowCountBoundedByRows() {
    // 数量与行数相等合法。
    TerminalScreenProto.ScreenPatch.Builder full = validPatch();
    for (int row = 0; row < ROWS; row++) {
      full.addScreenRows(screenRow(row));
    }
    assertTrue(validate(full).ok);

    // 超过行数直接拒绝（数量校验先于逐行索引校验）。
    TerminalScreenProto.ScreenPatch.Builder tooMany = validPatch();
    for (int row = 0; row <= ROWS; row++) {
      tooMany.addScreenRows(screenRow(row));
    }
    ScreenMessageValidator.ValidationResult result = validate(tooMany);
    assertFalse(result.ok);
    assertTrue(result.reason.startsWith("too many patch rows"));
  }

  @Test
  public void screenRowIndexBoundedAndUnique() {
    assertFalse(validate(validPatch().addScreenRows(screenRow(ROWS))).ok);
    assertFalse(validate(validPatch().addScreenRows(screenRow(-1))).ok);
    assertFalse(validate(validPatch()
        .addScreenRows(screenRow(1)).addScreenRows(screenRow(1))).ok);
    assertTrue(validate(validPatch()
        .addScreenRows(screenRow(1)).addScreenRows(screenRow(2))).ok);
  }

  @Test
  public void patchRowsRejectedWithoutLocalGeometry() {
    // rows=0（尚未收到任何 snapshot）时不存在合法行索引，空 patch 仍可放行。
    assertFalse(ScreenMessageValidator.validatePatch(
        validPatch().addScreenRows(screenRow(0)).build(), 0).ok);
    assertTrue(ScreenMessageValidator.validatePatch(validPatch().build(), 0).ok);
  }

  @Test
  public void historyAppendCountLimited() {
    TerminalScreenProto.ScreenPatch.Builder maxed = validPatch();
    for (long id = 1; id <= 500; id++) {
      maxed.addHistoryAppend(historyLine(id));
    }
    assertTrue(validate(maxed).ok);

    TerminalScreenProto.ScreenPatch.Builder overflow = validPatch();
    for (long id = 1; id <= 501; id++) {
      overflow.addHistoryAppend(historyLine(id));
    }
    assertFalse(validate(overflow).ok);
  }

  @Test
  public void historyLineIdsPositiveAndContiguous() {
    assertFalse(validate(validPatch().addHistoryAppend(historyLine(0))).ok);
    assertFalse(validate(validPatch()
        .addHistoryAppend(historyLine(1)).addHistoryAppend(historyLine(1))).ok);
    assertFalse(validate(validPatch()
        .addHistoryAppend(historyLine(2)).addHistoryAppend(historyLine(1))).ok);
    assertFalse(validate(validPatch()
        .addHistoryAppend(historyLine(1)).addHistoryAppend(historyLine(3))).ok);
    assertTrue(validate(validPatch()
        .addHistoryAppend(historyLine(1))
        .addHistoryAppend(historyLine(2))
        .addHistoryAppend(historyLine(3))).ok);
  }

  @Test
  public void historyLineContentValidated() {
    TerminalScreenProto.Cell validCell = TerminalScreenProto.Cell.newBuilder()
        .setText("x").setWidth(1).build();
    TerminalScreenProto.CellRun validRun = TerminalScreenProto.CellRun.newBuilder()
        .setCol(0).addCells(validCell).build();
    assertTrue(validate(validPatch()
        .addHistoryAppend(TerminalScreenProto.HistoryLine.newBuilder()
            .setId(1).addRuns(validRun))).ok);

    // cell 宽度非法。
    TerminalScreenProto.Cell badWidth = TerminalScreenProto.Cell.newBuilder()
        .setText("x").setWidth(3).build();
    assertFalse(validate(validPatch()
        .addHistoryAppend(TerminalScreenProto.HistoryLine.newBuilder().setId(1)
            .addRuns(TerminalScreenProto.CellRun.newBuilder().setCol(0).addCells(badWidth))))
        .ok);
    // cell 文本超长。
    TerminalScreenProto.Cell longText = TerminalScreenProto.Cell.newBuilder()
        .setText(repeat('x', 65)).setWidth(1).build();
    assertFalse(validate(validPatch()
        .addHistoryAppend(TerminalScreenProto.HistoryLine.newBuilder().setId(1)
            .addRuns(TerminalScreenProto.CellRun.newBuilder().setCol(0).addCells(longText))))
        .ok);
    // run 列越界。
    assertFalse(validate(validPatch()
        .addHistoryAppend(TerminalScreenProto.HistoryLine.newBuilder().setId(1)
            .addRuns(TerminalScreenProto.CellRun.newBuilder().setCol(500).addCells(validCell))))
        .ok);
  }

  @Test
  public void promotedRowsRangeAndUniqueMapping() {
    assertFalse(validate(validPatch().addPromotedRows(TerminalScreenProto.PromotedRow.newBuilder()
        .setScreenRow(ROWS).setHistoryLineId(1))).ok);
    assertFalse(validate(validPatch().addPromotedRows(TerminalScreenProto.PromotedRow.newBuilder()
        .setScreenRow(-1).setHistoryLineId(1))).ok);
    // screen_row 重复。
    assertFalse(validate(validPatch()
        .addPromotedRows(TerminalScreenProto.PromotedRow.newBuilder()
            .setScreenRow(1).setHistoryLineId(1))
        .addPromotedRows(TerminalScreenProto.PromotedRow.newBuilder()
            .setScreenRow(1).setHistoryLineId(2))).ok);
    // history_line_id 重复。
    assertFalse(validate(validPatch()
        .addPromotedRows(TerminalScreenProto.PromotedRow.newBuilder()
            .setScreenRow(1).setHistoryLineId(7))
        .addPromotedRows(TerminalScreenProto.PromotedRow.newBuilder()
            .setScreenRow(2).setHistoryLineId(7))).ok);
    assertTrue(validate(validPatch()
        .addPromotedRows(TerminalScreenProto.PromotedRow.newBuilder()
            .setScreenRow(1).setHistoryLineId(7))
        .addPromotedRows(TerminalScreenProto.PromotedRow.newBuilder()
            .setScreenRow(2).setHistoryLineId(8))).ok);
  }

  @Test
  public void newStylesCountIdAndLinkUriLimited() {
    // 数量上限（proto 头部：styles/links per layoutEpoch <=4096）。
    TerminalScreenProto.ScreenPatch.Builder tooManyStyles = validPatch();
    for (int id = 1; id <= 4097; id++) {
      tooManyStyles.addNewStyles(TerminalScreenProto.TerminalStyle.newBuilder().setId(id));
    }
    assertFalse(validate(tooManyStyles).ok);

    TerminalScreenProto.ScreenPatch.Builder tooManyLinks = validPatch();
    for (int id = 1; id <= 4097; id++) {
      tooManyLinks.addNewLinks(TerminalScreenProto.Hyperlink.newBuilder()
          .setId(id).setUri("https://example.com"));
    }
    assertFalse(validate(tooManyLinks).ok);

    // ID 0 为保留默认值，不是合法字典项。
    assertFalse(validate(validPatch()
        .addNewStyles(TerminalScreenProto.TerminalStyle.newBuilder().setId(0))).ok);
    assertFalse(validate(validPatch()
        .addNewLinks(TerminalScreenProto.Hyperlink.newBuilder()
            .setId(0).setUri("https://example.com"))).ok);

    // URI 上限 8 KiB。
    assertTrue(validate(validPatch()
        .addNewLinks(TerminalScreenProto.Hyperlink.newBuilder()
            .setId(1).setUri(repeat('u', 8192)))).ok);
    assertFalse(validate(validPatch()
        .addNewLinks(TerminalScreenProto.Hyperlink.newBuilder()
            .setId(1).setUri(repeat('u', 8193)))).ok);
  }

  @Test
  public void titleAndCwdLengthLimited() {
    assertTrue(validate(validPatch().setTitle(repeat('t', 4096))).ok);
    assertFalse(validate(validPatch().setTitle(repeat('t', 4097))).ok);
    assertTrue(validate(validPatch().setWorkingDirectory(repeat('d', 4096))).ok);
    assertFalse(validate(validPatch().setWorkingDirectory(repeat('d', 4097))).ok);
  }

  /** 兼容固化（§15）：旧 Go 可能发送无任何实际变化字段的空 patch，必须容忍。 */
  @Test
  public void emptyPatchTolerated() {
    assertTrue(validate(validPatch()).ok);
  }

  /** 兼容固化（§15）：旧 Go 初始 layout_epoch 为 0，§10.1 的 >=1 校验有意不实施。 */
  @Test
  public void layoutEpochZeroTolerated() {
    assertTrue(validate(validPatch().setLayoutEpoch(0)).ok);
  }

  @Test
  public void envelopeSizeLimited() {
    assertFalse(ScreenMessageValidator.validateEnvelopeSize(null).ok);
    assertTrue(ScreenMessageValidator.validateEnvelopeSize(new byte[16]).ok);
    assertTrue(ScreenMessageValidator.validateEnvelopeSize(new byte[MAX_ENVELOPE_BYTES]).ok);
    assertFalse(ScreenMessageValidator.validateEnvelopeSize(
        new byte[MAX_ENVELOPE_BYTES + 1]).ok);
  }
}
