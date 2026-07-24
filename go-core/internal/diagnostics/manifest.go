package diagnostics

import "time"

// Manifest 描述诊断导出包的元信息（方案 §7）。
type Manifest struct {
	SchemaVersion      int       `json:"schemaVersion"`
	ExportedAt         time.Time `json:"exportedAt"`
	Version            string    `json:"version"`
	GitCommit          string    `json:"gitCommit"`
	GitDirty           bool      `json:"gitDirty"`
	SourceTreeHash     string    `json:"sourceTreeHash"`
	BuildTime          string    `json:"buildTime"`
	BuildVariant       string    `json:"buildVariant"`
	ProtocolSchemaHash string    `json:"protocolSchemaHash"`
	RunID              string    `json:"runId"`
	Platform           string    `json:"platform"`
	Architecture       string    `json:"architecture"`
	// Truncated 为 true 表示事件超出预算，最旧记录已被截断。
	Truncated bool `json:"truncated"`
	// LiveState 为 true 表示 metrics.json/state.json 来自运行中的 Agent；
	// false 表示离线导出，这两份内容不可用。
	LiveState bool `json:"liveState"`
}
