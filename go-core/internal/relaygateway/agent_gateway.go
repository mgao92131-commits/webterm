package relaygateway

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"sync"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/relayrouter"
	"webterm/go-core/internal/relaystore"
)

const (
	AgentRegisterMessage   = "agent.register"
	AgentRegisteredMessage = "agent.registered"
	AgentErrorMessage      = "agent.error"
	webSocketReadLimit     = 8 << 20 // 8 MiB per message
	agentPlaneRealtime     = "realtime"
	agentPlaneBulk         = "bulk"
)

// Agent 注册错误码（稳定枚举）。Agent 据此判断错误分类与重试策略，绝不依赖
// 自由文本 message。新增错误码时同步更新 Agent 端 mapRegisterErrorCode。
const (
	AgentErrInvalidCredential = "invalid_credential"
	AgentErrRealtimeRequired  = "realtime_required"
	AgentErrDeviceDisabled    = "device_disabled"
	AgentErrProtocolMismatch  = "protocol_mismatch"
	AgentErrServerBusy        = "server_busy"
	AgentErrInternalError     = "internal_error"
)

// AgentRegisterError 是结构化的注册拒绝响应。Message 仅供服务端日志与兼容旧
// 客户端，Agent 不据此判断逻辑。
type AgentRegisterError struct {
	Type      string `json:"type"`
	Code      string `json:"code"`
	Retryable bool   `json:"retryable"`
	Message   string `json:"message,omitempty"`
}

// agentErrorRetryable 给出每个注册错误码的默认可重试性：永久性配置/协议/凭据
// 错误不可重试，服务端临时错误可重试。
func agentErrorRetryable(code string) bool {
	switch code {
	case AgentErrInvalidCredential, AgentErrDeviceDisabled, AgentErrProtocolMismatch:
		return false
	default:
		return true
	}
}

// registerError 让 readRegister 在拒绝注册时携带稳定错误码。
type registerError struct {
	code string
	msg  string
}

func (e *registerError) Error() string { return e.msg }

type agentDataRegistry interface {
	RegisterAgentDataConnection(deviceID string, sender relayrouter.AgentSender) bool
	RemoveAgentDataConnection(deviceID string, sender relayrouter.AgentSender)
}

type AgentGateway struct {
	store             relaystore.GatewayStore
	registry          relayrouter.AgentRegistry
	streams           relayrouter.StreamController
	events            *relaycore.EventBus
	heartbeatInterval time.Duration
	heartbeatTimeout  time.Duration
}

func NewAgentGateway(store relaystore.GatewayStore, registry relayrouter.AgentRegistry, streams relayrouter.StreamController) *AgentGateway {
	return newAgentGateway(store, registry, streams, nil)
}

func NewAgentGatewayWithEvents(store relaystore.GatewayStore, registry relayrouter.AgentRegistry, streams relayrouter.StreamController, events *relaycore.EventBus) *AgentGateway {
	return newAgentGateway(store, registry, streams, events)
}

func newAgentGateway(store relaystore.GatewayStore, registry relayrouter.AgentRegistry, streams relayrouter.StreamController, events *relaycore.EventBus) *AgentGateway {
	return &AgentGateway{
		store:             store,
		registry:          registry,
		streams:           streams,
		events:            events,
		heartbeatInterval: 15 * time.Second,
		heartbeatTimeout:  5 * time.Second,
	}
}

func (gateway *AgentGateway) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		CompressionMode: websocket.CompressionNoContextTakeover,
	})
	if err != nil {
		return
	}
	conn.SetReadLimit(webSocketReadLimit)
	defer conn.Close(websocket.StatusNormalClosure, "")
	gateway.handleConnection(r.Context(), conn)
}

