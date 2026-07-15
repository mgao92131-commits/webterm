package protocol

// CLICommand 是 webterm CLI 主动发起的命令请求。
// 与 HookEvent（shell hook 状态上报）不同，CLICommand 是命令-响应模型，
// Agent 需要返回一个或多个 CLIResponse。
type CLICommand struct {
	Kind       string `json:"kind"`                  // 固定为 "command"
	Type       string `json:"type"`                  // 命令类型，例如 "download"
	SessionID  string `json:"session_id,omitempty"`  // 可选，若为空则通过 PID 解析
	PID        int    `json:"pid,omitempty"`         // CLI 的父进程 PID（通常是 Shell PID），用于解析 session
	CWD        string `json:"cwd,omitempty"`         // 发起命令时的当前工作目录
	FilePath   string `json:"file_path,omitempty"`   // 命令相关文件路径，例如下载目标
	Device     string `json:"device,omitempty"`      // Android 接收端名称、短 ID、完整 ID 或 recent
	OnlineOnly bool   `json:"online_only,omitempty"` // devices 命令仅列在线设备
	Timestamp  int64  `json:"timestamp"`
}

// CLIResponse 是 Agent 对 CLICommand 的响应。
// 一个命令可能产生多次响应（例如 download 的 preparing/started/progress/complete）。
type CLIResponse struct {
	Kind             string             `json:"kind"`                  // 固定为 "response"
	Type             string             `json:"type"`                  // 响应类型，例如 "download_status"
	DownloadID       string             `json:"download_id,omitempty"` // 任务 ID
	Status           string             `json:"status,omitempty"`      // preparing | started | progress | complete | failed
	FilePath         string             `json:"file_path,omitempty"`   // 文件名
	BytesTransferred int64              `json:"bytes_transferred,omitempty"`
	TotalBytes       int64              `json:"total_bytes,omitempty"`
	Error            string             `json:"error,omitempty"` // 失败原因（英文内部码，CLI 映射为中文）
	TargetDevice     *DeviceClientInfo  `json:"target_device,omitempty"`
	Devices          []DeviceClientInfo `json:"devices,omitempty"`
	Timestamp        int64              `json:"timestamp"`
}

// DeviceClientInfo 是 Agent 已识别的 Android 接收端公开信息。
// ID 是稳定安装实例 ID；连接用的 Relay stream ID 不对 CLI 暴露。
type DeviceClientInfo struct {
	ID           string   `json:"id"`
	Name         string   `json:"name"`
	Kind         string   `json:"kind"`
	Capabilities []string `json:"capabilities,omitempty"`
	Online       bool     `json:"online"`
	LastActiveAt int64    `json:"last_active_at,omitempty"`
	ConnectedAt  int64    `json:"connected_at,omitempty"`
}
