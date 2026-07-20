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
