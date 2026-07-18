package com.webterm.core.contract.diagnostics;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DiagnosticsTest {

    private static class TestSink implements DiagnosticSink {
        final List<Record> records = new ArrayList<>();
        boolean throwOnRecord = false;

        static class Record {
            final DiagnosticLevel level;
            final String area;
            final String event;
            final Map<String, ?> fields;
            final String formatted;

            Record(DiagnosticLevel level, String area, String event, Map<String, ?> fields) {
                this.level = level;
                this.area = area;
                this.event = event;
                this.fields = fields;
                this.formatted = DiagnosticFormatter.format(area, event, fields);
            }
        }

        @Override
        public void record(DiagnosticLevel level, String area, String event, Map<String, ?> fields) {
            if (throwOnRecord) {
                throw new RuntimeException("Simulated error in sink");
            }
            synchronized (records) {
                records.add(new Record(level, area, event, fields));
            }
        }
    }

    private TestSink testSink;

    @Before
    public void setUp() {
        testSink = new TestSink();
        Diagnostics.install(testSink);
    }

    @After
    public void tearDown() {
        Diagnostics.reset();
    }

    @Test
    public void testDefaultNoOpNoException() {
        Diagnostics.reset();
        // Should not throw
        Diagnostics.debug("area_noop", "event_noop");
        Diagnostics.info("area_noop", "event_noop", null);
    }

    @Test
    public void testInstallAndReceive() {
        Diagnostics.info("auth", "login_success", Map.of("userId", "123"));
        assertEquals(1, testSink.records.size());
        TestSink.Record record = testSink.records.get(0);
        assertEquals(DiagnosticLevel.INFO, record.level);
        assertEquals("auth", record.area);
        assertEquals("login_success", record.event);
        assertEquals("123", record.fields.get("userId"));
    }

    @Test
    public void testInstallNullRestoresNoOp() {
        Diagnostics.install(null);
        Diagnostics.info("auth", "login_success", Map.of("userId", "123"));
        assertEquals(0, testSink.records.size());
    }

    @Test
    public void testSinkExceptionProtection() {
        testSink.throwOnRecord = true;
        // Should catch internally and not propagate to caller
        Diagnostics.error("system", "database_failure", Map.of("key", "val"));
    }

    @Test
    public void testFieldsNullHandling() {
        Diagnostics.warn("network", "timeout", null);
        assertEquals(1, testSink.records.size());
        TestSink.Record record = testSink.records.get(0);
        assertNotNull(record.fields);
        assertTrue(record.fields.isEmpty());
        assertEquals("area=network event=timeout", record.formatted);
    }

    @Test
    public void testFieldSortingAndOrder() {
        Map<String, Object> fields = Map.of(
            "z_field", "valZ",
            "a_field", "valA",
            "b_field", "valB"
        );
        Diagnostics.info("my_area", "my_event", fields);
        assertEquals(1, testSink.records.size());
        TestSink.Record record = testSink.records.get(0);
        // "area=" and "event=" must be at the very front
        // other fields ordered alphabetically: a_field, b_field, z_field
        assertEquals("area=my_area event=my_event a_field=valA b_field=valB z_field=valZ", record.formatted);
    }

    @Test
    public void testEscapeCharacters() {
        Diagnostics.debug("test", "escape", Map.of("message", "line1\nline2\rline3\tline4"));
        assertEquals(1, testSink.records.size());
        TestSink.Record record = testSink.records.get(0);
        assertEquals("area=test event=escape message=line1\\nline2\\rline3\\tline4", record.formatted);
    }

    @Test
    public void testTruncation() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("a");
        }
        Diagnostics.info("test", "truncate", Map.of("long_field", sb.toString()));
        assertEquals(1, testSink.records.size());
        TestSink.Record record = testSink.records.get(0);
        
        String expectedVal = sb.substring(0, 256) + "...[truncated]";
        assertEquals("area=test event=truncate long_field=" + expectedVal, record.formatted);
    }

    @Test
    public void testRedaction() {
        Map<String, Object> fields = Map.of(
            "password", "123456",
            "access_token", "secret_token",
            "clipboard", "user_clipboard_content",
            "sessionId", "keep_me"
        );
        Diagnostics.info("test", "redact", fields);
        assertEquals(1, testSink.records.size());
        TestSink.Record record = testSink.records.get(0);
        
        // sessionId should be kept, other fields redacted
        assertTrue(record.formatted.contains("sessionId=keep_me"));
        assertTrue(record.formatted.contains("password=[REDACTED]"));
        assertTrue(record.formatted.contains("access_token=[REDACTED]"));
        assertTrue(record.formatted.contains("clipboard=[REDACTED]"));
    }

    @Test
    public void testRedactionCaseAndSeparators() {
        Map<String, Object> fields = Map.of(
            "SET-COOKIE", "cookie_val",
            "Access_Token", "token_val",
            "otp", "1234",
            "terminaltext", "term_text"
        );
        Diagnostics.info("test", "redact_var", fields);
        assertEquals(1, testSink.records.size());
        TestSink.Record record = testSink.records.get(0);
        
        assertTrue(record.formatted.contains("SET_COOKIE=[REDACTED]"));
        assertTrue(record.formatted.contains("Access_Token=[REDACTED]"));
        assertTrue(record.formatted.contains("otp=[REDACTED]"));
        assertTrue(record.formatted.contains("terminaltext=[REDACTED]"));
    }

    @Test
    public void testRequiredTokenVariantsAreRedacted() {
        Map<String, Object> fields = Map.of(
            "transfer_token", "one",
            "deviceToken", "two",
            "webterm_token", "three",
            "relayToken", "four",
            "session-token", "five",
            "refreshToken", "six"
        );
        Diagnostics.info("test", "token_variants", fields);
        String formatted = testSink.records.get(0).formatted;
        assertFalse(formatted.contains("one"));
        assertFalse(formatted.contains("two"));
        assertFalse(formatted.contains("three"));
        assertFalse(formatted.contains("four"));
        assertFalse(formatted.contains("five"));
        assertFalse(formatted.contains("six"));
    }

    @Test
    public void testUnsafeFieldNamesAreNormalizedAndCannotOverrideHeaders() {
        Map<String, Object> fields = new HashMap<>();
        fields.put(null, "ignored");
        fields.put("area", "ignored");
        fields.put("event", "ignored");
        fields.put("line\nbreak/field", "kept");
        fields.put("x".repeat(80), "limited");
        Diagnostics.info("original_area", "original_event", fields);
        String formatted = testSink.records.get(0).formatted;
        assertTrue(formatted.startsWith("area=original_area event=original_event"));
        assertTrue(formatted.contains("line_break_field=kept"));
        assertFalse(formatted.contains("ignored"));
        assertTrue(formatted.contains("x".repeat(64) + "=limited"));
        assertFalse(formatted.contains("x".repeat(65) + "=limited"));
    }

    @Test
    public void testSafeFieldsRetained() {
        Map<String, Object> fields = Map.of(
            "instanceId", "inst_123",
            "clientId", "cli_456",
            "channelId", "chan_789",
            "layoutEpoch", 10,
            "screenRevision", 5,
            "transportGeneration", 2
        );
        Diagnostics.info("test", "safe_fields", fields);
        assertEquals(1, testSink.records.size());
        TestSink.Record record = testSink.records.get(0);
        
        assertTrue(record.formatted.contains("instanceId=inst_123"));
        assertTrue(record.formatted.contains("clientId=cli_456"));
        assertTrue(record.formatted.contains("channelId=chan_789"));
        assertTrue(record.formatted.contains("layoutEpoch=10"));
        assertTrue(record.formatted.contains("screenRevision=5"));
        assertTrue(record.formatted.contains("transportGeneration=2"));
    }

    static class MyComplexObject {
        @Override
        public String toString() {
            return "secret_password_exposed_in_tostring";
        }
    }

    @Test
    public void testComplexObjectAndToStringSecurity() {
        Diagnostics.info("test", "complex", Map.of("info", new MyComplexObject()));
        assertEquals(1, testSink.records.size());
        TestSink.Record record = testSink.records.get(0);
        
        // toString should not be executed for complex objects
        assertFalse(record.formatted.contains("secret_password_exposed_in_tostring"));
        assertTrue(record.formatted.contains("info=[unsupported:MyComplexObject]"));
    }

    enum TestEnum {
        VAL1,
        VAL2
    }

    @Test
    public void testSupportedTypes() {
        Map<String, Object> fields = Map.of(
            "enum_field", TestEnum.VAL1,
            "num_field", 42,
            "bool_field", true
        );
        Diagnostics.info("test", "supported", fields);
        assertEquals(1, testSink.records.size());
        TestSink.Record record = testSink.records.get(0);
        
        assertTrue(record.formatted.contains("bool_field=true"));
        assertTrue(record.formatted.contains("enum_field=VAL1"));
        assertTrue(record.formatted.contains("num_field=42"));
    }

    @Test
    public void testConcurrency() throws InterruptedException {
        int threadsCount = 10;
        int iterations = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadsCount);
        
        for (int i = 0; i < threadsCount; i++) {
            executor.submit(() -> {
                for (int j = 0; j < iterations; j++) {
                    Diagnostics.info("concurrent", "event", Map.of("key", "val"));
                }
            });
        }
        
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(threadsCount * iterations, testSink.records.size());
    }
}