func (gateway *AgentGateway) handleConnection(ctx context.Context, conn *websocket.Conn) {
	device, req, err := gateway.readRegister(ctx, conn)
	if err != nil {
		code := AgentErrInternalError
		var regErr *registerError
		if errors.As(err, &regErr) {
			code = regErr.code
		}
		_ = writeAgentRegisterError(ctx, conn, code)
		_ = conn.Close(websocket.StatusPolicyViolation, err.Error())
		return
	}

	now := time.Now().UTC()
	_ = gateway.store.TouchDevice(device.ID, now)
	connectionID := "agent:" + device.ID + ":" + now.Format("20060102150405.000000000")
	sender := newWebSocketAgentSender(conn)
	senderCtx, stopSender := context.WithCancel(ctx)
	defer stopSender()
	defer sender.Close()
	go sender.Run(senderCtx)
	if req.Plane == agentPlaneBulk {
		dataRegistry, ok := gateway.registry.(agentDataRegistry)
		if !ok || !dataRegistry.RegisterAgentDataConnection(device.ID, sender) {
			_ = writeAgentRegisterError(ctx, conn, AgentErrRealtimeRequired)
			return
		}
		defer dataRegistry.RemoveAgentDataConnection(device.ID, sender)
		if err := writeAgentJSON(ctx, conn, map[string]any{
			"type": AgentRegisteredMessage, "deviceId": device.ID,
			"deviceName": firstNonEmpty(req.DeviceName, device.Name), "plane": agentPlaneBulk,
		}); err != nil {
			return
		}
		heartbeatCtx, stopHeartbeat := context.WithCancel(ctx)
		defer stopHeartbeat()
		go gateway.runHeartbeat(heartbeatCtx, conn)
		gateway.readAgentFrames(ctx, conn)
		return
	}
	presence := relaycore.DevicePresence{
		UserID:            device.UserID,
		DeviceID:          device.ID,
		DeviceName:        firstNonEmpty(req.DeviceName, device.Name),
		AgentConnectionID: connectionID,
		Online:            true,
		ConnectedAt:       now,
		LastSeenAt:        now,
	}
	gateway.registry.RegisterAgentConnection(presence, sender)
	gateway.publish(relaycore.Event{
		Type:     relaycore.EventDeviceOnline,
		UserID:   device.UserID,
		DeviceID: device.ID,
		Payload: map[string]any{
			"deviceName":        presence.DeviceName,
			"agentConnectionId": connectionID,
		},
	})
	defer func() {
		gateway.registry.RemoveAgentConnection(device.ID, connectionID)
		if gateway.streams != nil {
			gateway.streams.CancelByDevice(device.ID, "agent disconnected")
		}
		gateway.publish(relaycore.Event{
			Type:     relaycore.EventDeviceOffline,
			UserID:   device.UserID,
			DeviceID: device.ID,
			Payload: map[string]any{
				"reason":            "agent disconnected",
				"agentConnectionId": connectionID,
			},
		})
	}()

	if err := writeAgentJSON(ctx, conn, map[string]any{
		"type":       AgentRegisteredMessage,
		"deviceId":   device.ID,
		"deviceName": firstNonEmpty(req.DeviceName, device.Name),
	}); err != nil {
		return
	}
	heartbeatCtx, stopHeartbeat := context.WithCancel(ctx)
	defer stopHeartbeat()
	go gateway.runHeartbeat(heartbeatCtx, conn)

	gateway.readAgentFrames(ctx, conn)
}

func (gateway *AgentGateway) readAgentFrames(ctx context.Context, conn *websocket.Conn) {
	for {
		messageType, data, err := conn.Read(ctx)
		if err != nil {
			return
		}
		if messageType == websocket.MessageBinary {
			frame, err := relaycore.DecodeFrame(data)
			if err == nil && gateway.streams != nil {
				gateway.streams.HandleAgentFrame(frame)
			}
		}
	}
}

func (gateway *AgentGateway) runHeartbeat(ctx context.Context, conn *websocket.Conn) {
	if gateway.heartbeatInterval <= 0 {
		return
	}
	ticker := time.NewTicker(gateway.heartbeatInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			pingCtx, cancel := context.WithTimeout(ctx, gateway.heartbeatTimeout)
			err := conn.Ping(pingCtx)
			cancel()
			if err != nil {
				_ = conn.Close(websocket.StatusPolicyViolation, "agent heartbeat failed")
				return
			}
		}
	}
}

