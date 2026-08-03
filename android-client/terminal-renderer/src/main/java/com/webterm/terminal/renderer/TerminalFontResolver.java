package com.webterm.terminal.renderer;

import androidx.annotation.NonNull;

/** 把一个服务端 grapheme 路由到普通文字、符号或 Emoji 字体角色。 */
final class TerminalFontResolver {
  private final EmojiPresentationClassifier emojiClassifier;
  private final FontCoverage unicodeSymbolsCoverage;
  private final FontCoverage nerdSymbolsCoverage;
  private final boolean enabled;

  TerminalFontResolver(
      @NonNull EmojiPresentationClassifier emojiClassifier,
      @NonNull FontCoverage unicodeSymbolsCoverage,
      @NonNull FontCoverage nerdSymbolsCoverage) {
    this(emojiClassifier, unicodeSymbolsCoverage, nerdSymbolsCoverage, true);
  }

  private TerminalFontResolver(
      EmojiPresentationClassifier emojiClassifier,
      FontCoverage unicodeSymbolsCoverage,
      FontCoverage nerdSymbolsCoverage,
      boolean enabled) {
    this.emojiClassifier = emojiClassifier;
    this.unicodeSymbolsCoverage = unicodeSymbolsCoverage;
    this.nerdSymbolsCoverage = nerdSymbolsCoverage;
    this.enabled = enabled;
  }

  static TerminalFontResolver defaultResolver() {
    return new TerminalFontResolver(
        new EmojiPresentationClassifier(),
        FontCoverage.unicodeSymbols(),
        FontCoverage.nerdSymbols());
  }

  static TerminalFontResolver mainOnly() {
    return new TerminalFontResolver(
        new EmojiPresentationClassifier(),
        FontCoverage.none(),
        FontCoverage.none(),
        false);
  }

  @NonNull
  TerminalFontRole resolve(@NonNull String grapheme) {
    if (!enabled) return TerminalFontRole.MAIN_TEXT;
    if (emojiClassifier.isEmojiPresentation(grapheme)) {
      return TerminalFontRole.EMOJI;
    }

    int codePoint = singleCodePoint(grapheme);
    if (codePoint >= 0 && isPrivateUse(codePoint)
        && nerdSymbolsCoverage.contains(codePoint)) {
      return TerminalFontRole.NERD_SYMBOL;
    }

    int symbolCodePoint = symbolBaseCodePoint(grapheme);
    if (symbolCodePoint >= 0
        && isUnicodeSymbolCandidate(symbolCodePoint)
        && unicodeSymbolsCoverage.contains(symbolCodePoint)) {
      return TerminalFontRole.UNICODE_SYMBOL;
    }
    return TerminalFontRole.MAIN_TEXT;
  }

  private static int singleCodePoint(String text) {
    if (text == null || text.isEmpty()) return -1;
    int codePoint = text.codePointAt(0);
    return Character.charCount(codePoint) == text.length() ? codePoint : -1;
  }

  /** 允许公共符号带一个 FE0E/FE0F，但不接管任意多 codepoint grapheme。 */
  private static int symbolBaseCodePoint(String text) {
    if (text == null || text.isEmpty()) return -1;
    int first = text.codePointAt(0);
    int firstLength = Character.charCount(first);
    if (text.length() == firstLength) return first;
    if (text.length() == firstLength + 1) {
      int selector = text.codePointAt(firstLength);
      if (selector == 0xFE0E || selector == 0xFE0F) return first;
    }
    return -1;
  }

  private static boolean isUnicodeSymbolCandidate(int codePoint) {
    return inRange(codePoint, 0x2190, 0x21FF)
        || inRange(codePoint, 0x2300, 0x23FF)
        || inRange(codePoint, 0x25A0, 0x25FF)
        || inRange(codePoint, 0x2600, 0x26FF)
        || inRange(codePoint, 0x2700, 0x27BF)
        || inRange(codePoint, 0x2B00, 0x2BFF);
  }

  private static boolean isPrivateUse(int codePoint) {
    return inRange(codePoint, 0xE000, 0xF8FF)
        || inRange(codePoint, 0xF0000, 0xFFFFD)
        || inRange(codePoint, 0x100000, 0x10FFFD);
  }

  private static boolean inRange(int codePoint, int start, int end) {
    return codePoint >= start && codePoint <= end;
  }
}
