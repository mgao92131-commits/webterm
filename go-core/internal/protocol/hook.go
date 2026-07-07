package protocol

// HookEvent 是 webterm CLI / shell hook 上报给本地 agent 的统一事件结构。
type HookEvent struct {
	Type      string `json:"type"`
	SessionID string `json:"session_id"`

	Title string `json:"title,omitempty"`
	Body  string `json:"body,omitempty"`
	Level string `json:"level,omitempty"`

	ShellState string `json:"shell_state,omitempty"`
	AgentState string `json:"agent_state,omitempty"`

	CWD         string `json:"cwd,omitempty"`
	LastCommand string `json:"last_command,omitempty"`
	InputKind   string `json:"input_kind,omitempty"` // last_command 的类型：shell / agent_prompt / agent_tool

	Source    string `json:"source,omitempty"`
	Timestamp int64  `json:"timestamp"`
}
