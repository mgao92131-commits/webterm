package com.webterm.mobile.diagnostics;

import com.webterm.core.contract.diagnostics.DiagnosticLevel;
import com.webterm.core.contract.diagnostics.Diagnostics;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** DiagnosticMemoryRing 的纯逻辑测试：条数与字节上限。 */
public class DiagnosticMemoryRingTest {

    @Before
    public void setUp() {
        DiagnosticsMemory.shutdownForTest();
        DiagnosticsMemory.init();
    }

    @After
    public void tearDown() {
        DiagnosticsMemory.shutdownForTest();
    }

    @Test
    public void countBudgetDropsOldest() {
        DiagnosticMemoryRing ring = DiagnosticMemoryRing.getInstance();
        for (int i = 0; i < DiagnosticMemoryRing.MAX_ENTRIES + 10; i++) {
            ring.record(DiagnosticLevel.INFO, "test", "evt", Map.of("i", i));
        }
        assertEquals(DiagnosticMemoryRing.MAX_ENTRIES, ring.entryCount());
        assertEquals(11L, ring.snapshot().get(0).seq);
        assertEquals(10L, ring.droppedEntryCount());
        DiagnosticMemoryRing.RingStats stats = ring.ringStats();
        assertEquals(10L, stats.droppedEntryCount);
        assertEquals(11L, stats.oldestSeq);
        assertEquals(DiagnosticMemoryRing.MAX_ENTRIES + 10L, stats.newestSeq);
    }

    @Test
    public void byteBudgetDropsOldest() {
        DiagnosticMemoryRing ring = DiagnosticMemoryRing.getInstance();
        String pad = repeat('x', 1200);
        for (int i = 0; i < 6000; i++) {
            ring.record(DiagnosticLevel.INFO, "test", "evt", Map.of("pad", pad));
        }
        assertTrue(ring.entryCount() <= DiagnosticMemoryRing.MAX_ENTRIES);
        assertTrue(ring.totalBytes() <= DiagnosticMemoryRing.MAX_BYTES);
    }

    @Test
    public void runIdStableForProcess() {
        DiagnosticMemoryRing ring = DiagnosticMemoryRing.getInstance();
        ring.record(DiagnosticLevel.INFO, "test", "evt", null);
        String runId = ring.runId();
        ring.record(DiagnosticLevel.INFO, "test", "evt2", null);
        assertEquals(runId, ring.runId());
        assertEquals(runId, ring.snapshot().get(0).runId);
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
