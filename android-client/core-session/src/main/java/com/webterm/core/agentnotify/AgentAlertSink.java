package com.webterm.core.agentnotify;

/** 接收“需要去重后渲染给用户”的 Agent 事件。真正的 NotificationController（里程碑 D 后续）
 * 会实现它；测试用 fake 实现断言。 */
public interface AgentAlertSink {
    void onAlert(String connectionKey, String sessionId, String eventId, String level, String title, String message);
}
