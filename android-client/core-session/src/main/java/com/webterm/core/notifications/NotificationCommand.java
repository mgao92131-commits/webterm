package com.webterm.core.notifications;

/** 渲染指令：NotificationController 产出、渲染器消费。优先级常量与 androidx.core
 * NotificationCompat 的 PRIORITY_* 数值对齐，core-session 不直接依赖 Android。 */
public final class NotificationCommand {
    public static final int PRIORITY_LOW = -1;
    public static final int PRIORITY_DEFAULT = 0;
    public static final int PRIORITY_HIGH = 1;

    public final int id;
    public final String channelId;
    public final String groupKey;
    public final String title;
    public final String text;
    public final int priority;
    public final boolean ongoing;
    public final boolean autoCancel;
    public final boolean onlyAlertOnce;
    /** 非空表示 “Open terminal” 动作路由目标。 */
    public final String openConnectionKey;
    public final String openSessionId;

    public NotificationCommand(int id, String channelId, String groupKey, String title, String text,
                               int priority, boolean ongoing, boolean autoCancel, boolean onlyAlertOnce,
                               String openConnectionKey, String openSessionId) {
        this.id = id;
        this.channelId = channelId;
        this.groupKey = groupKey;
        this.title = title;
        this.text = text;
        this.priority = priority;
        this.ongoing = ongoing;
        this.autoCancel = autoCancel;
        this.onlyAlertOnce = onlyAlertOnce;
        this.openConnectionKey = openConnectionKey;
        this.openSessionId = openSessionId;
    }
}
