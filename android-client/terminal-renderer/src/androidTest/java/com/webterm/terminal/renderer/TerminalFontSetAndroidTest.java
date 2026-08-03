package com.webterm.terminal.renderer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Paint;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** 真实 Android 资源表和打包字体的最小 smoke 检查。 */
@RunWith(AndroidJUnit4.class)
public final class TerminalFontSetAndroidTest {
  @Test
  public void loadsBundledSymbolFontsAndRoutesRepresentativeGlyphs() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    TerminalFontSet fonts = TerminalFontSet.fromContext(context);

    assertEquals(TerminalFontRole.UNICODE_SYMBOL, fonts.resolver.resolve("⏺"));
    assertEquals(TerminalFontRole.NERD_SYMBOL, fonts.resolver.resolve("\uE0B0"));
    assertEquals(TerminalFontRole.EMOJI, fonts.resolver.resolve("😀"));

    Paint unicodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    unicodePaint.setTypeface(fonts.unicodeSymbols);
    assertTrue(unicodePaint.hasGlyph("⏺"));

    Paint nerdPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    nerdPaint.setTypeface(fonts.nerdSymbols);
    assertTrue(nerdPaint.hasGlyph("\uE0B0"));
  }
}
