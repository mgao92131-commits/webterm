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
	"webterm/go-core/internal/mux"
	"webterm/go-core/internal/relaycore"
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
	streams *StreamMultiplexer

	writeMu sync.Mutex
}

func NewV2(cfg config.RelayConfig, appInstance *app.App) *V2Client {
	router := application.NewSessionRouterWithMux(appInstance.Sessions(), mux.MuxServeAdapter)
	client := &V2Client{
		cfg:    cfg,
		app:    appInstance,
		router: router,
	}
	client.http = NewHTTPProxy(router, client)
	client.p2p = NewP2PHandler(router, client)
	client.streams = NewStreamMultiplexer(router, client)
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
		client.streams.OpenStream(ctx, conn, frame)
	case relaycore.FrameTypeWSText, relaycore.FrameTypeWSBinary:
		client.streams.DeliverWS(frame)
	case relaycore.FrameTypeStreamClose, relaycore.FrameTypeStreamError:
		client.streams.CloseStream(frame.StreamID, false)
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
