package com.webterm.core.cache;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

public class TerminalDiskCacheCwdTest {
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
}
