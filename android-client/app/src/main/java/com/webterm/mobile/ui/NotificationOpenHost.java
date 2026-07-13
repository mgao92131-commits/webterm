package com.webterm.mobile.ui;

/** 通知跳转请求的宿主能力：由 MainActivity 实现。
 * AppFlowCoordinator 在通知打开终端到达终态（导航成功或确定找不到设备）后
 * 回调清理 Intent extra，避免旧任务清掉新通知。 */
public interface NotificationOpenHost {
    void clearNotificationOpenRequest(String connectionKey, String sessionId);
}
