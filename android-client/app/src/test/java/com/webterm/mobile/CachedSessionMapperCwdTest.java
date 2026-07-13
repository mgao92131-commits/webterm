package com.webterm.mobile;

import static org.junit.Assert.assertEquals;

import com.webterm.core.cache.CachedSessionMapper;
import com.webterm.core.cache.TerminalDiskCache;

import org.json.JSONArray;
import org.junit.Test;

import java.util.Collections;

public class CachedSessionMapperCwdTest {
    @Test
    public void toSessionsIncludesCwd() {
        TerminalDiskCache.Metadata meta = new TerminalDiskCache.Metadata();
        meta.sessionId = "s1";
        meta.instanceId = "inst1";
        meta.termTitle = "zsh";
        meta.createdAt = "2026-07-02T00:00:00Z";
        meta.cwd = "/home/user/projects";

        JSONArray sessions = CachedSessionMapper.toSessions(Collections.singletonList(meta));

        assertEquals(1, sessions.length());
        assertEquals("/home/user/projects", sessions.optJSONObject(0).optString("cwd"));
    }

    @Test
    public void toSessionsEmitsEmptyCwdWhenMissing() {
        TerminalDiskCache.Metadata meta = new TerminalDiskCache.Metadata();
        meta.sessionId = "s1";

        JSONArray sessions = CachedSessionMapper.toSessions(Collections.singletonList(meta));

        assertEquals("", sessions.optJSONObject(0).optString("cwd"));
    }
}
