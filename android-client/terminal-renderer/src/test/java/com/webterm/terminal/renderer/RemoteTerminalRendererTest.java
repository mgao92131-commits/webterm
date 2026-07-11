package com.webterm.terminal.renderer;

import com.webterm.terminal.model.TerminalColor;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class RemoteTerminalRendererTest {
  @Test public void resolvesAnsiIndexedAndColorCube() {
    assertEquals(0xFFCD0000, RemoteTerminalRenderer.resolveColor(TerminalColor.indexed(1)));
    assertEquals(0xFFFF0000, RemoteTerminalRenderer.resolveColor(TerminalColor.indexed(196)));
    assertEquals(0xFF080808, RemoteTerminalRenderer.resolveColor(TerminalColor.indexed(232)));
  }
}
