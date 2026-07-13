package com.webterm.core.agentnotify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.webterm.core.filesend.ControlSender;
import com.webterm.core.filesend.ControlSenderLookup;

public class AgentNotificationControllerTest {
    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private static final class FakeClock implements DedupeStore.Clock {
        long now;
        FakeClock(long now) { this.now = now; }
        @Override public long nowMillis() { return now; }
    }

    private static final class FakeSender implements ControlSender {
        final List<JSONObject> sent = Collections.synchronizedList(new ArrayList<>());
        @Override public boolean sendControl(JSONObject msg) { sent.add(msg); return true; }
        List<String> types() {
            List<String> out = new ArrayList<>();
            synchronized (sent) { for (JSONObject m : sent) out.add(m.optString("type")); }
            return out;
        }
    }

    private static final class FakeSink implements AgentAlertSink {
        static final class Alert {
            final String connectionKey, sessionId, eventId, importance, title, message;
            Alert(String c, String s, String e, String i, String t, String m) {
                connectionKey = c; sessionId = s; eventId = e; importance = i; title = t; message = m;
            }
        }
        final List<Alert> alerts = Collections.synchronizedList(new ArrayList<>());
        @Override public void onAlert(String c, String s, String e, String i, String t, String m) {
            alerts.add(new Alert(c, s, e, i, t, m));
        }
    }

    private static JSONObject notif(String eventId, String sessionId, String importance) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", AgentProtocol.TYPE_AGENT_NOTIFICATION);
            o.put("event_id", eventId);
            o.put("session_id", sessionId);
            o.put("importance", importance);
            o.put("title", "t");
            o.put("message", "m");
            return o;
        } catch (org.json.JSONException e) {
            throw new RuntimeException(e);
        }
    }

    private DedupeStore newStore(FakeClock clock) throws Exception {
        return new DedupeStore(tmp.newFile("dedup.json"), DedupeStore.DEFAULT_TTL_MILLIS, DedupeStore.DEFAULT_MAX_ENTRIES, clock);
    }

    @Test
    public void newEventForwardsToSinkAndAcks() throws Exception {
        FakeClock clock = new FakeClock(1000);
        DedupeStore store = newStore(clock);
        FakeSender sender = new FakeSender();
        ControlSenderLookup lookup = key -> sender;
        FakeSink sink = new FakeSink();
        AgentNotificationController ctl = new AgentNotificationController(lookup, sink, store);

        ctl.onNotification("connA", notif("ev1", "sess1", AgentProtocol.IMPORTANCE_ALERT));

        assertEquals(1, sink.alerts.size());
        FakeSink.Alert a = sink.alerts.get(0);
        assertEquals("connA", a.connectionKey);
        assertEquals("sess1", a.sessionId);
        assertEquals("ev1", a.eventId);
        assertEquals(AgentProtocol.IMPORTANCE_ALERT, a.importance);
        assertEquals(AgentProtocol.TYPE_AGENT_ACK, sender.types().get(0));
        assertEquals("ev1", sender.sent.get(0).optString("event_id"));
    }

    @Test
    public void duplicateAcksButDoesNotAlertTwice() throws Exception {
        FakeClock clock = new FakeClock(1000);
        DedupeStore store = newStore(clock);
        FakeSender sender = new FakeSender();
        ControlSenderLookup lookup = key -> sender;
        FakeSink sink = new FakeSink();
        AgentNotificationController ctl = new AgentNotificationController(lookup, sink, store);

        ctl.onNotification("connA", notif("ev1", "sess1", AgentProtocol.IMPORTANCE_QUIET));
        ctl.onNotification("connA", notif("ev1", "sess1", AgentProtocol.IMPORTANCE_QUIET));

        assertEquals(1, sink.alerts.size());
        assertEquals(2, sender.types().size());
    }

    @Test
    public void dedupIsScopedPerConnection() throws Exception {
        FakeClock clock = new FakeClock(1000);
        DedupeStore store = newStore(clock);
        FakeSender sender = new FakeSender();
        ControlSenderLookup lookup = key -> sender;
        FakeSink sink = new FakeSink();
        AgentNotificationController ctl = new AgentNotificationController(lookup, sink, store);

        ctl.onNotification("connA", notif("ev1", "s", AgentProtocol.IMPORTANCE_QUIET));
        ctl.onNotification("connB", notif("ev1", "s", AgentProtocol.IMPORTANCE_QUIET));

        assertEquals(2, sink.alerts.size());
    }

    @Test
    public void missingEventIdIsDropped() throws Exception {
        FakeClock clock = new FakeClock(1000);
        DedupeStore store = newStore(clock);
        FakeSender sender = new FakeSender();
        ControlSenderLookup lookup = key -> sender;
        FakeSink sink = new FakeSink();
        AgentNotificationController ctl = new AgentNotificationController(lookup, sink, store);

        ctl.onNotification("connA", notif("", "s", AgentProtocol.IMPORTANCE_QUIET));

        assertTrue(sink.alerts.isEmpty());
        assertTrue(sender.types().isEmpty());
    }

    @Test
    public void ttlExpiryAllowsReAlert() throws Exception {
        FakeClock clock = new FakeClock(0);
        DedupeStore store = new DedupeStore(tmp.newFile("dedup.json"), 1000, 1024, clock);
        assertFalse(store.seenOrAdd("k"));
        assertTrue(store.seenOrAdd("k"));
        clock.now = 2000;
        assertFalse(store.seenOrAdd("k"));
    }

    @Test
    public void persistsAcrossReload() throws Exception {
        File f = tmp.newFile("dedup.json");
        FakeClock clock = new FakeClock(1000);
        DedupeStore s1 = new DedupeStore(f, DedupeStore.DEFAULT_TTL_MILLIS, 1024, clock);
        assertFalse(s1.seenOrAdd("k"));

        DedupeStore s2 = new DedupeStore(f, DedupeStore.DEFAULT_TTL_MILLIS, 1024, clock);
        assertTrue(s2.seenOrAdd("k"));
    }

    @Test
    public void capacityEvictsOldest() throws Exception {
        FakeClock clock = new FakeClock(1000);
        DedupeStore store = new DedupeStore(tmp.newFile("dedup.json"), DedupeStore.DEFAULT_TTL_MILLIS, 2, clock);
        assertFalse(store.seenOrAdd("a")); // {a}
        assertFalse(store.seenOrAdd("b")); // {a,b}
        assertFalse(store.seenOrAdd("c")); // {b,c} — a 被淘汰
        assertFalse(store.seenOrAdd("a")); // a 已淘汰，重新计入
        assertTrue(store.seenOrAdd("c"));  // c 仍在
    }

    @Test
    public void corruptFileTreatedAsEmpty() throws Exception {
        File f = tmp.newFile("dedup.json");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write("not-json".getBytes(StandardCharsets.UTF_8));
        }
        FakeClock clock = new FakeClock(1000);
        DedupeStore store = new DedupeStore(f, DedupeStore.DEFAULT_TTL_MILLIS, 1024, clock);
        assertFalse(store.seenOrAdd("k"));
    }
}
