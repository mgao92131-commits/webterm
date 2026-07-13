package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;

import com.webterm.feature.terminal.TerminalViewModel;

import org.junit.Test;

public class TerminalRuntimeKeyTest {
    @Test
    public void fromArgs_usesLiveSessionIdentityNotPersistenceIdentity() {
        TerminalViewModel.TerminalSessionArgs initial = new TerminalViewModel.TerminalSessionArgs(
            "http://mac.test", "cookie", "session-1", "Terminal",
            "", "", false, "device-1", ""
        );
        TerminalViewModel.TerminalSessionArgs hydrated = new TerminalViewModel.TerminalSessionArgs(
            "http://mac.test", "cookie", "session-1", "Terminal",
            "2026-07-05T10:00:00Z", "instance-1", false, "device-1", ""
        );

        assertEquals(TerminalRuntimeKey.fromArgs(initial), TerminalRuntimeKey.fromArgs(hydrated));
    }
}
