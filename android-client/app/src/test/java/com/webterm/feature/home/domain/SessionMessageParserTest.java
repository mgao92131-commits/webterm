package com.webterm.feature.home.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class SessionMessageParserTest {
    @Test
    public void dispatchMessageDeliversSessionTitleUpdates() {
        RecordingListener listener = new RecordingListener();

        SessionMessageParser.dispatchMessage(
            "{\"type\":\"session\",\"data\":{\"id\":\"mac:s1\",\"termTitle\":\"vim README.md\",\"cwd\":\"/tmp\"}}",
            listener
        );

        assertNotNull(listener.session);
        assertEquals("mac:s1", listener.session.optString("id"));
        assertEquals("vim README.md", listener.session.optString("termTitle"));
        assertEquals("/tmp", listener.session.optString("cwd"));
    }

    @Test
    public void dispatchMessageDeliversSessionListsWithTitles() {
        RecordingListener listener = new RecordingListener();

        SessionMessageParser.dispatchMessage(
            "{\"type\":\"sessions\",\"data\":[{\"id\":\"mac:s1\",\"termTitle\":\"zsh\"},{\"id\":\"mac:s2\",\"termTitle\":\"top\"}]}",
            listener
        );

        assertNotNull(listener.sessions);
        assertEquals(2, listener.sessions.length());
        assertEquals("zsh", listener.sessions.optJSONObject(0).optString("termTitle"));
        assertEquals("top", listener.sessions.optJSONObject(1).optString("termTitle"));
    }

    @Test
    public void dispatchMessagePrefixesRelaySessionIds() {
        RecordingListener listener = new RecordingListener();

        SessionMessageParser.dispatchMessage(
            "{\"type\":\"session\",\"data\":{\"id\":\"s2\",\"termTitle\":\"top\"}}",
            listener,
            "d1"
        );

        assertNotNull(listener.session);
        assertEquals("d1:s2", listener.session.optString("id"));
        assertEquals("top", listener.session.optString("termTitle"));
    }

    @Test
    public void dispatchMessagePrefixesRelaySessionListIds() {
        RecordingListener listener = new RecordingListener();

        SessionMessageParser.dispatchMessage(
            "{\"type\":\"sessions\",\"data\":[{\"id\":\"s1\"},{\"id\":\"s2\"}]}",
            listener,
            "d1"
        );

        assertNotNull(listener.sessions);
        assertEquals("d1:s1", listener.sessions.optJSONObject(0).optString("id"));
        assertEquals("d1:s2", listener.sessions.optJSONObject(1).optString("id"));
    }

    @Test
    public void dispatchMessagePrefixesClosedRelaySessionId() {
        RecordingListener listener = new RecordingListener();

        SessionMessageParser.dispatchMessage(
            "{\"type\":\"session-closed\",\"id\":\"s1\"}",
            listener,
            "d1"
        );

        assertEquals("d1:s1", listener.closedSessionId);
    }

    @Test
    public void dispatchMessageDoesNotDoublePrefixCanonicalSessionId() {
        RecordingListener listener = new RecordingListener();

        SessionMessageParser.dispatchMessage(
            "{\"type\":\"session\",\"data\":{\"id\":\"d1:s1\"}}",
            listener,
            "d1"
        );

        assertNotNull(listener.session);
        assertEquals("d1:s1", listener.session.optString("id"));
    }

    @Test
    public void dispatchMessageDeliversManagerError() {
        RecordingListener listener = new RecordingListener();

        SessionMessageParser.dispatchMessage(
            "{\"type\":\"error\",\"message\":\"temporary failure\"}",
            listener
        );

        assertEquals("temporary failure", listener.errorMessage);
    }

    private static final class RecordingListener implements SessionMessageParser.Listener {
        JSONArray sessions;
        JSONObject session;
        String closedSessionId;
        String errorMessage;

        @Override
        public void onMonitorSessions(JSONArray sessions) {
            this.sessions = sessions;
        }

        @Override
        public void onMonitorSession(JSONObject session) {
            this.session = session;
        }

        @Override
        public void onMonitorSessionClosed(String sessionId) {
            closedSessionId = sessionId;
        }

        @Override
        public void onMonitorError(String errorMsg) {
            errorMessage = errorMsg;
        }
    }
}
