package relay

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/agentrouter"
	"webterm/go-core/internal/app"
	"webterm/go-core/internal/application"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/diagnostics"
	"webterm/go-core/internal/relaycore"
)

const (
	v2AgentRegisterMessage   = "agent.register"
	v2AgentRegisteredMessage = "agent.registered"
	v2AgentErrorMessage      = "agent.error"
	agentPlaneRealtime       = "realtime"
	agentPlaneBulk           = "bulk"
)

// Relay 注册错误码（与 relaygateway 的 AgentErr* 常量一一对应）。
const (
	v2ErrInvalidCredential = "invalid_credential"
	v2ErrRealtimeRequired  = "realtime_required"
	v2ErrDeviceDisabled    = "device_disabled"
	v2ErrProtocolMismatch  = "protocol_mismatch"
	v2ErrServerBusy        = "server_busy"
	v2ErrInternalError     = "internal_error"
)

// agentRegisterResponse 是 Relay 注册响应的结构化形状。旧 Relay 只有 type/message，
// 缺少 code 时按兼容规则处理（见 mapRegisterErrorCode）。
type agentRegisterResponse struct {
	Type      string `json:"type"`
	DeviceID  string `json:"deviceId"`
	Code      string `json:"code"`
	Retryable *bool  `json:"retryable"`
}

// mapRegisterErrorCode 把 Relay 注册错误码映射为 (RelayErrorKind, retryable)。
// 服务端显式给出的 retryable 优先；缺省时按错误码语义取默认值。未知或缺失 code
// （旧 Relay）映射为 protocol_failed 且默认可重试，避免升级后的 Agent 因旧 Relay
// 的未知错误永久停止；绝不默认映射为 auth_rejected。
func mapRegisterErrorCode(code string, retryable *bool) (app.RelayErrorKind, bool) {
	var kind app.RelayErrorKind
	defaultRetryable := true
	switch code {
	case v2ErrInvalidCredential:
		kind, defaultRetryable = app.RelayErrorAuthRejected, false
	case v2ErrDeviceDisabled:
		kind, defaultRetryable = app.RelayErrorDeviceDisabled, false
	case v2ErrProtocolMismatch:
		kind, defaultRetryable = app.RelayErrorProtocolFailed, false
	case v2ErrRealtimeRequired:
		kind, defaultRetryable = app.RelayErrorProtocolFailed, true
	case v2ErrServerBusy, v2ErrInternalError:
		kind, defaultRetryable = app.RelayErrorServerBusy, true
	default:
		kind, defaultRetryable = app.RelayErrorProtocolFailed, true
	}
	if retryable != nil {
		return kind, *retryable
	}
	return kind, defaultRetryable
}

type V2Client struct {
	cfg     config.RelayConfig
	app     *app.App
	router  *application.SessionRouter
	http    *HTTPProxy
	streams *StreamMultiplexer

	// connected 标记本次 runOnce 的 realtime plane 是否注册成功，用于在 Run 循环
	// 区分“连接成功后断开”与“连接失败”两类计数（仅旁路指标，不影响重连流程）。
	connected atomic.Bool

	writeLocks sync.Map // map[*websocket.Conn]*sync.Mutex，两个 plane 绝不共享写锁
}

func NewV2(cfg config.RelayConfig, appInstance *app.App) *V2Client {
	// SessionRouter 的完整装配（Mux、控制消息、文件传输、通知）统一由 agentrouter
	// 提供，Direct Server 复用同一份装配逻辑。
	router := agentrouter.New(appInstance, "relay")
	client := &V2Client{
		cfg:    cfg,
		app:    appInstance,
		router: router,
	}
	client.http = NewHTTPProxy(router, client)
	client.streams = NewStreamMultiplexer(router, client, appInstance.Logs())
	return client
}

func (client *V2Client) Run(ctx context.Context) error {
	if client.cfg.URL == "" {
		return errors.New("RELAY_URL must be set")
	}
	if client.cfg.Secret == "" {
		return errors.New("RELAY_SECRET must be set")
	}
	delay := time.Second
	for {
		client.connected.Store(false)
		diagnostics.Default.RelayConnectCount.Add(1)
		err := client.runOnce(ctx)
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if client.connected.Load() {
			diagnostics.Default.RelayDisconnectCount.Add(1)
		} else {
			diagnostics.Default.RelayConnectFailureCount.Add(1)
		}
		kind := ClassifyRelayError(err)
		client.app.SetRelayConnected(false, "", kind)

		// 不可重试的永久性错误（凭据无效、设备禁用、协议不兼容）：停止认证循环，
		// 但保持进程与 Local IPC 存活，Relay 状态停留在 disconnected 并保留
		// lastErrorKind，等待运维修正配置后重启 Agent。若此处直接 return，
		// Supervisor 会结束进程，LaunchAgent KeepAlive 又将其拉起，形成重启风暴。
		var relayErr *RelayConnectError
		if errors.As(err, &relayErr) && !relayErr.Retryable {
			client.app.Log("error", "relay",
				fmt.Sprintf("relay registration permanently rejected (%s); stopping reconnect loop until restart", relayErr.Kind))
			<-ctx.Done()
			return ctx.Err()
		}

		diagnostics.Default.RelayReconnectCount.Add(1)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(delay):
		}
		if delay < 10*time.Second {
			delay *= 2
		}
	}
}

