package com.webterm.core.api;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class SessionIdsTest {
    @Test
    public void agentLocalAcceptsCanonicalLocalAndLegacyIds() {
        assertEquals("s1", SessionIds.agentLocal("d5:s1", "d5"));
        assertEquals("s1", SessionIds.agentLocal("s1", "d5"));
        assertEquals("s1", SessionIds.agentLocal("relay:s1", "d5"));
        assertEquals("s1", SessionIds.agentLocal("relay:d5:s1", "d5"));
    }

    @Test
    public void localCompatibilityEntryDelegatesToAgentResolver() {
        assertEquals(
            SessionIds.agentLocal("relay:d5:s1", "d5"),
            SessionIds.local("relay:d5:s1", "d5"));
    }
}
