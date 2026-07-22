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
  public void trimsTerminalPaddingAndUsesIndentationForContinuation() {
    TerminalLine[] screen = new TerminalLine[] {
        screenRow(0, "  alpha  beta    "),
        screenRow(1, "    gamma       ")
    };
    TerminalSelection sel = new TerminalSelection(scr(0, 0), scr(1, 16)).normalized();
    assertEquals("alpha  beta\ngamma", extract(sel, Collections.emptyList(), screen));
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
  public void keepsUnmarkedHardRowsSeparate() {
    TerminalLine[] screen = new TerminalLine[] {
        screenRow(0, false, "1234567890"),
        screenRow(1, false, "continued ")
    };
    TerminalSelection sel = new TerminalSelection(scr(0, 0), scr(1, 10)).normalized();
    assertEquals("1234567890\ncontinued", extract(sel, Collections.emptyList(), screen));
  }

  @Test
  public void doesNotGuessVisualWrapWithoutKnownScreenWidth() {
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
  public void joinsListContinuationAcrossHistoryAndScreenBoundary() {
    List<TerminalLine> history = Arrays.asList(
        historyLine(1, "- C1 第一项"));
    TerminalLine[] screen = new TerminalLine[] {
        screenRow(0, "  续行"),
        screenRow(1, "- C2 第二项")
    };
    TerminalSelection sel = new TerminalSelection(hist(1, 0), scr(1, 30)).normalized();
    assertEquals("- C1 第一项续行\n- C2 第二项", extract(sel, history, screen));
  }

  @Test
  public void formatsCompleteSampleThroughExtractor() {
    TerminalLine[] screen = new TerminalLine[] {
        screenRow(0, "  各提交内容"),
        screenRow(1, "  - C1 统一连接身份：DeviceConnectionKeys.resolve() 唯一入口 + 协调器全部终端入口贯穿 direct:{configId}。"),
        screenRow(2, "  - C2 终端/上传/通知按 key 路由：TerminalSessionArgs"),
        screenRow(3, "  加身份字段、工厂分流、上传复用 connectionKey（删"),
        screenRow(4, "  UploadConnectionKeys）、通知 Resolver Direct 分支。"),
        screenRow(5, "  - C3 编辑/删除保身份：updateDirectDevice 保"),
        screenRow(6, "  configId、去重排除自身、Registry"),
        screenRow(7, "  地址变化重建、删除补全本地清理。"),
        screenRow(8, "  - C4 Agent 安全加固：allowInsecureRemote +"),
        screenRow(9, "  默认回环地址、原子 Token 旋转、登录限流、HTTP"),
        screenRow(10, "  超时、Android 风险提示。"),
        screenRow(11, "  - C5 文档：agent-config.md 安全加固说明。"),
        screenRow(12, "  关键处理")
    };
    TerminalSelection sel = new TerminalSelection(scr(0, 0), scr(12, 30)).normalized();
    assertEquals(
        "各提交内容\n"
            + "- C1 统一连接身份：DeviceConnectionKeys.resolve() 唯一入口 + 协调器全部终端入口贯穿 direct:{configId}。\n"
            + "- C2 终端/上传/通知按 key 路由：TerminalSessionArgs 加身份字段、工厂分流、上传复用 connectionKey（删 UploadConnectionKeys）、通知 Resolver Direct 分支。\n"
            + "- C3 编辑/删除保身份：updateDirectDevice 保 configId、去重排除自身、Registry 地址变化重建、删除补全本地清理。\n"
            + "- C4 Agent 安全加固：allowInsecureRemote + 默认回环地址、原子 Token 旋转、登录限流、HTTP 超时、Android 风险提示。\n"
            + "- C5 文档：agent-config.md 安全加固说明。\n"
            + "关键处理",
        extract(sel, Collections.emptyList(), screen));
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
