package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TerminalViewportStateTest {

  @Test
  public void resetForSnapshot_returnsViewportToAuthoritativeTail() {
    TerminalViewportState viewport = new TerminalViewportState();
    viewport.followTail = false;
    viewport.scrollOffsetPixels = 480;
    viewport.anchorHistoryLineId = 42L;
    viewport.anchorPixelOffset = 7;
    viewport.selection = new TerminalSelection(
        new TerminalSelection.Anchor(42L, -1, 0),
        new TerminalSelection.Anchor(42L, -1, 3));
    viewport.loadingOlderHistory = true;

    viewport.resetForSnapshot();

    assertTrue(viewport.followTail);
    assertEquals(0, viewport.scrollOffsetPixels);
    assertNull(viewport.anchorHistoryLineId);
    assertEquals(0, viewport.anchorPixelOffset);
    assertNull(viewport.selection);
    assertFalse(viewport.loadingOlderHistory);
  }

  @Test
  public void scrollBy_clampsAtBothEndsWithoutInvisibleOverscroll() {
    TerminalViewportState viewport = new TerminalViewportState();

    viewport.scrollBy(4_000, 600);
    assertEquals(600, viewport.scrollOffsetPixels);
    assertFalse(viewport.followTail);

    // A single reverse gesture starts moving visible content immediately;
    // excess drag at the top was discarded rather than retained in state.
    viewport.scrollBy(-120, 600);
    assertEquals(480, viewport.scrollOffsetPixels);

    viewport.scrollBy(-4_000, 600);
    assertEquals(0, viewport.scrollOffsetPixels);
    assertTrue(viewport.followTail);
  }

  @Test
  public void tailAppendCompensationPinsViewportButNeverExceedsHardTop() {
    TerminalViewportState viewport = new TerminalViewportState();
    viewport.scrollBy(500, 1_000);
    assertFalse(viewport.followTail);

    // Live output appended below the visible window pins the same lines by
    // growing the offset, but never past the rendered hard top — an unclamped
    // += would retain invisible overscroll the next reverse gesture must eat.
    viewport.scrollBy(360, 1_000);
    assertEquals(860, viewport.scrollOffsetPixels);
    assertFalse(viewport.followTail);

    viewport.scrollBy(9_999, 1_000);
    assertEquals(1_000, viewport.scrollOffsetPixels);
    assertFalse(viewport.followTail);
  }
}
