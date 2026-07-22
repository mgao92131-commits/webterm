package relaygateway

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/relayrouter"
	"webterm/go-core/internal/relaystore"
)

type WSGateway struct {
	store    relaystore.GatewayStore
	registry relayrouter.AgentRegistry
	streams  relayrouter.StreamController
	timeout  time.Duration

	heartbeatInterval time.Duration
	heartbeatTimeout  time.Duration
}

func NewWSGateway(store relaystore.GatewayStore, registry relayrouter.AgentRegistry, streams relayrouter.StreamController) *WSGateway {
	return &WSGateway{
		store:    store,
		registry: registry,
		streams:  streams,
		// WebSocket 已经完成 HTTP Upgrade，后续是由读写错误和 Ping/Pong
		// 管理的长连接，不能再套用从创建时间起算的绝对 stream Deadline。
		timeout: 0,
		// Android 手机网络容易半开：没有 heartbeat 时旧 stream 会在 Relay
		// 与 Agent 中残留数分钟。周期性 Ping 让 Relay 在约 20 秒内识别并清理。
		heartbeatInterval: 15 * time.Second,
		heartbeatTimeout:  5 * time.Second,
	}
}

func (gateway *WSGateway) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	user, ok := gateway.authenticateRequest(w, r)
	if !ok {
		return
	}
	agentPath, ok := agentSessionPathFromClientPath(r.URL.Path)
	if !ok {
		http.Error(w, "unsupported websocket path", http.StatusBadRequest)
		return
	}
	deviceID := firstNonEmpty(r.URL.Query().Get("deviceId"), r.Header.Get("x-device-id"))
	presence, sender, ok := gateway.registry.GetSenderForUser(user.ID, deviceID)
	if !ok {
		http.Error(w, "target agent unavailable", http.StatusServiceUnavailable)
		return
	}
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		Subprotocols:    websocketSubprotocols(r),
		CompressionMode: websocket.CompressionNoContextTakeover,
	})
	if err != nil {
		return
	}
	conn.SetReadLimit(webSocketReadLimit)
	defer conn.Close(websocket.StatusNormalClosure, "")

	route := relaycore.StreamRoute{
		Method:      r.Method,
		Path:        agentPath,
		Subprotocol: conn.Subprotocol(),
	}
	handle := gateway.streams.CreateStream(relaycore.StreamKindTerminal, route, user.ID, presence.DeviceID, presence.AgentConnectionID, gateway.timeout)
	gateway.streams.AttachClient(handle.ID, "client:ws:"+handle.ID)
	defer handle.Close("websocket closed")
	gateway.streams.Open(handle.ID)

	metaPayload, err := json.Marshal(route)
	if err != nil {
		conn.Close(websocket.StatusInternalError, "encode stream route failed")
		return
	}
	if err := sender.SendFrame(r.Context(), relaycore.NewFrame(relaycore.FrameTypeStreamOpen, handle.ID, 0, metaPayload)); err != nil {
		conn.Close(websocket.StatusTryAgainLater, "agent unavailable")
		return
	}

	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	errCh := make(chan error, 3)
	go func() {
		errCh <- gateway.clientToAgent(ctx, conn, sender, handle.ID)
	}()
	go func() {
		errCh <- gateway.agentToClient(ctx, conn, handle)
	}()
	go func() {
		errCh <- gateway.runHeartbeat(ctx, conn)
	}()
	<-errCh
	cancel()
	_ = sender.SendFrame(context.Background(), relaycore.NewFrame(relaycore.FrameTypeStreamClose, handle.ID, 0, nil))
}

// runHeartbeat 周期性向 Android 客户端发送 Ping。任一 Ping 在 heartbeatTimeout
// 内未收到 Pong 即返回错误，触发 ServeHTTP 的统一清理路径（cancel + 单次
// StreamClose）。heartbeatInterval<=0 时禁用，直接返回 nil（永不主动结束）。
//
// Ping 与 clientToAgent 的 Reader 并发运行：nhooyr websocket 的 Ping 不自行
// 读连接，而是依赖 Reader 读取 pong，因此必须有 Reader 协程同时存活。
func (gateway *WSGateway) runHeartbeat(ctx context.Context, conn *websocket.Conn) error {
	if gateway.heartbeatInterval <= 0 {
		// 禁用时阻塞直到 ctx 取消，避免向 errCh 写入 nil 触发提前清理。
		<-ctx.Done()
		return ctx.Err()
	}

	ticker := time.NewTicker(gateway.heartbeatInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return ctx.Err()

		case <-ticker.C:
			pingCtx, cancel := context.WithTimeout(ctx, gateway.heartbeatTimeout)
			err := conn.Ping(pingCtx)
			cancel()

			if err != nil {
				return err
			}
		}
	}
}

func agentSessionPathFromClientPath(path string) (string, bool) {
	if path == "/ws/sessions" {
		return "/ws/sessions", true
	}
	return "", false
}

func (gateway *WSGateway) clientToAgent(ctx context.Context, conn *websocket.Conn, sender relayrouter.AgentSender, streamID string) error {
	for {
		messageType, data, err := conn.Read(ctx)
		if err != nil {
			return err
		}
		frameType := relaycore.FrameTypeWSText
		if messageType == websocket.MessageBinary {
			frameType = relaycore.FrameTypeWSBinary
		}
		frame := relaycore.NewFrame(frameType, streamID, 0, data)
		gateway.streams.RecordClientFrame(frame)
		if err := sender.SendFrame(ctx, frame); err != nil {
			return err
		}
	}
}

func (gateway *WSGateway) agentToClient(ctx context.Context, conn *websocket.Conn, handle relayrouter.StreamHandle) error {
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case frame, ok := <-handle.Responses:
			if !ok {
				return relaycore.ErrStreamClosed
			}
			handle.ReleaseResponseFrame(frame)
			switch frame.Type {
			case relaycore.FrameTypeWSText:
				if err := conn.Write(ctx, websocket.MessageText, frame.Payload); err != nil {
					return err
				}
			case relaycore.FrameTypeWSBinary:
				if err := conn.Write(ctx, websocket.MessageBinary, frame.Payload); err != nil {
					return err
				}
			case relaycore.FrameTypeStreamClose:
				return conn.Close(websocket.StatusNormalClosure, string(frame.Payload))
			case relaycore.FrameTypeStreamError:
				return conn.Close(websocket.StatusInternalError, string(frame.Payload))
			}
		}
	}
}

func (gateway *WSGateway) authenticateRequest(w http.ResponseWriter, r *http.Request) (relaystore.User, bool) {
	tokenValue := relaycore.BearerToken(r.Header.Get("Authorization"))
	if tokenValue == "" {
		if cookie, err := r.Cookie(relaycore.AuthCookieName); err == nil {
			tokenValue = cookie.Value
		}
	}
	if tokenValue == "" {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return relaystore.User{}, false
	}
	user, err := gateway.store.AuthenticateToken(tokenValue)
	if err != nil {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return relaystore.User{}, false
	}
	return user, true
}

func websocketSubprotocols(r *http.Request) []string {
	values := r.Header.Values("Sec-WebSocket-Protocol")
	if len(values) == 0 {
		return nil
	}
	out := make([]string, 0, len(values))
	for _, value := range values {
		for _, item := range strings.Split(value, ",") {
			item = strings.TrimSpace(item)
			if item != "" {
				out = append(out, item)
			}
		}
	}
	return out
}
