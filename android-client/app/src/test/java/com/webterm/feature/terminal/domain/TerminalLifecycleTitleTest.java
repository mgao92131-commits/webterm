package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TerminalLifecycleTitleTest {

    @Test
    public void displayTermTitleFallsBackForMissingOrBlankTitle() {
        assertEquals("Terminal", RemoteTerminalIntegration.displayTermTitle(null));
        assertEquals("Terminal", RemoteTerminalIntegration.displayTermTitle(""));
        assertEquals("Terminal", RemoteTerminalIntegration.displayTermTitle("   "));
    }

    @Test
    public void displayTermTitleTrimsReportedTitle() {
        assertEquals("Codex", RemoteTerminalIntegration.displayTermTitle("  Codex  "));
    }

    @Test
    public void displayCwdPreservesPathAndHandlesNull() {
        assertEquals("", RemoteTerminalIntegration.displayCwd(null));
        assertEquals("/Users/gao/My Project", RemoteTerminalIntegration.displayCwd("/Users/gao/My Project"));
    }
}
