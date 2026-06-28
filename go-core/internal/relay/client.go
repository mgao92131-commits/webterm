package relay

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/session"
)

type Client struct {
	cfg config.RelayConfig
	app *app.App
}

func New(cfg config.RelayConfig, application *app.App) *Client {
	return &Client{cfg: cfg, app: application}
}

func (client *Client) Run(ctx context.Context) error {
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

func (client *Client) runOnce(ctx context.Context) error {
	relayURL, err := agentWebSocketURL(client.cfg.URL)
	if err != nil {
		return err
	}
	conn, _, err := websocket.Dial(ctx, relayURL, nil)
	if err != nil {
		return err
	}
	defer conn.Close(websocket.StatusNormalClosure, "")

	if err := writeJSON(ctx, conn, map[string]any{
		"type":       protocol.AgentRegister,
		"deviceName": client.cfg.DeviceName,
		"secret":     client.cfg.Secret,
	}); err != nil {
		return err
	}

	transport := newConnectionTransport(conn)
	defer transport.closeAll()

	for {
		messageType, data, err := conn.Read(ctx)
		if err != nil {
			return err
		}
		if messageType == websocket.MessageBinary {
			transport.handleBinaryFrame(data)
			continue
		}
		if messageType != websocket.MessageText {
			continue
		}
		var msg map[string]any
		if err := json.Unmarshal(data, &msg); err != nil {
			continue
		}
		switch stringValue(msg["type"]) {
		case protocol.Registered:
			deviceID := stringValue(msg["deviceId"])
			client.app.SetRelayConnected(true, deviceID, "")
		case protocol.HTTPRequest:
			client.handleHTTPRequest(ctx, conn, msg)
		case protocol.WSConnect:
			client.handleWSConnect(ctx, transport, msg)
		case protocol.WSClose:
			transport.closeSocket(stringValue(msg["tunnelConnectionId"]))
		case "p2p-offer":
			client.handleP2POffer(ctx, conn, msg)
		case "p2p-ice":
			// Go Core does not implement WebRTC/P2P yet. ICE candidates can be
			// ignored after the offer has been rejected with p2p-unavailable.
		case protocol.Error:
			return fmt.Errorf("relay error: %s", stringValue(msg["message"]))
		}
	}
}

func (client *Client) handleP2POffer(ctx context.Context, conn *websocket.Conn, msg map[string]any) {
	clientID := stringValue(msg["from"])
	if clientID == "" {
		return
	}
	_ = writeJSON(ctx, conn, map[string]any{
		"type":    "p2p-unavailable",
		"to":      clientID,
		"message": "Go Core relay agent does not support P2P yet; falling back to relay tunnel",
	})
}

func (client *Client) handleWSConnect(ctx context.Context, transport *connectionTransport, msg map[string]any) {
	tunnelID := stringValue(msg["tunnelConnectionId"])
	path := stringValue(msg["path"])
	pathname := path
	if parsed, err := url.Parse(path); err == nil {
		pathname = parsed.Path
	}
	if tunnelID == "" {
		return
	}

	protocolName := selectedProtocol(protocolsValue(msg["protocols"]))
	socket := transport.newSocket(tunnelID, protocolName)

	if pathname == "/ws/sessions" {
		_ = transport.sendJSON(ctx, map[string]any{"type": protocol.WSConnected, "tunnelConnectionId": tunnelID})
		managerClient := session.NewManagerClient(socket)
		go managerClient.Run(ctx, client.app.Sessions())
		return
	}

	if !strings.HasPrefix(pathname, "/ws/sessions/") {
		transport.removeSocket(tunnelID)
		_ = transport.sendJSON(ctx, map[string]any{
			"type":               protocol.WSError,
			"tunnelConnectionId": tunnelID,
			"code":               http.StatusNotFound,
			"message":            "Session path match failed",
		})
		return
	}

	id := strings.TrimPrefix(pathname, "/ws/sessions/")
	id, _ = url.PathUnescape(id)
	terminal, ok := client.app.Sessions().Get(id)
	if !ok {
		transport.removeSocket(tunnelID)
		_ = transport.sendJSON(ctx, map[string]any{
			"type":               protocol.WSError,
			"tunnelConnectionId": tunnelID,
			"code":               http.StatusNotFound,
			"message":            fmt.Sprintf("Session %s not found", id),
		})
		return
	}

	_ = transport.sendJSON(ctx, map[string]any{"type": protocol.WSConnected, "tunnelConnectionId": tunnelID})
	terminalClient := session.NewClient(socket, terminal, session.ClientModeFromProtocol(protocolName))
	go terminalClient.Run(ctx)
}

func (client *Client) handleHTTPRequest(ctx context.Context, conn *websocket.Conn, msg map[string]any) {
	requestID := stringValue(msg["requestId"])
	method := stringValue(msg["method"])
	path := stringValue(msg["path"])
	status, payload, err := client.routeMemoryAPI(method, path, decodeBody(msg))
	if err != nil {
		_ = writeJSON(ctx, conn, map[string]any{
			"type":      protocol.HTTPError,
			"requestId": requestID,
			"error":     errorCode(status),
			"message":   err.Error(),
		})
		return
	}
	encoded := base64.StdEncoding.EncodeToString(payload)
	_ = writeJSON(ctx, conn, map[string]any{
		"type":         protocol.HTTPResponse,
		"requestId":    requestID,
		"statusCode":   status,
		"headers":      map[string]string{"content-type": "application/json; charset=utf-8", "content-length": fmt.Sprintf("%d", len(payload))},
		"bodyEncoding": "base64",
		"body":         encoded,
		"hasChunks":    false,
	})
}

func (client *Client) routeMemoryAPI(method string, rawPath string, body []byte) (int, []byte, error) {
	path := rawPath
	if parsed, err := url.Parse(rawPath); err == nil {
		path = parsed.Path
	}
	if method == http.MethodGet && path == "/api/sessions" {
		return marshalStatus(http.StatusOK, client.app.Sessions().List())
	}
	if method == http.MethodPost && path == "/api/sessions" {
		var req struct {
			Name string `json:"name"`
			CWD  string `json:"cwd"`
		}
		if len(body) > 0 {
			_ = json.Unmarshal(body, &req)
		}
		terminal, err := client.app.Sessions().Create(req.Name, req.CWD)
		if err != nil {
			return http.StatusBadRequest, nil, err
		}
		return marshalStatus(http.StatusCreated, terminal.Info())
	}
	if strings.HasPrefix(path, "/api/sessions/") {
		id := strings.TrimPrefix(path, "/api/sessions/")
		id, _ = url.PathUnescape(id)
		if method == http.MethodPatch {
			var req struct {
				Name string `json:"name"`
			}
			if len(body) > 0 {
				_ = json.Unmarshal(body, &req)
			}
			terminal, ok := client.app.Sessions().Rename(id, req.Name)
			if !ok {
				return http.StatusNotFound, nil, errors.New("session not found")
			}
			return marshalStatus(http.StatusOK, terminal.Info())
		}
		if method == http.MethodDelete {
			if !client.app.Sessions().Close(id) {
				return http.StatusNotFound, nil, errors.New("session not found")
			}
			return http.StatusNoContent, []byte{}, nil
		}
	}
	return http.StatusNotFound, nil, errors.New("not found")
}

func marshalStatus(status int, value any) (int, []byte, error) {
	payload, err := json.Marshal(value)
	return status, payload, err
}

func decodeBody(msg map[string]any) []byte {
	body := stringValue(msg["body"])
	if body == "" {
		return nil
	}
	if stringValue(msg["bodyEncoding"]) == "base64" {
		decoded, err := base64.StdEncoding.DecodeString(body)
		if err == nil {
			return decoded
		}
	}
	return []byte(body)
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

func selectedProtocol(protocols []string) string {
	for _, item := range protocols {
		if item == protocol.ScreenSubprotocol {
			return protocol.ScreenSubprotocol
		}
	}
	for _, item := range protocols {
		if item == protocol.BinarySubprotocol {
			return protocol.BinarySubprotocol
		}
	}
	for _, item := range protocols {
		if item == protocol.JSONSubprotocol {
			return protocol.JSONSubprotocol
		}
	}
	return protocol.JSONSubprotocol
}

func protocolsValue(value any) []string {
	items, ok := value.([]any)
	if !ok {
		return nil
	}
	out := make([]string, 0, len(items))
	for _, item := range items {
		if text := stringValue(item); text != "" {
			out = append(out, text)
		}
	}
	return out
}

func agentWebSocketURL(raw string) (string, error) {
	parsed, err := url.Parse(raw)
	if err != nil {
		return "", err
	}
	switch parsed.Scheme {
	case "http":
		parsed.Scheme = "ws"
	case "https":
		parsed.Scheme = "wss"
	case "ws", "wss":
	default:
		return "", fmt.Errorf("unsupported relay URL scheme: %s", parsed.Scheme)
	}
	parsed.Path = strings.TrimRight(parsed.Path, "/") + "/ws/agent"
	parsed.RawQuery = ""
	return parsed.String(), nil
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

func errorCode(status int) string {
	if status == http.StatusNotFound {
		return "not_found"
	}
	return "failed"
}

func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}
