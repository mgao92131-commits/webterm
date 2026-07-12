package com.webterm.core.agentnotify;

/** Agent 通知协议常量（与 Go 端 agent_notification 控制消息保持一致）。 */
public final class AgentProtocol {
    private AgentProtocol() {}

    public static final String TYPE_AGENT_NOTIFICATION = "agent_notification";
    public static final String TYPE_AGENT_ACK = "agent_notification.ack";

    public static final String IMPORTANCE_ALERT = "alert";
    public static final String IMPORTANCE_NORMAL = "normal";
    public static final String IMPORTANCE_QUIET = "quiet";
}
