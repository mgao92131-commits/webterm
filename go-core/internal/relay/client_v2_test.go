package relay

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"


	"nhooyr.io/websocket"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/relayapp"
	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/testutil"
)

func TestV2ClientWorksWithGoRelayMuxWebSocket(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)

	relayApp := relayapp.NewInMemory(relayapp.Config{})
	user, err := relayApp.Store().CreateUser("owner@example.com", "secret", "admin")
	if err != nil {
		t.Fatalf("CreateUser returned error: %v", err)
	}
	device, credential, err := relayApp.Store().CreateDevice(user.ID, "Go Agent")
	if err != nil {
		t.Fatalf("CreateDevice returned error: %v", err)
	}
	token, err := relayApp.Store().IssueToken(user.ID, time.Hour)
	if err != nil {
		t.Fatalf("IssueToken returned error: %v", err)
	}

	server := httptest.NewServer(relayApp.Handler())
	defer server.Close()

	cfg := config.Config{
		Mode:  config.ModeRelay,
		Relay: config.RelayConfig{URL: server.URL, Secret: credential, DeviceName: "Go Agent"},
		Shell: config.ShellConfig{Command: "/bin/sh", CWD: "."},
	}
	agentApp := app.New(cfg, "test")
	client := NewV2(cfg.Relay, agentApp)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	errCh := make(chan error, 1)
	go func() {
		errCh <- client.runOnce(ctx)
	}()
	waitForV2Presence(t, relayApp, user.ID, device.ID)

	headers := http.Header{"Cookie": []string{(&http.Cookie{Name: relaycore.AuthCookieName, Value: token.Value}).String()}}
	muxURL := "ws" + strings.TrimPrefix(server.URL, "http") + "/ws/sessions?deviceId=" + device.ID
	ws, _, err := websocket.Dial(ctx, muxURL, &websocket.DialOptions{
		HTTPHeader:   headers,
		Subprotocols: []string{protocol.MuxSubprotocol},
	})
	if err != nil {
		t.Fatalf("mux websocket dial failed: %v", err)
	}
	defer ws.Close(websocket.StatusNormalClosure, "")

	writeMuxJSON(t, ctx, ws, map[string]any{
		"type":               protocol.WSConnect,
		"tunnelConnectionId": "manager:" + device.ID,
		"path":               "/ws/sessions",
	})
	managerConnected := readMuxJSON(t, ctx, ws)
	if managerConnected["type"] != protocol.WSConnected || managerConnected["tunnelConnectionId"] != "manager:"+device.ID {
		t.Fatalf("manager ws-connected = %#v", managerConnected)
	}
	managerFrame := readMuxTunnel(t, ctx, ws)
	if managerFrame.ID != "manager:"+device.ID || !strings.Contains(string(managerFrame.Payload), `"type":"sessions"`) {
		t.Fatalf("manager initial frame = %#v payload=%s", managerFrame, managerFrame.Payload)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, server.URL+"/api/sessions", bytes.NewBufferString(`{"name":"mux","cwd":"."}`))
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	req.AddCookie(&http.Cookie{Name: relaycore.AuthCookieName, Value: token.Value})
	req.Header.Set("x-device-id", device.ID)
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("create session request failed: %v", err)
	}
	body, _ := io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != http.StatusCreated {
		t.Fatalf("create session status = %d body=%s", res.StatusCode, body)
	}
	if !strings.Contains(string(body), `"id":"s1"`) {
		t.Fatalf("create session body = %s, want s1", body)
	}

	writeMuxJSON(t, ctx, ws, map[string]any{
		"type":               protocol.WSConnect,
		"tunnelConnectionId": "term:s1",
		"path":               "/ws/sessions/s1",
		"protocols":          []string{protocol.BinarySubprotocol},
	})
	terminalConnected := readMuxJSON(t, ctx, ws)
	if terminalConnected["type"] != protocol.WSConnected || terminalConnected["tunnelConnectionId"] != "term:s1" {
		t.Fatalf("terminal ws-connected = %#v", terminalConnected)
	}

	hello := append([]byte{protocol.MsgHello}, []byte(`{"lastSeq":0,"cols":80,"rows":24}`)...)
	writeMuxTunnel(t, ctx, ws, "term:s1", hello, true)
	input := append([]byte{protocol.MsgInput}, []byte("printf V2_MUX_RELAY_OK\\n\r")...)
	writeMuxTunnel(t, ctx, ws, "term:s1", input, true)

	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		frame := readMuxTunnel(t, ctx, ws)
		if frame.ID != "term:s1" || len(frame.Payload) == 0 {
			continue
		}
		if frame.Payload[0] == protocol.MsgOutput && strings.Contains(string(frame.Payload), "V2_MUX_RELAY_OK") {
			cancel()
			if err := <-errCh; !isContextCanceledError(err) {
				t.Fatalf("client returned %v, want context.Canceled", err)
			}
			return
		}
	}
	t.Fatalf("did not receive V2_MUX_RELAY_OK")
}

func isContextCanceledError(err error) bool {
	if err == nil {
		return false
	}
	message := err.Error()
	return errors.Is(err, context.Canceled) ||
		strings.Contains(message, "context canceled") ||
		strings.Contains(message, "use of closed network connection")
}

func writeMuxJSON(t *testing.T, ctx context.Context, conn *websocket.Conn, value any) {
	t.Helper()
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatalf("marshal mux json: %v", err)
	}
	if err := conn.Write(ctx, websocket.MessageText, data); err != nil {
		t.Fatalf("write mux json: %v", err)
	}
}

func readMuxJSON(t *testing.T, ctx context.Context, conn *websocket.Conn) map[string]any {
	t.Helper()
	for {
		messageType, data, err := conn.Read(ctx)
		if err != nil {
			t.Fatalf("read mux json: %v", err)
		}
		if messageType != websocket.MessageText {
			continue
		}
		var msg map[string]any
		if err := json.Unmarshal(data, &msg); err != nil {
			t.Fatalf("decode mux json %q: %v", data, err)
		}
		return msg
	}
}

func writeMuxTunnel(t *testing.T, ctx context.Context, conn *websocket.Conn, id string, payload []byte, binary bool) {
	t.Helper()
	extra := protocol.WSDataText
	if binary {
		extra = protocol.WSDataBinary
	}
	frame, err := protocol.EncodeTunnelFrame(protocol.MsgTypeWSData, id, extra, payload)
	if err != nil {
		t.Fatalf("encode mux tunnel: %v", err)
	}
	if err := conn.Write(ctx, websocket.MessageBinary, frame); err != nil {
		t.Fatalf("write mux tunnel: %v", err)
	}
}

func readMuxTunnel(t *testing.T, ctx context.Context, conn *websocket.Conn) protocol.TunnelFrame {
	t.Helper()
	for {
		messageType, data, err := conn.Read(ctx)
		if err != nil {
			t.Fatalf("read mux tunnel: %v", err)
		}
		if messageType != websocket.MessageBinary {
			continue
		}
		frame, err := protocol.DecodeTunnelFrame(data)
		if err != nil {
			t.Fatalf("decode mux tunnel: %v", err)
		}
		return frame
	}
}


func waitForV2Presence(t *testing.T, relayApp *relayapp.App, userID, deviceID string) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if _, ok := relayApp.Registry().GetAgentForUser(userID, deviceID); ok {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("agent presence %s/%s did not appear", userID, deviceID)
}