func (client *V2Client) runOnce(ctx context.Context) error {
	relayURL, err := agentWebSocketURL(client.cfg.URL)
	if err != nil {
		return markRelayError(app.RelayErrorDialFailed, true, err)
	}
	realtimeConn, _, err := websocket.Dial(ctx, relayURL, &websocket.DialOptions{
		CompressionMode: websocket.CompressionNoContextTakeover,
	})
	if err != nil {
		return markRelayError(app.RelayErrorDialFailed, true, err)
	}
	realtimeConn.SetReadLimit(8 << 20)
	defer realtimeConn.Close(websocket.StatusNormalClosure, "")
	defer client.streams.CloseAllForConnection(realtimeConn)
	defer client.writeLocks.Delete(realtimeConn)

	if err := client.registerV2(ctx, realtimeConn, agentPlaneRealtime); err != nil {
		return err
	}
	bulkConn, _, err := websocket.Dial(ctx, relayURL, nil)
	if err != nil {
		return markRelayError(app.RelayErrorDialFailed, true, fmt.Errorf("connect bulk plane: %w", err))
	}
	bulkConn.SetReadLimit(8 << 20)
	defer bulkConn.Close(websocket.StatusNormalClosure, "")
	defer client.http.CloseAllForConnection(bulkConn)
	defer client.writeLocks.Delete(bulkConn)
	if err := client.registerV2(ctx, bulkConn, agentPlaneBulk); err != nil {
		return fmt.Errorf("register bulk plane: %w", err)
	}

	errCh := make(chan error, 2)
	go func() { errCh <- client.readLoop(ctx, realtimeConn) }()
	go func() { errCh <- client.readLoop(ctx, bulkConn) }()
	return markRelayError(app.RelayErrorConnectionClosed, true, <-errCh)
}

func (client *V2Client) registerV2(ctx context.Context, conn *websocket.Conn, plane string) error {
	if err := writeJSON(ctx, conn, map[string]any{
		"type":       v2AgentRegisterMessage,
		"credential": client.cfg.Secret,
		"deviceName": client.cfg.DeviceName,
		"plane":      plane,
	}); err != nil {
		return markRelayError(app.RelayErrorConnectionClosed, true, err)
	}
	_, data, err := conn.Read(ctx)
	if err != nil {
		return markRelayError(app.RelayErrorConnectionClosed, true, err)
	}
	var response agentRegisterResponse
	if err := json.Unmarshal(data, &response); err != nil {
		return markRelayError(app.RelayErrorProtocolFailed, false, errors.New("bad register response"))
	}
	switch response.Type {
	case v2AgentRegisteredMessage:
		if plane == agentPlaneRealtime {
			client.connected.Store(true)
			client.app.SetRelayConnected(true, response.DeviceID, app.RelayErrorNone)
		}
		return nil
	case v2AgentErrorMessage:
		// 结构化错误码决定分类与重试策略；错误正文只含稳定 code，不含服务端自由文本。
		kind, retryable := mapRegisterErrorCode(response.Code, response.Retryable)
		return markRelayError(kind, retryable, fmt.Errorf("relay register error: code=%s", response.Code))
	default:
		return markRelayError(app.RelayErrorProtocolFailed, false,
			fmt.Errorf("unexpected register response: %s", response.Type))
	}
}

func (client *V2Client) readLoop(ctx context.Context, conn *websocket.Conn) error {
	for {
		messageType, data, err := conn.Read(ctx)
		if err != nil {
			return err
		}
		if messageType != websocket.MessageBinary {
			continue
		}
		frame, err := relaycore.DecodeFrame(data)
		if err != nil {
			continue
		}
		client.handleFrame(ctx, conn, frame)
	}
}

func (client *V2Client) handleFrame(ctx context.Context, conn *websocket.Conn, frame relaycore.Frame) {
	switch frame.Type {
	case relaycore.FrameTypeHTTPHeaders:
		client.http.HandleHTTPHeaders(ctx, conn, frame)
	case relaycore.FrameTypeHTTPChunk:
		client.http.DeliverChunk(frame)
	case relaycore.FrameTypeStreamOpen:
		client.streams.OpenStream(ctx, conn, frame)
	case relaycore.FrameTypeWSText, relaycore.FrameTypeWSBinary:
		client.streams.DeliverWS(frame)
	case relaycore.FrameTypeStreamClose, relaycore.FrameTypeStreamError:
		client.streams.CloseStream(frame.StreamID, false)
		// 同时通知 HTTP 代理：若该 stream 正在流式转发文件，及时中止上游读取。
		client.http.CloseStream(frame.StreamID)
	}
}

func (client *V2Client) writeFrame(ctx context.Context, conn *websocket.Conn, frame relaycore.Frame) {
	data, err := relaycore.EncodeFrame(frame)
	if err != nil {
		return
	}
	writeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	writeMu := client.writeLock(conn)
	writeMu.Lock()
	defer writeMu.Unlock()
	_ = conn.Write(writeCtx, websocket.MessageBinary, data)
}

func (client *V2Client) writeRaw(ctx context.Context, conn *websocket.Conn, data []byte) error {
	writeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	writeMu := client.writeLock(conn)
	writeMu.Lock()
	defer writeMu.Unlock()
	return conn.Write(writeCtx, websocket.MessageBinary, data)
}

func (client *V2Client) writeLock(conn *websocket.Conn) *sync.Mutex {
	lock, _ := client.writeLocks.LoadOrStore(conn, &sync.Mutex{})
	return lock.(*sync.Mutex)
}
