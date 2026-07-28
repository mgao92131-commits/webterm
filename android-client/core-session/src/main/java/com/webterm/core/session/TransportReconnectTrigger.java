package com.webterm.core.session;

/**
 * 物理 Mux 主动重建触发原因。结构化诊断只输出本枚举名，人类可读说明仅允许进 Logcat。
 */
public enum TransportReconnectTrigger {
    COOKIE_UPDATED,
    SCREEN_CHANNEL_REBUILD,
    SCREEN_HELLO_SEND_FAILED,
    CONTROL_SEND_REJECTED,
    TUNNEL_SEND_REJECTED,
    SUPERSEDED_CHANNEL_CLOSE_FAILED,
    PAGE_REATTACHED_NOT_READY,
    MANUAL_FORCE_RECONNECT,
    UNKNOWN
}
