package relayapp

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
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

func TestAppProxiesSessionHTTPToAgentStream(t *testing.T) {
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
		agentDone <- runHTTPProxyAgent(ctx, server.URL, credential)
	}()
	waitForPresence(t, app, user.ID, device.ID)

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, server.URL+"/api/sessions", bytes.NewBufferString(`{"name":"Android"}`))
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	req.AddCookie(&http.Cookie{Name: relaycore.AuthCookieName, Value: token.Value})
	req.Header.Set("x-device-id", device.ID)
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("Do returned error: %v", err)
	}
	defer res.Body.Close()
	body, _ := io.ReadAll(res.Body)
	if res.StatusCode != http.StatusCreated {
		t.Fatalf("status = %d body=%s", res.StatusCode, body)
	}
	if string(body) != `{"id":"s1"}` {
		t.Fatalf("body = %s, want session response", body)
	}
	if err := <-agentDone; err != nil {
		t.Fatalf("agent failed: %v", err)
	}
	if len(app.Streams().Snapshot()) != 0 {
		t.Fatalf("streams still active: %#v", app.Streams().Snapshot())
	}
}

func runHTTPProxyAgent(ctx context.Context, serverURL, credential string) error {
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

	frame, err := readRelayFrame(ctx, conn)
	if err != nil {
		return err
	}
	if frame.Type != relaycore.FrameTypeHTTPHeaders {
		return errUnexpected("first frame type", frame.Type, relaycore.FrameTypeHTTPHeaders)
	}
	var meta relaycore.HTTPRequestMeta
	if err := json.Unmarshal(frame.Payload, &meta); err != nil {
		return err
	}
	if meta.Method != http.MethodPost || meta.Path != "/api/sessions" {
		return errUnexpected("request meta", meta.Method+" "+meta.Path, "POST /api/sessions")
	}

	bodyFrame, err := readRelayFrame(ctx, conn)
	if err != nil {
		return err
	}
	if bodyFrame.Type != relaycore.FrameTypeHTTPChunk || string(bodyFrame.Payload) != `{"name":"Android"}` {
		return errUnexpected("request body", string(bodyFrame.Payload), `{"name":"Android"}`)
	}

	responseMeta, _ := json.Marshal(relaycore.HTTPResponseMeta{
		StatusCode: http.StatusCreated,
		Headers:    map[string]string{"content-type": "application/json"},
	})
	if err := writeRelayFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeHTTPHeaders, frame.StreamID, 0, responseMeta)); err != nil {
		return err
	}
	return writeRelayFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeHTTPChunk, frame.StreamID, relaycore.FrameFlagFin, []byte(`{"id":"s1"}`)))
}

func waitForPresence(t *testing.T, app *App, userID, deviceID string) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if _, ok := app.Registry().GetAgentForUser(userID, deviceID); ok {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("agent presence %s/%s did not appear", userID, deviceID)
}

func writeWSJSON(ctx context.Context, conn *websocket.Conn, value any) error {
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}
	return conn.Write(ctx, websocket.MessageText, data)
}

func readRelayFrame(ctx context.Context, conn *websocket.Conn) (relaycore.Frame, error) {
	messageType, data, err := conn.Read(ctx)
	if err != nil {
		return relaycore.Frame{}, err
	}
	if messageType != websocket.MessageBinary {
		return relaycore.Frame{}, errUnexpected("message type", messageType, websocket.MessageBinary)
	}
	return relaycore.DecodeFrame(data)
}

func writeRelayFrame(ctx context.Context, conn *websocket.Conn, frame relaycore.Frame) error {
	data, err := relaycore.EncodeFrame(frame)
	if err != nil {
		return err
	}
	return conn.Write(ctx, websocket.MessageBinary, data)
}

type unexpectedError struct {
	name string
	got  any
	want any
}

func errUnexpected(name string, got, want any) error {
	return unexpectedError{name: name, got: got, want: want}
}

func (err unexpectedError) Error() string {
	return "unexpected " + err.name
}
