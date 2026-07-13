package main

import (
	"bytes"
	"context"
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
	"webterm/go-core/internal/relaycore"
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
		"WEBTERM_RELAY_PROTOCOL=v2",
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
	if register["type"] != "agent.register" || register["credential"] != "smoke-secret" {
		return fmt.Errorf("bad register: %#v", register)
	}
	if err := writeJSON(ctx, conn, map[string]any{"type": "agent.registered", "deviceId": "relay-smoke-device-id"}); err != nil {
		return err
	}

	createID := "req_create"
	createMeta, _ := json.Marshal(relaycore.HTTPRequestMeta{
		Method:  http.MethodPost,
		Path:    "/api/sessions",
		Headers: map[string]string{"content-type": "application/json"},
	})
	if err := writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeHTTPHeaders, createID, 0, createMeta)); err != nil {
		return err
	}
	createBody := []byte(fmt.Sprintf(`{"cwd":%q}`, cwd))
	if err := writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeHTTPChunk, createID, relaycore.FrameFlagFin, createBody)); err != nil {
		return err
	}
	responseHeaders, err := readFrame(ctx, conn)
	if err != nil {
		return fmt.Errorf("read create response headers: %w", err)
	}
	responseChunk, err := readFrame(ctx, conn)
	if err != nil {
		return fmt.Errorf("read create response body: %w", err)
	}
	sessionID, err := decodeCreatedSession(responseHeaders, responseChunk)
	if err != nil {
		return err
	}

	tunnelID := "tc_relay_flow"
	route, _ := json.Marshal(relaycore.StreamRoute{
		Path:        "/ws/sessions/" + sessionID,
		Subprotocol: protocol.BinarySubprotocol,
	})
	if err := writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeStreamOpen, tunnelID, 0, route)); err != nil {
		return err
	}

	helloPayload, _ := json.Marshal(map[string]any{"lastSeq": 0, "cols": 100, "rows": 30})
	if err := writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeWSBinary, tunnelID, 0, append([]byte{protocol.MsgHello}, helloPayload...))); err != nil {
		return err
	}
	marker := fmt.Sprintf("GO_CORE_RELAY_FLOW_OK_%d", time.Now().UnixNano())
	if err := writeFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeWSBinary, tunnelID, 0, append([]byte{protocol.MsgInput}, []byte("printf "+marker+"\\n")...))); err != nil {
		return err
	}

	for {
		frame, err := readFrame(ctx, conn)
		if err != nil {
			return err
		}
		if frame.StreamID != tunnelID || frame.Type != relaycore.FrameTypeWSBinary {
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

func decodeCreatedSession(headers relaycore.Frame, chunk relaycore.Frame) (string, error) {
	if headers.Type != relaycore.FrameTypeHTTPHeaders || headers.StreamID != "req_create" {
		return "", fmt.Errorf("bad create response headers: %#v", headers)
	}
	var meta relaycore.HTTPResponseMeta
	if err := json.Unmarshal(headers.Payload, &meta); err != nil {
		return "", err
	}
	if meta.StatusCode != http.StatusCreated {
		return "", fmt.Errorf("bad create status: %d", meta.StatusCode)
	}
	if chunk.Type != relaycore.FrameTypeHTTPChunk || chunk.StreamID != "req_create" {
		return "", fmt.Errorf("bad create response body: %#v", chunk)
	}
	var created struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(chunk.Payload, &created); err != nil {
		return "", err
	}
	if created.ID == "" {
		return "", fmt.Errorf("create response returned empty id")
	}
	return created.ID, nil
}

func writeFrame(ctx context.Context, conn *websocket.Conn, frame relaycore.Frame) error {
	data, err := relaycore.EncodeFrame(frame)
	if err != nil {
		return err
	}
	return conn.Write(ctx, websocket.MessageBinary, data)
}

func readFrame(ctx context.Context, conn *websocket.Conn) (relaycore.Frame, error) {
	for {
		messageType, data, err := conn.Read(ctx)
		if err != nil {
			return relaycore.Frame{}, err
		}
		if messageType != websocket.MessageBinary {
			continue
		}
		frame, err := relaycore.DecodeFrame(data)
		if err != nil {
			return relaycore.Frame{}, err
		}
		return frame, nil
	}
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
