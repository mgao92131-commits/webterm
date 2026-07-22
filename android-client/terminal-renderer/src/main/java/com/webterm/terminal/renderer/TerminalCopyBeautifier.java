package com.webterm.terminal.renderer;

import java.util.List;

/** 清理选中终端文本中的视觉折行、填充空格和重复空白行。 */
final class TerminalCopyBeautifier {

  private TerminalCopyBeautifier() {}

  static String beautify(List<CopyLine> lines) {
    if (lines == null || lines.isEmpty()) return "";

    StringBuilder result = new StringBuilder();
    boolean hasContent = false;
    boolean previousWrapped = false;
    boolean previousEndedWithWhitespace = false;
    boolean pendingBlankLine = false;

    for (CopyLine line : lines) {
      if (line == null || isBlank(line.text)) {
        if (hasContent) pendingBlankLine = true;
        previousWrapped = false;
        previousEndedWithWhitespace = false;
        continue;
      }

      String raw = line.text;
      boolean startsWithWhitespace = !raw.isEmpty()
          && Character.isWhitespace(raw.charAt(0));
      boolean endsWithWhitespace = !raw.isEmpty()
          && Character.isWhitespace(raw.charAt(raw.length() - 1));
      String content = trimTrailingWhitespace(raw);

      if (!hasContent) {
        result.append(content);
        hasContent = true;
      } else if (previousWrapped) {
        appendWrappedContinuation(
            result,
            content,
            previousEndedWithWhitespace || startsWithWhitespace);
      } else {
        result.append(pendingBlankLine ? "\n\n" : "\n");
        result.append(content);
      }

      previousWrapped = line.wrapped;
      previousEndedWithWhitespace = endsWithWhitespace;
      pendingBlankLine = false;
    }

    return result.toString();
  }

  private static void appendWrappedContinuation(
      StringBuilder result,
      String continuation,
      boolean boundaryHasWhitespace) {
    String normalized = stripLeadingWhitespace(continuation);
    if (boundaryHasWhitespace
        && result.length() > 0
        && !Character.isWhitespace(result.charAt(result.length() - 1))) {
      result.append(' ');
    }
    result.append(normalized);
  }

  private static String trimTrailingWhitespace(String value) {
    if (value == null || value.isEmpty()) return "";

    int end = value.length();
    while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) end--;
    return end == value.length() ? value : value.substring(0, end);
  }

  private static String stripLeadingWhitespace(String value) {
    if (value == null || value.isEmpty()) return "";

    int start = 0;
    while (start < value.length()
        && Character.isWhitespace(value.charAt(start))) {
      start++;
    }
    return start == 0 ? value : value.substring(start);
  }

  private static boolean isBlank(String value) {
    if (value == null || value.isEmpty()) return true;

    for (int index = 0; index < value.length(); index++) {
      if (!Character.isWhitespace(value.charAt(index))) return false;
    }
    return true;
  }
}
