package com.webterm.terminal.renderer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.webterm.terminal.model.StyleValue;
import com.webterm.terminal.model.TerminalColor;
import com.webterm.terminal.model.TerminalPalette;

/** 把 CellValue 的 ANSI 属性解析成绘制阶段唯一使用的最终样式。 */
final class TerminalStyleResolver {
  void resolveInto(@NonNull TerminalPalette palette, @Nullable StyleValue source,
                   boolean invertForBlockCursor, ResolvedTerminalStyle out) {
    TerminalColor foreground = source != null && source.fg() != null
        ? source.fg() : palette.defaultFg;
    TerminalColor background = source != null && source.bg() != null
        ? source.bg() : palette.defaultBg;
    boolean reverse = palette.reverseVideo
        ^ (source != null && source.reverse())
        ^ invertForBlockCursor;
    if (reverse) {
      TerminalColor swap = foreground;
      foreground = background;
      background = swap;
    }

    out.bold = source != null && source.bold();
    out.dim = source != null && source.dim();
    out.italic = source != null && source.italic();
    out.hidden = source != null && source.hidden();
    out.strike = source != null && source.strike();
    out.blinkSlow = source != null && source.blinkSlow();
    out.blinkFast = source != null && source.blinkFast();

    int resolvedForeground = RemoteTerminalRenderer.resolveColor(palette, foreground);
    if (out.bold && foreground != null && foreground.kind == TerminalColor.Kind.INDEXED
        && foreground.index >= 0 && foreground.index < 8) {
      resolvedForeground = RemoteTerminalRenderer.resolveIndexedColor(
          palette, foreground.index + 8);
    }
    if (out.dim) resolvedForeground = TerminalVisualRules.dim(resolvedForeground);
    out.foreground = resolvedForeground;
    out.background = RemoteTerminalRenderer.resolveColor(palette, background);

    TerminalColor explicitUnderlineColor = source != null ? source.underlineColor() : null;
    out.underlineColor = explicitUnderlineColor != null
        ? RemoteTerminalRenderer.resolveColor(palette, explicitUnderlineColor)
        : out.foreground;
    out.underlineKind = underlineKind(source);
  }

  private static ResolvedTerminalStyle.UnderlineKind underlineKind(@Nullable StyleValue style) {
    if (style == null) return ResolvedTerminalStyle.UnderlineKind.NONE;
    if (style.dashedUnderline()) return ResolvedTerminalStyle.UnderlineKind.DASHED;
    if (style.dottedUnderline()) return ResolvedTerminalStyle.UnderlineKind.DOTTED;
    if (style.curlyUnderline()) return ResolvedTerminalStyle.UnderlineKind.CURLY;
    if (style.doubleUnderline()) return ResolvedTerminalStyle.UnderlineKind.DOUBLE;
    if (style.underline()) return ResolvedTerminalStyle.UnderlineKind.SINGLE;
    return ResolvedTerminalStyle.UnderlineKind.NONE;
  }
}
