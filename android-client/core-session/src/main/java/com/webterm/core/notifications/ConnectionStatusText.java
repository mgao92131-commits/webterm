package com.webterm.core.notifications;

/** 持久前台通知（设备连接状态）的纯文案策略，便于 JVM 单测。
 * 标题固定；正文随当前由服务保持在线的设备数变化。 */
public final class ConnectionStatusText {
    private ConnectionStatusText() {}

    public static String title() {
        return "WebTerm 设备在线";
    }

    /** @param onlineCount 当前由 WebTermDeviceService 保持在线的设备数（>=0）。 */
    public static String contentText(int onlineCount) {
        if (onlineCount <= 0) {
            return "等待接收文件与代理通知";
        }
        if (onlineCount == 1) {
            return "保持 1 台设备在线";
        }
        return "保持 " + onlineCount + " 台设备在线";
    }
}
