package com.webterm.core.session;

/**
 * 物理 Mux 主动重建触发原因。结构化诊断只输出本枚举名，人类可读说明仅允许进 Logcat。
 */
public enum TransportReconnectTrigger {
    COOKIE_UPDATED,
    SCREEN_CHANNEL_REBUILD,
    /** 仅 beginSynchronization 中 Hello 本地发送失败路径使用。 */
    SCREEN_HELLO_SEND_FAILED,
    INPUT_CHANNEL_NOT_OPEN,
    CONTROL_CHANNEL_NOT_OPEN,
    SCREEN_CHANNEL_NOT_OPEN,
    OTHER_CHANNEL_NOT_OPEN,
    CONTROL_SEND_REJECTED,
    TUNNEL_SEND_REJECTED,
    SUPERSEDED_CHANNEL_CLOSE_FAILED,
    PAGE_REATTACHED_NOT_READY,
    MANUAL_FORCE_RECONNECT,
    UNKNOWN
}
