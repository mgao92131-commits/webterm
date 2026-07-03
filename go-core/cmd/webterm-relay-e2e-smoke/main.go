package main

import (
	"bytes"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"strings"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/relay"
	"webterm/go-core/internal/relayapp"
	"webterm/go-core/internal/relaycore"
)

func main() {
	agentPath := flag.String("agent", "", "optional path to webterm-agent binary; empty uses in-process Go Agent")
	cwd := flag.String("cwd", ".", "agent/session working directory")
	shell := flag.String("shell", "/bin/sh", "agent shell command")
	cycles := flag.Int("cycles", 2, "agent connect/disconnect lifecycle cycles to run")
	timeout := flag.Duration("timeout", 12*time.Second, "smoke test timeout")
	useMux := flag.Bool("mux", true, "probe terminal through /ws/sessions?deviceId=... using webterm.mux.v1")
	flag.Parse()

	ctx, cancel := context.WithTimeout(context.Background(), *timeout)
	defer cancel()

	if err := run(ctx, *cwd, *shell, *agentPath, *cycles, *useMux); err != nil {
		fmt.Fprintf(os.Stderr, "relay e2e smoke failed: %v\n", err)
		os.Exit(1)
	}
	fmt.Println("relay e2e smoke ok")
}

func run(ctx context.Context, cwd string, shell string, agentPath string, cycles int, useMux bool) error {
	if cycles <= 0 {
		return fmt.Errorf("--cycles must be greater than zero")
	}
	relayApp := relayapp.NewInMemory(relayapp.Config{})
	user, err := relayApp.Store().CreateUser("owner@example.com", "secret", "admin")
	if err != nil {
		return err
	}
	device, credential, err := relayApp.Store().CreateDevice(user.ID, "Go Agent")
	if err != nil {
		return err
	}
	token, err := relayApp.Store().IssueToken(user.ID, time.Hour)
	if err != nil {
		return err
	}

	server := httptest.NewServer(relayApp.Handler())
	defer server.Close()

	for cycle := 1; cycle <= cycles; cycle++ {
		phase := fmt.Sprintf("cycle-%02d", cycle)
		agentRunner, err := startAgent(ctx, server.URL, credential, cwd, shell, agentPath)
		if err != nil {
			return err
		}
		if err := waitForPresence(ctx, relayApp, user.ID, device.ID); err != nil {
			if output := agentRunner.Output(); output != "" {
				_ = agentRunner.Stop()
				return fmt.Errorf("%w; %s agent output: %s", err, phase, output)
			}
			_ = agentRunner.Stop()
			return err
		}
		if err := runSessionLifecycle(ctx, relayApp, server.URL, token.Value, device.ID, cwd, phase, useMux); err != nil {
			_ = agentRunner.Stop()
			return err
		}
		if err := agentRunner.Stop(); err != nil {
			return err
		}
		if err := waitForNoPresence(ctx, relayApp, user.ID, device.ID); err != nil {
			return err
		}
		if err := waitForNoStreams(ctx, relayApp, phase+" agent stop"); err != nil {
			return err
		}
	}
	return nil
}

