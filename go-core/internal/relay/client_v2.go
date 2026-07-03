package relay

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/application"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/session"
)

const (
	v2AgentRegisterMessage   = "agent.register"
	v2AgentRegisteredMessage = "agent.registered"
	v2AgentErrorMessage      = "agent.error"
)

type V2Client struct {
	cfg     config.RelayConfig
	app     *app.App
	router  *application.SessionRouter
	http    *HTTPProxy
	p2p     *P2PHandler

	writeMu sync.Mutex
	mu      sync.Mutex
	ws      map[string]*relayStreamSocket
}

func NewV2(cfg config.RelayConfig, application *app.App) *V2Client {
	router := application.NewSessionRouter(application.Sessions())
	client := &V2Client{
		cfg:    cfg,
		app:    application,
		router: router,
		ws:     make(map[string]*relayStreamSocket),
		p2p:    make(map[string]*p2pPeer),
	}
	client.http = NewHTTPProxy(router, client)
	client.p2p = NewP2PHandler(router, client)
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
		err := client.runOnce(ctx)
		if ctx.Err() != nil {
			return ctx.Err()
		}
		client.app.SetRelayConnected(false, "", errString(err))
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
		return err
	}
	conn, _, err := websocket.Dial(ctx, relayURL, nil)
	if err != nil {
		return err
	}
	defer conn.Close(websocket.StatusNormalClosure, "")

	if err := client.registerV2(ctx, conn); err != nil {
		return err
	}
	return client.readLoop(ctx, conn)
}

func (client *V2Client) registerV2(ctx context.Context, conn *websocket.Conn) error {
	if err := writeJSON(ctx, conn, map[string]any{
		"type":       v2AgentRegisterMessage,
		"credential": client.cfg.Secret,
		"deviceName": client.cfg.DeviceName,
	}); err != nil {
		return err
	}
	_, data, err := conn.Read(ctx)
	if err != nil {
		return err
	}
	var msg map[string]any
	if err := json.Unmarshal(data, &msg); err != nil {
		return errors.New("bad register response")
	}
	switch stringValue(msg["type"]) {
	case v2AgentRegisteredMessage:
		client.app.SetRelayConnected(true, stringValue(msg["deviceId"]), "")
		return nil
	case v2AgentErrorMessage:
		return fmt.Errorf("relay error: %s", stringValue(msg["message"]))
	default:
		return fmt.Errorf("unexpected register response: %s", stringValue(msg["type"]))
	}
}

func (client *V2Client) readLoop(ctx context.Context, conn *websocket.Conn) error {
	defer client.p2p.CloseAll()
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
		client.handleStreamOpen(ctx, conn, frame)
	case relaycore.FrameTypeWSText, relaycore.FrameTypeWSBinary:
		client.deliverWS(frame)
	case relaycore.FrameTypeStreamClose, relaycore.FrameTypeStreamError:
		client.closeWS(frame.StreamID, false)
	case relaycore.FrameTypeP2POffer:
		if err := client.p2p.AcceptOffer(ctx, conn, frame); err != nil {
			payload, _ := json.Marshal(relaycore.P2PUnavailable{
				Message: err.Error(),
			})
			client.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeP2PUnavailable, frame.StreamID, 0, payload))
		}
	case relaycore.FrameTypeP2PIce:
		client.p2p.DeliverICE(frame)
	}
}

func (client *V2Client) handleStreamOpen(ctx context.Context, conn *websocket.Conn, frame relaycore.Frame) {
	var route relaycore.StreamRoute
	if err := json.Unmarshal(frame.Payload, &route); err != nil {
		client.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeStreamError, frame.StreamID, 0, []byte("invalid stream route")))
		return
	}
	socket := newRelayStreamSocket(frame.StreamID, route.Subprotocol, client, conn)
	start, err := client.router.RouteOpen(ctx, socket, route.Path, []string{route.Subprotocol})
	if err != nil {
		client.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeStreamError, frame.StreamID, 0, []byte(err.Error())))
		return
	}
	client.mu.Lock()
	client.ws[frame.StreamID] = socket
	client.mu.Unlock()
	if start != nil {
		go start()
	}
}

