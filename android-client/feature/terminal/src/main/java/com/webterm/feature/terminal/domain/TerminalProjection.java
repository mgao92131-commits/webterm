package com.webterm.feature.terminal.domain;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * UI-independent terminal recovery material.
 *
 * <p>A full {@code MSG_STATE} is an authoritative terminal byte stream. Later
 * output is retained as a delta. Keeping this at the session/runtime boundary
 * means a Fragment can disappear without making the network layer claim a
 * sequence that no terminal model can render.</p>
 */
final class TerminalProjection {
    static final int MAX_REPLAY_BYTES = 1024 * 1024;

    private byte[] state;
    private final ByteArrayOutputStream outputAfterState = new ByteArrayOutputStream();
    private long committedSeq;
    private boolean requiresFreshState;

    void recordState(long seq, byte[] bytes) {
        state = copy(bytes);
        outputAfterState.reset();
        committedSeq = Math.max(committedSeq, seq);
        requiresFreshState = false;
    }

    void recordOutput(long seq, byte[] bytes) {
        if (requiresFreshState || bytes == null || bytes.length == 0) {
            committedSeq = Math.max(committedSeq, seq);
            return;
        }
        if (state == null || outputAfterState.size() + bytes.length > MAX_REPLAY_BYTES) {
            state = null;
            outputAfterState.reset();
            requiresFreshState = true;
            committedSeq = Math.max(committedSeq, seq);
            return;
        }
        outputAfterState.write(bytes, 0, bytes.length);
        committedSeq = Math.max(committedSeq, seq);
    }

    boolean hasReplayMaterial() {
        return state != null && !requiresFreshState;
    }

    boolean requiresFreshState() {
        return requiresFreshState;
    }

    long committedSeq() {
        return committedSeq;
    }

    byte[] stateBytes() {
        return copy(state);
    }

    byte[] outputBytes() {
        return outputAfterState.toByteArray();
    }

    void clear() {
        state = null;
        outputAfterState.reset();
        committedSeq = 0;
        requiresFreshState = false;
    }

    private static byte[] copy(byte[] bytes) {
        return bytes == null ? null : Arrays.copyOf(bytes, bytes.length);
    }
}
