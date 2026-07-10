package com.webterm.core.notifications;

/** 通知渠道 id 常量（core-session 纯逻辑与 app 端渲染器共用）。 */
public final class NotificationChannels {
    private NotificationChannels() {}

    /** 持久前台通知：设备连接状态（已有，low/min 重要性）。 */
    public static final String DEVICE = "webterm.device";
    /** 文件传输进度/结果通知。 */
    public static final String TRANSFER = "webterm.transfer";
    /** Agent 告警通道，独立的声音/重要性设置。 */
    public static final String AGENT_ALERTS = "webterm.agent_alerts";
}
