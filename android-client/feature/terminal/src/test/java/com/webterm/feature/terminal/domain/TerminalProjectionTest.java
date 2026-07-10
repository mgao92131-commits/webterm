package com.webterm.feature.terminal.domain;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TerminalProjectionTest {
    @Test
    public void statePlusDetachedOutputCanRebuildTerminal() {
        TerminalProjection projection = new TerminalProjection();

        projection.recordState(10L, new byte[] {1, 2});
        projection.recordOutput(11L, new byte[] {3});
        projection.recordOutput(12L, new byte[] {4, 5});

        assertTrue(projection.hasReplayMaterial());
        assertFalse(projection.requiresFreshState());
        assertArrayEquals(new byte[] {1, 2}, projection.stateBytes());
        assertArrayEquals(new byte[] {3, 4, 5}, projection.outputBytes());
    }

    @Test
    public void outputWithoutAuthoritativeStateRequestsFreshRecovery() {
        TerminalProjection projection = new TerminalProjection();

        projection.recordOutput(1L, new byte[] {1});

        assertFalse(projection.hasReplayMaterial());
        assertTrue(projection.requiresFreshState());
    }

    @Test
    public void laterStateRepairsAnIncompleteProjection() {
        TerminalProjection projection = new TerminalProjection();
        projection.recordOutput(1L, new byte[] {1});

        projection.recordState(5L, new byte[] {9});

        assertTrue(projection.hasReplayMaterial());
        assertFalse(projection.requiresFreshState());
        assertArrayEquals(new byte[] {9}, projection.stateBytes());
    }
}
