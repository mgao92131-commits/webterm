package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"strings"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/protocol"
)

func main() {
	agentPath := flag.String("agent", "", "path to webterm-agent binary")
	cwd := flag.String("cwd", ".", "agent/session working directory")
	timeout := flag.Duration("timeout", 12*time.Second, "smoke test timeout")
	flag.Parse()

	if *agentPath == "" {
		fmt.Fprintln(os.Stderr, "--agent is required")
		os.Exit(2)
	}

	ctx, cancel := context.WithTimeout(context.Background(), *timeout)
	defer cancel()

	if err := run(ctx, *agentPath, *cwd); err != nil {
		fmt.Fprintf(os.Stderr, "relay flow smoke failed: %v\n", err)
		os.Exit(1)
	}
	fmt.Println("relay flow smoke ok")
}

func run(ctx context.Context, agentPath string, cwd string) error {
	resultCh := make(chan error, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		resultCh <- handleAgent(ctx, w, r, cwd)
	}))
	defer server.Close()

	agentCtx, stopAgent := context.WithCancel(ctx)
	defer stopAgent()
	cmd := exec.CommandContext(agentCtx, agentPath, "--mode", "relay")
	cmd.Dir = cwd
	cmd.Env = append(os.Environ(),
		"RELAY_URL=ws"+strings.TrimPrefix(server.URL, "http"),
		"RELAY_SECRET=smoke-secret",
		"DEVICE_NAME=relay-smoke-device",
		"WEBTERM_CONTROL_ADDR=127.0.0.1:0",
		"WEBTERM_SHELL=/bin/sh",
	)
	var output bytes.Buffer
	cmd.Stdout = &output
	cmd.Stderr = &output
	if err := cmd.Start(); err != nil {
		return err
	}

	var relayErr error
	select {
	case relayErr = <-resultCh:
	case <-ctx.Done():
		relayErr = ctx.Err()
	}

	stopAgent()
	waitErr := cmd.Wait()
	if relayErr != nil {
		return fmt.Errorf("%w; agent output: %s", relayErr, output.String())
	}
	if waitErr != nil && ctx.Err() != nil && !errors.Is(waitErr, context.Canceled) {
		return fmt.Errorf("agent exited unexpectedly: %w; output: %s", waitErr, output.String())
	}
	return nil
}

func handleAgent(ctx context.Context, w http.ResponseWriter, r *http.Request, cwd string) error {
	if r.URL.Path != "/ws/agent" {
		return fmt.Errorf("relay path = %s, want /ws/agent", r.URL.Path)
	}
	conn, err := websocket.Accept(w, r, nil)
	if err != nil {
		return err
	}
	defer conn.Close(websocket.StatusNormalClosure, "")

	register, err := readJSON(ctx, conn)
	if err != nil {
		return fmt.Errorf("read register: %w", err)
	}
	if register["type"] != protocol.AgentRegister || register["secret"] != "smoke-secret" {
		return fmt.Errorf("bad register: %#v", register)
	}
	if err := writeJSON(ctx, conn, map[string]any{"type": protocol.Registered, "deviceId": "relay-smoke-device-id"}); err != nil {
		return err
	}

	createBody := base64.StdEncoding.EncodeToString([]byte(fmt.Sprintf(`{"name":"relay-flow-smoke","cwd":%q}`, cwd)))
	if err := writeJSON(ctx, conn, map[string]any{
		"type":         protocol.HTTPRequest,
		"requestId":    "req_create",
		"method":       http.MethodPost,
		"path":         "/api/sessions",
		"body":         createBody,
		"bodyEncoding": "base64",
	}); err != nil {
		return err
	}
	response, err := readJSON(ctx, conn)
	if err != nil {
		return fmt.Errorf("read create response: %w", err)
	}
	sessionID, err := decodeCreatedSession(response)
	if err != nil {
		return err
	}

	tunnelID := "tc_relay_flow"
	if err := writeJSON(ctx, conn, map[string]any{
		"type":               protocol.WSConnect,
		"tunnelConnectionId": tunnelID,
		"path":               "/ws/sessions/" + sessionID,
		"protocols":          []string{protocol.BinarySubprotocol},
	}); err != nil {
		return err
	}
	connected, err := readJSON(ctx, conn)
	if err != nil {
		return fmt.Errorf("read ws-connected: %w", err)
	}
	if connected["type"] != protocol.WSConnected || connected["tunnelConnectionId"] != tunnelID {
		return fmt.Errorf("bad ws-connected: %#v", connected)
	}

	helloPayload, _ := json.Marshal(map[string]any{"lastSeq": 0, "cols": 100, "rows": 30})
	if err := writeTunnelBinary(ctx, conn, tunnelID, append([]byte{protocol.MsgHello}, helloPayload...)); err != nil {
		return err
	}
	marker := fmt.Sprintf("GO_CORE_RELAY_FLOW_OK_%d", time.Now().UnixNano())
	if err := writeTunnelBinary(ctx, conn, tunnelID, append([]byte{protocol.MsgInput}, []byte("printf "+marker+"\\n")...)); err != nil {
		return err
	}

	for {
		messageType, data, err := conn.Read(ctx)
		if err != nil {
			return err
		}
		if messageType != websocket.MessageBinary {
			continue
		}
		frame, err := protocol.DecodeTunnelFrame(data)
		if err != nil || frame.ID != tunnelID || frame.ExtraByte != protocol.WSDataBinary {
			continue
		}
		if len(frame.Payload) > 9 && (frame.Payload[0] == protocol.MsgOutput || frame.Payload[0] == protocol.MsgState) {
			_, _, payload, err := protocol.DecodeSequencedData(frame.Payload)
			if err == nil && bytes.Contains(payload, []byte(marker)) {
				return nil
			}
		}
	}
}

func decodeCreatedSession(response map[string]any) (string, error) {
	if response["type"] != protocol.HTTPResponse || response["requestId"] != "req_create" {
		return "", fmt.Errorf("bad create response: %#v", response)
	}
	if code, ok := response["statusCode"].(float64); !ok || int(code) != http.StatusCreated {
		return "", fmt.Errorf("bad create status: %#v", response["statusCode"])
	}
	encoded, _ := response["body"].(string)
	body, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return "", err
	}
	var created struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(body, &created); err != nil {
		return "", err
	}
	if created.ID == "" {
		return "", fmt.Errorf("create response returned empty id")
	}
	return created.ID, nil
}

func writeTunnelBinary(ctx context.Context, conn *websocket.Conn, tunnelID string, payload []byte) error {
	frame, err := protocol.EncodeTunnelFrame(protocol.MsgTypeWSData, tunnelID, protocol.WSDataBinary, payload)
	if err != nil {
		return err
	}
	return conn.Write(ctx, websocket.MessageBinary, frame)
}

func writeJSON(ctx context.Context, conn *websocket.Conn, value any) error {
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}
	return conn.Write(ctx, websocket.MessageText, data)
}

func readJSON(ctx context.Context, conn *websocket.Conn) (map[string]any, error) {
	messageType, data, err := conn.Read(ctx)
	if err != nil {
		return nil, err
	}
	if messageType != websocket.MessageText {
		return nil, fmt.Errorf("message type = %v, want text", messageType)
	}
	var msg map[string]any
	if err := json.Unmarshal(data, &msg); err != nil {
		return nil, err
	}
	return msg, nil
}
