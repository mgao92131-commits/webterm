package com.webterm.core.notifications;

import com.webterm.core.agentnotify.AgentProtocol;

/** 通知的唯一持有者：决定告警的渠道、分组、优先级与“按 connectionKey+sessionId 替换”
 * 语义，再交给 NotificationRenderer 落到平台。当前覆盖 Agent 告警；传输/连接状态在
 * Phase 7 接入。纯逻辑，便于 JVM 测试。 */
public final class NotificationController {
    public static final int AGENT_ID_BASE = 2000;
    public static final int AGENT_ID_RANGE = 1_000_000;
    public static final int TRANSFER_ID_BASE = 4000;
    public static final int TRANSFER_ID_RANGE = 1_000_000;

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

    /** 接收中进度：ongoing、低优先级、按 connectionKey+transferId 复用同一通知 id。 */
    public void postTransferProgress(String connectionKey, String transferId, String fileName, long bytes, long total) {
        if (renderer == null) return;
        int percent = (total > 0) ? (int) Math.min(100L, bytes * 100L / total) : -1;
        String title = "正在接收 " + safeName(fileName);
        String text = (total > 0) ? percent + "% - " + formatBytes(bytes) + " / " + formatBytes(total) : formatBytes(bytes);
        NotificationCommand cmd = new NotificationCommand(
            transferNotificationId(connectionKey, transferId),
            NotificationChannels.TRANSFER,
            connectionKey,
            title,
            text,
            NotificationCommand.PRIORITY_LOW,
            /* ongoing */ true,
            /* autoCancel */ false,
            /* onlyAlertOnce */ true,
            null, null,
            percent,
            transferId);
        renderer.show(cmd);
    }

    /** 接收成功：替换同 id 的进度通知，点按可消失。 */
    public void postTransferSaved(String connectionKey, String transferId, String fileName, String savedName) {
        if (renderer == null) return;
        NotificationCommand cmd = new NotificationCommand(
            transferNotificationId(connectionKey, transferId),
            NotificationChannels.TRANSFER,
            connectionKey,
            "已保存 " + safeName(fileName),
            "Saved to WebTerm/" + (savedName == null ? safeName(fileName) : savedName),
            NotificationCommand.PRIORITY_DEFAULT,
            /* ongoing */ false,
            /* autoCancel */ true,
            /* onlyAlertOnce */ true,
            null, null, -1, null);
        renderer.show(cmd);
    }

    /** 接收失败：高优先级、点按可消失。 */
    public void postTransferFailed(String connectionKey, String transferId, String fileName, String error) {
        if (renderer == null) return;
        NotificationCommand cmd = new NotificationCommand(
            transferNotificationId(connectionKey, transferId),
            NotificationChannels.TRANSFER,
            connectionKey,
            "接收失败 " + safeName(fileName),
            error == null || error.isEmpty() ? "io_error" : error,
            NotificationCommand.PRIORITY_HIGH,
            /* ongoing */ false,
            /* autoCancel */ true,
            /* onlyAlertOnce */ true,
            null, null, -1, null);
        renderer.show(cmd);
    }

    /** 接收取消：替换进度通知为已取消状态。 */
    public void postTransferCancelled(String connectionKey, String transferId, String fileName) {
        if (renderer == null) return;
        NotificationCommand cmd = new NotificationCommand(
            transferNotificationId(connectionKey, transferId),
            NotificationChannels.TRANSFER,
            connectionKey,
            "已取消 " + safeName(fileName),
            "",
            NotificationCommand.PRIORITY_DEFAULT,
            /* ongoing */ false,
            /* autoCancel */ true,
            /* onlyAlertOnce */ true,
            null, null, -1, null);
        renderer.show(cmd);
    }

    public void cancelTransfer(String connectionKey, String transferId) {
        if (renderer == null) return;
        renderer.cancel(transferNotificationId(connectionKey, transferId));
    }

    static int transferNotificationId(String connectionKey, String transferId) {
        int hash = (connectionKey + "\n" + transferId).hashCode() & 0x7fffffff;
        return TRANSFER_ID_BASE + (hash % TRANSFER_ID_RANGE);
    }

    private static String safeName(String fileName) {
        return (fileName == null || fileName.isEmpty()) ? "file" : fileName;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb);
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0);
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
