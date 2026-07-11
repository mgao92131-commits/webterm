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
        assertEquals(NotificationChannels.AGENT_COMPLETED_V2, c.channelId);
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
    public void attentionUsesHighPriorityAttentionChannel() {
        FakeRenderer r = new FakeRenderer();
        new NotificationController(r).postAgent("c", "s", "attention", "", "approve");
        assertEquals(NotificationChannels.AGENT_ATTENTION_V2, r.shown.get(0).channelId);
        assertEquals(NotificationCommand.PRIORITY_HIGH, r.shown.get(0).priority);
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

    @Test
    public void transferProgressIsOngoingLowPriorityWithCancelAction() {
        FakeRenderer r = new FakeRenderer();
        NotificationController ctl = new NotificationController(r);
        ctl.postTransferProgress("connA", "t_1", "app.apk", 50, 200);

        assertEquals(1, r.shown.size());
        NotificationCommand c = r.shown.get(0);
        assertEquals(NotificationChannels.TRANSFER, c.channelId);
        assertEquals("connA", c.groupKey);
        assertEquals(NotificationCommand.PRIORITY_LOW, c.priority);
        assertTrue(c.ongoing);
        assertEquals(false, c.autoCancel);
        assertTrue(c.onlyAlertOnce);
        assertEquals(25, c.progress);
        assertEquals("t_1", c.cancelTransferId);
        assertEquals(NotificationController.transferNotificationId("connA", "t_1"), c.id);
    }

    @Test
    public void transferProgressUnknownTotalIsIndeterminate() {
        FakeRenderer r = new FakeRenderer();
        new NotificationController(r).postTransferProgress("c", "t", "f.bin", 10, -1);
        assertEquals(-1, r.shown.get(0).progress);
    }

    @Test
    public void transferTerminalStatesReplaceSameIdAndDropCancelAction() {
        FakeRenderer r = new FakeRenderer();
        NotificationController ctl = new NotificationController(r);
        ctl.postTransferProgress("connA", "t_1", "a.bin", 1, 10);
        ctl.postTransferSaved("connA", "t_1", "a.bin", "a.bin");
        ctl.postTransferFailed("connA", "t_1", "a.bin", "hash_mismatch");
        ctl.postTransferCancelled("connA", "t_1", "a.bin");

        assertEquals(4, r.shown.size());
        int id = r.shown.get(0).id;
        for (NotificationCommand c : r.shown) {
            assertEquals(id, c.id);
        }
        // 终态都不带取消动作、都允许点按消失。
        for (int i = 1; i < 4; i++) {
            NotificationCommand c = r.shown.get(i);
            assertEquals(null, c.cancelTransferId);
            assertEquals(false, c.ongoing);
            assertTrue(c.autoCancel);
            assertEquals(-1, c.progress);
        }
        assertEquals(NotificationCommand.PRIORITY_DEFAULT, r.shown.get(1).priority); // saved
        assertEquals(NotificationCommand.PRIORITY_HIGH, r.shown.get(2).priority);    // failed
        assertEquals(NotificationCommand.PRIORITY_DEFAULT, r.shown.get(3).priority); // cancelled
    }

    @Test
    public void cancelTransferCallsRendererWithSameId() {
        FakeRenderer r = new FakeRenderer();
        NotificationController ctl = new NotificationController(r);
        ctl.cancelTransfer("c", "t_1");
        assertEquals(1, r.cancelled.size());
        assertEquals(Integer.valueOf(NotificationController.transferNotificationId("c", "t_1")), r.cancelled.get(0));
    }

    @Test
    public void transferAndAgentIdsDoNotCollide() {
        assertNotEquals(
            NotificationController.transferNotificationId("c", "x"),
            NotificationController.agentNotificationId("c", "x"));
    }
}