func (gateway *AgentGateway) publish(event relaycore.Event) {
	if gateway.events != nil {
		gateway.events.Publish(event)
	}
}

type websocketAgentSender struct {
	conn   *websocket.Conn
	queue  chan []byte
	closed chan struct{}
	once   sync.Once
}

func newWebSocketAgentSender(conn *websocket.Conn) *websocketAgentSender {
	return &websocketAgentSender{
		conn:   conn,
		queue:  make(chan []byte, 256),
		closed: make(chan struct{}),
	}
}

func (sender *websocketAgentSender) SendFrame(ctx context.Context, frame relaycore.Frame) error {
	data, err := relaycore.EncodeFrame(frame)
	if err != nil {
		return err
	}
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-sender.closed:
		return relaycore.ErrConnectionClosed
	case sender.queue <- data:
		return nil
	default:
		return relaycore.ErrBackpressure
	}
}

func (sender *websocketAgentSender) Run(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case <-sender.closed:
			return
		case data := <-sender.queue:
			writeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
			err := sender.conn.Write(writeCtx, websocket.MessageBinary, data)
			cancel()
			if err != nil {
				_ = sender.conn.Close(websocket.StatusInternalError, "agent write failed")
				return
			}
		}
	}
}

func (sender *websocketAgentSender) Close() {
	sender.once.Do(func() {
		close(sender.closed)
	})
}

type registerRequest struct {
	Type       string `json:"type"`
	Credential string `json:"credential"`
	DeviceName string `json:"deviceName"`
	Plane      string `json:"plane"`
}

func (gateway *AgentGateway) readRegister(ctx context.Context, conn *websocket.Conn) (relaystore.Device, registerRequest, error) {
	messageType, data, err := conn.Read(ctx)
	if err != nil {
		return relaystore.Device{}, registerRequest{}, err
	}
	if messageType != websocket.MessageText {
		return relaystore.Device{}, registerRequest{}, &registerError{AgentErrProtocolMismatch, "agent register must be a text message"}
	}
	var req registerRequest
	if err := json.Unmarshal(data, &req); err != nil {
		return relaystore.Device{}, registerRequest{}, &registerError{AgentErrProtocolMismatch, "invalid agent register json"}
	}
	if req.Type != AgentRegisterMessage {
		return relaystore.Device{}, registerRequest{}, &registerError{AgentErrProtocolMismatch, "first agent message must be agent.register"}
	}
	if req.Credential == "" {
		return relaystore.Device{}, registerRequest{}, &registerError{AgentErrInvalidCredential, "agent credential is required"}
	}
	if req.Plane == "" {
		req.Plane = agentPlaneRealtime
	}
	if req.Plane != agentPlaneRealtime && req.Plane != agentPlaneBulk {
		return relaystore.Device{}, registerRequest{}, &registerError{AgentErrProtocolMismatch, "invalid agent plane"}
	}
	device, err := gateway.store.FindDeviceByCredential(req.Credential)
	if err != nil {
		if errors.Is(err, relaystore.ErrDeviceDisabled) {
			return relaystore.Device{}, registerRequest{}, &registerError{AgentErrDeviceDisabled, "device disabled"}
		}
		return relaystore.Device{}, registerRequest{}, &registerError{AgentErrInvalidCredential, "invalid agent credential"}
	}
	return device, req, nil
}

func writeAgentJSON(ctx context.Context, conn *websocket.Conn, value any) error {
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}
	writeCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	return conn.Write(writeCtx, websocket.MessageText, data)
}

// writeAgentRegisterError 写出结构化注册拒绝响应（code + retryable）。
func writeAgentRegisterError(ctx context.Context, conn *websocket.Conn, code string) error {
	return writeAgentJSON(ctx, conn, AgentRegisterError{
		Type:      AgentErrorMessage,
		Code:      code,
		Retryable: agentErrorRetryable(code),
	})
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}
	return ""
}
