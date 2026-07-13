package com.webterm.core.notifications;

/** 渲染指令：NotificationController 产出、渲染器消费。优先级常量与 androidx.core
 * NotificationCompat 的 PRIORITY_* 数值对齐，core-session 不直接依赖 Android。 */
public final class NotificationCommand {
    public static final int PRIORITY_LOW = -1;
    public static final int PRIORITY_DEFAULT = 0;
    public static final int PRIORITY_HIGH = 1;

    /** 取消动作方向：接收（filesend）与上传（fileupload）必须分开路由，互不误取消。 */
    public static final String DIRECTION_RECEIVE = "receive";
    public static final String DIRECTION_UPLOAD = "upload";

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
    /** 0..100 表示确定进度；-1 表示不显示进度条。 */
    public final int progress;
    /** 非空表示渲染 “取消传输” 动作，点击后回调到 WebTermDeviceService 取消该传输。
     * 接收方向是 transferId；上传方向是 sessionId（配合 cancelConnectionKey 定位任务）。 */
    public final String cancelTransferId;
    /** 取消动作方向：DIRECTION_RECEIVE 路由到 FileReceiveController，
     * DIRECTION_UPLOAD 路由到 FileUploadController；无取消动作时为 null。 */
    public final String cancelDirection;
    /** 取消动作所属 connectionKey（上传方向路由必需；接收方向沿用历史行为可为 null）。 */
    public final String cancelConnectionKey;

    public NotificationCommand(int id, String channelId, String groupKey, String title, String text,
                               int priority, boolean ongoing, boolean autoCancel, boolean onlyAlertOnce,
                               String openConnectionKey, String openSessionId) {
        this(id, channelId, groupKey, title, text, priority, ongoing, autoCancel, onlyAlertOnce,
            openConnectionKey, openSessionId, -1, null);
    }

    public NotificationCommand(int id, String channelId, String groupKey, String title, String text,
                               int priority, boolean ongoing, boolean autoCancel, boolean onlyAlertOnce,
                               String openConnectionKey, String openSessionId,
                               int progress, String cancelTransferId) {
        this(id, channelId, groupKey, title, text, priority, ongoing, autoCancel, onlyAlertOnce,
            openConnectionKey, openSessionId, progress, cancelTransferId,
            cancelTransferId == null ? null : DIRECTION_RECEIVE, groupKey);
    }

    public NotificationCommand(int id, String channelId, String groupKey, String title, String text,
                               int priority, boolean ongoing, boolean autoCancel, boolean onlyAlertOnce,
                               String openConnectionKey, String openSessionId,
                               int progress, String cancelTransferId,
                               String cancelDirection, String cancelConnectionKey) {
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
        this.progress = progress;
        this.cancelTransferId = cancelTransferId;
        this.cancelDirection = cancelDirection;
        this.cancelConnectionKey = cancelConnectionKey;
    }
}
