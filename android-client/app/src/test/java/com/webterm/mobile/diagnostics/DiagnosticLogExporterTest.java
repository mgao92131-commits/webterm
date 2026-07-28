package com.webterm.mobile.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Handler;

import com.webterm.core.api.WebTermUrls;
import com.webterm.core.contract.diagnostics.DiagnosticIdHasher;
import com.webterm.core.contract.diagnostics.DiagnosticLevel;
import com.webterm.core.contract.diagnostics.Diagnostics;
import com.webterm.core.session.DeviceConnection;
import com.webterm.core.session.DeviceConnectionDiagnosticsRegistry;
import com.webterm.core.session.DeviceConnectionRegistry;
import com.webterm.core.session.traffic.NetworkTrafficStats;
import com.webterm.feature.terminal.domain.TerminalPipelineDiagnosticsRegistry;
import com.webterm.mobile.BuildConfig;
import com.webterm.transport.api.MuxTransport;
import com.webterm.transport.api.TransportFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** DiagnosticLogExporter 的纯逻辑测试：命名并发唯一、processHash 关联、schema v2。 */
public class DiagnosticLogExporterTest {

    @Before
    public void setUp() {
        DiagnosticsMemory.shutdownForTest();
        DiagnosticsMemory.init();
        NetworkTrafficStats.clearAll();
        DeviceConnectionDiagnosticsRegistry.clearForTest();
        TerminalPipelineDiagnosticsRegistry.clearForTest();
    }

    @After
    public void tearDown() {
        NetworkTrafficStats.clearAll();
        DeviceConnectionDiagnosticsRegistry.clearForTest();
        TerminalPipelineDiagnosticsRegistry.clearForTest();
        DiagnosticsMemory.shutdownForTest();
    }

