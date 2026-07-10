package com.webterm.core.notifications;

import com.webterm.core.agentnotify.AgentProtocol;

/** 通知的唯一持有者：决定告警的渠道、分组、优先级与“按 connectionKey+sessionId 替换”
 * 语义，再交给 NotificationRenderer 落到平台。当前覆盖 Agent 告警；传输/连接状态在
 * Phase 7 接入。纯逻辑，便于 JVM 测试。 */
public final class NotificationController {
    public static final int AGENT_ID_BASE = 2000;
    public static final int AGENT_ID_RANGE = 1_000_000;

    private final NotificationRenderer renderer;

    public NotificationController(NotificationRenderer renderer) {
        this.renderer = renderer;
    }

    /** 渲染（或替换）一条 Agent 告警。同一 connectionKey+sessionId 复用同一通知 id。 */
    public void postAgent(String connectionKey, String sessionId, String level, String title, String message) {
        if (renderer == null) return;
        int id = agentNotificationId(connectionKey, sessionId);
        int priority = priorityForLevel(level);
        String resolvedTitle = (title == null || title.isEmpty()) ? defaultTitle(level) : title;
        String resolvedText = message == null ? "" : message;
        NotificationCommand cmd = new NotificationCommand(
            id,
            NotificationChannels.AGENT_ALERTS,
            connectionKey,
            resolvedTitle,
            resolvedText,
            priority,
            /* ongoing */ false,
            /* autoCancel */ true,
            /* onlyAlertOnce */ true,
            connectionKey,
            sessionId);
        renderer.show(cmd);
    }

    public void cancelAgent(String connectionKey, String sessionId) {
        if (renderer == null) return;
        renderer.cancel(agentNotificationId(connectionKey, sessionId));
    }

    static int agentNotificationId(String connectionKey, String sessionId) {
        int hash = (connectionKey + "\n" + sessionId).hashCode() & 0x7fffffff;
        return AGENT_ID_BASE + (hash % AGENT_ID_RANGE);
    }

    static int priorityForLevel(String level) {
        if (AgentProtocol.LEVEL_RUNNING.equals(level)) {
            return NotificationCommand.PRIORITY_LOW;
        }
        if (AgentProtocol.LEVEL_ERROR.equals(level)) {
            return NotificationCommand.PRIORITY_HIGH;
        }
        return NotificationCommand.PRIORITY_DEFAULT;
    }

    private static String defaultTitle(String level) {
        if (AgentProtocol.LEVEL_ERROR.equals(level)) return "Agent 出错";
        if (AgentProtocol.LEVEL_RUNNING.equals(level)) return "Agent 运行中";
        return "Agent 待处理";
    }
}
