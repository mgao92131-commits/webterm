package com.webterm.terminal.model;

import static org.junit.Assert.assertEquals;

import java.util.Collections;
import org.junit.Test;

public final class RemoteTerminalModelScreenPatchTest {
  @Test
  public void patchAdvancesOnlyContiguousScreenRevision() throws Exception {
    RemoteTerminalModel model = new RemoteTerminalModel();
    model.applyBaseline(V2ModelTestData.baseline(1, 1));
    model.applyScreenPatch(new ScreenPatchV2(
        "i1", 1, 1, 1, 2, new long[] {1000},
        Collections.singletonList(V2ModelTestData.line(1000, 2, 0, "b")),
        null, null, null, null, null, null));
    assertEquals(2, model.screenRevision);
    assertEquals("b", model.renderSnapshot().screen[0].at(0).text);

    try {
      model.applyScreenPatch(new ScreenPatchV2(
          "i1", 1, 1, 1, 3, null, Collections.emptyList(),
          null, null, null, null, null, null));
    } catch (RemoteTerminalModel.RevisionGapException expected) {
      // expected
    }
    assertEquals(2, model.screenRevision);
  }
}
