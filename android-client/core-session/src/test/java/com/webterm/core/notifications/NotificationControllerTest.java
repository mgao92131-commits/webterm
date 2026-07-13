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
    public void normalPostUsesDefaultPriorityGroupAndAction() {
        FakeRenderer r = new FakeRenderer();
        NotificationController ctl = new NotificationController(r);
        ctl.postAgent("connA", "sess1", "normal", "T", "hello");

        assertEquals(1, r.shown.size());
        NotificationCommand c = r.shown.get(0);
        assertEquals(NotificationChannels.AGENT_NORMAL, c.channelId);
        assertEquals("connA", c.groupKey);
        assertEquals(NotificationCommand.PRIORITY_DEFAULT, c.priority);
        assertEquals("sess1", c.openSessionId);
        assertEquals("connA", c.openConnectionKey);
        assertTrue(c.autoCancel);
        assertEquals(false, c.onlyAlertOnce);
        assertEquals(NotificationController.agentNotificationId("connA", "sess1"), c.id);
    }

    @Test
    public void quietIsNotRendered() {
        FakeRenderer r = new FakeRenderer();
        new NotificationController(r).postAgent("c", "s", "quiet", "", "m");
        assertTrue(r.shown.isEmpty());
    }

    @Test
    public void alertIsHighPriorityAlertChannel() {
        FakeRenderer r = new FakeRenderer();
        new NotificationController(r).postAgent("c", "s", "alert", "", "approve");
        assertEquals(NotificationChannels.AGENT_ALERT, r.shown.get(0).channelId);
        assertEquals(NotificationCommand.PRIORITY_HIGH, r.shown.get(0).priority);
        // 空标题按 importance 落到默认标题
        assertEquals("Agent 等待处理", r.shown.get(0).title);
    }

    @Test
    public void normalEmptyTitleFallsBackToDefault() {
        FakeRenderer r = new FakeRenderer();
        new NotificationController(r).postAgent("c", "s", "normal", "", "m");
        assertEquals("Agent 任务完成", r.shown.get(0).title);
    }

    @Test
    public void consecutiveEventsOnSameSessionBothAlert() {
        FakeRenderer r = new FakeRenderer();
        NotificationController ctl = new NotificationController(r);
        ctl.postAgent("c", "s", "normal", "t1", "m1");
        ctl.postAgent("c", "s", "normal", "t2", "m2");
        assertEquals(2, r.shown.size());
        // 同 session 复用同一通知 id，但每条事件都应出声（onlyAlertOnce=false）
        assertEquals(r.shown.get(0).id, r.shown.get(1).id);
        assertEquals(false, r.shown.get(0).onlyAlertOnce);
        assertEquals(false, r.shown.get(1).onlyAlertOnce);
    }

    @Test
    public void differentSessionGetsDifferentId() {
        FakeRenderer r = new FakeRenderer();
        NotificationController ctl = new NotificationController(r);
        ctl.postAgent("c", "s1", "normal", "t", "m");
        ctl.postAgent("c", "s2", "normal", "t", "m");
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

    @Test
    public void uploadAndReceiveIdsDoNotCollideForSameKey() {
        assertNotEquals(
            NotificationController.uploadNotificationId("connA", "sess1"),
            NotificationController.transferNotificationId("connA", "sess1"));
    }

    @Test
    public void uploadProgressIsOngoingLowPriorityWithDirectionalCancelAction() {
        FakeRenderer r = new FakeRenderer();
        NotificationController ctl = new NotificationController(r);
        ctl.postUploadProgress("connA", "sess1", "demo.zip", 50, 200);

        assertEquals(1, r.shown.size());
        NotificationCommand c = r.shown.get(0);
        assertEquals(NotificationChannels.TRANSFER, c.channelId);
        assertEquals("connA", c.groupKey);
        assertEquals(NotificationCommand.PRIORITY_LOW, c.priority);
        assertTrue(c.ongoing);
        assertEquals(false, c.autoCancel);
        assertEquals(25, c.progress);
        assertEquals("正在上传 demo.zip", c.title);
        // 点击通知跳转到对应终端：open 参数来自传入的 connectionKey/sessionId。
        assertEquals("connA", c.openConnectionKey);
        assertEquals("sess1", c.openSessionId);
        // 取消动作带方向与 connectionKey：上传路由到 FileUploadController，不误取消接收。
        assertEquals("sess1", c.cancelTransferId);
        assertEquals(NotificationCommand.DIRECTION_UPLOAD, c.cancelDirection);
        assertEquals("connA", c.cancelConnectionKey);
        assertEquals(NotificationController.uploadNotificationId("connA", "sess1"), c.id);
    }

    @Test
    public void uploadProgressUnknownTotalIsIndeterminate() {
        FakeRenderer r = new FakeRenderer();
        new NotificationController(r).postUploadProgress("c", "s", "f.bin", 10, -1);
        assertEquals(-1, r.shown.get(0).progress);
        assertEquals("10 B", r.shown.get(0).text);
    }

    @Test
    public void uploadTerminalStatesReplaceSameIdAndUseDirectionalTexts() {
        FakeRenderer r = new FakeRenderer();
        NotificationController ctl = new NotificationController(r);
        ctl.postUploadProgress("connA", "sess1", "a.bin", 1, 10);
        ctl.postUploadSucceeded("connA", "sess1", "a.bin", "WebTermUploads/a.bin");
        ctl.postUploadFailed("connA", "sess1", "a.bin", "当前终端目录没有写入权限");
        ctl.postUploadCancelled("connA", "sess1", "a.bin");

        assertEquals(4, r.shown.size());
        int id = r.shown.get(0).id;
        for (NotificationCommand c : r.shown) {
            assertEquals(id, c.id); // 同一上传任务复用同一通知 id
        }
        NotificationCommand saved = r.shown.get(1);
        assertEquals("上传完成 a.bin", saved.title);
        assertEquals("WebTermUploads/a.bin", saved.text);
        assertEquals(NotificationCommand.PRIORITY_DEFAULT, saved.priority);
        NotificationCommand failed = r.shown.get(2);
        assertEquals("上传失败 a.bin", failed.title);
        assertEquals("当前终端目录没有写入权限", failed.text);
        assertEquals(NotificationCommand.PRIORITY_HIGH, failed.priority);
        NotificationCommand cancelled = r.shown.get(3);
        assertEquals("已取消上传 a.bin", cancelled.title);
        assertEquals(NotificationCommand.PRIORITY_DEFAULT, cancelled.priority);
        // 四种上传通知点击都能跳转到对应终端。
        for (NotificationCommand c : r.shown) {
            assertEquals("connA", c.openConnectionKey);
            assertEquals("sess1", c.openSessionId);
        }
        // 终态都不带取消动作、都允许点按消失。
        for (int i = 1; i < 4; i++) {
            NotificationCommand c = r.shown.get(i);
            assertEquals(null, c.cancelTransferId);
            assertEquals(null, c.cancelDirection);
            assertEquals(false, c.ongoing);
            assertTrue(c.autoCancel);
        }
    }

    @Test
    public void receiveCancelActionDefaultsToReceiveDirection() {
        // 回归：接收通知的取消动作方向默认为 receive，行为与文案不变。
        FakeRenderer r = new FakeRenderer();
        NotificationController ctl = new NotificationController(r);
        ctl.postTransferProgress("connA", "t_1", "app.apk", 50, 200);
        NotificationCommand c = r.shown.get(0);
        assertEquals("正在接收 app.apk", c.title);
        assertEquals(NotificationCommand.DIRECTION_RECEIVE, c.cancelDirection);
        assertEquals("connA", c.cancelConnectionKey);
    }

    @Test
    public void cancelUploadCallsRendererWithUploadId() {
        FakeRenderer r = new FakeRenderer();
        NotificationController ctl = new NotificationController(r);
        ctl.cancelUpload("c", "sess1");
        assertEquals(1, r.cancelled.size());
        assertEquals(Integer.valueOf(NotificationController.uploadNotificationId("c", "sess1")), r.cancelled.get(0));
    }
}
