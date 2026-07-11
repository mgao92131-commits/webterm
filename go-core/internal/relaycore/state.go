package relaycore

type ConnectionState string

const (
	ConnectionConnecting ConnectionState = "connecting"
	ConnectionActive     ConnectionState = "active"
	ConnectionDraining   ConnectionState = "draining"
	ConnectionClosed     ConnectionState = "closed"
)

type ClientKind string

const (
	ClientKindManager   ClientKind = "manager"
	ClientKindTerminal  ClientKind = "terminal"
	ClientKindAPIStream ClientKind = "api-stream"
	ClientKindDebug     ClientKind = "debug"
)

type StreamKind string

const (
	StreamKindHTTP      StreamKind = "http"
	StreamKindWebSocket StreamKind = "websocket"
	StreamKindTerminal  StreamKind = "terminal"
)

type StreamState string

const (
	StreamPending    StreamState = "pending"
	StreamOpen       StreamState = "open"
	StreamHalfClosed StreamState = "half-closed"
	StreamClosing    StreamState = "closing"
	StreamClosed     StreamState = "closed"
	StreamTimeout    StreamState = "timeout"
	StreamFailed     StreamState = "failed"
)

func (state StreamState) Terminal() bool {
	return state == StreamClosed || state == StreamTimeout || state == StreamFailed
}

type EventType string

const (
	EventDeviceOnline       EventType = "device.online"
	EventDeviceOffline      EventType = "device.offline"
	EventDeviceUpdated      EventType = "device.updated"
	EventAgentConnected     EventType = "agent.connected"
	EventAgentDisconnected  EventType = "agent.disconnected"
	EventClientConnected    EventType = "client.connected"
	EventClientDisconnected EventType = "client.disconnected"
	EventStreamCreated      EventType = "stream.created"
	EventStreamOpened       EventType = "stream.opened"
	EventStreamClosed       EventType = "stream.closed"
	EventStreamError        EventType = "stream.error"
	EventAuthLogin          EventType = "auth.login"
	EventAuthLogout         EventType = "auth.logout"
	EventAuthFailure        EventType = "auth.failure"
	EventCredentialRotated  EventType = "credential.rotated"
	EventCredentialRevoked  EventType = "credential.revoked"
)
