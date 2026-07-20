package protocol

// CLIResponse 是 Agent 对本地 IPC 命令（send/devices 等）的响应。
// 一个命令可能产生多次响应（例如 send 的 preparing/started/progress/complete）。
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
