package com.webterm.terminal.renderer;

/**
 * 新旧 Android 终端共享的视觉规则。
 *
 * <p>这里刻意只放与终端状态源无关的规则：ANSI 默认调色板、dim 算法和
 * 字形比例保护。无论状态来自本地 TerminalEmulator 还是 Go 屏幕投影，画面语义一致。</p>
 */
public final class TerminalVisualRules {

  private static final int[] ANSI_16 = {
      0xFF000000, 0xFFCD0000, 0xFF00CD00, 0xFFCDCD00,
      0xFF6495ED, 0xFFCD00CD, 0xFF00CDCD, 0xFFE5E5E5,
      0xFF7F7F7F, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00,
      0xFF5C5CFF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF
  };

  private TerminalVisualRules() {}

  /** Termux 旧 renderer 使用的 xterm 256 色默认值。 */
  public static int ansiColor(int index) {
    int i = Math.max(0, Math.min(255, index));
    if (i < 16) return ANSI_16[i];
    if (i < 232) {
      int n = i - 16;
      int[] level = {0, 95, 135, 175, 215, 255};
      return 0xFF000000 | (level[n / 36] << 16)
          | (level[(n / 6) % 6] << 8) | level[n % 6];
    }
    int gray = 8 + (i - 232) * 10;
    return 0xFF000000 | (gray << 16) | (gray << 8) | gray;
  }

  /** 与旧 TerminalRenderer 对齐的 xterm/libvte dim 算法。 */
  public static int dim(int color) {
    int alpha = color & 0xFF000000;
    int red = ((color >> 16) & 0xFF) * 2 / 3;
    int green = ((color >> 8) & 0xFF) * 2 / 3;
    int blue = (color & 0xFF) * 2 / 3;
    return alpha | (red << 16) | (green << 8) | blue;
  }

  /**
   * Powerline、Nerd Font、emoji 与符号字体的连接/轮廓不应被横向挤压。
   * 其他宽度不匹配字符仍缩放到终端列宽。
   */
  public static boolean shouldPreserveGlyphAspect(int codePoint, int wcWidth,
                                                  boolean hasRightPadding) {
    if (isPowerlineGlyph(codePoint)) return true;
    return wcWidth >= 2 && hasRightPadding && (isNerdFontGlyph(codePoint)
        || isEmojiGlyph(codePoint) || isSymbolGlyph(codePoint) || isPrivateUseGlyph(codePoint));
  }

  private static boolean isPowerlineGlyph(int codePoint) {
    return codePoint >= 0xE0A0 && codePoint <= 0xE0D7;
  }

  private static boolean isNerdFontGlyph(int codePoint) {
    return (codePoint >= 0xE5FA && codePoint <= 0xE6B1)
        || (codePoint >= 0xE700 && codePoint <= 0xE8EF)
        || (codePoint >= 0xEA60 && codePoint <= 0xEBEB)
        || (codePoint >= 0xED00 && codePoint <= 0xEFC1)
        || (codePoint >= 0xF000 && codePoint <= 0xFD46);
  }

  private static boolean isPrivateUseGlyph(int codePoint) {
    return (codePoint >= 0xE000 && codePoint <= 0xF8FF)
        || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD)
        || (codePoint >= 0x100000 && codePoint <= 0x10FFFD);
  }

  private static boolean isEmojiGlyph(int codePoint) {
    return (codePoint >= 0x1F000 && codePoint <= 0x1FAFF)
        || (codePoint >= 0x2600 && codePoint <= 0x27BF);
  }

  private static boolean isSymbolGlyph(int codePoint) {
    return (codePoint >= 0x25A0 && codePoint <= 0x25FF)
        || (codePoint >= 0x2B00 && codePoint <= 0x2BFF);
  }
}
