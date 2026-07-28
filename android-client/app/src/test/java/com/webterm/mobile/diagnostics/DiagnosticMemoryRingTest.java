package com.webterm.mobile.diagnostics;

import com.webterm.core.contract.diagnostics.DiagnosticLevel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

    @Test
    public void concurrentRecordsHaveUniqueStrictlyIncreasingSeqs() throws InterruptedException {
        DiagnosticMemoryRing ring = DiagnosticMemoryRing.getInstance();
        int threads = 16;
        int perThread = 1000;
        int total = threads * perThread;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        ring.record(DiagnosticLevel.INFO, "test", "evt",
                            Map.of("t", threadId, "i", i));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        List<DiagnosticEntry> snapshot = ring.snapshot();
        assertEquals(DiagnosticMemoryRing.MAX_ENTRIES, snapshot.size());
        long expectedDropped = total - DiagnosticMemoryRing.MAX_ENTRIES;
        assertEquals(expectedDropped, ring.droppedEntryCount());

        long prev = snapshot.get(0).seq - 1;
        for (DiagnosticEntry entry : snapshot) {
            assertEquals(prev + 1, entry.seq);
            prev = entry.seq;
        }
        DiagnosticMemoryRing.RingStats stats = ring.ringStats();
        assertEquals(snapshot.get(0).seq, stats.oldestSeq);
        assertEquals(snapshot.get(snapshot.size() - 1).seq, stats.newestSeq);
        assertEquals(expectedDropped, stats.droppedEntryCount);
        assertEquals(total, stats.newestSeq);
        assertEquals(expectedDropped + 1, stats.oldestSeq);
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