func runSessionLifecycle(ctx context.Context, relayApp *relayapp.App, baseURL string, token string, deviceID string, cwd string, phase string, useMux bool) error {
	sessionID, err := createSession(ctx, baseURL, token, deviceID, cwd, phase)
	if err != nil {
		return err
	}
	if err := waitForNoStreams(ctx, relayApp, phase+" create session"); err != nil {
		return err
	}
	if err := expectSessionName(ctx, baseURL, token, deviceID, sessionID, "relay-e2e-"+phase); err != nil {
		return err
	}
	secondSessionID := ""
	if useMux {
		secondSessionID, err = createSessionWithName(ctx, baseURL, token, deviceID, cwd, "relay-e2e-"+phase+"-second")
		if err != nil {
			return err
		}
		if err := waitForNoStreams(ctx, relayApp, phase+" create second session"); err != nil {
			return err
		}
		if err := expectSessionName(ctx, baseURL, token, deviceID, secondSessionID, "relay-e2e-"+phase+"-second"); err != nil {
			return err
		}
	}
	if err := waitForNoStreams(ctx, relayApp, phase+" list sessions"); err != nil {
		return err
	}
	marker := fmt.Sprintf("GO_RELAY_E2E_%s_OK_%d", strings.ToUpper(phase), time.Now().UnixNano())
	if useMux && secondSessionID != "" {
		secondMarker := fmt.Sprintf("GO_RELAY_E2E_%s_SECOND_OK_%d", strings.ToUpper(phase), time.Now().UnixNano())
		if err := runMuxDualTerminalProbe(ctx, baseURL, token, deviceID, []terminalProbe{
			{sessionID: sessionID, marker: marker},
			{sessionID: secondSessionID, marker: secondMarker},
		}); err != nil {
			return err
		}
	} else if err := runTerminalProbe(ctx, baseURL, token, deviceID, sessionID, marker, useMux); err != nil {
		return err
	}
	if err := waitForNoStreams(ctx, relayApp, phase+" terminal websocket close"); err != nil {
		return err
	}
	if secondSessionID != "" {
		if err := deleteSession(ctx, baseURL, token, deviceID, secondSessionID); err != nil {
			return err
		}
		if err := waitForNoStreams(ctx, relayApp, phase+" delete second session"); err != nil {
			return err
		}
		if err := expectSessionDeleted(ctx, baseURL, token, deviceID, secondSessionID); err != nil {
			return err
		}
	}
	renamed := "relay-e2e-" + phase + "-renamed"
	if err := renameSession(ctx, baseURL, token, deviceID, sessionID, renamed); err != nil {
		return err
	}
	if err := waitForNoStreams(ctx, relayApp, phase+" rename session"); err != nil {
		return err
	}
	if err := expectSessionName(ctx, baseURL, token, deviceID, sessionID, renamed); err != nil {
		return err
	}
	if err := waitForNoStreams(ctx, relayApp, phase+" list renamed session"); err != nil {
		return err
	}
	if err := deleteSession(ctx, baseURL, token, deviceID, sessionID); err != nil {
		return err
	}
	if err := waitForNoStreams(ctx, relayApp, phase+" delete session"); err != nil {
		return err
	}
	if err := expectSessionDeleted(ctx, baseURL, token, deviceID, sessionID); err != nil {
		return err
	}
	if err := waitForNoStreams(ctx, relayApp, phase+" list after delete"); err != nil {
		return err
	}
	return nil
}

type agentRunner struct {
	stop   context.CancelFunc
	done   chan error
	output func() string
}

func startAgent(ctx context.Context, relayURL string, credential string, cwd string, shell string, agentPath string) (*agentRunner, error) {
	agentCtx, stopAgent := context.WithCancel(ctx)
	done := make(chan error, 1)
	if agentPath == "" {
		agentCfg := config.Config{
			Mode:  config.ModeRelay,
			Relay: config.RelayConfig{URL: relayURL, Secret: credential, DeviceName: "Go Agent", Protocol: config.RelayProtocolV2},
			Shell: config.ShellConfig{Command: shell, CWD: cwd},
		}
		agentApp := app.New(agentCfg, "smoke")
		agentClient := relay.NewV2(agentCfg.Relay, agentApp)
		go func() {
			done <- agentClient.Run(agentCtx)
		}()
		return &agentRunner{
			stop: stopAgent,
			done: done,
		}, nil
	}

	cmd := exec.CommandContext(agentCtx, agentPath, "--mode", "relay")
	cmd.Dir = cwd
	cmd.Env = append(os.Environ(),
		"RELAY_URL="+relayURL,
		"RELAY_SECRET="+credential,
		"DEVICE_NAME=Go Agent",
		"WEBTERM_RELAY_PROTOCOL=v2",
		"WEBTERM_CONTROL_ADDR=127.0.0.1:0",
		"WEBTERM_SHELL="+shell,
	)
	var output bytes.Buffer
	cmd.Stdout = &output
	cmd.Stderr = &output
	if err := cmd.Start(); err != nil {
		stopAgent()
		return nil, err
	}
	go func() {
		done <- cmd.Wait()
	}()
	return &agentRunner{
		stop:   stopAgent,
		done:   done,
		output: output.String,
	}, nil
}

