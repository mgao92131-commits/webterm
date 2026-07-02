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

	"github.com/pion/webrtc/v4"
	"nhooyr.io/websocket"

	"webterm/go-core/internal/app"
	"webterm/go-core/internal/config"
	"webterm/go-core/internal/protocol"
	"webterm/go-core/internal/relayapp"
	"webterm/go-core/internal/relaycontrol"
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

	headers := http.Header{"Cookie": []string{(&http.Cookie{Name: relaycontrol.AuthCookieName, Value: token.Value}).String()}}
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
	req.AddCookie(&http.Cookie{Name: relaycontrol.AuthCookieName, Value: token.Value})
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

func TestV2ClientP2PDataChannelServesMuxManager(t *testing.T) {
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
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	errCh := make(chan error, 1)
	go func() {
		errCh <- client.runOnce(ctx)
	}()
	waitForV2Presence(t, relayApp, user.ID, device.ID)

	pc, err := webrtc.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatalf("NewPeerConnection returned error: %v", err)
	}
	defer pc.Close()

	dc, err := pc.CreateDataChannel("tunnel", nil)
	if err != nil {
		t.Fatalf("CreateDataChannel returned error: %v", err)
	}
	textMessages := make(chan string, 8)
	binaryMessages := make(chan []byte, 8)
	opened := make(chan struct{})
	dc.OnOpen(func() {
		close(opened)
	})
	dc.OnMessage(func(msg webrtc.DataChannelMessage) {
		if msg.IsString {
			textMessages <- string(msg.Data)
			return
		}
		binaryMessages <- append([]byte(nil), msg.Data...)
	})

	offer, err := pc.CreateOffer(nil)
	if err != nil {
		t.Fatalf("CreateOffer returned error: %v", err)
	}
	gatherComplete := webrtc.GatheringCompletePromise(pc)
	if err := pc.SetLocalDescription(offer); err != nil {
		t.Fatalf("SetLocalDescription returned error: %v", err)
	}
	select {
	case <-gatherComplete:
	case <-time.After(3 * time.Second):
		t.Fatalf("browser-side offer ICE gathering timed out")
	}

	offerBody, _ := json.Marshal(relaycore.P2POffer{
		DeviceID: device.ID,
		SDP:      pc.LocalDescription().SDP,
	})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, server.URL+"/api/p2p/offer", bytes.NewReader(offerBody))
	if err != nil {
		t.Fatalf("new p2p offer request: %v", err)
	}
	req.AddCookie(&http.Cookie{Name: relaycontrol.AuthCookieName, Value: token.Value})
	req.Header.Set("content-type", "application/json")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("p2p offer request failed: %v", err)
	}
	answerBody, _ := io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != http.StatusOK {
		t.Fatalf("p2p offer status = %d body=%s", res.StatusCode, answerBody)
	}
	var answer relaycore.P2PAnswer
	if err := json.Unmarshal(answerBody, &answer); err != nil {
		t.Fatalf("decode p2p answer: %v body=%s", err, answerBody)
	}
	if answer.SDP == "" {
		t.Fatalf("p2p answer missing SDP")
	}
	if err := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeAnswer, SDP: answer.SDP}); err != nil {
		t.Fatalf("SetRemoteDescription returned error: %v", err)
	}

	select {
	case <-opened:
	case <-ctx.Done():
		t.Fatalf("datachannel did not open: %v", ctx.Err())
	}

	managerConnect, _ := json.Marshal(map[string]any{
		"type":               protocol.WSConnect,
		"tunnelConnectionId": "manager:" + device.ID,
		"path":               "/ws/sessions",
	})
	if err := dc.SendText(string(managerConnect)); err != nil {
		t.Fatalf("send mux ws-connect failed: %v", err)
	}
	msg := readP2PTextJSON(t, ctx, textMessages)
	if msg["type"] != protocol.WSConnected || msg["tunnelConnectionId"] != "manager:"+device.ID {
		t.Fatalf("manager ws-connected over p2p = %#v", msg)
	}
	frame := readP2PTunnel(t, ctx, binaryMessages)
	if frame.ID != "manager:"+device.ID || !strings.Contains(string(frame.Payload), `"type":"sessions"`) {
		t.Fatalf("manager initial p2p frame = %#v payload=%s", frame, frame.Payload)
	}

	cancel()
	if err := <-errCh; !isContextCanceledError(err) {
		t.Fatalf("client returned %v, want context.Canceled", err)
	}
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

func readP2PTextJSON(t *testing.T, ctx context.Context, messages <-chan string) map[string]any {
	t.Helper()
	for {
		select {
		case <-ctx.Done():
			t.Fatalf("read p2p text: %v", ctx.Err())
		case data := <-messages:
			var msg map[string]any
			if err := json.Unmarshal([]byte(data), &msg); err != nil {
				t.Fatalf("decode p2p json %q: %v", data, err)
			}
			return msg
		}
	}
}

func readP2PTunnel(t *testing.T, ctx context.Context, messages <-chan []byte) protocol.TunnelFrame {
	t.Helper()
	for {
		select {
		case <-ctx.Done():
			t.Fatalf("read p2p tunnel: %v", ctx.Err())
		case data := <-messages:
			frame, err := protocol.DecodeTunnelFrame(data)
			if err != nil {
				t.Fatalf("decode p2p tunnel: %v", err)
			}
			return frame
		}
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
