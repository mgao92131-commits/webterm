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
)

const (
	v2AgentRegisterMessage   = "agent.register"
	v2AgentRegisteredMessage = "agent.registered"
	v2AgentErrorMessage      = "agent.error"
)

type V2Client struct {
	cfg    config.RelayConfig
	app    *app.App
	router *application.SessionRouter

	writeMu sync.Mutex
	mu      sync.Mutex
	http    map[string]chan relaycore.Frame
	ws      map[string]*relayStreamSocket
	p2p     map[string]*p2pPeer
}

func NewV2(cfg config.RelayConfig, application *app.App) *V2Client {
	return &V2Client{
		cfg:    cfg,
		app:    application,
		router: application.NewSessionRouter(application.Sessions()),
		http:   make(map[string]chan relaycore.Frame),
		ws:     make(map[string]*relayStreamSocket),
		p2p:    make(map[string]*p2pPeer),
	}
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
	defer client.closeAllP2P()
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
		ch := make(chan relaycore.Frame, 8)
		client.mu.Lock()
		client.http[frame.StreamID] = ch
		client.mu.Unlock()
		go client.handleHTTPStream(ctx, conn, frame, ch)
	case relaycore.FrameTypeHTTPChunk:
		client.deliverHTTP(frame)
	case relaycore.FrameTypeStreamOpen:
		client.handleStreamOpen(ctx, conn, frame)
	case relaycore.FrameTypeWSText, relaycore.FrameTypeWSBinary:
		client.deliverWS(frame)
	case relaycore.FrameTypeStreamClose, relaycore.FrameTypeStreamError:
		client.closeWS(frame.StreamID, false)
	case relaycore.FrameTypeP2POffer:
		client.handleP2POfferV2(ctx, conn, frame)
	case relaycore.FrameTypeP2PIce:
		client.deliverP2PIce(frame)
	}
}

func (client *V2Client) handleHTTPStream(ctx context.Context, conn *websocket.Conn, first relaycore.Frame, ch <-chan relaycore.Frame) {
	defer func() {
		client.mu.Lock()
		delete(client.http, first.StreamID)
		client.mu.Unlock()
	}()

	var meta relaycore.HTTPRequestMeta
	if err := json.Unmarshal(first.Payload, &meta); err != nil {
		client.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeStreamError, first.StreamID, 0, []byte("invalid http metadata")))
		return
	}
	body := make([]byte, 0)
	for {
		select {
		case <-ctx.Done():
			return
		case frame, ok := <-ch:
			if !ok {
				return
			}
			if frame.Type != relaycore.FrameTypeHTTPChunk {
				continue
			}
			body = append(body, frame.Payload...)
			if frame.Flags.Has(relaycore.FrameFlagFin) {
				client.respondHTTP(ctx, conn, first.StreamID, meta, body)
				return
			}
		}
	}
}

func (client *V2Client) respondHTTP(ctx context.Context, conn *websocket.Conn, streamID string, meta relaycore.HTTPRequestMeta, body []byte) {
	path := meta.Path
	if meta.Query != "" {
		path += "?" + meta.Query
	}
	status, payload, err := client.router.RouteHTTP(meta.Method, path, body)
	if err != nil && status == 0 {
		status = http.StatusInternalServerError
	}
	if err != nil {
		client.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeStreamError, streamID, 0, []byte(err.Error())))
		return
	}
	responseMeta, _ := json.Marshal(relaycore.HTTPResponseMeta{
		StatusCode: status,
		Headers: map[string]string{
			"content-type":   "application/json; charset=utf-8",
			"content-length": fmt.Sprintf("%d", len(payload)),
		},
	})
	client.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeHTTPHeaders, streamID, 0, responseMeta))
	client.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeHTTPChunk, streamID, relaycore.FrameFlagFin, payload))
}

func (client *V2Client) deliverHTTP(frame relaycore.Frame) {
	client.mu.Lock()
	ch := client.http[frame.StreamID]
	client.mu.Unlock()
	if ch == nil {
		return
	}
	select {
	case ch <- frame:
	default:
		close(ch)
		client.mu.Lock()
		delete(client.http, frame.StreamID)
		client.mu.Unlock()
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

func (client *V2Client) handleP2POfferV2(ctx context.Context, conn *websocket.Conn, frame relaycore.Frame) {
	if err := client.acceptP2POffer(ctx, conn, frame); err != nil {
		payload, _ := json.Marshal(relaycore.P2PUnavailable{
			Message: err.Error(),
		})
		client.writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeP2PUnavailable, frame.StreamID, 0, payload))
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
	writeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	socket.client.writeMu.Lock()
	defer socket.client.writeMu.Unlock()
	return socket.conn.Write(writeCtx, websocket.MessageBinary, encoded)
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

func (socket *relayStreamSocket) Subprotocol() string {
	return socket.protocol
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
