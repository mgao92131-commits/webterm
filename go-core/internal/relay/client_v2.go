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

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/application"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/diagnostics"
	"webterm/go-core/internal/mux"
	"webterm/go-core/internal/relaycore"
)

const (
	v2AgentRegisterMessage   = "agent.register"
	v2AgentRegisteredMessage = "agent.registered"
	v2AgentErrorMessage      = "agent.error"
	agentPlaneRealtime       = "realtime"
	agentPlaneBulk           = "bulk"
)

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
	router := application.NewSessionRouterWithMux(appInstance.Sessions(), mux.MuxServeAdapter, appInstance.Logs())
	router.SetControlHandler(func(ctx context.Context, _ application.MuxSession, msg map[string]any) {
		if appInstance.Logs() != nil {
			appInstance.Logs().Add("debug", "relay", "mux control message type="+stringValue(msg["type"]))
		}
	})
	// 在 SetControlHandler 之后注入，使 file_send.* 优先分发到 FileSendService，
	// 其余控制消息继续落到上面的 debug logger。
	router.SetFileSendService(appInstance.FileSendService())
	// 注入 Relay 上传服务；缺少该注入时上传路由会返回 503。
	router.SetFileUploadService(appInstance.FileUploadService())
	// agent_notification.ack 链到 Dispatcher（清 pending），顺序在 file_send 之后无冲突。
	router.SetAgentNotificationDispatcher(appInstance.AgentNotificationDispatcher())
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
		client.app.SetRelayConnected(false, "", ClassifyRelayError(err))
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
		return markRelayError(app.RelayErrorDialFailed, err)
	}
	realtimeConn, _, err := websocket.Dial(ctx, relayURL, &websocket.DialOptions{
		CompressionMode: websocket.CompressionNoContextTakeover,
	})
	if err != nil {
		return markRelayError(app.RelayErrorDialFailed, err)
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
		return markRelayError(app.RelayErrorDialFailed, fmt.Errorf("connect bulk plane: %w", err))
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
	return markRelayError(app.RelayErrorConnectionClosed, <-errCh)
}

func (client *V2Client) registerV2(ctx context.Context, conn *websocket.Conn, plane string) error {
	if err := writeJSON(ctx, conn, map[string]any{
		"type":       v2AgentRegisterMessage,
		"credential": client.cfg.Secret,
		"deviceName": client.cfg.DeviceName,
		"plane":      plane,
	}); err != nil {
		return markRelayError(app.RelayErrorConnectionClosed, err)
	}
	_, data, err := conn.Read(ctx)
	if err != nil {
		return markRelayError(app.RelayErrorConnectionClosed, err)
	}
	var msg map[string]any
	if err := json.Unmarshal(data, &msg); err != nil {
		return markRelayError(app.RelayErrorProtocolFailed, errors.New("bad register response"))
	}
	switch stringValue(msg["type"]) {
	case v2AgentRegisteredMessage:
		if plane == agentPlaneRealtime {
			client.connected.Store(true)
			client.app.SetRelayConnected(true, stringValue(msg["deviceId"]), app.RelayErrorNone)
		}
		return nil
	case v2AgentErrorMessage:
		return markRelayError(app.RelayErrorAuthRejected,
			fmt.Errorf("relay error: %s", stringValue(msg["message"])))
	default:
		return markRelayError(app.RelayErrorProtocolFailed,
			fmt.Errorf("unexpected register response: %s", stringValue(msg["type"])))
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
