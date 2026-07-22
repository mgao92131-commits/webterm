package com.webterm.terminal.renderer;

import java.util.List;
import java.util.regex.Pattern;

/** 将选中的物理终端行整理成适合阅读和粘贴的文本。 */
final class TerminalCopyBeautifier {

  private static final Pattern COMMIT_ITEM = Pattern.compile("^-\\s+C\\d+\\s+.*");
  private static final String OPEN_PUNCTUATION = "（([{《“‘";
  private static final String CLOSE_PUNCTUATION = "，。；：！？、）)]}》”’,.;:!?";
  private static final String INCOMPLETE_ENDING = "，、：,:+（(";
  private static final String SENTENCE_TERMINATORS = "。！？.!?";

  private TerminalCopyBeautifier() {}

  static String beautify(List<CopyLine> lines) {
    if (lines == null || lines.isEmpty()) return "";

    int size = lines.size();
    String[] contents = new String[size];
    boolean[] nonBlank = new boolean[size];
    int[] nextNonBlank = new int[size];

    prepareLines(lines, contents, nonBlank, nextNonBlank);

    StringBuilder result = new StringBuilder();
    StringBuilder currentCommitItem = null;
    boolean inCommitItem = false;
    boolean previousWrapped = false;
    boolean pendingBlank = false;

    for (int index = 0; index < size; index++) {
      CopyLine line = lines.get(index);
      if (line == null) {
        previousWrapped = false;
        pendingBlank = true;
        continue;
      }

      if (!nonBlank[index]) {
        previousWrapped = false;
        pendingBlank = true;
        continue;
      }

      String content = contents[index];
      if (isCommitItem(content)) {
        if (currentCommitItem != null) {
          appendLogicalLine(result, currentCommitItem.toString(), false);
        }
        currentCommitItem = new StringBuilder(content);
        inCommitItem = true;
        previousWrapped = line.wrapped;
        pendingBlank = false;
        continue;
      }

      if (inCommitItem) {
        boolean heading = endsWithSentenceTerminator(currentCommitItem)
            && looksLikeSectionHeading(
                content,
                nextNonBlank[index] < 0,
                nextNonBlank[index] >= 0
                    && isCommitItem(contents[nextNonBlank[index]]));

        if (heading) {
          appendLogicalLine(result, currentCommitItem.toString(), false);
          currentCommitItem = null;
          inCommitItem = false;
          appendLogicalLine(result, content, pendingBlank);
        } else {
          appendContinuation(currentCommitItem, content);
        }

        previousWrapped = line.wrapped;
        pendingBlank = false;
        continue;
      }

      if (result.length() == 0) {
        result.append(content);
      } else if (previousWrapped) {
        appendContinuation(result, content);
      } else {
        appendLogicalLine(result, content, pendingBlank);
      }

      previousWrapped = line.wrapped;
      pendingBlank = false;
    }

    if (currentCommitItem != null) {
      appendLogicalLine(result, currentCommitItem.toString(), false);
    }
    return result.toString();
  }

  private static void prepareLines(List<CopyLine> lines, String[] contents,
                                   boolean[] nonBlank, int[] nextNonBlank) {
    for (int index = 0; index < lines.size(); index++) {
      CopyLine line = lines.get(index);
      if (line == null) continue;

      String raw = trimTrailingWhitespace(line.text);
      if (isBlank(raw)) continue;

      contents[index] = stripLeading(raw);
      nonBlank[index] = true;
    }

    int next = -1;
    for (int index = lines.size() - 1; index >= 0; index--) {
      nextNonBlank[index] = next;
      if (nonBlank[index]) next = index;
    }
  }

  private static boolean isCommitItem(String text) {
    return text != null && COMMIT_ITEM.matcher(text).matches();
  }

  private static boolean endsWithSentenceTerminator(CharSequence text) {
    if (text == null) return false;
    for (int index = text.length() - 1; index >= 0; index--) {
      char value = text.charAt(index);
      if (Character.isWhitespace(value)) continue;
      return SENTENCE_TERMINATORS.indexOf(value) >= 0;
    }
    return false;
  }

  private static boolean looksLikeSectionHeading(String content, boolean lastNonBlank,
                                                 boolean nextNonBlankIsCommitItem) {
    if (content == null || content.isEmpty()) return false;
    if (!(lastNonBlank || nextNonBlankIsCommitItem)) return false;
    if (isCommitItem(content)) return false;

    int count = content.codePointCount(0, content.length());
    if (count < 1 || count > 40) return false;
    if (INCOMPLETE_ENDING.indexOf(content.charAt(content.length() - 1)) >= 0) return false;

    for (int index = 0; index < content.length(); index++) {
      if (SENTENCE_TERMINATORS.indexOf(content.charAt(index)) >= 0) return false;
    }
    return true;
  }

  private static void appendLogicalLine(StringBuilder result, String content, boolean blankBefore) {
    if (content == null || content.isEmpty()) return;
    if (result.length() == 0) {
      result.append(content);
      return;
    }
    result.append(blankBefore ? "\n\n" : "\n").append(content);
  }

  private static void appendContinuation(StringBuilder target, String continuation) {
    if (continuation == null || continuation.isEmpty()) return;
    if (needsSpace(target, continuation)) target.append(' ');
    target.append(continuation);
  }

  private static boolean needsSpace(CharSequence leftText, String rightText) {
    if (leftText == null || leftText.length() == 0 || rightText == null || rightText.isEmpty()) {
      return false;
    }

    char left = leftText.charAt(leftText.length() - 1);
    char right = rightText.charAt(0);
    if (Character.isWhitespace(left) || Character.isWhitespace(right)) return false;
    if (OPEN_PUNCTUATION.indexOf(left) >= 0 || OPEN_PUNCTUATION.indexOf(right) >= 0) return false;
    if (CLOSE_PUNCTUATION.indexOf(right) >= 0) return false;
    if (left == '+' || right == '+') return true;
    if (isPathOrQualifiedNameConnector(left) && isAsciiToken(right)) return false;

    return isAsciiToken(left) || isAsciiToken(right) || isAsciiExpressionEnd(left, right);
  }

  private static boolean isAsciiExpressionEnd(char left, char right) {
    return isChinese(right) && (left == ')' || left == ']' || left == '}');
  }

  private static boolean isPathOrQualifiedNameConnector(char value) {
    return value == '/' || value == '\\' || value == '.' || value == '@';
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

  private static boolean isChinese(char value) {
    return value >= 0x2E80 && value <= 0x9FFF;
  }

  private static String trimTrailingWhitespace(String value) {
    if (value == null || value.isEmpty()) return "";
    int end = value.length();
    while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) end--;
    return end == value.length() ? value : value.substring(0, end);
  }

  private static boolean isBlank(String value) {
    if (value.isEmpty()) return true;
    for (int index = 0; index < value.length(); index++) {
      if (!Character.isWhitespace(value.charAt(index))) return false;
    }
    return true;
  }

  private static String stripLeading(String value) {
    int start = 0;
    while (start < value.length() && Character.isWhitespace(value.charAt(start))) start++;
    return start == 0 ? value : value.substring(start);
  }
}
