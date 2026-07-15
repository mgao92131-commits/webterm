package com.webterm.core.cache;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.webterm.core.config.ServerConfig;

public class TerminalDiskCacheCwdTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void metadataRoundTripsCwd() throws Exception {
        TerminalDiskCache.Metadata meta = new TerminalDiskCache.Metadata();
        meta.baseUrl = "https://example.com";
        meta.sessionId = "s1";
        meta.instanceId = "inst1";
        meta.createdAt = "2026-07-02T00:00:00Z";
        meta.cwd = "/home/user/projects";

        TerminalDiskCache.Metadata restored = TerminalDiskCache.Metadata.fromJson(meta.toJson());

        assertEquals("/home/user/projects", restored.cwd);
    }

    @Test
    public void fromJsonFallsBackToEmptyCwdForOldCache() throws Exception {
        JSONObject json = new JSONObject()
            .put("baseUrl", "https://example.com")
            .put("sessionId", "s1")
            .put("instanceId", "")
            .put("createdAt", "")
            .put("termTitle", "zsh");

        TerminalDiskCache.Metadata restored = TerminalDiskCache.Metadata.fromJson(json);

        assertEquals("", restored.cwd);
    }

    @Test
    public void updateCwdPreservesExistingMetadataAndMatchesRelayLocalId() throws Exception {
        TerminalDiskCache cache = new TerminalDiskCache(temporaryFolder.newFolder("files"));
        TerminalDiskCache.Metadata meta = new TerminalDiskCache.Metadata();
        meta.baseUrl = "https://relay.example.com";
        meta.sessionId = "mac:s1";
        meta.instanceId = "inst1";
        meta.createdAt = "2026-07-02T00:00:00Z";
        meta.termTitle = "zsh";
        meta.cwd = "/old";
        cache.saveMetadataBlocking(meta);
        ServerConfig server = new ServerConfig(
            "relay-device", "Mac", meta.baseUrl, "cookie", "", "",
            false, true, "mac");

        cache.updateCwdBlocking(server, "s1", "/new path");

        TerminalDiskCache.RestoreResult restored = cache.restore(
            meta.baseUrl, meta.sessionId, meta.instanceId, meta.createdAt);
        assertEquals("/new path", restored.metadata.cwd);
        assertEquals("zsh", restored.metadata.termTitle);
        assertEquals("inst1", restored.metadata.instanceId);
        assertEquals("2026-07-02T00:00:00Z", restored.metadata.createdAt);
        cache.shutdown();
    }
}
