package relayapp

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/relaygateway"
	"webterm/go-core/internal/testutil"
)

func TestAppProxiesMuxWebSocketFramesToAgentStream(t *testing.T) {
	testAppProxiesWebSocketFramesToAgentStream(t, "/ws/sessions", "webterm.mux.v1", "/ws/sessions")
}

func testAppProxiesWebSocketFramesToAgentStream(t *testing.T, clientPath string, subprotocol string, expectedAgentPath string) {
	t.Helper()
	testutil.SkipIfLoopbackListenUnavailable(t)

	app := NewInMemory(Config{})
	user, err := app.Store().CreateUser("owner@example.com", "secret", "admin")
	if err != nil {
		t.Fatalf("CreateUser returned error: %v", err)
	}
	device, credential, err := app.Store().CreateDevice(user.ID, "Agent Mac")
	if err != nil {
		t.Fatalf("CreateDevice returned error: %v", err)
	}
	token, err := app.Store().IssueToken(user.ID, time.Hour)
	if err != nil {
		t.Fatalf("IssueToken returned error: %v", err)
	}

	server := httptest.NewServer(app.Handler())
	defer server.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	agentDone := make(chan error, 1)
	go func() {
		agentDone <- runWebSocketProxyAgent(ctx, server.URL, credential, expectedAgentPath, subprotocol)
	}()
	waitForPresence(t, app, user.ID, device.ID)

	headers := http.Header{}
	headers.Add("Cookie", (&http.Cookie{Name: relaycore.AuthCookieName, Value: token.Value}).String())
	client, response, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(server.URL, "http")+clientPath+"?deviceId="+device.ID, &websocket.DialOptions{
		HTTPHeader:      headers,
		Subprotocols:    []string{subprotocol},
		CompressionMode: websocket.CompressionNoContextTakeover,
	})
	if err != nil {
		t.Fatalf("client Dial returned error: %v", err)
	}
	defer client.Close(websocket.StatusNormalClosure, "")
	if response == nil || response.StatusCode != http.StatusSwitchingProtocols {
		t.Fatalf("dial response = %#v", response)
	}
	if !strings.Contains(response.Header.Get("Sec-WebSocket-Extensions"), "permessage-deflate") {
		t.Fatalf("permessage-deflate was not negotiated: %q", response.Header.Get("Sec-WebSocket-Extensions"))
	}

	if err := client.Write(ctx, websocket.MessageText, []byte("hello text")); err != nil {
		t.Fatalf("write text: %v", err)
	}
	messageType, payload, err := client.Read(ctx)
	if err != nil {
		t.Fatalf("read text echo: %v", err)
	}
	if messageType != websocket.MessageText || string(payload) != "agent text" {
		t.Fatalf("text echo type/payload = %v/%q", messageType, payload)
	}

	if err := client.Write(ctx, websocket.MessageBinary, []byte{0x01, 0x02}); err != nil {
		t.Fatalf("write binary: %v", err)
	}
	messageType, payload, err = client.Read(ctx)
	if err != nil {
		t.Fatalf("read binary echo: %v", err)
	}
	if messageType != websocket.MessageBinary || string(payload) != "\x03\x04" {
		t.Fatalf("binary echo type/payload = %v/%v", messageType, payload)
	}

	_ = client.Close(websocket.StatusNormalClosure, "done")
	if err := <-agentDone; err != nil {
		t.Fatalf("agent failed: %v", err)
	}
}

func runWebSocketProxyAgent(ctx context.Context, serverURL, credential string, expectedPath string, expectedSubprotocol string) error {
	conn, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(serverURL, "http")+"/ws/agent", nil)
	if err != nil {
		return err
	}
	defer conn.Close(websocket.StatusNormalClosure, "")
	if err := writeWSJSON(ctx, conn, map[string]any{
		"type":       relaygateway.AgentRegisterMessage,
		"credential": credential,
		"deviceName": "Agent Mac",
	}); err != nil {
		return err
	}
	if _, _, err := conn.Read(ctx); err != nil {
		return err
	}

	openFrame, err := readRelayFrame(ctx, conn)
	if err != nil {
		return err
	}
	if openFrame.Type != relaycore.FrameTypeStreamOpen {
		return errUnexpected("open frame type", openFrame.Type, relaycore.FrameTypeStreamOpen)
	}
	var route relaycore.StreamRoute
	if err := json.Unmarshal(openFrame.Payload, &route); err != nil {
		return err
	}
	if route.Path != expectedPath || route.Subprotocol != expectedSubprotocol {
		return errUnexpected("stream route", route.Path+"/"+route.Subprotocol, expectedPath+"/"+expectedSubprotocol)
	}

	textFrame, err := readRelayFrame(ctx, conn)
	if err != nil {
		return err
	}
	if textFrame.Type != relaycore.FrameTypeWSText || string(textFrame.Payload) != "hello text" {
		return errUnexpected("text frame", string(textFrame.Payload), "hello text")
	}
	if err := writeRelayFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeWSText, openFrame.StreamID, 0, []byte("agent text"))); err != nil {
		return err
	}

	binaryFrame, err := readRelayFrame(ctx, conn)
	if err != nil {
		return err
	}
	if binaryFrame.Type != relaycore.FrameTypeWSBinary || string(binaryFrame.Payload) != "\x01\x02" {
		return errUnexpected("binary frame", binaryFrame.Payload, []byte{0x01, 0x02})
	}
	if err := writeRelayFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeWSBinary, openFrame.StreamID, 0, []byte{0x03, 0x04})); err != nil {
		return err
	}

	closeFrame, err := readRelayFrame(ctx, conn)
	if err != nil {
		return err
	}
	if closeFrame.Type != relaycore.FrameTypeStreamClose {
		return errUnexpected("close frame type", closeFrame.Type, relaycore.FrameTypeStreamClose)
	}
	return nil
}