func (client *V2Client) deliverWS(frame relaycore.Frame) {
	client.mu.Lock()
	socket := client.ws[frame.StreamID]
	client.mu.Unlock()
	if socket == nil {
		return
	}
	binary := frame.Type == relaycore.FrameTypeWSBinary
	socket.Emit(frame.Payload, binary)
}

func (client *V2Client) closeWS(streamID string, notifyRemote bool) {
	client.mu.Lock()
	socket := client.ws[streamID]
	delete(client.ws, streamID)
	client.mu.Unlock()
	if socket != nil {
		socket.close(notifyRemote)
	}
}

func (client *V2Client) writeFrame(ctx context.Context, conn *websocket.Conn, frame relaycore.Frame) {
	data, err := relaycore.EncodeFrame(frame)
	if err != nil {
		return
	}
	writeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	client.writeMu.Lock()
	defer client.writeMu.Unlock()
	_ = conn.Write(writeCtx, websocket.MessageBinary, data)
}

func (client *V2Client) writeRaw(ctx context.Context, conn *websocket.Conn, data []byte) error {
	writeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	client.writeMu.Lock()
	defer client.writeMu.Unlock()
	return conn.Write(writeCtx, websocket.MessageBinary, data)
}

type relayStreamSocket struct {
	id       string
	protocol string
	client   *V2Client
	conn     *websocket.Conn
	incoming chan relayStreamMessage
	done     chan struct{}
	once     sync.Once
}

type relayStreamMessage struct {
	messageType session.MessageType
	payload     []byte
}

func newRelayStreamSocket(id string, protocolName string, client *V2Client, conn *websocket.Conn) *relayStreamSocket {
	return &relayStreamSocket{
		id:       id,
		protocol: protocolName,
		client:   client,
		conn:     conn,
		incoming: make(chan relayStreamMessage, 256),
		done:     make(chan struct{}),
	}
}

func (socket *relayStreamSocket) Read(ctx context.Context) (session.MessageType, []byte, error) {
	select {
	case <-ctx.Done():
		return 0, nil, ctx.Err()
	case <-socket.done:
		return 0, nil, errors.New("relay stream socket closed")
	case message := <-socket.incoming:
		return message.messageType, message.payload, nil
	}
}

func (socket *relayStreamSocket) Write(ctx context.Context, messageType session.MessageType, data []byte) error {
	frameType := relaycore.FrameTypeWSText
	if messageType == session.MessageBinary {
		frameType = relaycore.FrameTypeWSBinary
	}
	frame := relaycore.NewFrame(frameType, socket.id, 0, data)
	encoded, err := relaycore.EncodeFrame(frame)
	if err != nil {
		return err
	}
	return socket.client.writeRaw(ctx, socket.conn, encoded)
}

func (socket *relayStreamSocket) Close() error {
	socket.close(true)
	return nil
}

func (socket *relayStreamSocket) close(notifyRemote bool) {
	socket.once.Do(func() {
		close(socket.done)
		socket.client.mu.Lock()
		delete(socket.client.ws, socket.id)
		socket.client.mu.Unlock()
		if notifyRemote {
			socket.client.writeFrame(context.Background(), socket.conn, relaycore.NewFrame(relaycore.FrameTypeStreamClose, socket.id, 0, nil))
		}
	})
}

func (socket *relayStreamSocket) Emit(payload []byte, binary bool) bool {
	messageType := session.MessageText
	if binary {
		messageType = session.MessageBinary
	}
	select {
	case <-socket.done:
		return false
	case socket.incoming <- relayStreamMessage{messageType: messageType, payload: payload}:
		return true
	default:
		_ = socket.Close()
		return false
	}
}

func writeJSON(ctx context.Context, conn *websocket.Conn, value any) error {
	bytes, err := json.Marshal(value)
	if err != nil {
		return err
	}
	writeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	return conn.Write(writeCtx, websocket.MessageText, bytes)
}

func stringValue(value any) string {
	if value == nil {
		return ""
	}
	if text, ok := value.(string); ok {
		return text
	}
	return fmt.Sprint(value)
}

func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}
