package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;

public final class RemoteTerminalModelBaselineTest {
  @Test
  public void baselineAtomicallyBuildsSparseProjection() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(V2ModelTestData.baseline(1, 1)));
    assertEquals(new HistoryExtent(1, 300), model.displayExtent());
    assertEquals(173, model.firstCachedHistorySeq());
    assertEquals("a", model.renderSnapshot().screen[0].at(0).text);
  }

  @Test
  public void rejectedBaselineDoesNotPartiallyReplaceProjection() {
    RemoteTerminalModel model = new RemoteTerminalModel();
    assertTrue(model.applyBaseline(V2ModelTestData.baseline(1, 1)));
    ScreenBaseline invalid = new ScreenBaseline(
        "s1", "i2", 2, 2, 2, 1, 1, TerminalBufferKind.MAIN,
        new HistoryExtent(1, 1),
        Collections.singletonList(V2ModelTestData.line(2, 1, 2, "bad")),
        Collections.singletonList(V2ModelTestData.line(2000, 1, 0, "b")),
        TerminalCursor.hidden(), TerminalModes.defaults(), TerminalPalette.defaults(), "", "");
    try {
      model.applyBaseline(invalid);
    } catch (RuntimeException expected) {
      // expected
    }
    assertEquals("i1", model.instanceId);
    assertEquals("a", model.renderSnapshot().screen[0].at(0).text);
  }
}
