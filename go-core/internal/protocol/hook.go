package protocol

// HookEvent 是 webterm CLI / shell hook 上报给本地 agent 的统一事件结构。
type HookEvent struct {
	Type      string `json:"type"`
	SessionID string `json:"session_id,omitempty"`
	PID       int    `json:"pid,omitempty"`

	Level   string `json:"level,omitempty"`
	Message string `json:"message,omitempty"`
	Source  string `json:"source,omitempty"`

	ShellState string `json:"shell_state,omitempty"`

	CWD         string `json:"cwd,omitempty"`
	LastCommand string `json:"last_command,omitempty"`
	InputKind   string `json:"input_kind,omitempty"` // last_command 的类型：shell / agent_prompt / agent_tool

	// 以下字段用于 Agent 向 Android 客户端推送下载任务通知（MSG_HOOK）。
	FilePath   string `json:"file_path,omitempty"`
	FileName   string `json:"file_name,omitempty"`
	DownloadID string `json:"download_id,omitempty"`
	Status     string `json:"status,omitempty"`
	TotalBytes int64  `json:"total_bytes,omitempty"`
	FileSize   int64  `json:"file_size,omitempty"`

	Timestamp int64 `json:"timestamp"`
}