func (runner *agentRunner) Stop() error {
	if runner == nil || runner.stop == nil {
		return nil
	}
	runner.stop()
	runner.stop = nil
	select {
	case err := <-runner.done:
		if err != nil && err != context.Canceled {
			return nil
		}
		return nil
	case <-time.After(time.Second):
		return fmt.Errorf("agent did not stop after smoke success")
	}
}

func (runner *agentRunner) Output() string {
	if runner == nil || runner.output == nil {
		return ""
	}
	return runner.output()
}

func waitForPresence(ctx context.Context, relayApp *relayapp.App, userID string, deviceID string) error {
	ticker := time.NewTicker(10 * time.Millisecond)
	defer ticker.Stop()
	for {
		if _, ok := relayApp.Registry().GetAgentForUser(userID, deviceID); ok {
			return nil
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("agent presence %s/%s did not appear: %w", userID, deviceID, ctx.Err())
		case <-ticker.C:
		}
	}
}

func waitForNoPresence(ctx context.Context, relayApp *relayapp.App, userID string, deviceID string) error {
	ticker := time.NewTicker(10 * time.Millisecond)
	defer ticker.Stop()
	for {
		if _, ok := relayApp.Registry().GetAgentForUser(userID, deviceID); !ok {
			return nil
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("agent presence %s/%s still online: %w", userID, deviceID, ctx.Err())
		case <-ticker.C:
		}
	}
}

func waitForNoStreams(ctx context.Context, relayApp *relayapp.App, phase string) error {
	ticker := time.NewTicker(10 * time.Millisecond)
	defer ticker.Stop()
	for {
		streams := relayApp.Streams().Snapshot()
		if len(streams) == 0 {
			return nil
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("streams still active after %s: %#v: %w", phase, streams, ctx.Err())
		case <-ticker.C:
		}
	}
}

func createSession(ctx context.Context, baseURL string, token string, deviceID string, cwd string, phase string) (string, error) {
	return createSessionWithName(ctx, baseURL, token, deviceID, cwd, "relay-e2e-"+phase)
}

func createSessionWithName(ctx context.Context, baseURL string, token string, deviceID string, cwd string, name string) (string, error) {
	statusCode, body, err := sessionAPI(ctx, http.MethodPost, baseURL, token, deviceID, "/api/sessions", []byte(fmt.Sprintf(`{"name":%q,"cwd":%q}`, name, cwd)))
	if err != nil {
		return "", err
	}
	if statusCode != http.StatusCreated {
		return "", fmt.Errorf("create session status=%d body=%s", statusCode, body)
	}
	var created struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(body, &created); err != nil {
		return "", err
	}
	if created.ID == "" {
		return "", fmt.Errorf("create session returned empty id: %s", body)
	}
	return created.ID, nil
}

func expectSessionName(ctx context.Context, baseURL string, token string, deviceID string, sessionID string, name string) error {
	sessions, err := listSessions(ctx, baseURL, token, deviceID)
	if err != nil {
		return err
	}
	for _, session := range sessions {
		if session.ID == sessionID {
			if session.Name != name {
				return fmt.Errorf("session %s name=%q, want %q", sessionID, session.Name, name)
			}
			return nil
		}
	}
	return fmt.Errorf("session %s missing from list", sessionID)
}

func expectSessionDeleted(ctx context.Context, baseURL string, token string, deviceID string, sessionID string) error {
	sessions, err := listSessions(ctx, baseURL, token, deviceID)
	if err != nil {
		return err
	}
	for _, session := range sessions {
		if session.ID == sessionID {
			return fmt.Errorf("session %s still present after delete", sessionID)
		}
	}
	return nil
}

func listSessions(ctx context.Context, baseURL string, token string, deviceID string) ([]sessionInfo, error) {
	statusCode, body, err := sessionAPI(ctx, http.MethodGet, baseURL, token, deviceID, "/api/sessions", nil)
	if err != nil {
		return nil, err
	}
	if statusCode != http.StatusOK {
		return nil, fmt.Errorf("list sessions status=%d body=%s", statusCode, body)
	}
	var sessions []sessionInfo
	if err := json.Unmarshal(body, &sessions); err != nil {
		return nil, err
	}
	return sessions, nil
}

