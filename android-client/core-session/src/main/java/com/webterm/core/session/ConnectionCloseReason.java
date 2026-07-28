package com.webterm.core.session;

/**
 * 物理连接 / Registry 回收的关闭原因。诊断与生命周期路径只允许使用本枚举，
 * 禁止任意字符串作为关闭原因。
 */
public enum ConnectionCloseReason {
    CHANNELS_IDLE,
    RUNTIME_CLOSED,
    RUNTIME_REPLACED,
    CONTROL_LISTENER_REMOVED,
    COOKIE_UPDATED,
    CONNECT_TIMEOUT,
    AUTH_REQUIRED,
    RECONNECT_RESET,
    DEVICE_REMOVED,
    APP_SHUTDOWN
}
