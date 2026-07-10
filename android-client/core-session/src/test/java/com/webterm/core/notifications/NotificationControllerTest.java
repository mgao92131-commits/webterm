package com.webterm.core.notifications;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class NotificationControllerTest {

    private static final class FakeRenderer implements NotificationRenderer {
        final List<NotificationCommand> shown = new ArrayList<>();
        final List<Integer> cancelled = new ArrayList<>();
        @Override public void show(NotificationCommand command) { shown.add(command); }
        @Override public void cancel(int id) { cancelled.add(id); }
    }

    @Test
    public void idlePostUsesDefaultPriorityGroupAndAction() {
        FakeRenderer r = new FakeRenderer();
        NotificationController ctl = new NotificationController(r);
        ctl.postAgent("connA", "sess1", "idle", "T", "hello");

        assertEquals(1, r.shown.size());
        NotificationCommand c = r.shown.get(0);
        assertEquals(NotificationChannels.AGENT_ALERTS, c.channelId);
        assertEquals("connA", c.groupKey);
        assertEquals(NotificationCommand.PRIORITY_DEFAULT, c.priority);
        assertEquals("sess1", c.openSessionId);
        assertEquals("connA", c.openConnectionKey);
        assertTrue(c.autoCancel);
        assertTrue(c.onlyAlertOnce);
        assertEquals(NotificationController.agentNotificationId("connA", "sess1"), c.id);
    }

    @Test
    public void runningIsLowPriority() {
        FakeRenderer r = new FakeRenderer();
        new NotificationController(r).postAgent("c", "s", "running", "", "m");
        assertEquals(NotificationCommand.PRIORITY_LOW, r.shown.get(0).priority);
    }

    @Test
    public void errorIsHighPriority() {
        FakeRenderer r = new FakeRenderer();
        new NotificationController(r).postAgent("c", "s", "error", "", "m");
        assertEquals(NotificationCommand.PRIORITY_HIGH, r.shown.get(0).priority);
        // 空标题按级别落到默认标题
        assertEquals("Agent 出错", r.shown.get(0).title);
    }

    @Test
    public void sameConnectionSessionReusesId() {
        FakeRenderer r = new FakeRenderer();
        NotificationController ctl = new NotificationController(r);
        ctl.postAgent("c", "s", "idle", "t1", "m");
        ctl.postAgent("c", "s", "idle", "t2", "m");
        assertEquals(2, r.shown.size());
        assertEquals(r.shown.get(0).id, r.shown.get(1).id);
    }

    @Test
    public void differentSessionGetsDifferentId() {
        FakeRenderer r = new FakeRenderer();
        NotificationController ctl = new NotificationController(r);
        ctl.postAgent("c", "s1", "idle", "t", "m");
        ctl.postAgent("c", "s2", "idle", "t", "m");
        assertNotEquals(r.shown.get(0).id, r.shown.get(1).id);
    }

    @Test
    public void cancelAgentCallsRendererWithSameId() {
        FakeRenderer r = new FakeRenderer();
        NotificationController ctl = new NotificationController(r);
        ctl.cancelAgent("c", "s");
        assertEquals(1, r.cancelled.size());
        assertEquals(Integer.valueOf(NotificationController.agentNotificationId("c", "s")), r.cancelled.get(0));
    }
}