func renameSession(ctx context.Context, baseURL string, token string, deviceID string, sessionID string, name string) error {
	statusCode, body, err := sessionAPI(ctx, http.MethodPatch, baseURL, token, deviceID, "/api/sessions/"+sessionID, []byte(fmt.Sprintf(`{"name":%q}`, name)))
	if err != nil {
		return err
	}
	if statusCode != http.StatusOK {
		return fmt.Errorf("rename session status=%d body=%s", statusCode, body)
	}
	var renamed sessionInfo
	if err := json.Unmarshal(body, &renamed); err != nil {
		return err
	}
	if renamed.ID != sessionID || renamed.Name != name {
		return fmt.Errorf("rename response id=%q name=%q, want id=%q name=%q", renamed.ID, renamed.Name, sessionID, name)
	}
	return nil
}

func deleteSession(ctx context.Context, baseURL string, token string, deviceID string, sessionID string) error {
	statusCode, body, err := sessionAPI(ctx, http.MethodDelete, baseURL, token, deviceID, "/api/sessions/"+sessionID, nil)
	if err != nil {
		return err
	}
	if statusCode != http.StatusNoContent {
		return fmt.Errorf("delete session status=%d body=%s", statusCode, body)
	}
	return nil
}

func sessionAPI(ctx context.Context, method string, baseURL string, token string, deviceID string, path string, body []byte) (int, []byte, error) {
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, baseURL+path, reader)
	if err != nil {
		return 0, nil, err
	}
	req.AddCookie(&http.Cookie{Name: relaycore.AuthCookieName, Value: token})
	req.Header.Set("x-device-id", deviceID)
	if body != nil {
		req.Header.Set("content-type", "application/json")
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer res.Body.Close()
	responseBody, _ := io.ReadAll(res.Body)
	return res.StatusCode, responseBody, nil
}

type sessionInfo struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

type terminalProbe struct {
	sessionID string
	marker    string
}

func runTerminalProbe(ctx context.Context, baseURL string, token string, deviceID string, sessionID string, marker string, useMux bool) error {
	if !useMux {
		return fmt.Errorf("legacy relay terminal probe has been removed; use --mux")
	}
	return runMuxTerminalProbe(ctx, baseURL, token, deviceID, sessionID, marker)
}

func runMuxTerminalProbe(ctx context.Context, baseURL string, token string, deviceID string, sessionID string, marker string) error {
	return runMuxDualTerminalProbe(ctx, baseURL, token, deviceID, []terminalProbe{{sessionID: sessionID, marker: marker}})
}

