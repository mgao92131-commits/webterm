package control

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
)

const (
	agentRegisterMessage   = "agent.register"
	agentRegisteredMessage = "agent.registered"
	agentErrorMessage      = "agent.error"
)

type connectionTestRequest struct {
	Mode   string        `json:"mode"`
	Config config.Config `json:"config"`
	Live   bool          `json:"live"`
}

type connectionTestResult struct {
	OK      bool   `json:"ok"`
	Mode    string `json:"mode"`
	Live    bool   `json:"live"`
	Message string `json:"message"`
	Error   string `json:"error,omitempty"`
}

func (control *Server) handleConnectionTest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	var req connectionTestRequest
	if r.Body != nil {
		err := json.NewDecoder(r.Body).Decode(&req)
		if err != nil && !errors.Is(err, io.EOF) {
			writeError(w, http.StatusBadRequest, "invalid json")
			return
		}
	}
	cfg := config.MergeEditable(control.app.Config(), req.Config)
	if req.Mode != "" {
		cfg.Mode = config.NormalizeMode(req.Mode)
	}
	result := testConnection(r.Context(), control.app.Status(), cfg, req.Live)
	status := http.StatusOK
	if !result.OK {
		status = http.StatusBadRequest
	}
	writeJSON(w, status, result)
}

func testConnection(ctx context.Context, status app.Status, cfg config.Config, live bool) connectionTestResult {
	switch cfg.Mode {
	case config.ModeDirect:
		return testDirectConnection(status, cfg.Direct)
	case config.ModeRelay:
		return testRelayConnection(ctx, cfg.Relay, live)
	default:
		return connectionTestResult{OK: false, Mode: cfg.Mode, Live: live, Error: "unsupported mode"}
	}
}

func testDirectConnection(status app.Status, direct config.DirectConfig) connectionTestResult {
	if direct.Addr == "" {
		return connectionTestResult{OK: false, Mode: config.ModeDirect, Error: "direct addr is required"}
	}
	if status.Direct.Listening && status.Direct.Addr == direct.Addr {
		return connectionTestResult{OK: true, Mode: config.ModeDirect, Message: "direct addr is already listening"}
	}
	listener, err := net.Listen("tcp", direct.Addr)
	if err != nil {
		return connectionTestResult{OK: false, Mode: config.ModeDirect, Error: err.Error()}
	}
	_ = listener.Close()
	return connectionTestResult{OK: true, Mode: config.ModeDirect, Message: "direct addr is available"}
}

func testRelayConnection(ctx context.Context, relay config.RelayConfig, live bool) connectionTestResult {
	if relay.URL == "" {
		return connectionTestResult{OK: false, Mode: config.ModeRelay, Live: live, Error: "relay url is required"}
	}
	if relay.Secret == "" {
		return connectionTestResult{OK: false, Mode: config.ModeRelay, Live: live, Error: "relay secret is required"}
	}
	wsURL, err := relayAgentWebSocketURL(relay.URL)
	if err != nil {
		return connectionTestResult{OK: false, Mode: config.ModeRelay, Live: live, Error: err.Error()}
	}
	if !live {
		return connectionTestResult{OK: true, Mode: config.ModeRelay, Live: false, Message: "relay config is valid"}
	}
	dialCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	conn, _, err := websocket.Dial(dialCtx, wsURL, nil)
	if err != nil {
		return connectionTestResult{OK: false, Mode: config.ModeRelay, Live: true, Error: err.Error()}
	}
	defer conn.Close(websocket.StatusNormalClosure, "")

	register := map[string]any{
		"type":       agentRegisterMessage,
		"deviceName": emptyDefault(relay.DeviceName, "webterm-agent-test"),
		"credential": relay.Secret,
		"test":       true,
	}
	if err := wsWriteJSON(dialCtx, conn, register); err != nil {
		return connectionTestResult{OK: false, Mode: config.ModeRelay, Live: true, Error: err.Error()}
	}
	msgType, data, err := conn.Read(dialCtx)
	if err != nil {
		return connectionTestResult{OK: false, Mode: config.ModeRelay, Live: true, Error: err.Error()}
	}
	if msgType != websocket.MessageText {
		return connectionTestResult{OK: false, Mode: config.ModeRelay, Live: true, Error: "relay returned non-text response"}
	}
	var response map[string]any
	if err := json.Unmarshal(data, &response); err != nil {
		return connectionTestResult{OK: false, Mode: config.ModeRelay, Live: true, Error: err.Error()}
	}
	switch fmt.Sprint(response["type"]) {
	case agentRegisteredMessage:
		return connectionTestResult{OK: true, Mode: config.ModeRelay, Live: true, Message: "relay registration succeeded"}
	case agentErrorMessage:
		return connectionTestResult{OK: false, Mode: config.ModeRelay, Live: true, Error: fmt.Sprint(response["message"])}
	default:
		return connectionTestResult{OK: false, Mode: config.ModeRelay, Live: true, Error: fmt.Sprintf("unexpected relay response type %q", response["type"])}
	}
}

func relayAgentWebSocketURL(raw string) (string, error) {
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
		if parsed.Scheme == "" {
			return "", errors.New("relay url scheme is required")
		}
		return "", fmt.Errorf("unsupported relay url scheme: %s", parsed.Scheme)
	}
	parsed.Path = strings.TrimRight(parsed.Path, "/") + "/ws/agent"
	parsed.RawQuery = ""
	parsed.Fragment = ""
	return parsed.String(), nil
}

func wsWriteJSON(ctx context.Context, conn *websocket.Conn, value any) error {
	bytes, err := json.Marshal(value)
	if err != nil {
		return err
	}
	return conn.Write(ctx, websocket.MessageText, bytes)
}

func emptyDefault(value string, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}
