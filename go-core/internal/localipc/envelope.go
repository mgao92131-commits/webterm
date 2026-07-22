package localipc

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
)

const Version = 1

const (
	KindCommand  = "command"
	KindEvent    = "event"
	KindResponse = "response"

	TypeSend          = "send"
	TypeDevices       = "devices"
	TypeNotify        = "notify"
	TypeSessionUpdate = "session_update"
	TypeDiagnostics   = "diagnostics"
)

// diagnostics 命令的子动作。
const (
	DiagnosticsActionSummary = "summary"
	DiagnosticsActionExport  = "export"
	DiagnosticsActionState   = "state"
)

// Envelope is the only wire shape accepted by the local IPC service.
// Payload is deliberately command-specific to keep notification and terminal
// metadata independent from one another.
type Envelope struct {
	Version   int             `json:"version"`
	Kind      string          `json:"kind"`
	Type      string          `json:"type"`
	RequestID string          `json:"request_id,omitempty"`
	Payload   json.RawMessage `json:"payload,omitempty"`
	Error     string          `json:"error,omitempty"`
}

type SendRequest struct {
	FilePath string `json:"file_path"`
	Device   string `json:"device,omitempty"`
	CWD      string `json:"cwd,omitempty"`
}

type DevicesRequest struct {
	OnlineOnly bool `json:"online_only,omitempty"`
}

type Notification struct {
	SessionID  string `json:"session_id,omitempty"`
	PID        int    `json:"pid,omitempty"`
	Importance string `json:"importance"`
	Message    string `json:"message"`
	Source     string `json:"source,omitempty"`
	Timestamp  int64  `json:"timestamp,omitempty"`
}

type SessionUpdate struct {
	SessionID  string `json:"session_id,omitempty"`
	PID        int    `json:"pid,omitempty"`
	ShellState string `json:"shell_state,omitempty"`
	CWD        string `json:"cwd,omitempty"`
	LastInput  string `json:"last_input,omitempty"`
	InputKind  string `json:"input_kind,omitempty"`
	Timestamp  int64  `json:"timestamp,omitempty"`
}

// DiagnosticsRequest 请求运行中 Agent 的诊断摘要或诊断包导出。
// Action 为 summary 或 export；export 时 ExportPath 指定输出目录（空则由 Agent 选择默认位置）。
// IncludePaths 为 true 时恢复完整 session id / termTitle / cwd / ipcEndpoint
// （对应 CLI 的 --include-paths 显式选项；默认脱敏）。
type DiagnosticsRequest struct {
	Action       string `json:"action"`
	ExportPath   string `json:"export_path,omitempty"`
	IncludePaths bool   `json:"include_paths,omitempty"`
}

// DiagnosticsResponse 是 diagnostics 命令的响应。
// summary 动作返回 Summary；export 动作返回生成的 ExportPath。
type DiagnosticsResponse struct {
	Action     string         `json:"action"`
	Summary    map[string]any `json:"summary,omitempty"`
	ExportPath string         `json:"export_path,omitempty"`
}

func DecodePayload(raw json.RawMessage, target any) error {
	if len(raw) == 0 {
		return fmt.Errorf("missing payload")
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return err
	}
	if err := decoder.Decode(&struct{}{}); err != io.EOF {
		return fmt.Errorf("invalid payload")
	}
	return nil
}

func NewRequest(kind, typ, requestID string, payload any) (Envelope, error) {
	raw, err := json.Marshal(payload)
	if err != nil {
		return Envelope{}, err
	}
	return Envelope{Version: Version, Kind: kind, Type: typ, RequestID: requestID, Payload: raw}, nil
}
