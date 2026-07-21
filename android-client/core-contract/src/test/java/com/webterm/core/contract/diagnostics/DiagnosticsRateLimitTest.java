package com.webterm.core.contract.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Diagnostics 门面级限流集成测试：注入可控时间源的限流器，验证抑制、补报与不限流通道。 */
public class DiagnosticsRateLimitTest {

    private static final long WINDOW = DiagnosticRateLimiter.DEFAULT_WINDOW_MILLIS;

    private static final class TestSink implements DiagnosticSink {
        final List<Map<String, ?>> records = new ArrayList<>();

        @Override
        public void record(DiagnosticLevel level, String area, String event, Map<String, ?> fields) {
            synchronized (records) {
                records.add(fields);
            }
        }
    }

    private AtomicLong clock;
    private TestSink sink;

    @Before
    public void setUp() {
        clock = new AtomicLong(1_000_000L);
        sink = new TestSink();
        Diagnostics.install(sink, new DiagnosticRateLimiter(clock::get, WINDOW));
    }

    @After
    public void tearDown() {
        Diagnostics.reset();
    }

    private int recordCount() {
        synchronized (sink.records) {
            return sink.records.size();
        }
    }

    private Map<String, ?> lastRecord() {
        synchronized (sink.records) {
            return sink.records.get(sink.records.size() - 1);
        }
    }

    @Test
    public void sameEventThousandTimesLandsOnceThenReportsSuppressedCount() {
        for (int i = 0; i < 1000; i++) {
            Diagnostics.warn("net", "flap", Map.of("failureKind", "MUX_TEMPORARY"));
        }
        assertEquals(1, recordCount());
        assertNull("窗口首条不附带 suppressedCount", lastRecord().get("suppressedCount"));

        // 窗口结束后下一条放行，并补报上一窗口被抑制的 999 条。
        clock.addAndGet(WINDOW);
        Diagnostics.warn("net", "flap", Map.of("failureKind", "MUX_TEMPORARY"));
        assertEquals(2, recordCount());
        assertEquals(999L, lastRecord().get("suppressedCount"));
    }

    @Test
    public void unthrottledEventsAreNeverSuppressed() {
        for (int i = 0; i < 100; i++) {
            Diagnostics.errorUnthrottled("net", "session_terminated", Map.of("reason", "AUTH"));
        }
        assertEquals(100, recordCount());
    }

    @Test
    public void nonWhitelistedFieldsDoNotSplitWindows() {
        // closeCode 不在 discriminator 白名单中：取值不同仍属同一窗口，第二条被抑制。
        Diagnostics.info("conn", "closed", Map.of("closeCode", 1000));
        Diagnostics.info("conn", "closed", Map.of("closeCode", 1001));
        assertEquals(1, recordCount());
    }

    @Test
    public void failureKindSplitsWindows() {
        Diagnostics.warn("conn", "failed", Map.of("failureKind", "AUTH_REQUIRED"));
        Diagnostics.warn("conn", "failed", Map.of("failureKind", "MUX_TEMPORARY"));
        assertEquals(2, recordCount());
    }

    @Test
    public void deviceIdSplitsWindowsByHashAndSameDeviceIsSuppressed() {
        Map<String, Object> first = new HashMap<>();
        first.put("deviceId", "device-raw-1");
        Map<String, Object> second = new HashMap<>();
        second.put("deviceId", "device-raw-2");

        Diagnostics.info("conn", "state", first);
        Diagnostics.info("conn", "state", second);
        assertEquals("不同 deviceId 应各自开窗", 2, recordCount());

        Diagnostics.info("conn", "state", first);
        assertEquals("相同 deviceId 窗内应被抑制", 2, recordCount());
    }

    @Test
    public void suppressedCountIsOnlyAttachedOnce() {
        Diagnostics.warn("net", "flap", null);
        Diagnostics.warn("net", "flap", null);

        clock.addAndGet(WINDOW);
        Diagnostics.warn("net", "flap", null);
        assertEquals(1L, lastRecord().get("suppressedCount"));

        // 再过一个窗口，期间无抑制：不应重复补报。
        clock.addAndGet(WINDOW);
        Diagnostics.warn("net", "flap", null);
        assertNull(lastRecord().get("suppressedCount"));
    }

    @Test
    public void installResetsLimiterState() {
        Diagnostics.warn("net", "flap", null);
        Diagnostics.warn("net", "flap", null);
        assertEquals(1, recordCount());

        // 重新安装（注入新限流器）后，相同事件立即放行。
        Diagnostics.install(sink, new DiagnosticRateLimiter(clock::get, WINDOW));
        Diagnostics.warn("net", "flap", null);
        assertEquals(2, recordCount());
    }

    @Test
    public void formattedSuppressedRecordContainsCount() {
        Diagnostics.warn("net", "flap", null);
        Diagnostics.warn("net", "flap", null);
        clock.addAndGet(WINDOW);
        Diagnostics.warn("net", "flap", null);
        String formatted = DiagnosticFormatter.format("net", "flap", lastRecord());
        assertTrue(formatted.contains("suppressedCount=1"));
        assertFalse(formatted.contains("null"));
    }
}
