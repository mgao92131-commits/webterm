package com.webterm.core.notifications;

/** 平台渲染入口。app 模块基于 NotificationManager/NotificationCompat 实现；
 * 测试用 fake 断言。 */
public interface NotificationRenderer {
    void show(NotificationCommand command);
    void cancel(int id);
}
