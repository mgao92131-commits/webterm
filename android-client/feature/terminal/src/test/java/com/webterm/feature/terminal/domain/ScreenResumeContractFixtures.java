package com.webterm.feature.terminal.domain;

import com.webterm.terminal.model.HistoryWindow;
import com.webterm.terminal.model.ScreenSnapshot;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalModes;
import com.webterm.terminal.model.TerminalPalette;

import java.util.Collections;

final class ScreenResumeContractFixtures {
  private ScreenResumeContractFixtures() {}

  static ScreenSnapshot snapshotModel(long revision) {
    return new ScreenSnapshot("s1", "i1", 1, revision, 1, 1,
        ScreenSnapshot.BufferKind.MAIN, TerminalCursor.hidden(), TerminalModes.defaults(),
        TerminalPalette.defaults(), new HistoryWindow(1, 0, 0, false, Collections.emptyList()),
        Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap(), "", "");
  }
}
