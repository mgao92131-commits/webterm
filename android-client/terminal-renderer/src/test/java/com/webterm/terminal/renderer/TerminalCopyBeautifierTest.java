package com.webterm.terminal.renderer;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

/** {@link TerminalCopyBeautifier} 的纯 JVM 单元测试。 */
public class TerminalCopyBeautifierTest {

  @Test
  public void keepsHardLineBreak() {
    assertEquals("first\nsecond", beautify(
        new CopyLine("first", false),
        new CopyLine("second", false)));
  }

  @Test
  public void joinsWrappedRowsWithoutInventingSpace() {
    assertEquals("abcdefghijklmnopqrst", beautify(
        new CopyLine("abcdefghijkl", true),
        new CopyLine("mnopqrst", false)));
  }

  @Test
  public void keepsOneSpaceAtWrappedBoundary() {
    assertEquals("hello world", beautify(
        new CopyLine("hello   ", true),
        new CopyLine("world", false)));
  }

  @Test
  public void collapsesLeadingWhitespaceAtWrappedBoundary() {
    assertEquals("hello world", beautify(
        new CopyLine("hello", true),
        new CopyLine("   world", false)));
  }

  @Test
  public void doesNotDuplicateWrappedBoundarySpace() {
    assertEquals("hello world", beautify(
        new CopyLine("hello   ", true),
        new CopyLine("   world", false)));
  }

  @Test
  public void trimsTrailingTerminalPadding() {
    assertEquals("first\nsecond", beautify(
        new CopyLine("first     ", false),
        new CopyLine("second    ", false)));
  }

  @Test
  public void preservesIndentationOnHardLine() {
    assertEquals("root\n    child", beautify(
        new CopyLine("root", false),
        new CopyLine("    child", false)));
  }

  @Test
  public void collapsesRepeatedBlankLines() {
    assertEquals("first\n\nsecond", beautify(
        new CopyLine("first", false),
        new CopyLine("", false),
        new CopyLine("    ", false),
        new CopyLine("\t", false),
        new CopyLine("second", false)));
  }

  @Test
  public void removesLeadingAndTrailingBlankLines() {
    assertEquals("content", beautify(
        new CopyLine("", false),
        new CopyLine("   ", false),
        new CopyLine("content", false),
        new CopyLine("   ", false)));
  }

  @Test
  public void blankLineStopsWrappedContinuation() {
    assertEquals("first\n\nsecond", beautify(
        new CopyLine("first", true),
        new CopyLine("   ", false),
        new CopyLine("second", false)));
  }

  @Test
  public void handlesNullInputAndNullRows() {
    assertEquals("", TerminalCopyBeautifier.beautify(null));
    assertEquals("first\n\nsecond", beautify(
        new CopyLine("first", false),
        null,
        new CopyLine("second", false)));
  }

  private static String beautify(CopyLine... lines) {
    return TerminalCopyBeautifier.beautify(Arrays.asList(lines));
  }
}
