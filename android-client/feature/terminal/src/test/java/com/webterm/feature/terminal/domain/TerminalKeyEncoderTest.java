package com.webterm.feature.terminal.domain;

import android.view.KeyEvent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class TerminalKeyEncoderTest {

  @Test
  public void semanticKey_mapsArrowKeys() {
    assertEquals("ArrowUp", TerminalKeyEncoder.semanticKey(KeyEvent.KEYCODE_DPAD_UP));
    assertEquals("ArrowDown", TerminalKeyEncoder.semanticKey(KeyEvent.KEYCODE_DPAD_DOWN));
    assertEquals("ArrowLeft", TerminalKeyEncoder.semanticKey(KeyEvent.KEYCODE_DPAD_LEFT));
    assertEquals("ArrowRight", TerminalKeyEncoder.semanticKey(KeyEvent.KEYCODE_DPAD_RIGHT));
  }

  @Test
  public void semanticKey_mapsSpecialKeys() {
    assertEquals("Enter", TerminalKeyEncoder.semanticKey(KeyEvent.KEYCODE_ENTER));
    assertEquals("Enter", TerminalKeyEncoder.semanticKey(KeyEvent.KEYCODE_NUMPAD_ENTER));
    assertEquals("Backspace", TerminalKeyEncoder.semanticKey(KeyEvent.KEYCODE_DEL));
    assertEquals("Delete", TerminalKeyEncoder.semanticKey(KeyEvent.KEYCODE_FORWARD_DEL));
    assertEquals("Tab", TerminalKeyEncoder.semanticKey(KeyEvent.KEYCODE_TAB));
    assertEquals("Escape", TerminalKeyEncoder.semanticKey(KeyEvent.KEYCODE_ESCAPE));
  }

  @Test
  public void semanticKey_unknownCode_returnsNull() {
    assertNull(TerminalKeyEncoder.semanticKey(KeyEvent.KEYCODE_A));
  }

  @Test
  public void describe_functionalKeyWithModifiers() {
    TerminalKeyEncoder.KeyDescriptor desc = TerminalKeyEncoder.describe(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN, 0, false, false, false, false);
    assertTrue(desc.isFunctional());
    assertEquals("ArrowUp", desc.key);
    assertTrue(desc.pressed);
    assertFalse(desc.ctrl);
  }

  @Test
  public void describe_controlledUnicode_sendsKeyInsteadOfText() {
    TerminalKeyEncoder.KeyDescriptor desc = TerminalKeyEncoder.describe(
        KeyEvent.KEYCODE_C, KeyEvent.ACTION_DOWN, 'c', false, false, true, false);
    assertFalse(desc.isFunctional());
    assertEquals("c", desc.unicodeChar);
    assertTrue(desc.ctrl);
  }
}
