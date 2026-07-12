// Package agentnotify 把 PC 侧解析后的 Agent Hook 事件转换为设备级 mux control
// 消息 agent_notification，经与 file_send 共用的设备连接下发到 Android。
package agentnotify

const (
	// TypeAgentNotification 是 Go→Android 的设备级通知消息类型。
	TypeAgentNotification = "agent_notification"
	// TypeAgentAck 是 Android→Go 的确认消息类型。
	TypeAgentAck = "agent_notification.ack"

	// importance 三档：alert（等审批/失败）| normal（任务完成）| quiet（运行中/会话结束）。
	ImportanceAlert  = "alert"
	ImportanceNormal = "normal"
	ImportanceQuiet  = "quiet"
)
