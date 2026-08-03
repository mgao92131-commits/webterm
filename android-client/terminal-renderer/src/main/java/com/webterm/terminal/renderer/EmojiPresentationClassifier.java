package com.webterm.terminal.renderer;

/**
 * 对服务端已经切好的 grapheme 做 Emoji presentation 分类。
 *
 * <p>这个类不做 grapheme segmentation，也不判断终端宽度；它只识别 presentation
 * 相关的 Unicode 结构。Unicode 数据版本固定在代码中，后续升级时应同步更新测试。</p>
 */
final class EmojiPresentationClassifier {
  private static final int VS15 = 0xFE0E;
  private static final int VS16 = 0xFE0F;
  private static final int ZWJ = 0x200D;
  private static final int KEYCAP = 0x20E3;
  private static final int REGIONAL_INDICATOR_START = 0x1F1E6;
  private static final int REGIONAL_INDICATOR_END = 0x1F1FF;
  private static final int SKIN_TONE_START = 0x1F3FB;
  private static final int SKIN_TONE_END = 0x1F3FF;

  boolean isEmojiPresentation(String grapheme) {
    if (grapheme == null || grapheme.isEmpty()) return false;

    boolean hasVs15 = false;
    boolean hasVs16 = false;
    int codePointCount = 0;
    int regionalIndicators = 0;
    boolean hasEmojiBase = false;
    boolean hasZwj = false;
    boolean hasKeycap = false;
    boolean hasSkinTone = false;

    for (int offset = 0; offset < grapheme.length(); ) {
      int codePoint = grapheme.codePointAt(offset);
      codePointCount++;
      if (codePoint == VS15) hasVs15 = true;
      if (codePoint == VS16) hasVs16 = true;
      if (codePoint == ZWJ) hasZwj = true;
      if (codePoint == KEYCAP) hasKeycap = true;
      if (codePoint >= REGIONAL_INDICATOR_START
          && codePoint <= REGIONAL_INDICATOR_END) {
        regionalIndicators++;
      }
      if (codePoint >= SKIN_TONE_START && codePoint <= SKIN_TONE_END) {
        hasSkinTone = true;
      }
      if (isDefaultEmojiPresentation(codePoint)) hasEmojiBase = true;
      offset += Character.charCount(codePoint);
    }

    // 明确的文字 variation selector 优先，避免把 ⏺︎ 误送进系统彩色字体。
    if (hasVs15) return false;
    if (hasVs16) return true;
    if (hasKeycap && codePointCount >= 2) return true;
    if (regionalIndicators == 2 && codePointCount == 2) return true;
    if (hasZwj && hasEmojiBase) return true;
    if (hasSkinTone && hasEmojiBase) return true;
    return codePointCount == 1 && hasEmojiBase;
  }

  private static boolean isDefaultEmojiPresentation(int codePoint) {
    if (codePoint >= REGIONAL_INDICATOR_START && codePoint <= REGIONAL_INDICATOR_END) {
      return false;
    }
    if (codePoint >= SKIN_TONE_START && codePoint <= SKIN_TONE_END) {
      return false;
    }
    if (codePoint >= 0x1F000 && codePoint <= 0x1FAFF) return true;
    if (codePoint >= 0x1FC00 && codePoint <= 0x1FFFD) return true;
    return inRanges(codePoint,
        0x231A, 0x231B,
        0x23E9, 0x23F3,
        0x25FD, 0x25FE,
        0x2614, 0x2615,
        0x2648, 0x2653,
        0x267F, 0x267F,
        0x2693, 0x2693,
        0x26A1, 0x26A1,
        0x26AA, 0x26AB,
        0x26BD, 0x26BE,
        0x26C4, 0x26C5,
        0x26CE, 0x26CE,
        0x26D4, 0x26D4,
        0x26EA, 0x26EA,
        0x26F2, 0x26F3,
        0x26F5, 0x26F5,
        0x26FA, 0x26FA,
        0x26FD, 0x26FD,
        0x2705, 0x2705,
        0x270A, 0x270B,
        0x2728, 0x2728,
        0x274C, 0x274C,
        0x274E, 0x274E,
        0x2753, 0x2755,
        0x2757, 0x2757,
        0x2795, 0x2797,
        0x27B0, 0x27B0,
        0x27BF, 0x27BF,
        0x2B50, 0x2B50,
        0x2B55, 0x2B55,
        0x3030, 0x3030,
        0x303D, 0x303D,
        0x3297, 0x3297,
        0x3299, 0x3299);
  }

  private static boolean inRanges(int codePoint, int... ranges) {
    for (int i = 0; i + 1 < ranges.length; i += 2) {
      if (codePoint >= ranges[i] && codePoint <= ranges[i + 1]) return true;
    }
    return false;
  }
}
