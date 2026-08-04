package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.webterm.terminal.model.CellValue;
import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineKey;
import com.webterm.terminal.model.RenderLine;
import com.webterm.terminal.model.TerminalPalette;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public final class TerminalFontResolverTest {
  private static final int BACKGROUND = 0xFF000000;

  @Test
  public void distinguishesTextAndEmojiPresentation() {
    EmojiPresentationClassifier classifier = new EmojiPresentationClassifier();
    assertFalse(classifier.isEmojiPresentation("⏺"));
    assertFalse(classifier.isEmojiPresentation("⏺︎"));
    assertTrue(classifier.isEmojiPresentation("⏺️"));
    assertFalse(classifier.isEmojiPresentation("⏸"));
    assertTrue(classifier.isEmojiPresentation("⏸️"));
    assertTrue(classifier.isEmojiPresentation("😀"));
    assertTrue(classifier.isEmojiPresentation("❤️"));
    assertTrue(classifier.isEmojiPresentation("👨‍👩‍👧‍👦"));
    assertTrue(classifier.isEmojiPresentation("1️⃣"));
    assertTrue(classifier.isEmojiPresentation("🇨🇳"));
    assertTrue(classifier.isEmojiPresentation("👍🏽"));
    assertFalse(classifier.isEmojiPresentation("中"));
  }

  @Test
  public void routesRepresentativeGraphemesToExpectedFontRole() {
    TerminalFontResolver resolver = TerminalFontResolver.defaultResolver();
    assertEquals(TerminalFontRole.UNICODE_SYMBOL, resolver.resolve("⏺"));
    assertEquals(TerminalFontRole.UNICODE_SYMBOL, resolver.resolve("⏺︎"));
    assertEquals(TerminalFontRole.EMOJI, resolver.resolve("⏺️"));
    assertEquals(TerminalFontRole.EMOJI, resolver.resolve("😀"));
    assertEquals(TerminalFontRole.EMOJI, resolver.resolve("👨‍👩‍👧‍👦"));
    assertEquals(TerminalFontRole.EMOJI, resolver.resolve("1️⃣"));
    assertEquals(TerminalFontRole.NERD_SYMBOL, resolver.resolve("\uE0B0"));
    assertEquals(TerminalFontRole.MAIN_TEXT, resolver.resolve("中"));
    assertEquals(TerminalFontRole.MAIN_TEXT, resolver.resolve("e\u0301"));
  }

  @Test
  public void asciiFastPathSkipsEmojiClassifier() {
    CountingClassifier classifier = new CountingClassifier();
    TerminalFontResolver resolver = new TerminalFontResolver(
        classifier, FontCoverage.unicodeSymbols(), FontCoverage.nerdSymbols());

    assertEquals(TerminalFontRole.MAIN_TEXT, resolver.resolve("A"));
    assertEquals(TerminalFontRole.MAIN_TEXT, resolver.resolve(" "));
    assertEquals(0, classifier.calls);

    assertEquals(TerminalFontRole.EMOJI, resolver.resolve("😀"));
    assertEquals(1, classifier.calls);
  }

  @Test
  public void knownSingleCodePointMatchesRegularResolution() {
    TerminalFontResolver resolver = TerminalFontResolver.defaultResolver();
    assertEquals(resolver.resolve("⏺"), resolver.resolve("⏺", 0x23FA));
    assertEquals(resolver.resolve("\uE0B0"), resolver.resolve("\uE0B0", 0xE0B0));
    assertEquals(resolver.resolve("😀"), resolver.resolve("😀", -1));
  }

  @Test
  public void asciiKeycapStillReachesEmojiClassifier() {
    CountingClassifier classifier = new CountingClassifier();
    TerminalFontResolver resolver = new TerminalFontResolver(
        classifier, FontCoverage.unicodeSymbols(), FontCoverage.nerdSymbols());

    assertEquals(TerminalFontRole.EMOJI, resolver.resolve("1\uFE0F\u20E3"));
    assertEquals(1, classifier.calls);
  }

  @Test
  public void compilerSplitsTextSpansWhenFontRoleChanges() {
    RenderLine line = line(
        new CellValue("A", (byte) 1, null, null),
        new CellValue("⏺", (byte) 1, null, null),
        new CellValue("B", (byte) 1, null, null));

    List<CompiledTerminalLine.Span> spans = new TerminalLineCompiler(
        TerminalFontResolver.defaultResolver()).compile(
            line, 3, TerminalPalette.defaults(), BACKGROUND).spans();

    assertEquals(3, spans.size());
    assertEquals(TerminalFontRole.MAIN_TEXT,
        ((CompiledTerminalLine.TextSpan) spans.get(0)).fontRole());
    assertEquals(TerminalFontRole.UNICODE_SYMBOL,
        ((CompiledTerminalLine.TextSpan) spans.get(1)).fontRole());
    assertEquals(TerminalFontRole.MAIN_TEXT,
        ((CompiledTerminalLine.TextSpan) spans.get(2)).fontRole());
  }

  @Test
  public void specialPainterStillWinsBeforeFontRouting() {
    RenderLine line = line(new CellValue("", (byte) 1, null, null));
    List<CompiledTerminalLine.Span> spans = new TerminalLineCompiler(
        TerminalFontResolver.defaultResolver()).compile(
            line, 1, TerminalPalette.defaults(), BACKGROUND).spans();

    assertEquals(1, spans.size());
    assertTrue(spans.get(0) instanceof CompiledTerminalLine.SpecialGlyphSpan);
  }

  private static RenderLine line(CellValue... cells) {
    return new RenderLine(
        new LineKey(1, 1), new LineBody(cells.length, false, Arrays.copyOf(cells, cells.length)));
  }

  private static final class CountingClassifier extends EmojiPresentationClassifier {
    int calls;

    @Override
    boolean isEmojiPresentation(String grapheme) {
      calls++;
      return super.isEmojiPresentation(grapheme);
    }
  }
}
