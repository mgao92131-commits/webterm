package com.webterm.core.notifications;

/** 通知渠道 id 常量（core-session 纯逻辑与 app 端渲染器共用）。 */
public final class NotificationChannels {
    private NotificationChannels() {}

    /** 持久前台通知：设备连接状态（已有，low/min 重要性）。 */
    public static final String DEVICE = "webterm.device";
    /** 文件传输进度/结果通知。 */
    public static final String TRANSFER = "webterm.transfer";
    /** Agent 紧急提醒（等审批/失败）；高重要性，横幅+声音。 */
    public static final String AGENT_ALERT = "webterm.agent.alert";
    /** Agent 任务提醒（任务完成）；默认重要性，声音。 */
    public static final String AGENT_NORMAL = "webterm.agent.normal";
}
