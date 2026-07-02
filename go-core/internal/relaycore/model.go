package relaycore

import "time"

type AgentConnection struct {
	ID              string
	UserID          string
	DeviceID        string
	DeviceName      string
	State           ConnectionState
	ActiveStreams   int
	BytesIn         uint64
	BytesOut        uint64
	ConnectedAt     time.Time
	LastSeenAt      time.Time
	CloseReason     string
	LastError       string
	LastHeartbeatAt time.Time
}

type ClientConnection struct {
	ID             string
	UserID         string
	Kind           ClientKind
	State          ConnectionState
	BytesIn        uint64
	BytesOut       uint64
	ConnectedAt    time.Time
	LastActivityAt time.Time
	CloseReason    string
	LastError      string
}

type DevicePresence struct {
	UserID            string
	DeviceID          string
	DeviceName        string
	AgentConnectionID string
	Online            bool
	ConnectedAt       time.Time
	LastSeenAt        time.Time
}

type Stream struct {
	ID                 string
	Kind               StreamKind
	UserID             string
	DeviceID           string
	ClientConnectionID string
	AgentConnectionID  string
	State              StreamState
	Route              StreamRoute
	Metadata           map[string]string
	Deadline           time.Time
	MaxPendingBytes    int64
	MaxPendingMessages int
	BytesIn            uint64
	BytesOut           uint64
	CreatedAt          time.Time
	LastActivityAt     time.Time
	CloseReason        string
	LastError          string
}

type StreamStats struct {
	BackpressureTotal uint64
	ActiveByKind      map[StreamKind]int
}

type StreamRoute struct {
	Method      string
	Path        string
	Query       string
	Subprotocol string
}

type Event struct {
	ID       string         `json:"id"`
	Type     EventType      `json:"type"`
	UserID   string         `json:"userId,omitempty"`
	DeviceID string         `json:"deviceId,omitempty"`
	StreamID string         `json:"streamId,omitempty"`
	Payload  map[string]any `json:"payload,omitempty"`
	At       time.Time      `json:"at"`
}
