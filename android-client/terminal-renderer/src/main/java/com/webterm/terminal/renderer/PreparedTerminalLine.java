package com.webterm.terminal.renderer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** CPU 侧可复用的行编译和文字布局结果。 */
final class PreparedTerminalLine {
  @NonNull final CompiledTerminalLine compiledLine;
  @NonNull final PreparedTextLayout[] textLayouts;
  final int visibleBlinkKinds;
  final long estimatedBytes;

  PreparedTerminalLine(
      @NonNull CompiledTerminalLine compiledLine,
      @NonNull PreparedTextLayout[] textLayouts) {
    this.compiledLine = compiledLine;
    this.textLayouts = textLayouts;
    this.visibleBlinkKinds = compiledLine.visibleBlinkKinds();

    long bytes = 96L;
    for (CompiledTerminalLine.Span span : compiledLine.spans()) {
      bytes += 48L;
      if (span instanceof CompiledTerminalLine.TextSpan) {
        CompiledTerminalLine.TextSpan textSpan = (CompiledTerminalLine.TextSpan) span;
        bytes += 32L + textSpan.text().length() * 2L
            + textSpan.clusterCount() * 16L;
      }
    }
    for (PreparedTextLayout layout : textLayouts) {
      if (layout != null) bytes += layout.estimatedBytes;
    }
    estimatedBytes = bytes;
  }

  @Nullable
  PreparedTextLayout layoutAt(int spanIndex) {
    return spanIndex >= 0 && spanIndex < textLayouts.length
        ? textLayouts[spanIndex] : null;
  }
}
