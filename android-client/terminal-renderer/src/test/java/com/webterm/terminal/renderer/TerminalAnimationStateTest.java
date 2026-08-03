package com.webterm.terminal.renderer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TerminalAnimationStateTest {
  @Test
  public void legacyCursorOnlyKeepsBlinkForegroundVisible() {
    CompiledTerminalLine.CompiledStyle slow = style(true, false);
    CompiledTerminalLine.CompiledStyle fast = style(false, true);

    TerminalAnimationState state = TerminalAnimationState.cursorOnly(true);

    assertTrue(state.foregroundVisible(slow));
    assertTrue(state.foregroundVisible(fast));
    assertFalse(TerminalAnimationState.staticContent().foregroundVisible(slow));
    assertFalse(TerminalAnimationState.staticContent().foregroundVisible(fast));
  }

  private static CompiledTerminalLine.CompiledStyle style(boolean slow, boolean fast) {
    return new CompiledTerminalLine.CompiledStyle(
        0xFFFFFFFF,
        0xFF000000,
        0xFFFFFFFF,
        false,
        false,
        false,
        false,
        false,
        slow,
        fast,
        ResolvedTerminalStyle.UnderlineKind.NONE);
  }
}
