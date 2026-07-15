package com.webterm.mobile;

import static org.junit.Assert.assertEquals;

import com.webterm.core.session.DeviceConnection;

import org.junit.Test;

public class DeviceConnectionTest {
    @Test
    public void canonicalSessionIdPrefixesRelaySession() {
        assertEquals("d1:s1", DeviceConnection.canonicalSessionId("s1", "d1"));
    }

    @Test
    public void canonicalSessionIdLeavesAlreadyPrefixedSessionAlone() {
        assertEquals("d1:s1", DeviceConnection.canonicalSessionId("d1:s1", "d1"));
    }

    @Test
    public void localSessionIdStripsMatchingRelayPrefix() {
        assertEquals("s1", DeviceConnection.localSessionId("d1:s1", "d1"));
    }
}
