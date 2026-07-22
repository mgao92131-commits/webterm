package com.webterm.terminal.renderer;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

/** {@link TerminalCopyBeautifier} 的纯 JVM 单元测试。 */
public class TerminalCopyBeautifierTest {

  @Test
  public void joinsListContinuation() {
    assertEquals("- C1 第一行第二行", beautify(
        new CopyLine("- C1 第一行", false),
        new CopyLine("  第二行", false)));
  }

  @Test
  public void keepsMultipleListItemsOnSeparateLines() {
    assertEquals("- C1 第一项续行\n- C2 第二项", beautify(
        new CopyLine("- C1 第一项", false),
        new CopyLine("  续行", false),
        new CopyLine("- C2 第二项", false)));
  }

  @Test
  public void addsSpaceBetweenAsciiAndChinese() {
    assertEquals("TerminalSessionArgs 加身份字段", beautify(
        new CopyLine("TerminalSessionArgs", true),
        new CopyLine("加身份字段", false)));
  }

  @Test
  public void doesNotAddSpaceBetweenChinese() {
    assertEquals("安全加固", beautify(
        new CopyLine("安全", true),
        new CopyLine("加固", false)));
  }

  @Test
  public void handlesParenthesesAndPunctuation() {
    assertEquals("connectionKey（删 UploadConnectionKeys）、通知", beautify(
        new CopyLine("connectionKey（删", true),
        new CopyLine("UploadConnectionKeys）、通知", false)));
  }

  @Test
  public void keepsShortTitleSeparateFromList() {
    assertEquals("各提交内容\n- C1 第一项", beautify(
        new CopyLine("各提交内容", false),
        new CopyLine("  - C1 第一项", false)));
  }

  @Test
  public void keepsSectionHeadingAfterCompletedCommitSeparate() {
    assertEquals("- C5 文档：agent-config.md 安全加固说明。\n关键处理", beautify(
        new CopyLine("  - C5 文档：agent-config.md 安全加固说明。", false),
        new CopyLine("  关键处理", false)));
  }

  @Test
  public void joinsContinuationWithEqualIndentation() {
    assertEquals("- C2 TerminalSessionArgs 加身份字段", beautify(
        new CopyLine("  - C2 TerminalSessionArgs", false),
        new CopyLine("  加身份字段", false)));
  }

  @Test
  public void keepsOrdinaryLongHardWrapSeparate() {
    assertEquals("first independent terminal output line\nsecond independent terminal output line", beautify(
        new CopyLine("first independent terminal output line", false),
        new CopyLine("second independent terminal output line", false)));
  }

  @Test
  public void joinsOrdinarySoftWrap() {
    assertEquals("first wrapped output continued text", beautify(
        new CopyLine("first wrapped output", true),
        new CopyLine("continued text", false)));
  }

  @Test
  public void blankLineResetsWrappedStateAndCreatesOneParagraphGap() {
    assertEquals("first\n\nsecond", beautify(
        new CopyLine("first", true),
        new CopyLine("      ", false),
        new CopyLine("second", false)));
  }

  @Test
  public void addsSpacesAroundPlusAtListContinuationBoundary() {
    assertEquals("- C1 唯一入口 + 协调器入口", beautify(
        new CopyLine("- C1 唯一入口", false),
        new CopyLine("+ 协调器入口", false)));
  }

  @Test
  public void addsSpaceAfterMethodCallBeforeChinese() {
    assertEquals("- C1 DeviceConnectionKeys.resolve() 唯一入口", beautify(
        new CopyLine("- C1 DeviceConnectionKeys.resolve()", false),
        new CopyLine("唯一入口", false)));
  }

  @Test
  public void joinsUrlWithoutAddingSpace() {
    assertEquals("- C1 https://example.com/path", beautify(
        new CopyLine("- C1 https://example.com/", false),
        new CopyLine("path", false)));
  }

  @Test
  public void joinsQualifiedNameWithoutAddingSpace() {
    assertEquals("- C1 com.webterm.terminal.renderer", beautify(
        new CopyLine("- C1 com.webterm.", false),
        new CopyLine("terminal.renderer", false)));
  }

  private static String beautify(CopyLine... lines) {
    return TerminalCopyBeautifier.beautify(Arrays.asList(lines));
  }
}
