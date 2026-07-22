package com.webterm.terminal.renderer;

import java.util.List;
import java.util.regex.Pattern;

/** 将选中的物理终端行整理成适合阅读和粘贴的文本。 */
final class TerminalCopyBeautifier {

  private static final Pattern COMMIT_ITEM = Pattern.compile("^-\\s+C\\d+\\s+.*");

  private TerminalCopyBeautifier() {}

  static String beautify(List<CopyLine> lines) {
    StringBuilder result = new StringBuilder();
    boolean previousWrapped = false;

    if (lines == null) return "";
    for (CopyLine line : lines) {
      if (line == null) continue;

      String raw = trimTrailingSpaces(line.text);
      if (isBlank(raw)) continue;

      String content = stripLeading(raw);
      if (result.length() == 0) {
        result.append(content);
        previousWrapped = line.wrapped;
        continue;
      }

      if (isCommitItem(content)) {
        appendNewLine(result, content);
      } else if (previousWrapped || line.indented) {
        appendContinuation(result, content);
      } else if (looksLikeShortStandaloneLine(content)) {
        appendNewLine(result, content);
      } else {
        appendContinuation(result, content);
      }
      previousWrapped = line.wrapped;
    }

    return result.toString();
  }

  private static boolean isCommitItem(String text) {
    return COMMIT_ITEM.matcher(text).matches();
  }

  private static boolean looksLikeShortStandaloneLine(String text) {
    if (text.length() > 24) return false;

    if (text.endsWith("，")
        || text.endsWith("、")
        || text.endsWith("：")
        || text.endsWith(",")
        || text.endsWith(":")
        || text.endsWith("+")
        || text.endsWith("(")
        || text.endsWith("（")) {
      return false;
    }

    return true;
  }

  private static void appendNewLine(StringBuilder result, String next) {
    result.append('\n').append(next);
  }

  private static void appendContinuation(StringBuilder result, String next) {
    if (next.isEmpty()) return;

    char left = result.charAt(result.length() - 1);
    char right = next.charAt(0);
    if (needsSpace(left, right)) result.append(' ');
    result.append(next);
  }

  private static boolean needsSpace(char left, char right) {
    if (Character.isWhitespace(left) || Character.isWhitespace(right)) return false;
    if ("（([{《".indexOf(left) >= 0) return false;
    if ("，。；：！？、）)]}》.,;:!?".indexOf(right) >= 0) return false;
    if (left == '+') return true;
    return isAsciiToken(left) || isAsciiToken(right);
  }

  private static boolean isAsciiToken(char value) {
    return value < 128
        && (Character.isLetterOrDigit(value)
            || value == '_'
            || value == '-'
            || value == '.'
            || value == '/'
            || value == '\\'
            || value == ':');
  }

  private static String trimTrailingSpaces(String value) {
    if (value == null || value.isEmpty()) return "";
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == ' ') end--;
    return end == value.length() ? value : value.substring(0, end);
  }

  private static boolean isBlank(String value) {
    if (value.isEmpty()) return true;
    for (int i = 0; i < value.length(); i++) {
      if (!Character.isWhitespace(value.charAt(i))) return false;
    }
    return true;
  }

  private static String stripLeading(String value) {
    int start = 0;
    while (start < value.length() && Character.isWhitespace(value.charAt(start))) start++;
    return start == 0 ? value : value.substring(start);
  }
}