func runMuxDualTerminalProbe(ctx context.Context, baseURL string, token string, deviceID string, probes []terminalProbe) error {
	if len(probes) == 0 {
		return nil
	}
	wsURL := "ws" + strings.TrimPrefix(baseURL, "http") + "/ws/sessions?deviceId=" + deviceID
	ws, _, err := websocket.Dial(ctx, wsURL, &websocket.DialOptions{
		HTTPHeader:   http.Header{"Cookie": []string{(&http.Cookie{Name: relaycore.AuthCookieName, Value: token}).String()}},
		Subprotocols: []string{protocol.MuxSubprotocol},
	})
	if err != nil {
		return err
	}
	defer ws.Close(websocket.StatusNormalClosure, "")

	managerID := "manager:" + deviceID
	if err := writeMuxControl(ctx, ws, map[string]any{
		"type":               protocol.WSConnect,
		"tunnelConnectionId": managerID,
		"path":               "/ws/sessions",
	}); err != nil {
		return err
	}
	if err := expectMuxConnected(ctx, ws, managerID); err != nil {
		return err
	}
	if _, err := readMuxTunnelFor(ctx, ws, managerID); err != nil {
		return err
	}

	seen := make(map[string]bool, len(probes))
	markers := make(map[string]string, len(probes))
	for _, probe := range probes {
		terminalID := "term:" + probe.sessionID
		markers[terminalID] = probe.marker
		if err := writeMuxControl(ctx, ws, map[string]any{
			"type":               protocol.WSConnect,
			"tunnelConnectionId": terminalID,
			"path":               "/ws/sessions/" + probe.sessionID,
			"protocols":          []string{protocol.BinarySubprotocol},
		}); err != nil {
			return err
		}
		if err := expectMuxConnected(ctx, ws, terminalID); err != nil {
			return err
		}
	}

	for _, probe := range probes {
		terminalID := "term:" + probe.sessionID
		hello, _ := protocol.EncodeJSONMessage(protocol.MsgHello, map[string]any{"lastSeq": 0, "cols": 80, "rows": 24})
		if err := writeMuxTunnel(ctx, ws, terminalID, hello, true); err != nil {
			return err
		}
		command := "printf " + probe.marker + "\\n\r"
		if err := writeMuxTunnel(ctx, ws, terminalID, append([]byte{protocol.MsgInput}, []byte(command)...), true); err != nil {
			return err
		}
	}

	for len(seen) < len(probes) {
		frame, err := readMuxTunnel(ctx, ws)
		if err != nil {
			return err
		}
		if _, ok := markers[frame.ID]; !ok {
			continue
		}
		if len(frame.Payload) == 0 || (frame.Payload[0] != protocol.MsgOutput && frame.Payload[0] != protocol.MsgState) {
			continue
		}
		payload := frame.Payload
		if len(frame.Payload) >= 9 {
			_, _, decoded, err := protocol.DecodeSequencedData(frame.Payload)
			if err == nil {
				payload = decoded
			}
		}
		for terminalID, marker := range markers {
			if !bytes.Contains(payload, []byte(marker)) {
				continue
			}
			if terminalID != frame.ID {
				return fmt.Errorf("mux channel leak: marker for %s arrived on %s", terminalID, frame.ID)
			}
			seen[terminalID] = true
		}
	}

	for _, probe := range probes {
		if err := writeMuxControl(ctx, ws, map[string]any{
			"type":               protocol.WSClose,
			"tunnelConnectionId": "term:" + probe.sessionID,
		}); err != nil {
			return err
		}
	}
	return nil
}

func writeMuxControl(ctx context.Context, ws *websocket.Conn, value any) error {
	payload, err := json.Marshal(value)
	if err != nil {
		return err
	}
	return ws.Write(ctx, websocket.MessageText, payload)
}

func expectMuxConnected(ctx context.Context, ws *websocket.Conn, tunnelID string) error {
	for {
		messageType, data, err := ws.Read(ctx)
		if err != nil {
			return err
		}
		if messageType != websocket.MessageText {
			continue
		}
		var msg map[string]any
		if err := json.Unmarshal(data, &msg); err != nil {
			return err
		}
		if stringValue(msg["type"]) == protocol.WSConnected && stringValue(msg["tunnelConnectionId"]) == tunnelID {
			return nil
		}
		if stringValue(msg["type"]) == protocol.WSError && stringValue(msg["tunnelConnectionId"]) == tunnelID {
			return fmt.Errorf("mux tunnel %s failed: %s", tunnelID, stringValue(msg["message"]))
		}
	}
}

func writeMuxTunnel(ctx context.Context, ws *websocket.Conn, tunnelID string, payload []byte, binary bool) error {
	extra := protocol.WSDataText
	if binary {
		extra = protocol.WSDataBinary
	}
	frame, err := protocol.EncodeTunnelFrame(protocol.MsgTypeWSData, tunnelID, extra, payload)
	if err != nil {
		return err
	}
	return ws.Write(ctx, websocket.MessageBinary, frame)
}

func readMuxTunnelFor(ctx context.Context, ws *websocket.Conn, tunnelID string) (protocol.TunnelFrame, error) {
	for {
		frame, err := readMuxTunnel(ctx, ws)
		if err != nil {
			return protocol.TunnelFrame{}, err
		}
		if frame.ID == tunnelID {
			return frame, nil
		}
	}
}

func readMuxTunnel(ctx context.Context, ws *websocket.Conn) (protocol.TunnelFrame, error) {
	for {
		messageType, data, err := ws.Read(ctx)
		if err != nil {
			return protocol.TunnelFrame{}, err
		}
		if messageType != websocket.MessageBinary {
			continue
		}
		frame, err := protocol.DecodeTunnelFrame(data)
		if err != nil {
			return protocol.TunnelFrame{}, err
		}
		return frame, nil
	}
}

func stringValue(value any) string {
	if text, ok := value.(string); ok {
		return text
	}
	return fmt.Sprint(value)
}
