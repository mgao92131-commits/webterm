package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class TerminalRuntimeStateTest {

    private TerminalRuntimeState state;

    @Before
    public void setUp() {
        state = new TerminalRuntimeState();
    }

    @Test
    public void initialState_hasNoSession() {
        assertFalse(state.hasSession());
    }

    @Test
    public void setServerSession_setsFields() {
        state.setServerSession("http://mac.test", "cookie", "s1", "d1");

        assertTrue(state.hasSession());
        assertEquals("http://mac.test", state.baseUrl());
        assertEquals("cookie", state.cookie());
        assertEquals("s1", state.sessionId());
        assertEquals("d1", state.relayDeviceId());
    }

    @Test
    public void clearServerSession_resetsSession() {
        state.setServerSession("http://mac.test", "cookie", "s1", "d1");
        state.clearServerSession();

        assertFalse(state.hasSession());
        assertEquals("", state.relayDeviceId());
    }

    @Test
    public void setCwd_trimsValue() {
        state.setCwd(" /tmp ");
        assertEquals("/tmp", state.cwd());
    }

    @Test
    public void updateSize_setsDimensions() {
        state.updateSize(120, 40);
        assertEquals(120, state.columns());
        assertEquals(40, state.rows());
    }

    @Test
    public void onOutput_updatesLastSeqWhenPositive() {
        state.onOutput(5, new byte[0]);
        assertEquals(5, state.lastSeq());
    }

    @Test
    public void onOutput_ignoresNonPositiveSeq() {
        state.onOutput(10, new byte[0]);
        state.onOutput(0, new byte[0]);
        assertEquals(10, state.lastSeq());
    }

    @Test
    public void resetLastSeq_clearsSequence() {
        state.onOutput(10, new byte[0]);
        state.resetLastSeq();
        assertEquals(0, state.lastSeq());
    }

    @Test
    public void updateIdentity_setsNonEmptyValues() {
        state.updateIdentity("i1", "2024-01-01");
        assertEquals("i1", state.instanceId());
        assertEquals("2024-01-01", state.createdAt());
    }

    @Test
    public void updateIdentity_keepsExistingWhenArgumentEmpty() {
        state.updateIdentity("i1", "2024-01-01");
        state.updateIdentity("", "");
        assertEquals("i1", state.instanceId());
        assertEquals("2024-01-01", state.createdAt());
    }

    @Test
    public void clearTerminalDetails_keepsServerSession() {
        state.setServerSession("http://mac.test", "cookie", "s1", "");
        state.updateSize(120, 40);
        state.updateIdentity("i1", "2024-01-01");

        state.clearTerminalDetails();

        assertTrue(state.hasSession());
        assertEquals("s1", state.sessionId());
        assertEquals(0, state.columns());
        assertEquals(0, state.rows());
        assertEquals("", state.instanceId());
        assertEquals("", state.createdAt());
        assertEquals("", state.cwd());
    }
}
