package com.webterm.core.cache;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class CachedSessionMapper {
    private CachedSessionMapper() {}

    public static JSONArray toSessions(java.util.List<TerminalDiskCache.Metadata> metadataList) {
        JSONArray sessions = new JSONArray();
        if (metadataList == null) return sessions;
        for (TerminalDiskCache.Metadata meta : metadataList) {
            JSONObject session = new JSONObject();
            try {
                session.put("id", meta.sessionId);
                session.put("instanceId", meta.instanceId);
                session.put("name", meta.sessionName);
                session.put("termTitle", meta.termTitle);
                session.put("createdAt", meta.createdAt);
                session.put("cwd", meta.cwd);
                session.put("cols", meta.columns);
                session.put("rows", meta.rows);
                sessions.put(session);
            } catch (JSONException ignored) {
            }
        }
        return sessions;
    }
}
