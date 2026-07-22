package com.webterm.terminal.renderer;

import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalHistory;
import com.webterm.terminal.model.TerminalHistorySnapshot;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalSelection;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * {@link TerminalSelectionTextExtractor} 的纯 JVM 单元测试。
 */
public class TerminalSelectionTextExtractorTest {

  private static TerminalCell cell(char c) {
    return new TerminalCell(String.valueOf(c), (byte) 1, 0, 0);
  }

  private static TerminalCell[] cells(String text) {
    TerminalCell[] out = new TerminalCell[text.length()];
    for (int i = 0; i < text.length(); i++) {
      out[i] = cell(text.charAt(i));
    }
    return out;
  }

  private static TerminalLine historyLine(long id, String text) {
    return new TerminalLine(id, false, cells(text));
  }

  private static TerminalLine historyLine(long id, boolean wrapped, String text) {
    return new TerminalLine(id, wrapped, cells(text));
  }

  private static TerminalLine screenRow(int row, String text) {
    return new TerminalLine(0, false, cells(text));
  }

  private static TerminalLine screenRow(int row, boolean wrapped, String text) {
    return new TerminalLine(0, wrapped, cells(text));
  }

  private static TerminalSelection.Anchor hist(long seq, int col) {
    return new TerminalSelection.Anchor(seq, 0, col);
  }

  private static TerminalSelection.Anchor scr(int row, int col) {
    return new TerminalSelection.Anchor(0, row, col);
  }

  @Test
  public void emptySelection() {
    TerminalSelection sel = new TerminalSelection(hist(1, 0), hist(1, 0)).normalized();
    assertEquals("", extract(sel, Arrays.asList(historyLine(1, "abc")), null));
  }

  @Test
  public void historyOnly() {
    List<TerminalLine> history = Arrays.asList(
        historyLine(1, "aaaa"),
        historyLine(2, "bbbb"),
        historyLine(3, "cccc"));
    TerminalSelection sel = new TerminalSelection(hist(1, 1), hist(3, 2)).normalized();
    assertEquals("aaa\nbbbb\ncc", extract(sel, history, null));
  }

  @Test
  public void historySelectionUsesHistorySeqRatherThanStableLineId() {
    List<TerminalLine> history = Arrays.asList(
        new TerminalLine(100, 1, 1, false, cells("first")),
        new TerminalLine(7, 1, 2, false, cells("second")));
    TerminalSelection sel = new TerminalSelection(hist(1, 0), hist(2, 6)).normalized();
    assertEquals("first\nsecond", extract(sel, history, null));
  }

  @Test
  public void screenOnlySameRow() {
    TerminalLine[] screen = new TerminalLine[] { screenRow(0, "hello") };
    TerminalSelection sel = new TerminalSelection(scr(0, 1), scr(0, 4)).normalized();
    assertEquals("ell", extract(sel, Collections.emptyList(), screen));
  }

  @Test
  public void screenOnlyMultiRow() {
    TerminalLine[] screen = new TerminalLine[] {
        screenRow(0, "abcd  "),
        screenRow(1, "efgh  "),
        screenRow(2, "ijkl  ")
    };
    TerminalSelection sel = new TerminalSelection(scr(0, 2), scr(2, 2)).normalized();
    assertEquals("cd\nefgh\nij", extract(sel, Collections.emptyList(), screen));
  }

  @Test
  public void trimsPaddingAndPreservesHardLineIndentation() {
    TerminalLine[] screen = new TerminalLine[] {
        screenRow(0, "  alpha  beta    "),
        screenRow(1, "    gamma       ")
    };
    TerminalSelection sel = new TerminalSelection(scr(0, 0), scr(1, 16)).normalized();
    assertEquals("  alpha  beta\n    gamma", extract(sel, Collections.emptyList(), screen));
  }

  @Test
  public void joinsSoftWrappedRowsAndPreservesWrappingSpace() {
    TerminalLine[] screen = new TerminalLine[] {
        screenRow(0, true, "long "),
        screenRow(1, false, "command   ")
    };
    TerminalSelection sel = new TerminalSelection(scr(0, 0), scr(1, 10)).normalized();
    assertEquals("long command", extract(sel, Collections.emptyList(), screen));
  }

  @Test
  public void dropsBlankRowsInsideAndAfterSelection() {
    TerminalLine[] screen = new TerminalLine[] {
        screenRow(0, "first     "),
        screenRow(1, "          "),
        screenRow(2, "second    "),
        screenRow(3, "          "),
        screenRow(4, "          ")
    };
    TerminalSelection sel = new TerminalSelection(scr(0, 0), scr(4, 10)).normalized();
    assertEquals("first\n\nsecond", extract(sel, Collections.emptyList(), screen));
  }

