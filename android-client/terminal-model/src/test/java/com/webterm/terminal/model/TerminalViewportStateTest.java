package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TerminalViewportStateTest {
  @Test
  public void bothSurfacesStartAtFollowTailAndRemainIndependent() {
    TerminalViewportState viewport = new TerminalViewportState();
    assertTrue(viewport.isFollowTail(TerminalBufferKind.MAIN));
    assertTrue(viewport.isFollowTail(TerminalBufferKind.ALTERNATE));

    viewport.anchorLine(TerminalBufferKind.MAIN, 42, -7);
    assertFalse(viewport.isFollowTail(TerminalBufferKind.MAIN));
    assertTrue(viewport.isFollowTail(TerminalBufferKind.ALTERNATE));

    viewport.followTail(TerminalBufferKind.MAIN);
    assertTrue(viewport.isFollowTail(TerminalBufferKind.MAIN));
  }

  @Test
  public void lineAnchorIsTheOnlyPersistentPixelPosition() {
    TerminalViewportState viewport = new TerminalViewportState();
    ViewportPosition.LineAnchor anchor = ViewportPosition.lineAnchor(77, -6);
    viewport.setPosition(TerminalBufferKind.MAIN, anchor);

    assertSame(anchor, viewport.position(TerminalBufferKind.MAIN));
    assertEquals(77, ((ViewportPosition.LineAnchor)
        viewport.position(TerminalBufferKind.MAIN)).lineId);
    assertEquals(-6, ((ViewportPosition.LineAnchor)
        viewport.position(TerminalBufferKind.MAIN)).pixelOffset);
  }

  @Test
  public void scrollOffsetIsDerivedAndReturnToTailIsImmediate() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(V2ModelTestData.baseline(1, 1)));
    RemoteTerminalModel.RenderSnapshot snapshot = model.renderSnapshot();
    TerminalViewportState viewport = new TerminalViewportState();

    viewport.scrollBy(100, 300, snapshot, 1f);
    assertFalse(viewport.isFollowTail(TerminalBufferKind.MAIN));
    assertEquals(100, viewport.derivedScrollOffsetPixels(snapshot, 1f, 300));

    viewport.scrollBy(-100, 300, snapshot, 1f);
    assertTrue(viewport.isFollowTail(TerminalBufferKind.MAIN));
    assertEquals(0, viewport.derivedScrollOffsetPixels(snapshot, 1f, 300));
  }
}
