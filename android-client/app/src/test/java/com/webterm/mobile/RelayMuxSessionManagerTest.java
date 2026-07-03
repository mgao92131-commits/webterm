package com.webterm.mobile;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RelayMuxSessionManagerTest {
    @Test
    public void canonicalSessionIdPrefixesRelaySession() {
        assertEquals("d1:s1", RelayMuxSessionManager.canonicalSessionId("s1", "d1"));
    }

    @Test
    public void canonicalSessionIdLeavesAlreadyPrefixedSessionAlone() {
        assertEquals("d1:s1", RelayMuxSessionManager.canonicalSessionId("d1:s1", "d1"));
    }

    @Test
    public void localSessionIdStripsMatchingRelayPrefix() {
        assertEquals("s1", RelayMuxSessionManager.localSessionId("d1:s1", "d1"));
    }
}