  @Test
  public void keepsHardRowsSeparateWithoutWidthInference() {
    TerminalLine[] screen = new TerminalLine[] {
        screenRow(0, false, "1234567890"),
        screenRow(1, false, "continued ")
    };
    TerminalSelection sel = new TerminalSelection(scr(0, 0), scr(1, 10)).normalized();
    assertEquals("1234567890\ncontinued", extract(sel, Collections.emptyList(), screen));
  }

  @Test
  public void keepsHistoryHardRowsSeparate() {
    List<TerminalLine> history = Arrays.asList(
        historyLine(1, false, "first"),
        historyLine(2, false, "second"));
    TerminalSelection sel = new TerminalSelection(hist(1, 0), hist(2, 6)).normalized();
    assertEquals("first\nsecond", extract(sel, history, null));
  }

  @Test
  public void joinsSoftWrapAcrossHistoryAndScreenBoundary() {
    List<TerminalLine> history = Arrays.asList(
        historyLine(1, false, "prompt$ "),
        historyLine(2, true, "very long "));
    TerminalLine[] screen = new TerminalLine[] {
        screenRow(0, false, "command   ")
    };
    TerminalSelection sel = new TerminalSelection(hist(1, 0), scr(0, 10)).normalized();
    assertEquals("prompt$\nvery long command", extract(sel, history, screen));
  }

  @Test
  public void historyToScreenCrossBoundary() {
    List<TerminalLine> history = Arrays.asList(
        historyLine(1, "aaaa"),
        historyLine(2, "bbbb"),
        historyLine(3, "cccc"));
    TerminalLine[] screen = new TerminalLine[] {
        screenRow(0, "dddd    "),
        screenRow(1, "eeee    ")
    };
    // 选择：从历史第 2 行第 1 列，到屏幕第 0 行第 3 列。
    TerminalSelection sel = new TerminalSelection(hist(2, 1), scr(0, 3)).normalized();
    assertEquals("bbb\ncccc\nddd", extract(sel, history, screen));
  }

  @Test
  public void historyToScreenCrossBoundaryUntilLastHistory() {
    List<TerminalLine> history = Arrays.asList(
        historyLine(1, "aaaa"),
        historyLine(2, "bbbb"));
    TerminalLine[] screen = new TerminalLine[] { screenRow(0, "cccc    ") };
    TerminalSelection sel = new TerminalSelection(hist(1, 0), scr(0, 2)).normalized();
    assertEquals("aaaa\nbbbb\ncc", extract(sel, history, screen));
  }

  @Test
  public void cleansWrappedTextThroughExtractor() {
    TerminalLine[] screen = new TerminalLine[] {
        screenRow(0, true, "first     "),
        screenRow(1, false, "continued "),
        screenRow(2, false, "hard line   "),
        screenRow(3, false, ""),
        screenRow(4, false, "    "),
        screenRow(5, false, "\t"),
        screenRow(6, false, "    indented   ")
    };
    TerminalSelection sel = new TerminalSelection(scr(0, 0), scr(6, 30)).normalized();
    assertEquals("first continued\nhard line\n\n    indented", extract(sel, Collections.emptyList(), screen));
  }

  @Test
  public void nullScreenRowStopsWrappedContinuation() {
    TerminalLine[] screen = new TerminalLine[] {
        screenRow(0, true, "first"),
        null,
        screenRow(2, false, "second")
    };
    TerminalSelection sel = new TerminalSelection(scr(0, 0), scr(2, 6)).normalized();
    assertEquals("first\n\nsecond", extract(sel, Collections.emptyList(), screen));
  }

  @Test
  public void trailingNullScreenRowsAreDroppedFromCopiedText() {
    TerminalLine[] screen = new TerminalLine[] {
        screenRow(0, false, "content"),
        null,
        null
    };
    TerminalSelection sel = new TerminalSelection(scr(0, 0), scr(2, 0)).normalized();
    assertEquals("content", extract(sel, Collections.emptyList(), screen));
  }

  @Test
  public void leadingNullScreenRowsAreDroppedFromCopiedText() {
    TerminalLine[] screen = new TerminalLine[] {
        null,
        screenRow(1, false, "content")
    };
    TerminalSelection sel = new TerminalSelection(scr(0, 0), scr(1, 7)).normalized();
    assertEquals("content", extract(sel, Collections.emptyList(), screen));
  }

  private static String extract(TerminalSelection sel, List<TerminalLine> history, TerminalLine[] screen) {
    return TerminalSelectionTextExtractor.extract(sel, snapshot(history), screen);
  }

  private static TerminalHistorySnapshot snapshot(List<TerminalLine> lines) {
    TerminalHistory h = new TerminalHistory(line -> 100); // estimator not used in tests
    for (TerminalLine line : lines) {
      h.append(line);
    }
    return h.snapshot();
  }
}
