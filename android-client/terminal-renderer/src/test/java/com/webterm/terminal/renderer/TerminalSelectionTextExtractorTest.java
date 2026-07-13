package com.webterm.terminal.renderer;

import com.webterm.terminal.model.TerminalCell;
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

  private static TerminalLine screenRow(int row, String text) {
    return new TerminalLine(0, false, cells(text));
  }

  private static TerminalSelection.Anchor hist(long lineId, int col) {
    return new TerminalSelection.Anchor(lineId, 0, col);
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
  public void screenOnlySameRow() {
    TerminalLine[] screen = new TerminalLine[] { screenRow(0, "hello") };
    TerminalSelection sel = new TerminalSelection(scr(0, 1), scr(0, 4)).normalized();
    assertEquals("ell", extract(sel, Collections.emptyList(), screen));
  }

  @Test
  public void screenOnlyMultiRow() {
    TerminalLine[] screen = new TerminalLine[] {
        screenRow(0, "abcd"),
        screenRow(1, "efgh"),
        screenRow(2, "ijkl")
    };
    TerminalSelection sel = new TerminalSelection(scr(0, 2), scr(2, 2)).normalized();
    assertEquals("cd\nefgh\nij", extract(sel, Collections.emptyList(), screen));
  }

  @Test
  public void historyToScreenCrossBoundary() {
    List<TerminalLine> history = Arrays.asList(
        historyLine(1, "aaaa"),
        historyLine(2, "bbbb"),
        historyLine(3, "cccc"));
    TerminalLine[] screen = new TerminalLine[] {
        screenRow(0, "dddd"),
        screenRow(1, "eeee")
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
    TerminalLine[] screen = new TerminalLine[] { screenRow(0, "cccc") };
    TerminalSelection sel = new TerminalSelection(hist(1, 0), scr(0, 2)).normalized();
    assertEquals("aaaa\nbbbb\ncc", extract(sel, history, screen));
  }

  private static String extract(TerminalSelection sel, List<TerminalLine> history, TerminalLine[] screen) {
    return TerminalSelectionTextExtractor.extract(sel, history, screen);
  }
}
