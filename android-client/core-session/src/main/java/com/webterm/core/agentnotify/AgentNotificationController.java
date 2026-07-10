package com.webterm.core.agentnotify;

import com.webterm.core.filesend.ControlSender;
import com.webterm.core.filesend.ControlSenderLookup;

import org.json.JSONObject;

/** 消费设备级 agent_notification：按 connectionKey+event_id 持久化去重，
 * 对新事件交给 AgentAlertSink 渲染，并始终回 agent_notification.ack（重复事件也 ack，
 * 以便 Go 端清理 pending 重放状态）。与通知 UI 解耦，便于 JVM 单元测试。 */
public final class AgentNotificationController {
    private final ControlSenderLookup senders;
    private final AgentAlertSink sink;
    private final DedupeStore store;

    public AgentNotificationController(ControlSenderLookup senders, AgentAlertSink sink, DedupeStore store) {
        this.senders = senders;
        this.sink = sink;
        this.store = store;
    }

    public void onNotification(String connectionKey, JSONObject msg) {
        if (msg == null) return;
        String eventId = msg.optString("event_id", "");
        if (eventId.isEmpty()) {
            // 无 event_id 无法去重也无法让 Go 端对应清理，按 malformed 丢弃。
            return;
        }
        String sessionId = msg.optString("session_id", "");
        String level = msg.optString("level", AgentProtocol.LEVEL_IDLE);
        String title = msg.optString("title", "");
        String message = msg.optString("message", "");

        boolean duplicate = store.seenOrAdd(dedupKey(connectionKey, eventId));
        sendAck(connectionKey, eventId);
        if (!duplicate && sink != null) {
            sink.onAlert(connectionKey, sessionId, eventId, level, title, message);
        }
    }

    static String dedupKey(String connectionKey, String eventId) {
        return connectionKey + "\n" + eventId;
    }

    private void sendAck(String connectionKey, String eventId) {
        if (senders == null) return;
        ControlSender sender = senders.senderFor(connectionKey);
        if (sender == null) return;
        JSONObject ack = new JSONObject();
        try {
            ack.put("type", AgentProtocol.TYPE_AGENT_ACK);
            ack.put("event_id", eventId);
        } catch (org.json.JSONException ignored) {
            return;
        }
        sender.sendControl(ack);
    }
}