    @Test
    public void concurrentArchiveNamesNeverCollide() throws InterruptedException {
        int threads = 8;
        int perThread = 100;
        Set<String> names = ConcurrentHashMap.newKeySet();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int j = 0; j < perThread; j++) {
                    names.add(DiagnosticLogExporter.newArchiveName());
                }
            });
        }
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(threads * perThread, names.size());
        for (String name : names) {
            assertTrue(name.endsWith(".zip"));
        }
    }

    @Test
    public void diagnosticManifestBuildIdentityIsStableAndPathFree() {
        assertTrue("unknown".equals(BuildConfig.GIT_COMMIT)
                || BuildConfig.GIT_COMMIT.matches("[0-9a-fA-F]{40}"));
        assertTrue("unknown".equals(BuildConfig.SOURCE_TREE_HASH)
                || BuildConfig.SOURCE_TREE_HASH.matches("[0-9a-f]{64}"));
        assertTrue("unknown".equals(BuildConfig.PROTOCOL_SCHEMA_HASH)
                || BuildConfig.PROTOCOL_SCHEMA_HASH.matches("[0-9a-f]{64}"));
        assertEquals("debug", BuildConfig.BUILD_VARIANT_ID);
        String identity = BuildConfig.GIT_COMMIT + BuildConfig.SOURCE_TREE_HASH
                + BuildConfig.BUILD_TIME_UTC + BuildConfig.PROTOCOL_SCHEMA_HASH;
        assertFalse(identity.contains("/Users/"));
        assertFalse(identity.matches(".*[A-Za-z]:\\\\.*"));
    }

    @Test
    public void schemaVersionIsV2AndTrafficSummaryRemoved() {
        assertEquals(2, DiagnosticLogExporter.SCHEMA_VERSION);
        // network-traffic-summary.txt 已删除；导出仅保留结构化 JSON。
        assertFalse(hasDeclaredMethod(DiagnosticLogExporter.class, "buildTrafficSummary"));
    }

    @Test
    public void exportedStatsUseProcessHashAndContainNoRawIdentifiers() throws Exception {
        String server = "https://relay-secret.example.com:8443/";
        String deviceId = "device-raw-777";
        String channelId = "channel-raw-999";
        NetworkTrafficStats.accumulatorForConnection(server, deviceId).recordTx(123);

        String metrics = DiagnosticLogExporter.buildMetricsJson().toString();
        assertFalse(metrics.contains("relay-secret.example.com"));
        assertFalse(metrics.contains(deviceId));
        assertFalse(metrics.contains(channelId));

        String expectedServerHash = DiagnosticIdHasher.processHash(
            WebTermUrls.normalizeBaseUrl(server));
        String expectedDeviceHash = DiagnosticIdHasher.processHash(deviceId);
        assertTrue(metrics.contains("\"serverHash\":\"" + expectedServerHash + "\""));
        assertTrue(metrics.contains("\"deviceHash\":\"" + expectedDeviceHash + "\""));
        assertTrue(metrics.contains("\"terminalCommitApplyLatencyBuckets\":["));
        assertTrue(metrics.contains("\"protobufParseLatencyBuckets\":["));
        assertTrue(metrics.contains("\"mapperLatencyBuckets\":["));
        assertTrue(metrics.contains("\"renderNodeRecordLatencyBuckets\":["));
        assertTrue(metrics.contains("\"outboundQueue\":{"));
        assertTrue(metrics.contains("\"inboundDrops\":{"));
        assertTrue(metrics.contains("\"connectionRecovery\":{"));
        assertTrue(metrics.contains("\"screenPipelineAggregate\":{"));
        assertTrue(metrics.contains("\"historyLoaderAggregate\":{"));

        // 同进程内再次导出，processHash 必须保持一致（可关联）。
        String metricsAgain = DiagnosticLogExporter.buildMetricsJson().toString();
        assertTrue(metricsAgain.contains("\"deviceHash\":\"" + expectedDeviceHash + "\""));
        assertTrue(metricsAgain.contains("\"serverHash\":\"" + expectedServerHash + "\""));
    }

    @Test
    public void stateIncludesEventRingDroppedCountAndEmptyCollections() throws Exception {
        DiagnosticMemoryRing ring = DiagnosticMemoryRing.getInstance();
        for (int i = 0; i < DiagnosticMemoryRing.MAX_ENTRIES + 5; i++) {
            ring.record(DiagnosticLevel.INFO, "test", "evt", java.util.Map.of("i", i));
        }
        JSONObject state = DiagnosticLogExporter.buildStateJson();
        assertEquals(ring.runId(), state.getString("runId"));
        JSONObject eventRing = state.getJSONObject("eventRing");
        assertEquals(DiagnosticMemoryRing.MAX_ENTRIES, eventRing.getInt("entryCount"));
        assertEquals(5L, eventRing.getLong("droppedEntryCount"));
        assertTrue(eventRing.getLong("oldestSeq") > 0L);
        assertTrue(eventRing.getLong("newestSeq") >= eventRing.getLong("oldestSeq"));
        assertFalse(eventRing.getString("oldestAt").isEmpty());
        assertFalse(eventRing.getString("newestAt").isEmpty());
        assertEquals(0, state.getJSONArray("connections").length());
        assertEquals(0, state.getJSONArray("terminalSessions").length());
        assertEquals(0, state.getJSONArray("historyLoaders").length());
    }

    @Test
    public void processHashMatchesAcrossMetricsAndStateWhenConnectionRegistered() throws Exception {
        String deviceId = "device-correlate-1";
        String server = "https://relay-correlate.example.com/";
        String cookie = "webterm_token=secret-cookie-value";
        NetworkTrafficStats.accumulatorForConnection(server, deviceId).recordRx(1);
        String expectedDeviceHash = DiagnosticIdHasher.processHash(deviceId);
        String expectedServerHash = DiagnosticIdHasher.processHash(
            WebTermUrls.normalizeBaseUrl(server));

        Handler handler = synchronousHandler();
        MuxTransport transport = mock(MuxTransport.class);
        TransportFactory factory = (url, c, protocol) -> transport;
        DeviceConnectionRegistry registry = new DeviceConnectionRegistry(handler, factory);
        DeviceConnection connection = registry.forDevice(server, cookie, deviceId);
        try {
            JSONObject metrics = DiagnosticLogExporter.buildMetricsJson();
            JSONObject state = DiagnosticLogExporter.buildStateJson();
            assertTrue(metrics.toString().contains("\"deviceHash\":\"" + expectedDeviceHash + "\""));
            assertTrue(metrics.toString().contains("\"serverHash\":\"" + expectedServerHash + "\""));

            JSONArray connections = state.getJSONArray("connections");
            assertEquals(1, connections.length());
            JSONObject connJson = connections.getJSONObject(0);
            assertEquals(expectedDeviceHash, connJson.getString("deviceHash"));
            assertEquals(expectedServerHash, connJson.getString("serverHash"));
            assertFalse(state.toString().contains(deviceId));
            assertFalse(state.toString().contains("secret-cookie-value"));
            assertFalse(metrics.toString().contains(deviceId));
            assertFalse(metrics.toString().contains(cookie));
        } finally {
            registry.forceRelease(connection);
        }
    }

    @Test
    public void eventRingAndExportedTextContainNoRawCookieAuthorizationOrDeviceId()
            throws Exception {
        String deviceId = "device-raw-privacy-777";
        String cookie = "webterm_token=secret-cookie-value";
        String authorization = "Bearer secret-authorization-token";
        String expectedDeviceHash = DiagnosticIdHasher.processHash(deviceId);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("deviceHash", expectedDeviceHash);
        fields.put("failureKind", "MUX_TEMPORARY");
        fields.put("closeCode", 1001);
        Diagnostics.warn("device_connection", "transport_disconnected", fields);

        StringBuilder eventsText = new StringBuilder();
        for (DiagnosticEntry entry : DiagnosticMemoryRing.getInstance().snapshot()) {
            eventsText.append(entry.toJson().toString()).append('\n');
        }
        String text = eventsText.toString();
        assertTrue(text.contains(expectedDeviceHash));
        assertFalse(text.contains(deviceId));
        assertFalse(text.contains(cookie));
        assertFalse(text.contains("secret-cookie-value"));
        assertFalse(text.contains(authorization));
        assertFalse(text.contains("Authorization"));
        assertFalse(text.contains("Cookie:"));
    }

    private static Handler synchronousHandler() {
        Handler handler = mock(Handler.class);
        when(handler.post(any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return true;
        });
        when(handler.postDelayed(any(Runnable.class), anyLong())).thenReturn(true);
        return handler;
    }

    private static boolean hasDeclaredMethod(Class<?> type, String name) {
        for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
            if (name.equals(method.getName())) {
                return true;
            }
        }
        return false;
    }
}
