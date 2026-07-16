package com.webterm.terminal.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class TerminalPalette {
  public final TerminalColor defaultFg;
  public final TerminalColor defaultBg;
  public final TerminalColor cursorColor;
  public final boolean reverseVideo;
  public final Map<Integer, Integer> indexedColors;
  public final long generation;

  public TerminalPalette(TerminalColor defaultFg, TerminalColor defaultBg, TerminalColor cursorColor) {
    this(defaultFg, defaultBg, cursorColor, false);
  }

  public TerminalPalette(TerminalColor defaultFg, TerminalColor defaultBg, TerminalColor cursorColor,
                         boolean reverseVideo) {
    this(defaultFg, defaultBg, cursorColor, reverseVideo, Collections.emptyMap(), 0L);
  }

  public TerminalPalette(TerminalColor defaultFg, TerminalColor defaultBg, TerminalColor cursorColor,
                         boolean reverseVideo, Map<Integer, Integer> indexedColors,
                         long generation) {
    this.defaultFg = defaultFg;
    this.defaultBg = defaultBg;
    this.cursorColor = cursorColor;
    this.reverseVideo = reverseVideo;
    this.indexedColors = Collections.unmodifiableMap(new HashMap<>(indexedColors));
    this.generation = generation;
  }

  public static TerminalPalette defaults() {
    return new TerminalPalette(TerminalColor.DEFAULT_FG, TerminalColor.DEFAULT_BG, TerminalColor.CURSOR);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof TerminalPalette)) return false;
    TerminalPalette that = (TerminalPalette) o;
    return Objects.equals(defaultFg, that.defaultFg)
        && Objects.equals(defaultBg, that.defaultBg)
        && Objects.equals(cursorColor, that.cursorColor)
        && reverseVideo == that.reverseVideo
        && Objects.equals(indexedColors, that.indexedColors)
        && generation == that.generation;
  }

  @Override
  public int hashCode() {
    return Objects.hash(defaultFg, defaultBg, cursorColor, reverseVideo, indexedColors, generation);
  }
}
