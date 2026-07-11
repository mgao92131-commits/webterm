package com.webterm.core.agentnotify;

/** Agent 通知协议常量（与 Go 端 agent_notification 控制消息保持一致）。 */
public final class AgentProtocol {
    private AgentProtocol() {}

    public static final String TYPE_AGENT_NOTIFICATION = "agent_notification";
    public static final String TYPE_AGENT_ACK = "agent_notification.ack";

    public static final String LEVEL_RUNNING = "running";
    public static final String LEVEL_IDLE = "idle";
    public static final String LEVEL_ERROR = "error";
    public static final String LEVEL_ATTENTION = "attention";
}
