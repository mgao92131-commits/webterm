package com.webterm.core.notifications;

/** 通知渠道 id 常量（core-session 纯逻辑与 app 端渲染器共用）。 */
public final class NotificationChannels {
    private NotificationChannels() {}

    /** 持久前台通知：设备连接状态（已有，low/min 重要性）。 */
    public static final String DEVICE = "webterm.device";
    /** 文件传输进度/结果通知。 */
    public static final String TRANSFER = "webterm.transfer";
    /** Agent 正常完成；默认重要性，允许声音和横幅。 */
    public static final String AGENT_COMPLETED_V2 = "webterm.agent_completed.v2";
    /** Agent 出错或等待用户处理；高重要性。 */
    public static final String AGENT_ATTENTION_V2 = "webterm.agent_attention.v2";
}
