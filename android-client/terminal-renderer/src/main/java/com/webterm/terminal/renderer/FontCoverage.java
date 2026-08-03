package com.webterm.terminal.renderer;

/**
 * 轻量的字体覆盖表。
 *
 * <p>路由发生在逐 cell 热路径，因此不在这里调用 Paint.hasGlyph()。覆盖表只描述已
 * 打包字体的静态 cmap 范围；真正的字体像素行为由设备测试验证。</p>
 */
final class FontCoverage {
  private final int[] ranges;

  private FontCoverage(int[] ranges) {
    this.ranges = ranges;
  }

  boolean contains(int codePoint) {
    for (int i = 0; i + 1 < ranges.length; i += 2) {
      if (codePoint < ranges[i]) return false;
      if (codePoint <= ranges[i + 1]) return true;
    }
    return false;
  }

  /** Noto Sans Symbols 2 中本阶段作为公共单色符号使用的 cmap 范围。 */
  static FontCoverage unicodeSymbols() {
    return new FontCoverage(new int[] {
        0x21AF, 0x21AF,
        0x21E6, 0x21F0,
        0x23CE, 0x23CF,
        0x23E9, 0x2426,
        0x25A0, 0x2609,
        0x260E, 0x2623,
        0x2630, 0x2637,
        0x263C, 0x263C,
        0x2654, 0x2668,
        0x267F, 0x268F,
        0x269E, 0x26E1,
        0x2700, 0x2704,
        0x2706, 0x2709,
        0x270B, 0x271C,
        0x2722, 0x2727,
        0x2729, 0x274B,
        0x274D, 0x274D,
        0x274F, 0x2753,
        0x2756, 0x2775,
        0x2794, 0x2794,
        0x2798, 0x27AF,
        0x27B1, 0x27BE,
        0x2B00, 0x2B0D,
        0x2B12, 0x2B2F,
        0x2B4D, 0x2B73,
        0x2B76, 0x2B95,
        0x2B97, 0x2BFD,
        0x2BFF, 0x2BFF
    });
  }

  /** Symbols Nerd Font Mono v3.5.0 中的 PUA cmap 范围。 */
  static FontCoverage nerdSymbols() {
    return new FontCoverage(new int[] {
        0xE000, 0xE00A,
        0xE0A0, 0xE0A3,
        0xE0B0, 0xE0C8,
        0xE0CA, 0xE0CA,
        0xE0CC, 0xE0D2,
        0xE0D4, 0xE0D4,
        0xE0D6, 0xE0D7,
        0xE200, 0xE2A9,
        0xE300, 0xE3E3,
        0xE5FA, 0xE6BB,
        0xE700, 0xE8EF,
        0xEA60, 0xEA88,
        0xEA8A, 0xEA8C,
        0xEA8F, 0xEAC7,
        0xEAC9, 0xEAC9,
        0xEACC, 0xEB09,
        0xEB0B, 0xEB4E,
        0xEB50, 0xEC5E,
        0xEC60, 0xEC84,
        0xED00, 0xEFCF,
        0xF000, 0xF385,
        0xF400, 0xF533,
        0xF0001, 0xF1AF0
    });
  }

  static FontCoverage none() {
    return new FontCoverage(new int[0]);
  }
}
