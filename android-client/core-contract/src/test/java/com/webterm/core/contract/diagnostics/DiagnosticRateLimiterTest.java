package com.webterm.core.contract.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Before;
import org.junit.Test;

public class DiagnosticRateLimiterTest {

    private static final long WINDOW = 5000L;

    private AtomicLong clock;
    private DiagnosticRateLimiter limiter;

    @Before
    public void setUp() {
        clock = new AtomicLong(1_000_000L);
        limiter = new DiagnosticRateLimiter(clock::get, WINDOW);
    }

    @Test
    public void firstEventInWindowPasses() {
        assertTrue(limiter.tryPass("screen", "mailbox_overflow", null));
    }

    @Test
    public void duplicatesWithinWindowAreSuppressedAndCounted() {
        assertTrue(limiter.tryPass("screen", "overflow", "d1"));
        assertFalse(limiter.tryPass("screen", "overflow", "d1"));
        assertFalse(limiter.tryPass("screen", "overflow", "d1"));
        assertFalse(limiter.tryPass("screen", "overflow", "d1"));

        // 窗口过期后下一条放行，上一窗口抑制数为 3。
        clock.addAndGet(WINDOW);
        assertTrue(limiter.tryPass("screen", "overflow", "d1"));
        assertEquals(3, limiter.suppressedSinceLast("screen", "overflow", "d1"));
    }

    @Test
    public void suppressedCountIsClearedAfterRead() {
        limiter.tryPass("screen", "overflow", null);
        limiter.tryPass("screen", "overflow", null);
        clock.addAndGet(WINDOW);
        limiter.tryPass("screen", "overflow", null);

        assertEquals(1, limiter.suppressedSinceLast("screen", "overflow", null));
        assertEquals(0, limiter.suppressedSinceLast("screen", "overflow", null));
    }

    @Test
    public void windowExpiryAllowsNextEvent() {
        assertTrue(limiter.tryPass("screen", "resync", null));
        clock.addAndGet(WINDOW - 1);
        assertFalse(limiter.tryPass("screen", "resync", null));
        clock.addAndGet(1);
        assertTrue(limiter.tryPass("screen", "resync", null));
    }

    @Test
    public void differentKeysAreIndependent() {
        assertTrue(limiter.tryPass("screen", "overflow", "d1"));
        // 不同 discriminator、event、area 互不影响。
        assertTrue(limiter.tryPass("screen", "overflow", "d2"));
        assertTrue(limiter.tryPass("screen", "resync", "d1"));
        assertTrue(limiter.tryPass("relay", "overflow", "d1"));
        assertTrue(limiter.tryPass("screen", "overflow", null));
        assertFalse(limiter.tryPass("screen", "overflow", "d1"));

        clock.addAndGet(WINDOW);
        limiter.tryPass("screen", "overflow", "d1");
        assertEquals(1, limiter.suppressedSinceLast("screen", "overflow", "d1"));
        assertEquals(0, limiter.suppressedSinceLast("screen", "overflow", "d2"));
    }

    @Test
    public void suppressedSinceLastWithoutAnyEventReturnsZero() {
        assertEquals(0, limiter.suppressedSinceLast("none", "none", null));
    }
}
