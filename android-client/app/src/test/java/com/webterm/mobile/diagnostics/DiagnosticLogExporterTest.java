package com.webterm.mobile.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.webterm.core.contract.diagnostics.DiagnosticIdHasher;
import com.webterm.core.session.traffic.NetworkTrafficStats;
import com.webterm.mobile.BuildConfig;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** DiagnosticLogExporter 的纯逻辑测试：命名并发唯一、历史清理、导出内容脱敏。 */
public class DiagnosticLogExporterTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @After
    public void tearDown() {
        NetworkTrafficStats.clearAll();
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
    public void pruneKeepsFiveNewestArchivesAndRemovesTempFiles() throws IOException {
        File dir = folder.newFolder("diagnostics-export");
        List<File> archives = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            File zip = new File(dir, "webterm-diagnostics-20260101-000000-00" + i + "-abcd" + i + ".zip");
            assertTrue(zip.createNewFile());
            assertTrue(zip.setLastModified(1_000_000L + i * 1000L));
            archives.add(zip);
        }
        File leftoverTmp = new File(dir, "webterm-diagnostics-stale.zip.tmp");
        assertTrue(leftoverTmp.createNewFile());

        DiagnosticLogExporter.pruneOldArchives(dir);

        File[] remaining = dir.listFiles();
        assertEquals(DiagnosticLogExporter.MAX_ARCHIVES, remaining.length);
        assertFalse("残留 .tmp 必须被清理", leftoverTmp.exists());
        // 最旧两个被删，最新 5 个保留。
        assertFalse(archives.get(0).exists());
        assertFalse(archives.get(1).exists());
        for (int i = 2; i < archives.size(); i++) {
            assertTrue(archives.get(i).exists());
        }
    }

    @Test
    public void exportedStatsContainNoRawIdentifiersAndHashesAreStableWithinPackage()
        throws Exception {
        String server = "https://relay-secret.example.com:8443/";
        String deviceId = "device-raw-777";
        String channelId = "channel-raw-999";
        NetworkTrafficStats.accumulatorForConnection(server, deviceId).recordTx(123);

        String salt = DiagnosticIdHasher.randomSalt();
        String summary = DiagnosticLogExporter.buildTrafficSummary(salt);
        String metrics = DiagnosticLogExporter.buildMetricsJson(salt).toString();
        String combined = summary + "\n" + metrics;

        assertFalse(combined.contains("relay-secret.example.com"));
        assertFalse(combined.contains(deviceId));
        assertFalse(combined.contains(channelId));

        // 同包内（同一 salt）summary 与 metrics 中同一标识的 hash 必须一致。
        String summaryServerHash = extract(summary, "serverHash=([0-9a-f]{12})");
        String summaryDeviceHash = extract(summary, "deviceHash=([0-9a-f]{12})");
        assertTrue(metrics.contains("\"serverHash\":\"" + summaryServerHash + "\""));
        assertTrue(metrics.contains("\"deviceHash\":\"" + summaryDeviceHash + "\""));
        assertEquals(summaryServerHash,
            DiagnosticIdHasher.hash(salt, com.webterm.core.api.WebTermUrls.normalizeBaseUrl(server)));
        assertEquals(summaryDeviceHash, DiagnosticIdHasher.hash(salt, deviceId));

        // 换 salt（另一次导出）后 hash 必须不同，跨包不可关联。
        String otherSalt = DiagnosticIdHasher.randomSalt();
        assertFalse(DiagnosticLogExporter.buildTrafficSummary(otherSalt)
            .contains(summaryServerHash));
    }

    @Test
    public void trafficSummaryPointsToGoAgentDiagnosticsCli() {
        NetworkTrafficStats.clearAll();
        String summary = DiagnosticLogExporter.buildTrafficSummary(DiagnosticIdHasher.randomSalt());
        assertTrue(summary.contains("webterm diagnostics summary"));
        assertTrue(summary.contains("webterm diagnostics export"));
        assertTrue(summary.contains("Android-side statistics only"));
    }

    private static String extract(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        assertTrue("expected pattern " + regex + " in:\n" + text, matcher.find());
        return matcher.group(1);
    }
}
