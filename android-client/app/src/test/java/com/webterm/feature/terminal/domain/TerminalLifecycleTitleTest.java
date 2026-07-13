package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TerminalLifecycleTitleTest {

    @Test
    public void displayTermTitleFallsBackForMissingOrBlankTitle() {
        assertEquals("Terminal", TerminalLifecycleController.displayTermTitle(null));
        assertEquals("Terminal", TerminalLifecycleController.displayTermTitle(""));
        assertEquals("Terminal", TerminalLifecycleController.displayTermTitle("   "));
    }

    @Test
    public void displayTermTitleTrimsReportedTitle() {
        assertEquals("Codex", TerminalLifecycleController.displayTermTitle("  Codex  "));
    }
}
