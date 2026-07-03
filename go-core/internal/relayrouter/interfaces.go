package relayrouter

import (
	"time"

	"webterm/go-core/internal/relaycore"
)

// StreamController manages stream lifecycle.
type StreamController interface {
	CreateStream(kind relaycore.StreamKind, route relaycore.StreamRoute, userID, deviceID, agentConnectionID string, timeout time.Duration) StreamHandle
	Open(streamID string) bool
	AttachClient(streamID, clientID string) bool
	RecordClientFrame(frame relaycore.Frame) bool
	HandleAgentFrame(frame relaycore.Frame) bool
	CancelByDevice(deviceID, reason string)
	CancelByClient(clientID, reason string) int
	CancelExpired(now time.Time) int
	FindActiveStream(kind relaycore.StreamKind, userID, deviceID string) (relaycore.Stream, bool)
	Snapshot() []relaycore.Stream
	Stats() relaycore.StreamStats
}

// AgentRegistry manages agent presence and message routing.
type AgentRegistry interface {
	RegisterAgentConnection(presence relaycore.DevicePresence, sender AgentSender)
	RemoveAgent(deviceID string)
	GetAgentForUser(userID, deviceID string) (relaycore.DevicePresence, bool)
	GetSenderForUser(userID, deviceID string) (relaycore.DevicePresence, AgentSender, bool)
	ListPresence(userID string) []relaycore.DevicePresence
	Snapshot() []relaycore.DevicePresence
}
