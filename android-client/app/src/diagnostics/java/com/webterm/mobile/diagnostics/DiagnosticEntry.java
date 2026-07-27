package com.webterm.mobile.diagnostics;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;
import java.util.TreeMap;

/** 与 Go {@code logs.Entry} 对齐的单条诊断事件。 */
final class DiagnosticEntry {
    final String runId;
    final long seq;
    final String time;
    final String level;
    final String source;
    final String event;
    final Map<String, Object> fields;
    final String message;
    final int encodedSize;

    DiagnosticEntry(String runId, long seq, String time, String level, String source,
                      String event, Map<String, Object> fields, String message, int encodedSize) {
        this.runId = runId;
        this.seq = seq;
        this.time = time;
        this.level = level;
        this.source = source;
        this.event = event;
        this.fields = fields;
        this.message = message;
        this.encodedSize = encodedSize;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        if (runId != null && !runId.isEmpty()) {
            json.put("runId", runId);
        }
        json.put("seq", seq);
        json.put("time", time);
        json.put("level", level);
        json.put("source", source);
        if (event != null && !event.isEmpty()) {
            json.put("event", event);
        }
        if (fields != null && !fields.isEmpty()) {
            JSONObject fieldJson = new JSONObject();
            for (Map.Entry<String, Object> entry : new TreeMap<>(fields).entrySet()) {
                fieldJson.put(entry.getKey(), entry.getValue());
            }
            json.put("fields", fieldJson);
        }
        if (message != null && !message.isEmpty()) {
            json.put("message", message);
        }
        return json;
    }
}
